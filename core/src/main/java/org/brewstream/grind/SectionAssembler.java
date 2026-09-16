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
import java.util.Arrays;
import java.util.List;

/**
 * Reassembles PSI sections from the transport packets of one PID
 * (ISO/IEC 13818-1 §2.4.4).
 *
 * <p>Three things make this more than a concatenation, and each is a place a
 * naive implementation quietly produces wrong tables rather than failing:
 *
 * <ul>
 *   <li><b>The pointer field.</b> A packet that starts a section
 *       ({@code payload_unit_start_indicator} set) begins its payload with one
 *       byte giving how far ahead the new section starts. The bytes in between
 *       belong to the <em>previous</em> section, finishing it. Skipping the
 *       pointer without consuming those bytes truncates every table that
 *       happens to straddle a packet.</li>
 *   <li><b>Sections span packets, and packets hold several sections.</b> A long
 *       PMT needs more than one packet; a small PAT and the section after it
 *       share one. Both directions have to work.</li>
 *   <li><b>Stuffing.</b> The remainder of a packet after the last section is
 *       filled with {@code 0xFF}. A table id of {@code 0xFF} is the signal to
 *       stop, not a table to parse.</li>
 * </ul>
 *
 * <p>Sections failing their CRC are dropped and counted rather than surfaced.
 * That is the point of the checksum: a table assembled across a continuity break
 * looks structurally fine and describes a stream that does not exist.
 *
 * <p>One assembler per PID. Not thread-safe, like the rest of this package.
 */
public final class SectionAssembler {

    /** The byte multiplexers pad with, and a table id that therefore cannot be real. */
    private static final int STUFFING = 0xFF;

    /** Enough for any single section: the length field is 12 bits, plus the 3-byte prefix. */
    private static final int MAX_SECTION_LENGTH = 4096 + 3;

    private byte[] pending = new byte[0];
    private int pendingLength;
    private int expectedLength = -1;
    private long crcFailures;
    private long sectionsCompleted;

    /**
     * Feeds one packet's payload in.
     *
     * @param packet a packet on this assembler's PID, carrying payload
     * @return the sections completed by this packet, in order — usually empty,
     *         since most packets of a large table complete nothing
     */
    public List<TableSection> consume(TsPacket packet) {
        if (!packet.hasPayload() || packet.transportErrorIndicator() || packet.isScrambled()) {
            // A flagged or encrypted packet cannot be trusted to continue a
            // section; whatever was in progress is now unreliable.
            reset();
            return List.of();
        }

        byte[] payload = packet.payload();
        int cursor = 0;
        List<TableSection> completed = new ArrayList<>(2);

        if (packet.payloadUnitStart()) {
            int pointer = payload[0] & 0xFF;
            cursor = 1;
            if (pointer > 0) {
                // These bytes finish the section already in progress. If none is
                // in progress they are the tail of one whose start we missed -
                // joining a stream mid-table - and are discarded by append().
                int available = Math.min(pointer, payload.length - cursor);
                append(payload, cursor, available, completed);
                cursor += available;
            } else if (expectedLength > 0) {
                // A new section starts immediately while one is unfinished: the
                // old one will never be completed, so drop it rather than let
                // its bytes contaminate the new table.
                reset();
            }
        } else if (expectedLength < 0) {
            // Continuation bytes with nothing in progress: we joined mid-section
            // and cannot know where this one began.
            return List.of();
        }

        while (cursor < payload.length) {
            if (expectedLength < 0 && (payload[cursor] & 0xFF) == STUFFING) {
                // The rest of the packet is padding. This is an optimisation
                // rather than a correctness guard: without it, 0xFF is read as a
                // table id announcing a 4098-byte section, which buffers the
                // padding and is then discarded when the next packet's pointer
                // field resets us. Verified by mutation - removing this check
                // fails no test. It is kept because PSI PIDs repeat constantly
                // and buffering four kilobytes of padding per packet to throw it
                // away is work worth not doing.
                break;
            }
            int consumed = append(payload, cursor, payload.length - cursor, completed);
            if (consumed <= 0) {
                break;
            }
            cursor += consumed;
        }

        return completed;
    }

    /**
     * Appends bytes to the section in progress, starting one if needed, and
     * emits it once complete.
     *
     * @return how many bytes were consumed
     */
    private int append(byte[] payload, int offset, int length, List<TableSection> completed) {
        if (length <= 0) {
            return 0;
        }

        if (expectedLength < 0) {
            // Starting a section. The length lives in the low 12 bits of bytes
            // 1-2 and counts everything after itself, so the total is 3 more.
            if (length < 3) {
                // Split across the packet boundary before the length is even
                // readable. Buffer what there is and decide next time.
                grow(3);
                System.arraycopy(payload, offset, pending, pendingLength, length);
                pendingLength += length;
                return length;
            }
            expectedLength = 3 + (((payload[offset + 1] & 0x0F) << 8) | (payload[offset + 2] & 0xFF));
            if (expectedLength > MAX_SECTION_LENGTH) {
                reset();
                return length;
            }
            grow(expectedLength);
        }

        int needed = expectedLength - pendingLength;
        int taken = Math.min(needed, length);
        System.arraycopy(payload, offset, pending, pendingLength, taken);
        pendingLength += taken;

        if (pendingLength == expectedLength) {
            TableSection section = finish();
            if (section != null) {
                completed.add(section);
            }
            reset();
        }
        return taken;
    }

    /** Validates and parses a fully buffered section. */
    private TableSection finish() {
        int tableId = pending[0] & 0xFF;
        boolean longSection = (pending[1] & 0x80) != 0;
        if (!longSection) {
            // A short section carries no CRC, version or section numbering. None
            // of the tables this library reads use them, so it is skipped rather
            // than guessed at.
            return null;
        }
        if (!Crc32Mpeg.isValid(pending, 0, expectedLength)) {
            crcFailures++;
            return null;
        }

        int extension = ((pending[3] & 0xFF) << 8) | (pending[4] & 0xFF);
        int version = (pending[5] & 0x3E) >> 1;
        boolean current = (pending[5] & 0x01) != 0;
        int sectionNumber = pending[6] & 0xFF;
        int lastSectionNumber = pending[7] & 0xFF;

        int bodyStart = TableSection.LONG_HEADER_LENGTH;
        int bodyLength = expectedLength - bodyStart - TableSection.CRC_LENGTH;
        if (bodyLength < 0) {
            return null;
        }

        sectionsCompleted++;
        return new TableSection(tableId, extension, version, current, sectionNumber, lastSectionNumber,
                Arrays.copyOfRange(pending, bodyStart, bodyStart + bodyLength));
    }

    private void grow(int capacity) {
        if (pending.length < capacity) {
            pending = Arrays.copyOf(pending, Math.max(capacity, MAX_SECTION_LENGTH));
        }
    }

    private void reset() {
        pendingLength = 0;
        expectedLength = -1;
    }

    /** Sections discarded because their checksum did not match — a sign of loss on this PID. */
    public long crcFailures() {
        return crcFailures;
    }

    /** Sections successfully reassembled and verified. */
    public long sectionsCompleted() {
        return sectionsCompleted;
    }
}