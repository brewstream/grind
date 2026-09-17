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
import java.util.HashMap;
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
    /** One section assembler per PSI PID: PID 0 from the start, PMT PIDs as the PAT names them. */
    private final Map<Integer, SectionAssembler> psiAssemblers = new LinkedHashMap<>();

    private ProgramMap programMap = ProgramMap.EMPTY;

    private long packets;
    private long bytes;
    private long nullPackets;
    private long continuityErrors;
    private long packetsLost;
    private long transportErrors;
    private long duplicates;
    private long pesPackets;
    private long syncLosses;
    private long pcrDiscontinuities;

    /**
     * One clock per PID that carries a PCR, which in practice means one per
     * program.
     *
     * <p>A transport stream is a multiplex, and there is no rule that its
     * programs share a time base. A DVB transponder carrying unrelated services
     * has a PCR per program and those clocks are genuinely independent — they can
     * be hours apart and drift against each other. Measuring everything against
     * whichever PCR happened to arrive last would make every program's figures
     * depend on its neighbours, which is wrong in a way that only shows up on
     * real multiplexes: a stream ffmpeg produced from one source has one clock
     * shared by every program, and hides the bug completely.
     *
     * <p>Keyed by PCR PID rather than by program number so that clocks exist
     * before any PSI has been parsed. Attribution improves once the PMTs arrive
     * and say which program a PID belongs to; until then everything falls back to
     * the reference clock.
     */
    private final Map<Integer, Clock> clocksByPcrPid = new LinkedHashMap<>();

    /**
     * Which clock a PID's errors are counted against, rebuilt whenever the
     * program tables change. A PID the tables do not account for is not present,
     * and falls back to the reference clock.
     */
    private Map<Integer, Clock> clockByPid = Map.of();

    /**
     * The clock stream-level figures are measured on: the first one the stream
     * revealed, and never reassigned.
     *
     * <p>Something has to provide the timeline for a figure that spans programs,
     * and a reference that changed mid-stream would make the count meaningless.
     * When programs really do have independent clocks, the per-PID figures are
     * the ones to read; this is the transport stream's own summary and says so.
     */
    private Clock referenceClock;

    private long streamErroredSecond = -1;
    private long erroredSeconds;

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
            markErroredSecond(state);
            markGopDamaged(state);
            state.hasPrevious = false;
            return;
        }

        trackScrambling(packet, state);
        trackContinuity(packet, state);
        // After continuity, deliberately: a gap detected on the packet that
        // carries a random-access indicator belongs to the interval that just
        // ended, not the one it begins.
        trackRandomAccess(packet, state);
        trackPcr(packet, state);
        trackTables(packet);
        trackPes(packet, state);
    }

    /**
     * Reads the PES header of a packet that starts one, recording the track's
     * timing.
     *
     * <p>Only attempted on PIDs a PMT named as elementary streams. A PES start
     * code is three bytes and will occur by chance inside compressed video, so
     * scanning every PID for one would invent timestamps from picture data.
     */
    private void trackPes(TsPacket packet, PidState state) {
        if (!packet.payloadUnitStart() || !packet.hasPayload() || !isElementaryStream(packet.pid())) {
            return;
        }

        byte[] payload = packet.payload();
        PesHeader header = PesHeader.parse(payload, 0, payload.length);
        if (header == null) {
            return;
        }

        state.pesPackets++;
        pesPackets++;
        state.streamId = header.streamId();
        if (header.hasPts()) {
            // A backwards PTS is not necessarily wrong: with B-frames the
            // presentation order is not the transmission order, which is exactly
            // what the DTS exists to express. Recorded, not judged.
            state.lastPts = header.pts();
        }
        if (header.hasDts()) {
            state.lastDts = header.dts();
        }
    }

    private boolean isElementaryStream(int pid) {
        for (ProgramMapTable table : programMap.programs().values()) {
            for (ElementaryStream stream : table.streams()) {
                if (stream.pid() == pid) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Feeds PSI packets to their section assemblers and applies whatever tables
     * come out.
     *
     * <p>Only PID 0 and the PMT PIDs the PAT names are assembled. Running an
     * assembler over every PID would try to read video as tables, which is not
     * merely wasteful: a PES payload will occasionally look like a plausible
     * section header, and the CRC is the only thing standing between that and a
     * fabricated program map.
     */
    private void trackTables(TsPacket packet) {
        SectionAssembler assembler = psiAssemblers.get(packet.pid());
        if (assembler == null) {
            if (packet.pid() != TsPacket.PAT_PID && !programMap.pmtPids().containsValue(packet.pid())) {
                return;
            }
            assembler = new SectionAssembler();
            psiAssemblers.put(packet.pid(), assembler);
        }

        long crcBefore = assembler.crcFailures();
        List<TableSection> sections = assembler.consume(packet);
        if (assembler.crcFailures() > crcBefore) {
            markErroredSecond(byPid.get(packet.pid()));
        }
        for (TableSection section : sections) {
            if (!section.current()) {
                // Describes a future state, not the one in force. Applying it
                // would report tracks the stream is not carrying yet.
                continue;
            }
            applySection(packet.pid(), section);
        }
    }

    private void applySection(int pid, TableSection section) {
        ProgramMap previous = programMap;

        if (pid == TsPacket.PAT_PID && section.tableId() == TableSection.TABLE_ID_PAT) {
            ProgramAssociationTable pat = ProgramAssociationTable.parse(section);
            if (pat == null) {
                return;
            }
            programMap = ProgramMap.fromPat(pat, previous);
            // A PMT PID that is no longer listed stops being assembled; one that
            // has just appeared starts on its next packet.
            psiAssemblers.keySet().removeIf(
                    assembled -> assembled != TsPacket.PAT_PID
                            && !programMap.pmtPids().containsValue(assembled));
        } else if (section.tableId() == TableSection.TABLE_ID_PMT) {
            ProgramMapTable pmt = ProgramMapTable.parse(section);
            if (pmt == null) {
                return;
            }
            programMap = programMap.withProgram(pmt.programNumber(), pmt);
        } else {
            return; // a table this library does not read yet - SDT, EIT, NIT
        }

        if (!programMap.equals(previous)) {
            rebindClocks();
            ProgramMap announced = programMap;
            fire(listener -> listener.onProgramsChanged(announced));
        }
    }

    /**
     * What the stream carries, as far as its tables have revealed. Empty until a
     * PAT arrives.
     */
    public ProgramMap programs() {
        return programMap;
    }

    /**
     * Records that bytes had to be discarded to regain packet alignment.
     *
     * @param bytesDiscarded how many bytes were skipped before a sync byte was found
     */
    public void recordSyncLoss(int bytesDiscarded) {
        syncLosses++;
        markErroredSecond(null);
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
        markErroredSecond(state);
        markGopDamaged(state);
        fire(listener -> listener.onContinuityError(packet.pid(), expected, counter, lost));
    }

    /**
     * Tracks where a decoder could start, and whether the span since the last
     * such point took damage.
     *
     * <p>The random-access indicator marks the packets a decoder can begin from —
     * keyframes, in practice — and that is what decides whether loss is a blink
     * or a second of visible corruption. Everything between two of these points
     * depends on the frame at the start of it, so a gap anywhere in the span
     * damages the whole span. Counting damaged spans says far more about what a
     * viewer saw than counting lost packets does: five packets lost inside one
     * interval is one glitch, while five lost across five intervals is five.
     */
    private void trackRandomAccess(TsPacket packet, PidState state) {
        boolean randomAccess = packet.adaptationField() != null
                && packet.adaptationField().randomAccess();
        if (randomAccess) {
            state.randomAccessPoints++;
            state.packetsSinceRandomAccess = 0;
            state.currentGopDamaged = false;
        } else if (state.randomAccessPoints > 0) {
            state.packetsSinceRandomAccess++;
        }
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
                pcrDiscontinuities++;
                markErroredSecond(state);
                fire(listener -> listener.onPcrDiscontinuity(packet.pid(), previous, pcr));
            }
        }
        state.lastPcr = pcr;

        clockFor(packet.pid()).advanceTo(pcr / AdaptationField.PCR_RATE_HZ);
    }

    /**
     * The clock a PCR PID drives, created on first sight.
     *
     * <p>The first one created becomes the reference, which is why this is the
     * only place clocks come into being: a clock conjured from a lookup on some
     * unrelated PID could win that race and anchor the stream-level figures to a
     * timeline nothing else uses.
     */
    private Clock clockFor(int pcrPid) {
        Clock clock = clocksByPcrPid.get(pcrPid);
        if (clock == null) {
            clock = new Clock();
            clocksByPcrPid.put(pcrPid, clock);
            if (referenceClock == null) {
                referenceClock = clock;
            }
        }
        return clock;
    }

    /**
     * The clock one PID's errors belong to: its own program's, or the reference
     * when the tables do not account for it.
     *
     * <p>PSI PIDs and null packets genuinely belong to no program, and errors on
     * them are errors against the whole multiplex rather than against one
     * service, so the reference is the right answer rather than a fallback.
     */
    private Clock clockOf(PidState state) {
        if (state == null) {
            return referenceClock;
        }
        Clock clock = clockByPid.get(state.pid);
        return clock != null ? clock : referenceClock;
    }

    /**
     * Rebinds each PID to its program's clock after the tables changed.
     *
     * <p>Rebuilt wholesale rather than patched: a PMT can move a track between
     * programs or change a program's PCR PID, and reconciling that incrementally
     * is more code than redoing a map with a few dozen entries.
     */
    private void rebindClocks() {
        Map<Integer, Clock> rebound = new HashMap<>();
        for (ProgramMapTable table : programMap.programs().values()) {
            int pcrPid = table.pcrPid();
            if (pcrPid == TsPacket.NULL_PID) {
                continue; // a program with no clock of its own
            }
            Clock clock = clockFor(pcrPid);
            rebound.put(pcrPid, clock);
            for (ElementaryStream stream : table.streams()) {
                rebound.put(stream.pid(), clock);
            }
        }
        clockByPid = Map.copyOf(rebound);
    }

    /**
     * Records that the GOP currently open on this PID took damage.
     *
     * <p>Counted once per GOP however many errors it takes. Everything between
     * two random-access points depends on the frame that begins it, so one gap
     * anywhere ruins all of it and a second gap does not ruin it twice. That is
     * what makes this different from an error count, and closer to what someone
     * watching would describe: two packets lost inside one GOP is a single
     * glitch, two spread across two GOPs is two.
     *
     * <p>Damage before the first random-access point is not attributed to
     * anything — there is no GOP open yet to damage.
     *
     * <p>Read this on video. Nearly every audio frame is its own random-access
     * point, so an audio GOP is one frame long and damage does not propagate;
     * the figure degenerates into an error count there.
     */
    private void markGopDamaged(PidState state) {
        if (state == null || state.randomAccessPoints == 0 || state.currentGopDamaged) {
            return;
        }
        state.currentGopDamaged = true;
        state.damagedGops++;
    }

    /**
     * Records that this second of stream time contained an error, on one PID and
     * on the stream as a whole.
     *
     * <p>A second is counted once however many errors it holds. That is the point
     * of the measure: it answers "for how long was this stream broken", which is
     * much closer to what a viewer experienced than a packet count, and it is
     * what broadcast monitoring equipment reports so the figure can be compared
     * against one.
     */
    private void markErroredSecond(PidState state) {
        // The PID's own program clock, so a second counted against program 2 is a
        // second of program 2's timeline, whatever program 1's clock happens to
        // read at the time.
        Clock own = clockOf(state);
        if (state != null && own != null && own.currentSecond >= 0
                && state.erroredSecond != own.currentSecond) {
            state.erroredSecond = own.currentSecond;
            state.erroredSeconds++;
        }
        // The stream-level figure is measured on the reference clock regardless
        // of which program erred: it answers "was this multiplex broken, and for
        // how long", and needs one timeline to answer it on.
        if (referenceClock != null && referenceClock.currentSecond >= 0
                && streamErroredSecond != referenceClock.currentSecond) {
            streamErroredSecond = referenceClock.currentSecond;
            erroredSeconds++;
        }
    }

    /**
     * One program's notion of time, in whole seconds, taken from its PCR rather
     * than a wall clock.
     *
     * <p>A file analysed at a hundred times real speed still yields the right
     * answer, and a live stream is measured in its own time base rather than the
     * analyser's. Negative until this program's first PCR arrives, during which
     * its errors cannot be attributed to a second and so are not counted as
     * errored ones.
     */
    private static final class Clock {

        private long currentSecond = -1;
        private long firstSecond = -1;

        void advanceTo(long second) {
            if (firstSecond < 0) {
                firstSecond = second;
            }
            currentSecond = second;
        }

        long observedSeconds() {
            return firstSecond < 0 ? 0 : currentSecond - firstSecond + 1;
        }
    }

    /** A point-in-time snapshot of everything seen so far. */
    public TsStreamStats stats() {
        List<PidStats> pids = new ArrayList<>(byPid.size());
        for (PidState state : byPid.values()) {
            pids.add(new PidStats(state.pid, state.packets, state.bytes, state.continuityErrors,
                    state.packetsLost, state.transportErrors, state.duplicates, state.scrambled,
                    state.lastPcr, state.pcrCount, state.pcrDiscontinuities,
                    state.pesPackets, state.lastPts, state.lastDts, state.streamId,
                    state.randomAccessPoints, state.packetsSinceRandomAccess,
                    state.erroredSeconds, state.damagedGops));
        }
        long crcFailures = 0;
        for (SectionAssembler assembler : psiAssemblers.values()) {
            crcFailures += assembler.crcFailures();
        }
        long observedSeconds = referenceClock == null ? 0 : referenceClock.observedSeconds();
        return new TsStreamStats(packets, bytes, nullPackets, continuityErrors, packetsLost,
                transportErrors, duplicates, pesPackets, syncLosses, crcFailures,
                pcrDiscontinuities, erroredSeconds, observedSeconds, programMap,
                List.copyOf(pids));
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
        private long randomAccessPoints;
        private long packetsSinceRandomAccess = -1;
        private long erroredSeconds;
        private long erroredSecond = -1;
        private long damagedGops;
        private boolean currentGopDamaged;
        private long pesPackets;
        private long lastPts = -1;
        private long lastDts = -1;
        private int streamId = -1;

        private PidState(int pid) {
            this.pid = pid;
        }
    }
}