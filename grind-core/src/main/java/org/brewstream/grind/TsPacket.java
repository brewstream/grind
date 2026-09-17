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
import java.util.Objects;

/**
 * One MPEG-TS transport packet: a fixed 188 bytes, four of header and the rest
 * split between an optional adaptation field and an optional payload
 * (ISO/IEC 13818-1 §2.4.3.2).
 *
 * <p><b>A view, not a copy.</b> Parsing records where the payload sits rather
 * than extracting it, because a transport stream at broadcast rates is tens of
 * thousands of these a second and copying each one's payload would dominate the
 * cost of doing anything useful with them. The packet keeps a reference to the
 * array it was parsed from, so {@link #payload()} and
 * {@link #payloadInto(byte[], int)} can reach the bytes — <b>that array must
 * outlive the packet, and must not be modified while the packet is in use</b>.
 * A caller reusing one buffer across packets has to consume each packet before
 * reading the next.
 *
 * <p>This is a class rather than a record precisely because of that array: a
 * record would derive {@code equals} from array identity, which is a worse
 * answer than being explicit. {@link #equals} compares the header fields and the
 * payload bytes, so two packets parsed from different buffers can be equal.
 *
 * <p><b>Why {@code byte[]} rather than {@code ByteBuffer} or Netty's
 * {@code ByteBuf}.</b> This module deliberately has no dependencies, and a
 * parser that owns no buffer type is the easiest to bridge to whichever one the
 * caller already has. The {@code grind-netty} module carries the Netty glue.
 */
public final class TsPacket {

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

    private static final byte[] NO_DATA = new byte[0];

    private final byte[] data;
    private final boolean transportErrorIndicator;
    private final boolean payloadUnitStart;
    private final boolean transportPriority;
    private final int pid;
    private final int scramblingControl;
    private final AdaptationFieldControl adaptationFieldControl;
    private final int continuityCounter;
    private final AdaptationField adaptationField;
    private final int payloadOffset;
    private final int payloadLength;

