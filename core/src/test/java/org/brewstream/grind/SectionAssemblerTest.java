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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section reassembly against hand-built packets.
 *
 * <p>These cases cannot come from the sample stream: its PAT is sixteen bytes
 * and its PMT twenty-six, so both fit in one packet each and neither exercises
 * the parts of this class most likely to be wrong — a section split across
 * packets, two sections sharing one, and the pointer field that makes both
 * possible. Mutating the pointer-field handling left every fixture-driven test
 * passing, which is what prompted these.
 */
class SectionAssemblerTest {

    private static final int TABLE_ID = 0x50;

    /** Builds a complete long section with a correct CRC. */
    private static byte[] section(int tableId, int extension, int bodyLength) {
        int sectionLength = 5 + bodyLength + 4; // extension..last_section + body + CRC
        byte[] out = new byte[3 + sectionLength];
        out[0] = (byte) tableId;
        out[1] = (byte) (0x80 | 0x30 | ((sectionLength >> 8) & 0x0F)); // syntax indicator + reserved
        out[2] = (byte) (sectionLength & 0xFF);
        out[3] = (byte) (extension >> 8);
        out[4] = (byte) extension;
        out[5] = (byte) 0x01; // version 0, current
        out[6] = 0;           // section number
        out[7] = 0;           // last section number
        for (int i = 0; i < bodyLength; i++) {
            out[8 + i] = (byte) (i & 0xFF);
        }
        int crc = Crc32Mpeg.of(out, 0, out.length - 4);
        out[out.length - 4] = (byte) (crc >>> 24);
        out[out.length - 3] = (byte) (crc >>> 16);
        out[out.length - 2] = (byte) (crc >>> 8);
        out[out.length - 1] = (byte) crc;
        return out;
    }

    /**
     * Wraps payload bytes into a transport packet.
     *
     * @param pointer the pointer field value, or -1 for a continuation packet
     *                (payload_unit_start_indicator clear, no pointer byte)
     */
    private static TsPacket packet(int pid, int counter, int pointer, byte[] payload) {
        byte[] data = new byte[TsPacket.LENGTH];
        Arrays.fill(data, (byte) 0xFF);
        data[0] = TsPacket.SYNC_BYTE;
        data[1] = (byte) (((pointer >= 0 ? 0x40 : 0x00)) | ((pid >> 8) & 0x1F));
        data[2] = (byte) pid;
        data[3] = (byte) (0x10 | (counter & 0x0F)); // payload only
        int cursor = 4;
        if (pointer >= 0) {
            data[cursor++] = (byte) pointer;
        }
        System.arraycopy(payload, 0, data, cursor, Math.min(payload.length, TsPacket.LENGTH - cursor));
        return TsPacket.parse(data, 0);
    }

    private static List<TableSection> feed(SectionAssembler assembler, TsPacket... packets) {
        List<TableSection> all = new ArrayList<>();
        for (TsPacket packet : packets) {
            all.addAll(assembler.consume(packet));
        }
        return all;
    }

