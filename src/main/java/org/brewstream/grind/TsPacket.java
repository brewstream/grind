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

/**
 * One MPEG-TS transport packet: a fixed 188 bytes, four of header and the rest
 * split between an optional adaptation field and an optional payload
 * (ISO/IEC 13818-1 §2.4.3.2).
 *
 * <p><b>A view, not a copy.</b> Parsing records where the payload sits rather
 * than extracting it, because a transport stream at broadcast rates is tens of
 * thousands of these a second and copying each one's payload would dominate the
 * cost of doing anything useful with them. {@link #payloadOffset} and
 * {@link #payloadLength} index into the caller's own array.
 *
 * <p><b>Why {@code byte[]} rather than {@code ByteBuffer} or Netty's
 * {@code ByteBuf}.</b> This library deliberately has no dependencies, and a
 * parser that owns no buffer type is the easiest to bridge to whichever one the
 * caller already has. A Netty {@code ByteBuf} reaches this through
 * {@code readBytes} into a reusable array, or {@code nioBuffer().array()} for a
 * heap buffer, with no copy in the second case.
 *
 * @param transportErrorIndicator set by an upstream demodulator to mean "this packet is
 *                                known corrupt". Its payload should not be trusted, and
 *                                its continuity counter should not be treated as a
 *                                discontinuity (§2.4.3.3)
 * @param payloadUnitStart        for a PES PID, this packet begins a new PES packet; for a
 *                                PSI PID, it begins a new section and the payload's first
 *                                byte is a pointer field
 * @param transportPriority       a hint from the multiplexer that this packet matters more
 *                                than others on the same PID; carried, not acted on
 * @param pid                     which elementary stream or table this packet belongs to, 13 bits
 * @param scramblingControl       0 means clear; anything else means the payload is
 *                                encrypted and parsing it further is meaningless
 * @param adaptationFieldControl  which of adaptation field and payload are present
 * @param continuityCounter       increments per packet on a PID, wrapping at 16 — the basis
 *                                of loss detection
 * @param adaptationField         the parsed adaptation field, or {@code null} when absent
 * @param payloadOffset           index into the source array where the payload starts
 * @param payloadLength           payload length in bytes; zero when there is no payload
 */
public record TsPacket(
        boolean transportErrorIndicator,
        boolean payloadUnitStart,
        boolean transportPriority,
        int pid,
        int scramblingControl,
        AdaptationFieldControl adaptationFieldControl,
        int continuityCounter,
        AdaptationField adaptationField,
        int payloadOffset,
        int payloadLength) {

    /** Every transport packet is exactly this long (§2.4.3.2). */
    public static final int LENGTH = 188;

    /** The byte every packet starts with. Finding it is how a reader regains sync. */
    public static final byte SYNC_BYTE = 0x47;

    /**
     * The null-packet PID, used by multiplexers as stuffing to hold a constant
     * bitrate. Carries no data and is normally discarded (§2.4.3.2).
     */
    public static final int NULL_PID = 0x1FFF;

    /** The PID carrying the Program Association Table, fixed by the spec. */
    public static final int PAT_PID = 0x0000;

    /**
     * Parses the 188 bytes starting at {@code offset}.
     *
     * @param data   the source array; not modified or retained beyond this call
     * @param offset index of the sync byte
     * @return the parsed packet, or {@code null} if the sync byte is not where it
     *         should be — which means the caller has lost alignment and needs to
     *         resynchronise rather than that this one packet is bad
     * @throws IndexOutOfBoundsException if fewer than {@link #LENGTH} bytes remain
     */
    public static TsPacket parse(byte[] data, int offset) {
        if (offset + LENGTH > data.length) {
            throw new IndexOutOfBoundsException(
                    "a transport packet needs " + LENGTH + " bytes from offset " + offset
                            + ", but the array holds " + data.length);
        }
        if (data[offset] != SYNC_BYTE) {
            return null;
        }

        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;

        boolean errorIndicator = (b1 & 0x80) != 0;
        boolean unitStart = (b1 & 0x40) != 0;
        boolean priority = (b1 & 0x20) != 0;
        int pid = ((b1 & 0x1F) << 8) | b2;
        int scrambling = (b3 & 0xC0) >> 6;
        AdaptationFieldControl control = AdaptationFieldControl.fromCode((b3 & 0x30) >> 4);
        int counter = b3 & 0x0F;

        int cursor = offset + 4;
        AdaptationField adaptationField = null;
        if (control.hasAdaptationField()) {
            // The length byte counts everything after itself, so a zero-length
            // field is one byte of pure stuffing and carries no flags at all -
            // legal, and common as single-byte padding (§2.4.3.4).
            int adaptationLength = data[cursor] & 0xFF;
            adaptationField = adaptationLength == 0
                    ? AdaptationField.EMPTY
                    : AdaptationField.parse(data, cursor + 1, adaptationLength);
            cursor += 1 + adaptationLength;
        }

        int payloadLength = control.hasPayload() ? offset + LENGTH - cursor : 0;
        if (payloadLength < 0) {
            // An adaptation field longer than the packet. Malformed rather than
            // merely unaligned, but the caller's recovery is the same: resync.
            return null;
        }

        return new TsPacket(errorIndicator, unitStart, priority, pid, scrambling, control,
                counter, adaptationField, control.hasPayload() ? cursor : offset + LENGTH, payloadLength);
    }

    /** Whether this packet is multiplexer stuffing that carries nothing. */
    public boolean isNull() {
        return pid == NULL_PID;
    }

    /** Whether the payload is encrypted, and so not worth parsing further. */
    public boolean isScrambled() {
        return scramblingControl != 0;
    }

    /** Whether this packet carries any payload bytes at all. */
    public boolean hasPayload() {
        return payloadLength > 0;
    }

    /**
     * The program clock reference this packet carries, in 27 MHz units, or
     * {@code -1} if it carries none. Only some packets on a program's designated
     * PCR PID carry one.
     */
    public long pcr() {
        return adaptationField == null ? -1 : adaptationField.pcr();
    }
}