    /**
     * Constructs a packet directly, for synthesising one rather than parsing it.
     *
     * @param data          the array {@code payloadOffset} indexes into. May be empty when the
     *                      packet is synthesised and carries no readable payload, in which case
     *                      {@code payloadLength} must be zero
     * @param payloadOffset index into {@code data} where the payload starts
     * @param payloadLength payload length in bytes; zero when there is no payload
     */
    public TsPacket(byte[] data, boolean transportErrorIndicator, boolean payloadUnitStart,
            boolean transportPriority, int pid, int scramblingControl,
            AdaptationFieldControl adaptationFieldControl, int continuityCounter,
            AdaptationField adaptationField, int payloadOffset, int payloadLength) {
        this.data = data == null ? NO_DATA : data;
        this.transportErrorIndicator = transportErrorIndicator;
        this.payloadUnitStart = payloadUnitStart;
        this.transportPriority = transportPriority;
        this.pid = pid;
        this.scramblingControl = scramblingControl;
        this.adaptationFieldControl = adaptationFieldControl;
        this.continuityCounter = continuityCounter;
        this.adaptationField = adaptationField;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    /**
     * Parses the 188 bytes starting at {@code offset}.
     *
     * @param data   the array to parse from. Retained by the returned packet so its
     *               payload stays reachable, so it must outlive the packet
     * @param offset index of the sync byte
     * @return the parsed packet, or {@code null} if the packet is not usable — the
     *         sync byte is not where it should be, or the adaptation field claims
     *         more bytes than the packet holds. Either way the caller has to
     *         resynchronise rather than trust the next 188 bytes
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

        // Checked against the packet end regardless of whether a payload is
        // expected. Deriving this from payloadLength alone missed the case where
        // the control field says "adaptation only": the length was then zero by
        // definition, so an adaptation field claiming 200 bytes sailed through
        // and produced a packet describing bytes that are not in it.
        if (cursor > offset + LENGTH) {
            return null;
        }

        int payloadLength = control.hasPayload() ? offset + LENGTH - cursor : 0;
        return new TsPacket(data, errorIndicator, unitStart, priority, pid, scrambling, control,
                counter, adaptationField, control.hasPayload() ? cursor : offset + LENGTH, payloadLength);
    }

    /**
     * Set by an upstream demodulator to mean "this packet is known corrupt". Its
     * payload should not be trusted, and its continuity counter should not be
     * treated as a discontinuity (§2.4.3.3).
     */
    public boolean transportErrorIndicator() {
        return transportErrorIndicator;
    }

    /**
     * For a PES PID, this packet begins a new PES packet; for a PSI PID, it
     * begins a new section and the payload's first byte is a pointer field.
     */
    public boolean payloadUnitStart() {
        return payloadUnitStart;
    }

    /** A hint from the multiplexer that this packet matters more than others on the same PID. */
    public boolean transportPriority() {
        return transportPriority;
    }

    /** Which elementary stream or table this packet belongs to, 13 bits. */
    public int pid() {
        return pid;
    }

    /** Zero means clear; anything else means the payload is encrypted. */
    public int scramblingControl() {
        return scramblingControl;
    }

    /** Which of adaptation field and payload are present. */
    public AdaptationFieldControl adaptationFieldControl() {
        return adaptationFieldControl;
    }

    /** Increments per packet on a PID, wrapping at 16 — the basis of loss detection. */
    public int continuityCounter() {
        return continuityCounter;
    }

    /** The parsed adaptation field, or {@code null} when absent. */
    public AdaptationField adaptationField() {
        return adaptationField;
    }

    /** Index into the backing array where the payload starts. */
    public int payloadOffset() {
        return payloadOffset;
    }

    /** Payload length in bytes; zero when there is no payload. */
    public int payloadLength() {
        return payloadLength;
    }

    /**
     * The payload bytes, copied into a fresh array.
     *
     * <p>Allocates, so it suits inspection rather than a per-packet hot path —
     * use {@link #payloadInto(byte[], int)} there.
     *
     * @return the payload, or an empty array when the packet carries none
     */
    public byte[] payload() {
        if (payloadLength == 0) {
            return NO_DATA;
        }
        return Arrays.copyOfRange(data, payloadOffset, payloadOffset + payloadLength);
    }

    /**
     * Copies the payload into {@code destination} without allocating.
     *
     * @param destination where to copy to
     * @param offset      index in {@code destination} to start at
     * @return how many bytes were copied, which is {@link #payloadLength()}
     * @throws IndexOutOfBoundsException if the payload does not fit
     */
    public int payloadInto(byte[] destination, int offset) {
        if (payloadLength > 0) {
            System.arraycopy(data, payloadOffset, destination, offset, payloadLength);
        }
        return payloadLength;
    }

    /** Whether this packet is multiplexer stuffing that carries nothing. */
    public boolean isNull() {
        return pid == NULL_PID;
    }

    /** Whether the payload is encrypted, and so not worth parsing further. */
    public boolean isScrambled() {
        return scramblingControl != 0;
    }

    /**
     * Whether this packet carries any payload <em>bytes</em>.
     *
     * <p>Distinct from {@code adaptationFieldControl().hasPayload()}, which is
     * the flag on the wire. The two disagree when an adaptation field fills the
     * packet exactly: the control field says payload, and there are no bytes left
     * for one. Continuity counting must use the flag, not this — the counter
     * increments on the flag (§2.4.3.3), and using this instead reports a
     * spurious error on every such packet.
     */
    public boolean hasPayload() {
        return payloadLength > 0;
    }

    /**
     * The program clock reference this packet carries, in 27 MHz units, or
     * {@code -1} if it carries none.
     */
    public long pcr() {
        return adaptationField == null ? -1 : adaptationField.pcr();
    }

    /** Compares header fields and payload content, not the identity of the backing array. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TsPacket that)) {
            return false;
        }
        return transportErrorIndicator == that.transportErrorIndicator
                && payloadUnitStart == that.payloadUnitStart
                && transportPriority == that.transportPriority
                && pid == that.pid
                && scramblingControl == that.scramblingControl
                && adaptationFieldControl == that.adaptationFieldControl
                && continuityCounter == that.continuityCounter
                && Objects.equals(adaptationField, that.adaptationField)
                && Arrays.equals(payload(), that.payload());
    }

    @Override
    public int hashCode() {
        return Objects.hash(transportErrorIndicator, payloadUnitStart, transportPriority, pid,
                scramblingControl, adaptationFieldControl, continuityCounter, adaptationField,
                Arrays.hashCode(payload()));
    }

    @Override
    public String toString() {
        return "TsPacket[pid=0x" + Integer.toHexString(pid) + ", cc=" + continuityCounter
                + ", " + adaptationFieldControl + ", payload=" + payloadLength + " bytes"
                + (isScrambled() ? ", scrambled" : "") + "]";
    }
}
