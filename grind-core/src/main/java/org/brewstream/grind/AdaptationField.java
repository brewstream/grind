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
 * A transport packet's adaptation field: timing and discontinuity metadata that
 * rides alongside the payload (ISO/IEC 13818-1 §2.4.3.4).
 *
 * <p>Only the fields an inspector actually needs are parsed. OPCR, splice
 * countdown, private data and the extension are recognised well enough to be
 * skipped correctly but are not surfaced — see the project roadmap. Parsing
 * exactly the fields that are used keeps the hot path short.
 *
 * @param discontinuity     the continuity counter on this PID is about to jump, or the
 *                          clock reference is about to change, legitimately. A decoder
 *                          must not treat the following counter jump as loss
 * @param randomAccess      the next PES packet on this PID starts at a point a decoder can
 *                          begin from — a keyframe, in practice
 * @param elementaryStreamPriority a hint that this payload matters more than others on the PID
 * @param pcr               the program clock reference in 27 MHz units, or {@code -1} if
 *                          absent. This is the stream's master clock; its arrival times
 *                          against local time are what reveal jitter and drift
 */
public record AdaptationField(
        boolean discontinuity,
        boolean randomAccess,
        boolean elementaryStreamPriority,
        long pcr) {

    /**
     * A zero-length adaptation field: one byte of stuffing, no flags. Shared
     * rather than allocated, since multiplexers emit these constantly to pad to
     * a constant bitrate.
     */
    public static final AdaptationField EMPTY = new AdaptationField(false, false, false, -1);

    /**
     * The 27 MHz tick rate the PCR counts in. The base counts 90 kHz ticks and
     * the extension counts the 300 subdivisions between them, so
     * {@code base * 300 + extension} is a 27 MHz value (§2.4.3.5).
     */
    public static final int PCR_RATE_HZ = 27_000_000;

    /**
     * Parses an adaptation field.
     *
     * @param data   the source array
     * @param offset index of the flags byte — that is, one past the length byte
     * @param length the adaptation field length as it appears on the wire, counting
     *               everything after the length byte itself
     * @return the parsed field
     */
    public static AdaptationField parse(byte[] data, int offset, int length) {
        int flags = data[offset] & 0xFF;
        boolean discontinuity = (flags & 0x80) != 0;
        boolean randomAccess = (flags & 0x40) != 0;
        boolean priority = (flags & 0x20) != 0;
        boolean pcrPresent = (flags & 0x10) != 0;

        long pcr = -1;
        // The flag can claim a PCR the field is too short to hold, in a stream
        // that is damaged or lying. Checking the length rather than trusting the
        // flag keeps a corrupt packet from reading its neighbour's bytes.
        if (pcrPresent && length >= 7) {
            pcr = readPcr(data, offset + 1);
        }

        return new AdaptationField(discontinuity, randomAccess, priority, pcr);
    }

    /**
     * Reads the six-byte PCR: 33 bits of 90 kHz base, 6 reserved, 9 bits of
     * 27 MHz extension.
     */
    private static long readPcr(byte[] data, int offset) {
        long base = ((long) (data[offset] & 0xFF) << 25)
                | ((long) (data[offset + 1] & 0xFF) << 17)
                | ((long) (data[offset + 2] & 0xFF) << 9)
                | ((long) (data[offset + 3] & 0xFF) << 1)
                | ((long) (data[offset + 4] & 0x80) >> 7);
        int extension = ((data[offset + 4] & 0x01) << 8) | (data[offset + 5] & 0xFF);
        return base * 300 + extension;
    }

    /** Whether this field carries a program clock reference. */
    public boolean hasPcr() {
        return pcr >= 0;
    }

    /** The PCR expressed in seconds, for display. {@code -1} when absent. */
    public double pcrSeconds() {
        return pcr < 0 ? -1 : (double) pcr / PCR_RATE_HZ;
    }
}