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
 * The header of a PES packet — the envelope an elementary stream's data travels
 * in, and where its presentation timing lives (ISO/IEC 13818-1 §2.4.3.6).
 *
 * <p>A PES packet begins in a transport packet whose
 * {@code payload_unit_start_indicator} is set, and continues across as many
 * more as it needs. Only the header is parsed here: it carries the timestamps,
 * which is what a health view wants. Reassembling the payload into access units
 * is a separate job.
 *
 * <p><b>Timestamps are 33 bits at 90 kHz</b>, spread across five bytes with a
 * marker bit after every few — a layout that exists so a decoder scanning for a
 * start code cannot mistake a timestamp for one. They wrap every ~26.5 hours,
 * the same period as the PCR, and are in the same time base, so
 * {@code pts - pcr} is how far ahead of the clock a frame is due: the buffer
 * depth a decoder needs, and a number that drifting apart is an early sign of a
 * stream going wrong.
 *
 * @param streamId          which elementary stream this belongs to: 0xE0–0xEF video,
 *                          0xC0–0xDF audio, and various reserved values above
 * @param packetLength      the declared length after this field, or 0 meaning unbounded —
 *                          legal for video, where a frame's size is not known in advance
 * @param pts               presentation timestamp in 90 kHz units, or -1 if absent
 * @param dts               decode timestamp in 90 kHz units, or -1 if absent. Present only
 *                          when it differs from the PTS, which is to say only where
 *                          frames are reordered
 * @param dataAlignment     the payload starts on an access unit boundary
 * @param headerLength      total header length, so the payload starts this far in
 */
public record PesHeader(
        int streamId,
        int packetLength,
        long pts,
        long dts,
        boolean dataAlignment,
        int headerLength) {

    /** Every PES packet starts with these three bytes. */
    private static final int START_CODE_PREFIX = 0x00_0001;

    /** The clock timestamps are counted in, shared with the PCR's base. */
    public static final int TIMESTAMP_RATE_HZ = 90_000;

    /** The shortest possible header: start code, stream id, and length. */
    private static final int MINIMUM_LENGTH = 6;

    /**
     * Parses a PES header.
     *
     * @param data   the payload of the transport packet that started this PES packet
     * @param offset where that payload begins
     * @param length how many bytes are available
     * @return the parsed header, or {@code null} if the start code is absent or the
     *         header runs past what is available — either means this is not the
     *         start of a PES packet, or not all of it has arrived
     */
    public static PesHeader parse(byte[] data, int offset, int length) {
        if (length < MINIMUM_LENGTH) {
            return null;
        }
        int prefix = ((data[offset] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
        if (prefix != START_CODE_PREFIX) {
            return null;
        }

        int streamId = data[offset + 3] & 0xFF;
        int packetLength = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);

        if (!hasExtension(streamId)) {
            // Padding and a handful of control streams carry no optional header
            // at all, so their payload starts immediately after the length.
            return new PesHeader(streamId, packetLength, -1, -1, false, MINIMUM_LENGTH);
        }
        if (length < MINIMUM_LENGTH + 3) {
            return null;
        }

        int flags1 = data[offset + 6] & 0xFF;
        int flags2 = data[offset + 7] & 0xFF;
        int headerDataLength = data[offset + 8] & 0xFF;
        boolean dataAlignment = (flags1 & 0x04) != 0;
        int ptsDtsFlags = (flags2 & 0xC0) >> 6;
        int headerLength = MINIMUM_LENGTH + 3 + headerDataLength;

        long pts = -1;
        long dts = -1;
        int cursor = offset + 9;
        // '10' means a PTS alone, '11' a PTS then a DTS. '01' is forbidden, and
        // treated as absent rather than guessed at.
        if (ptsDtsFlags == 0b10 && length >= (cursor - offset) + 5) {
            pts = readTimestamp(data, cursor);
        } else if (ptsDtsFlags == 0b11 && length >= (cursor - offset) + 10) {
            pts = readTimestamp(data, cursor);
            dts = readTimestamp(data, cursor + 5);
        }

        return new PesHeader(streamId, packetLength, pts, dts, dataAlignment, headerLength);
    }

    /**
     * Reads a 33-bit timestamp from five bytes.
     *
     * <p>The bits are split 3/15/15 with marker bits between, so this is not a
     * big-endian integer and cannot be read as one: bits 32-30 sit in the top
     * byte above a marker, and each subsequent group has its own low marker bit
     * to skip.
     */
    private static long readTimestamp(byte[] data, int offset) {
        return ((long) (data[offset] & 0x0E) << 29)
                | ((long) (data[offset + 1] & 0xFF) << 22)
                | ((long) (data[offset + 2] & 0xFE) << 14)
                | ((long) (data[offset + 3] & 0xFF) << 7)
                | ((long) (data[offset + 4] & 0xFE) >> 1);
    }

    /**
     * Whether a stream id carries the optional header with flags and timestamps.
     * A short list of control and padding streams does not (Table 2-18).
     */
    private static boolean hasExtension(int streamId) {
        return switch (streamId) {
            case 0xBC, // program_stream_map
                 0xBE, // padding_stream
                 0xBF, // private_stream_2
                 0xF0, // ECM
                 0xF1, // EMM
                 0xF2, // DSM-CC
                 0xF8, // ITU-T H.222.1 type E
                 0xFF  // program_stream_directory
                    -> false;
            default -> true;
        };
    }

    /** Whether this packet carries a presentation timestamp. */
    public boolean hasPts() {
        return pts >= 0;
    }

    /** Whether this packet carries a separate decode timestamp, meaning frames are reordered. */
    public boolean hasDts() {
        return dts >= 0;
    }

    /** The presentation timestamp in seconds, for display. {@code -1} when absent. */
    public double ptsSeconds() {
        return pts < 0 ? -1 : (double) pts / TIMESTAMP_RATE_HZ;
    }

    /** Whether this is a video stream, from its stream id (0xE0–0xEF). */
    public boolean isVideo() {
        return streamId >= 0xE0 && streamId <= 0xEF;
    }

    /** Whether this is an audio stream, from its stream id (0xC0–0xDF). */
    public boolean isAudio() {
        return streamId >= 0xC0 && streamId <= 0xDF;
    }
}