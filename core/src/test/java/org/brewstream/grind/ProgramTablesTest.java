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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PSI assembly and table parsing, checked against what TSDuck independently
 * reports about the same file.
 *
 * <p>{@code tstables sample.ts} says:
 * <pre>
 *   PAT, TID 0x00, PID 0x0000 — TS id 1, Program 1 → PID 4096 (0x1000)
 *   PMT, TID 0x02, PID 0x1000 — Program 1, PCR PID 0x0100
 *                               type 0x1B (AVC video)      PID 0x0100
 *                               type 0x0F (MPEG-2 AAC)     PID 0x0101
 * </pre>
 * Every expectation below is one of those numbers. A parser that agrees with its
 * own test proves nothing; agreeing with a mature external tool on a real
 * multiplexer's output is the claim worth making.
 */
class ProgramTablesTest {

    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    private static TsAnalyzer analyzeSample() throws IOException {
        byte[] data;
        try (InputStream in = ProgramTablesTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    @Test
    void theProgramAssociationTableMatchesTsduck() throws IOException {
        ProgramMap programs = analyzeSample().programs();

        assertThat(programs.isKnown()).isTrue();
        assertThat(programs.transportStreamId()).as("tstables: TS id 1").isEqualTo(1);
        assertThat(programs.pmtPids()).as("tstables: Program 1 -> PID 4096")
                .containsExactly(java.util.Map.entry(1, PMT_PID));
    }

    @Test
    void theProgramMapTableMatchesTsduck() throws IOException {
        ProgramMap programs = analyzeSample().programs();
        ProgramMapTable pmt = programs.programs().get(1);

        assertThat(pmt).as("program 1's PMT must have been assembled").isNotNull();
        assertThat(pmt.programNumber()).isEqualTo(1);
        assertThat(pmt.pcrPid()).as("tstables: PCR PID 0x0100").isEqualTo(VIDEO_PID);
        assertThat(pmt.streams()).hasSize(2);

        ElementaryStream video = pmt.streams().get(0);
        assertThat(video.pid()).isEqualTo(VIDEO_PID);
        assertThat(video.rawType()).as("tstables: type 0x1B").isEqualTo(0x1B);
        assertThat(video.streamType()).isEqualTo(StreamType.H264);
        assertThat(video.streamType().kind()).isEqualTo(StreamType.Kind.VIDEO);

        ElementaryStream audio = pmt.streams().get(1);
        assertThat(audio.pid()).isEqualTo(AUDIO_PID);
        assertThat(audio.rawType()).as("tstables: type 0x0F").isEqualTo(0x0F);
        assertThat(audio.streamType()).isEqualTo(StreamType.ADTS_AAC);
        assertThat(audio.streamType().kind()).isEqualTo(StreamType.Kind.AUDIO);
    }

    /** The point of all this: a PID number becomes something a person can read. */
    @Test
    void aPidCanBeDescribedInTermsOfItsProgramAndCodec() throws IOException {
        ProgramMap programs = analyzeSample().programs();

        assertThat(programs.describe(VIDEO_PID)).isEqualTo("program 1 H.264 / AVC");
        assertThat(programs.describe(AUDIO_PID)).isEqualTo("program 1 AAC (ADTS)");
        assertThat(programs.describe(TsPacket.PAT_PID)).isEqualTo("PAT");
        assertThat(programs.describe(PMT_PID)).isEqualTo("PMT for program 1");
        assertThat(programs.describe(0x0011)).as("SDT is not in the PAT, so not ours to name").isNull();
    }

    @Test
    void theProgramsChangedEventFiresOnceTheTablesAreKnown() throws IOException {
        byte[] data;
        try (InputStream in = ProgramTablesTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        List<ProgramMap> announced = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onProgramsChanged(ProgramMap programs) {
                announced.add(programs);
            }
        });

        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }

        // The PAT arrives first, then the PMT: two changes, not one per repeat.
        // ffmpeg repeats both tables throughout the file, and an event per repeat
        // would make this useless as a change signal.
        assertThat(announced).as("PAT then PMT, and nothing for the repeats").hasSize(2);
        assertThat(announced.get(announced.size() - 1).allStreams()).hasSize(2);
    }

    @Test
    void everySectionInTheFixturePassesItsChecksum() throws IOException {
        TsStreamStats stats = analyzeSample().stats();

        assertThat(stats.tableCrcFailures()).as("a locally muxed file has intact tables").isZero();
        assertThat(stats.isHealthy()).isTrue();
    }

    /**
     * A section whose CRC does not match must be discarded. Without the check a
     * table assembled across a continuity break parses cleanly and describes a
     * stream that does not exist — structurally plausible, entirely wrong.
     */
    @Test
    void aSectionWithACorruptChecksumIsDiscarded() throws IOException {
        byte[] data;
        try (InputStream in = ProgramTablesTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }

        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == TsPacket.PAT_PID || packet.pid() == PMT_PID) {
                // Corrupt a byte inside the section, not in the 0xFF stuffing
                // that follows it - a PAT is only sixteen bytes, so most of the
                // packet is padding and flipping a byte there changes nothing.
                // Six past the payload start is within the section body for both
                // tables, leaving the header and declared lengths intact so it
                // still assembles into a section-shaped thing.
                byte[] copy = java.util.Arrays.copyOfRange(data, offset, offset + TsPacket.LENGTH);
                copy[(packet.payloadOffset() - offset) + 6] ^= 0x5A;
                packet = TsPacket.parse(copy, 0);
            }
            analyzer.consume(packet);
        }

        assertThat(analyzer.programs().isKnown())
                .as("no table should have been accepted").isFalse();
        assertThat(analyzer.stats().tableCrcFailures()).isPositive();
        assertThat(analyzer.stats().isHealthy()).as("bad tables are a health problem").isFalse();
    }

    @Test
    void anUnrecognisedStreamTypeIsNamedRatherThanDropped() {
        ElementaryStream stream = new ElementaryStream(0x200, StreamType.fromCode(0x9A), 0x9A);

        assertThat(stream.streamType()).isEqualTo(StreamType.UNKNOWN);
        assertThat(stream.label()).as("a track we cannot name is still a track").isEqualTo("type 0x9A");
    }
}