    @Test
    void aSectionFittingOnePacketIsAssembled() {
        byte[] section = section(TABLE_ID, 7, 20);
        SectionAssembler assembler = new SectionAssembler();

        List<TableSection> sections = feed(assembler, packet(0x100, 0, 0, section));

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).tableId()).isEqualTo(TABLE_ID);
        assertThat(sections.get(0).tableIdExtension()).isEqualTo(7);
        assertThat(sections.get(0).body()).hasSize(20);
    }

    /** A section too big for one packet, which is the normal case for a real PMT. */
    @Test
    void aSectionSpanningThreePacketsIsAssembled() {
        byte[] section = section(TABLE_ID, 9, 400);
        SectionAssembler assembler = new SectionAssembler();

        int first = TsPacket.LENGTH - 5; // 4 header + 1 pointer
        int rest = TsPacket.LENGTH - 4;
        List<TableSection> sections = feed(assembler,
                packet(0x100, 0, 0, Arrays.copyOfRange(section, 0, first)),
                packet(0x100, 1, -1, Arrays.copyOfRange(section, first, first + rest)),
                packet(0x100, 2, -1, Arrays.copyOfRange(section, first + rest, section.length)));

        assertThat(sections).as("completed only once the last packet arrived").hasSize(1);
        assertThat(sections.get(0).body()).hasSize(400);
        assertThat(sections.get(0).tableIdExtension()).isEqualTo(9);
    }

    /**
     * The case the pointer field exists for: a packet finishing one section and
     * starting the next. Skipping the pointer without consuming the bytes it
     * points past truncates the first section and loses it silently.
     */
    @Test
    void aPacketCanFinishOneSectionAndBeginAnother() {
        byte[] first = section(TABLE_ID, 1, 200);
        byte[] second = section(TABLE_ID, 2, 20);
        SectionAssembler assembler = new SectionAssembler();

        int inFirstPacket = TsPacket.LENGTH - 5;
        byte[] tail = Arrays.copyOfRange(first, inFirstPacket, first.length);

        // The second packet carries the tail of the first section, then the whole
        // of the second, with the pointer naming where the second begins.
        byte[] combined = new byte[tail.length + second.length];
        System.arraycopy(tail, 0, combined, 0, tail.length);
        System.arraycopy(second, 0, combined, tail.length, second.length);

        List<TableSection> sections = feed(assembler,
                packet(0x100, 0, 0, Arrays.copyOfRange(first, 0, inFirstPacket)),
                packet(0x100, 1, tail.length, combined));

        assertThat(sections).as("both sections, in order").hasSize(2);
        assertThat(sections.get(0).tableIdExtension()).isEqualTo(1);
        assertThat(sections.get(0).body()).hasSize(200);
        assertThat(sections.get(1).tableIdExtension()).isEqualTo(2);
        assertThat(sections.get(1).body()).hasSize(20);
    }

    /** Two whole sections in one packet, back to back. */
    @Test
    void twoSectionsInOnePacketAreBothAssembled() {
        byte[] first = section(TABLE_ID, 1, 20);
        byte[] second = section(TABLE_ID, 2, 30);
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);

        List<TableSection> sections = feed(new SectionAssembler(), packet(0x100, 0, 0, combined));

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).tableIdExtension()).isEqualTo(1);
        assertThat(sections.get(1).tableIdExtension()).isEqualTo(2);
    }

    /**
     * Padding after a section must not cost us the next table.
     *
     * <p>Stated carefully, because mutation showed the stuffing check is not what
     * makes this hold: removing it still passes, since the next packet's pointer
     * field resets the bogus section the padding started. What this test actually
     * guards is the outcome — that a stuffed packet does not swallow the table
     * after it — by whichever mechanism.
     */
    @Test
    void stuffingAfterASectionDoesNotSwallowTheNextTable() {
        byte[] section = section(TABLE_ID, 3, 20);
        SectionAssembler assembler = new SectionAssembler();

        // First packet: a section followed by ~150 bytes of 0xFF padding.
        // Second: another complete section, which must still be found.
        List<TableSection> sections = feed(assembler,
                packet(0x100, 0, 0, section),
                packet(0x100, 1, 0, section(TABLE_ID, 4, 20)));

        assertThat(sections).as("the padding must not have swallowed the second table").hasSize(2);
        assertThat(sections.get(1).tableIdExtension()).isEqualTo(4);
    }

    /** Joining a stream mid-section: the leading fragment has no known start. */
    @Test
    void continuationBytesWithNothingInProgressAreDiscarded() {
        byte[] section = section(TABLE_ID, 5, 300);
        SectionAssembler assembler = new SectionAssembler();

        // Start from the middle, as a receiver joining late would.
        List<TableSection> sections = feed(assembler,
                packet(0x100, 0, -1, Arrays.copyOfRange(section, 100, 250)),
                packet(0x100, 1, -1, Arrays.copyOfRange(section, 250, section.length)));

        assertThat(sections).as("no section can be claimed from a fragment").isEmpty();
        assertThat(assembler.crcFailures()).as("discarded, not miscounted as corruption").isZero();
    }

    @Test
    void aCorruptSectionIsCountedAndDropped() {
        byte[] section = section(TABLE_ID, 6, 20);
        section[10] ^= 0x5A;

        SectionAssembler assembler = new SectionAssembler();
        List<TableSection> sections = feed(assembler, packet(0x100, 0, 0, section));

        assertThat(sections).isEmpty();
        assertThat(assembler.crcFailures()).isEqualTo(1);
        assertThat(assembler.sectionsCompleted()).isZero();
    }

    /** A flagged packet cannot be trusted to continue a section it was part of. */
    @Test
    void aTransportErroredPacketAbandonsTheSectionInProgress() {
        byte[] section = section(TABLE_ID, 8, 400);
        SectionAssembler assembler = new SectionAssembler();

        int first = TsPacket.LENGTH - 5;
        byte[] data = new byte[TsPacket.LENGTH];
        Arrays.fill(data, (byte) 0xFF);
        data[0] = TsPacket.SYNC_BYTE;
        data[1] = (byte) 0x81; // transport error indicator, PID 0x100
        data[2] = 0x00;
        data[3] = 0x11;

        List<TableSection> sections = feed(assembler,
                packet(0x100, 0, 0, Arrays.copyOfRange(section, 0, first)),
                TsPacket.parse(data, 0),
                packet(0x100, 2, -1, Arrays.copyOfRange(section, first, section.length)));

        assertThat(sections).as("the section is abandoned rather than stitched across a bad packet")
                .isEmpty();
    }
}