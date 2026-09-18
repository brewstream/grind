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

import java.util.Arrays;

/**
 * Reassembles one track's elementary stream from the packets carrying it.
 *
 * <p>One instance per PID. Feed it every packet on that PID and it returns an
 * {@link AccessUnit} whenever one completes.
 *
 * <p><b>A unit completes when the next one starts.</b> Video PES packets almost
 * always declare a length of zero, meaning unbounded, so there is nothing in the
 * stream that says "this picture ends here" — only the start of the following
 * one. A unit is therefore emitted one packet later than it finished arriving,
 * which is inherent to the format rather than a choice made here. Call
 * {@link #flush()} at end of stream for the last one.
 *
 * <p>Three questions have to be answered to do this at all, and they are the
 * reason this was left unbuilt until something needed it. Answering them in the
 * abstract means guessing; answering them for a consumer that repackages into
 * fragments makes them straightforward:
 *
 * <ul>
 *   <li><b>How much to buffer.</b> One unit, capped — see {@link #MAX_UNIT_BYTES}.
 *       A stream whose PES never terminates must not be able to exhaust memory.</li>
 *   <li><b>How long to hold it.</b> Until it is returned, and no longer. Nothing
 *       here retains a completed unit.</li>
 *   <li><b>What a gap produces.</b> Nothing. A unit missing packets cannot be
 *       made whole, and half a picture is worse than no picture — a decoder handed
 *       one produces artefacts rather than an error. It is counted and dropped.</li>
 * </ul>
 */
public final class AccessUnitAssembler {

    /**
     * The largest unit that will be assembled, at 8 MiB.
     *
     * <p>Comfortably above any real coded picture — a 4K keyframe at a high
     * bitrate is a small fraction of it — and low enough that a corrupt stream
     * whose PES length never terminates is bounded rather than fatal. The cap is
     * on the assembler rather than the caller because a caller cannot see the
     * unit growing.
     */
    public static final int MAX_UNIT_BYTES = 8 * 1024 * 1024;

    private final int pid;

    private byte[] buffer = new byte[16 * 1024];
    private int length;
    private boolean assembling;
    private boolean damaged;

    private long pts = -1;
    private long dts = -1;
    private boolean randomAccess;
    private int declaredLength;

    private int previousCounter = -1;
    private long completed;
    private long discardedForLoss;
    private long discardedForSize;

    public AccessUnitAssembler(int pid) {
        this.pid = pid;
    }

    /**
     * Accounts for one packet.
     *
     * @param packet a packet on this assembler's PID; others are ignored
     * @return the unit this packet completed, or null — which is the usual answer,
     *         since a unit spans many packets
     */
    public AccessUnit consume(TsPacket packet) {
        if (packet == null || packet.pid() != pid) {
            return null;
        }
        if (packet.transportErrorIndicator()) {
            // Known corrupt upstream: its payload cannot be trusted and its
            // continuity counter is meaningless.
            damaged = true;
            previousCounter = -1;
            return null;
        }

        Continuity continuity = checkContinuity(packet);
        if (continuity == Continuity.DUPLICATE) {
            // Permitted by ISO/IEC 13818-1 section 2.4.3.3: a packet may be sent
            // twice with the same counter and identical payload. Appending it
            // again would corrupt the unit with a repeated run of bytes — quietly,
            // since nothing about the result looks wrong until a decoder sees it.
            return null;
        }
        if (continuity == Continuity.GAP) {
            damaged = true;
        }

        AccessUnit finished = null;
        if (packet.payloadUnitStart()) {
            // The start of the next unit is the only signal that the previous one
            // ended, so it is emitted here rather than when its last byte arrived.
            finished = complete();
            begin(packet);
        } else if (assembling) {
            append(packet);
        }
        return finished;
    }

    /**
     * The unit still being assembled, at end of stream.
     *
     * <p>Necessary because nothing in the stream marks the last unit as finished
     * — only a following unit would have, and there is none.
     *
     * @return the final unit, or null if none was in progress or it was damaged
     */
    public AccessUnit flush() {
        return complete();
    }

    private void begin(TsPacket packet) {
        byte[] payload = packet.payload();
        PesHeader header = PesHeader.parse(payload, 0, payload.length);
        if (header == null) {
            // A unit start whose PES header will not parse. Nothing can be
            // assembled from it, and treating it as the start of a unit would
            // silently produce one made of the wrong bytes.
            assembling = false;
            return;
        }

        assembling = true;
        damaged = false;
        length = 0;
        pts = header.hasPts() ? header.pts() : -1;
        dts = header.hasDts() ? header.dts() : -1;
        declaredLength = header.packetLength();
        randomAccess = packet.adaptationField() != null && packet.adaptationField().randomAccess();

        int from = header.headerLength();
        if (from < payload.length) {
            add(payload, from, payload.length - from);
        }
    }

    private void append(TsPacket packet) {
        byte[] payload = packet.payload();
        add(payload, 0, payload.length);
    }

    private void add(byte[] source, int offset, int count) {
        if (count <= 0 || !assembling) {
            return;
        }
        if (length + count > MAX_UNIT_BYTES) {
            // A PES that never ends. Abandoned rather than grown into, and the
            // next unit start recovers - so one runaway unit costs one unit.
            discardedForSize++;
            assembling = false;
            damaged = false;
            length = 0;
            return;
        }
        if (length + count > buffer.length) {
            int capacity = Math.min(MAX_UNIT_BYTES, Math.max(buffer.length * 2, length + count));
            buffer = Arrays.copyOf(buffer, capacity);
        }
        System.arraycopy(source, offset, buffer, length, count);
        length += count;
    }

    private AccessUnit complete() {
        if (!assembling || length == 0) {
            assembling = false;
            return null;
        }
        assembling = false;

        if (damaged) {
            // Half a picture is worse than none: a decoder handed one produces
            // artefacts rather than an error, so the damage would surface as
            // something a viewer sees rather than something a log records.
            discardedForLoss++;
            damaged = false;
            length = 0;
            return null;
        }

        byte[] data = Arrays.copyOf(buffer, length);
        length = 0;
        completed++;
        return new AccessUnit(pid, pts, dts, randomAccess, data);
    }

    /** What this packet's continuity counter says about the one before it. */
    private enum Continuity {
        /** The expected next counter. */
        NEXT,
        /** The same counter again, which the standard permits and which carries no new bytes. */
        DUPLICATE,
        /** A jump, meaning packets went missing. */
        GAP
    }

    private Continuity checkContinuity(TsPacket packet) {
        if (!packet.adaptationFieldControl().hasPayload()) {
            // A counter only advances on packets carrying payload, so one without
            // is not a gap however far it is from the last.
            return Continuity.NEXT;
        }
        int counter = packet.continuityCounter();
        int previous = previousCounter;
        if (previous >= 0 && counter == previous) {
            return Continuity.DUPLICATE;
        }
        previousCounter = counter;
        if (previous < 0) {
            return Continuity.NEXT;
        }
        return counter == (previous + 1) % 16 ? Continuity.NEXT : Continuity.GAP;
    }

    /** The PID this assembler follows. */
    public int pid() {
        return pid;
    }

    /** Units assembled and handed back whole. */
    public long completed() {
        return completed;
    }

    /** Units abandoned because packets carrying them went missing. */
    public long discardedForLoss() {
        return discardedForLoss;
    }

    /** Units abandoned for exceeding {@link #MAX_UNIT_BYTES}. */
    public long discardedForSize() {
        return discardedForSize;
    }

    /** Whether a unit is part-assembled, which at end of stream means {@link #flush()} has something. */
    public boolean isAssembling() {
        return assembling;
    }
}
