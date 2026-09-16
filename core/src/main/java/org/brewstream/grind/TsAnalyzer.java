/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.brewstream.grind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the health of a transport stream as packets go past.
 *
 * <p>{@link TsPacket#parse} is a pure function: bytes in, structure out, no
 * memory. Stream health does not exist in a single packet — a continuity error
 * is a relationship between two packets on a PID, clock drift is a relationship
 * between a PCR and the one before it, and bitrate is a relationship between
 * bytes and time. All of that lives here, which is why this is the stateful half
 * and the parser stays stateless.
 *
 * <p>Feed every packet to {@link #consume}. Read {@link #stats()} on whatever
 * schedule a dashboard or metrics sampler wants, and register a
 * {@link TsStreamListener} for the things worth reacting to rather than polling.
 *
 * <p><b>Not thread-safe</b>, deliberately. A transport stream is a sequence, and
 * the packets arrive in order on one thread — typically the event loop that read
 * them. Synchronising per packet would cost more than the parsing at broadcast
 * rates, and buy nothing, since consuming out of order would produce nonsense
 * regardless of locking. Use one analyzer per stream, on the thread that reads it.
 */
public final class TsAnalyzer {

    /**
     * How far the clock may jump before it counts as a discontinuity rather than
     * a gap in delivery: 100ms in 27 MHz units. The spec requires a PCR at least
     * every 100ms (§2.7.2), so a jump beyond that is either a genuine
     * discontinuity or a stream that is already broken. Below it, the jump is
     * just the ordinary spacing between clock samples.
     */
    private static final long PCR_DISCONTINUITY_THRESHOLD = 27_000_000L / 10;

    /**
     * The full span of the PCR before it wraps: the base is 33 bits of 90 kHz,
     * and each base tick is 300 of the 27 MHz units this class works in
     * (§2.4.3.5). About 26.5 hours.
     */
    private static final long PCR_WRAP_MAGNITUDE = (1L << 33) * 300;

    private final List<TsStreamListener> listeners = new ArrayList<>();
    private final Map<Integer, PidState> byPid = new LinkedHashMap<>();

    private long packets;
    private long bytes;
    private long nullPackets;
    private long continuityErrors;
    private long packetsLost;
    private long transportErrors;
    private long duplicates;
    private long syncLosses;

    /** Registers a listener. Called synchronously on the consuming thread; see {@link TsStreamListener}. */
    public void addListener(TsStreamListener listener) {
        listeners.add(listener);
    }

    /**
     * Accounts for one packet.
     *
     * @param packet a packet from {@link TsPacket#parse}; nulls are rejected rather
     *               than silently ignored, since a null from the parser means lost
     *               sync and belongs in {@link #recordSyncLoss} instead
     */
    public void consume(TsPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException(
                    "null means the parser lost sync - report it with recordSyncLoss(int) so it is "
                            + "counted, rather than dropping it here where it would vanish");
        }

        packets++;
        bytes += packet.payloadLength();
        if (packet.isNull()) {
            // Stuffing. Counted so bitrate figures can exclude it, but it has no
            // continuity counter worth tracking and belongs to no program.
            nullPackets++;
            return;
        }

        PidState state = byPid.get(packet.pid());
        if (state == null) {
            state = new PidState(packet.pid());
            byPid.put(packet.pid(), state);
            fire(listener -> listener.onPidDiscovered(packet.pid()));
        }

        state.packets++;
        state.bytes += packet.payloadLength();

        if (packet.transportErrorIndicator()) {
            // Known corrupt upstream. Its counter is meaningless, so it must not
            // seed a comparison - otherwise one flagged packet manufactures two
            // continuity errors, the one it causes and the one it hides.
            state.transportErrors++;
            transportErrors++;
            state.hasPrevious = false;
            return;
        }

        trackScrambling(packet, state);
        trackContinuity(packet, state);
        trackPcr(packet, state);
    }

    /**
     * Records that bytes had to be discarded to regain packet alignment.
     *
     * @param bytesDiscarded how many bytes were skipped before a sync byte was found
     */
    public void recordSyncLoss(int bytesDiscarded) {
        syncLosses++;
        fire(listener -> listener.onSyncLost(bytesDiscarded));
    }

    private void trackScrambling(TsPacket packet, PidState state) {
        boolean scrambled = packet.isScrambled();
        if (scrambled != state.scrambled) {
            state.scrambled = scrambled;
            fire(listener -> listener.onScramblingChanged(packet.pid(), scrambled));
        }
    }

    private void trackContinuity(TsPacket packet, PidState state) {
        int counter = packet.continuityCounter();
        if (!state.hasPrevious) {
            state.hasPrevious = true;
            state.previousCounter = counter;
            return;
        }

        // Keyed on the payload *flag*, not on whether payload bytes are present.
        // The counter advances whenever adaptation_field_control has the payload
        // bit set (§2.4.3.3), and an adaptation field can fill a packet exactly,
        // leaving the flag set with zero bytes behind it. Reading payloadLength
        // instead reported a spurious error on every such packet.
        boolean advances = packet.adaptationFieldControl().hasPayload();
        int previous = state.previousCounter;
        int expected = advances ? (previous + 1) % 16 : previous;
        state.previousCounter = counter;

        if (counter == expected) {
            return;
        }
        if (packet.adaptationField() != null && packet.adaptationField().discontinuity()) {
            // Announced, so not loss. The stream is telling us the jump is
            // deliberate - a splice or a restart - and treating it as damage
            // would report an error on every well-formed ad insertion.
            return;
        }
        if (advances && counter == previous) {
            // A duplicate packet, which §2.4.3.3 permits: the multiplexer may
            // send a packet twice, with the same counter. Treated as a gap this
            // computes (previous - expected + 16) % 16 == 15, so one duplicate
            // was reported as fifteen lost packets - a dashboard reading that
            // would be worse than useless.
            state.duplicates++;
            duplicates++;
            return;
        }

        int lost = (counter - expected + 16) % 16;
        state.continuityErrors++;
        state.packetsLost += lost;
        continuityErrors++;
        packetsLost += lost;
        fire(listener -> listener.onContinuityError(packet.pid(), expected, counter, lost));
    }

    private void trackPcr(TsPacket packet, PidState state) {
        long pcr = packet.pcr();
        if (pcr < 0) {
            return;
        }

        state.pcrCount++;
        if (state.lastPcr >= 0) {
            long delta = pcr - state.lastPcr;
            // The 33-bit base wraps about every 26.5 hours, which on a 24/7
            // stream would otherwise look like a huge backwards jump exactly
            // once a day. Recognise it by its magnitude and fold it forward.
            if (delta < 0 && -delta > PCR_WRAP_MAGNITUDE / 2) {
                delta += PCR_WRAP_MAGNITUDE;
            }
            boolean announced = packet.adaptationField() != null && packet.adaptationField().discontinuity();
            if (!announced && (delta < 0 || delta > PCR_DISCONTINUITY_THRESHOLD)) {
                long previous = state.lastPcr;
                state.pcrDiscontinuities++;
                fire(listener -> listener.onPcrDiscontinuity(packet.pid(), previous, pcr));
            }
        }
        state.lastPcr = pcr;
    }

    /** A point-in-time snapshot of everything seen so far. */
    public TsStreamStats stats() {
        List<PidStats> pids = new ArrayList<>(byPid.size());
        for (PidState state : byPid.values()) {
            pids.add(new PidStats(state.pid, state.packets, state.bytes, state.continuityErrors,
                    state.packetsLost, state.transportErrors, state.duplicates, state.scrambled,
                    state.lastPcr, state.pcrCount, state.pcrDiscontinuities));
        }
        return new TsStreamStats(packets, bytes, nullPackets, continuityErrors, packetsLost,
                transportErrors, duplicates, syncLosses, List.copyOf(pids));
    }

    private void fire(java.util.function.Consumer<TsStreamListener> event) {
        for (TsStreamListener listener : listeners) {
            // One badly behaved listener must not stop the others from being told,
            // and must not take the stream down. Same contract as Roast's dispatcher.
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                // Deliberately swallowed: this library has no logger, and throwing
                // here would punish the stream for the listener's bug.
            }
        }
    }

    /** Mutable per-PID state. Kept package-private and separate from the immutable {@link PidStats}. */
    private static final class PidState {
        private final int pid;
        private long packets;
        private long bytes;
        private long continuityErrors;
        private long packetsLost;
        private long transportErrors;
        private long duplicates;
        private boolean scrambled;
        private boolean hasPrevious;
        private int previousCounter;
        private long lastPcr = -1;
        private long pcrCount;
        private long pcrDiscontinuities;

        private PidState(int pid) {
            this.pid = pid;
        }
    }
}