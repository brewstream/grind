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

package org.brewstream.grind.scte;

import org.brewstream.grind.Crc32Mpeg;
import org.brewstream.grind.SectionAssembler;
import org.brewstream.grind.TableSection;
import org.brewstream.grind.TsPacket;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cases the fixture cannot show, built by altering sections it does contain.
 *
 * <p>These start from real bytes rather than invented ones — a section TSDuck
 * wrote, with one field changed and the checksum recomputed. That keeps the
 * property that makes the other tests worth trusting: nothing here depends on my
 * reading of the layout being right, only on the alteration being the one
 * described.
 */
class SpliceInfoParserTest {

    private static final int SPLICE_PID = 500;

    /** Every splice section in the fixture, whole and CRC-valid. */
    private static List<byte[]> sectionsOf(String resource) throws IOException {
        byte[] data;
        try (InputStream in = SpliceInfoParserTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            data = in.readAllBytes();
        }
        SectionAssembler assembler = new SectionAssembler();
        List<byte[]> sections = new ArrayList<>();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null || packet.pid() != SPLICE_PID) {
                continue;
            }
            for (TableSection section : assembler.consume(packet)) {
                sections.add(section.body());
            }
        }
        assertThat(sections).as("the fixture must carry splice sections").isNotEmpty();
        return sections;
    }

    /** A section carrying a segmentation descriptor, which is what these alter. */
    private static byte[] sectionWithSegmentation() throws IOException {
        for (byte[] section : sectionsOf("/splice.ts")) {
            SpliceInfoSection parsed = SpliceInfoParser.parse(section);
            if (parsed != null && parsed.segmentation() != null) {
                return section;
            }
        }
        throw new AssertionError("no section in the fixture carries a segmentation descriptor");
    }

    /** Rewrites the trailing checksum so an altered section is still well-formed. */
    private static void reseal(byte[] section) {
        int crc = Crc32Mpeg.of(section, 0, section.length - 4);
        int at = section.length - 4;
        section[at] = (byte) (crc >>> 24);
        section[at + 1] = (byte) (crc >>> 16);
        section[at + 2] = (byte) (crc >>> 8);
        section[at + 3] = (byte) crc;
    }

    /** Finds the four-byte "CUEI" identifier inside a descriptor. */
    private static int indexOfCuei(byte[] section) {
        for (int at = 0; at + 4 <= section.length; at++) {
            if (section[at] == 'C' && section[at + 1] == 'U'
                    && section[at + 2] == 'E' && section[at + 3] == 'I') {
                return at;
            }
        }
        throw new AssertionError("the descriptor should carry a CUEI identifier");
    }

    /**
     * A descriptor using tag {@code 0x02} under another authority is not a
     * segmentation descriptor, and must not be read as one.
     *
     * <p>The tag is only unique within SCTE 35's own registry. Trusting it alone
     * would mean parsing some other body's descriptor as if the fields lined up,
     * and the result would look plausible rather than obviously wrong. No
     * fixture can show this, because a real SCTE-35 stream carries only CUEI.
     */
    @Test
    void aDescriptorUnderAnotherAuthorityIsNotRead() throws IOException {
        byte[] section = sectionWithSegmentation().clone();
        assertThat(SpliceInfoParser.parse(section).segmentation())
                .as("the unaltered section does carry one")
                .isNotNull();

        // "XYZQ" in place of "CUEI", everything else untouched.
        int at = indexOfCuei(section);
        section[at] = 'X';
        section[at + 1] = 'Y';
        section[at + 2] = 'Z';
        section[at + 3] = 'Q';
        reseal(section);

        SpliceInfoSection parsed = SpliceInfoParser.parse(section);
        assertThat(parsed).as("the section itself is still valid").isNotNull();
        assertThat(parsed.commandType()).isEqualTo(SpliceCommandType.TIME_SIGNAL);
        assertThat(parsed.segmentations())
                .as("the descriptor belongs to someone else, so it is not read as ours")
                .isEmpty();
    }

    /** A section whose checksum does not match is refused outright. */
    @Test
    void aSectionFailingItsChecksumIsRefused() throws IOException {
        byte[] section = sectionWithSegmentation().clone();
        section[section.length - 1] ^= (byte) 0xFF;

        assertThat(SpliceInfoParser.parse(section))
                .as("a corrupt section is not worth guessing at")
                .isNull();
    }

    /** Bytes that are not a splice section at all are refused rather than misread. */
    @Test
    void anotherTablesSectionIsRefused() throws IOException {
        byte[] section = sectionWithSegmentation().clone();
        section[0] = (byte) 0x02; // a PMT's table id
        reseal(section);

        assertThat(SpliceInfoParser.parse(section))
                .as("only table id 0xFC is a splice information section")
                .isNull();
    }

    /** Truncated input is refused rather than read past its end. */
    @Test
    void aTruncatedSectionIsRefused() throws IOException {
        byte[] section = sectionWithSegmentation();

        for (int length = 0; length < section.length; length += 3) {
            byte[] truncated = new byte[length];
            System.arraycopy(section, 0, truncated, 0, length);
            assertThat(SpliceInfoParser.parse(truncated))
                    .as("truncated to %d bytes", length)
                    .isNull();
        }
        assertThat(SpliceInfoParser.parse(section)).as("whole, it still parses").isNotNull();
    }
}
