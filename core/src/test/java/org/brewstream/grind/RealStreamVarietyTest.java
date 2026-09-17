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
 * Two further real streams, added because one fixture could not show enough.
 *
 * <p>Mutation testing kept surviving on paths {@code sample.ts} never takes — its
 * PAT is sixteen bytes, its PMT twenty-six, it has one program, and its encoder
 * emits no B-frames. Hand-built inputs closed those paths, but they cannot close
 * one particular gap, and it is the reason these exist:
 *
 * <p><b>A hand-built fixture written by whoever wrote the parser encodes the same
 * belief as the parser.</b> The DTS test in {@code PesHeaderTest} builds a
 * timestamp with the same understanding of the 33-bit marker-bit layout that the
 * parser reads it with. If that understanding is wrong, both are wrong together
 * and the test passes. For PTS that risk is covered, because ffprobe decodes the
 * same bytes independently and agrees. For DTS nothing covered it, because
 * {@code sample.ts} contains no DTS at all — zero packets where it differs from
 * the PTS.
 *
 * <ul>
 *   <li>{@code bframes.ts} — x264 with {@code -bf 2}, so all fifty video packets
 *       carry a real DTS that differs from the PTS and is reordered against it.</li>
 *   <li>{@code multiprogram.ts} — two programs, two PMTs on separate PIDs, four
 *       elementary streams.</li>
 * </ul>
 */
class RealStreamVarietyTest {

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data;
        try (InputStream in = RealStreamVarietyTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    private static List<PesHeader> videoHeaders(String resource, int pid) throws IOException {
        byte[] data;
        try (InputStream in = RealStreamVarietyTest.class.getResourceAsStream(resource)) {
            data = in.readAllBytes();
        }
        List<PesHeader> headers = new ArrayList<>();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == pid && packet.payloadUnitStart() && packet.hasPayload()) {
                PesHeader header = PesHeader.parse(packet.payload(), 0, packet.payloadLength());
                if (header != null) {
                    headers.add(header);
                }
            }
        }
        return headers;
    }

    // --- bframes.ts: a real decode timestamp, independently witnessed

    /**
     * ffprobe reports the first video packet of {@code bframes.ts} as
     * {@code pts 133200, dts 126000}. Those numbers come from a decoder that has
     * never seen this parser, which is what makes agreeing with them worth
     * something — unlike a hand-built header, which would agree with a shared
     * misreading.
     */
    @Test
    void aRealDecodeTimestampMatchesFfprobe() throws IOException {
        PesHeader first = videoHeaders("/bframes.ts", 0x100).get(0);

        assertThat(first.hasDts()).as("B-frames mean a DTS is genuinely carried").isTrue();
        assertThat(first.pts()).as("ffprobe: pts 133200").isEqualTo(133_200L);
        assertThat(first.dts()).as("ffprobe: dts 126000").isEqualTo(126_000L);
        assertThat(first.dts()).isLessThan(first.pts());
    }

    /**
     * With B-frames the decode order is not the presentation order. DTS must
     * advance monotonically while PTS does not — the whole reason the two fields
     * exist separately, and a property no hand-built fixture of mine established.
     */
    @Test
    void decodeOrderIsMonotonicWhilePresentationOrderIsNot() throws IOException {
        List<PesHeader> headers = videoHeaders("/bframes.ts", 0x100);

        assertThat(headers).hasSize(50);
        boolean presentationWentBackwards = false;
        for (int i = 1; i < headers.size(); i++) {
            assertThat(headers.get(i).dts())
                    .as("decode timestamps must never go backwards, at frame %d", i)
                    .isGreaterThan(headers.get(i - 1).dts());
            if (headers.get(i).pts() < headers.get(i - 1).pts()) {
                presentationWentBackwards = true;
            }
        }

        assertThat(presentationWentBackwards)
                .as("reordered frames are the point of this fixture")
                .isTrue();
    }

    @Test
    void everyVideoPacketInTheBframeStreamCarriesBothTimestamps() throws IOException {
        List<PesHeader> headers = videoHeaders("/bframes.ts", 0x100);

        assertThat(headers).allSatisfy(header -> {
            assertThat(header.hasPts()).isTrue();
            assertThat(header.hasDts()).isTrue();
        });
    }

    @Test
    void theBframeStreamIsOtherwiseHealthy() throws IOException {
        TsStreamStats stats = analyze("/bframes.ts").stats();

        assertThat(stats.isHealthy()).isTrue();
        assertThat(stats.crcErrors()).isZero();
    }

    // --- multiprogram.ts: two programs, two PMTs

    /**
     * TSDuck reports: program 1 on PID 0x1000 with PCR PID 0x0100, program 2 on
     * PID 0x1001 with PCR PID 0x0102, each carrying AVC video and AAC audio.
     */
    @Test
    void twoProgramsAndTwoPmtsAreBothAssembled() throws IOException {
        ProgramMap programs = analyze("/multiprogram.ts").programs();

        assertThat(programs.pmtPids())
                .as("tstables: program 1 -> 0x1000, program 2 -> 0x1001")
                .containsExactly(java.util.Map.entry(1, 0x1000), java.util.Map.entry(2, 0x1001));
        assertThat(programs.programs()).hasSize(2);

        ProgramMapTable first = programs.programs().get(1);
        assertThat(first.pcrPid()).isEqualTo(0x0100);
        assertThat(first.streams()).extracting(ElementaryStream::pid).containsExactly(0x0100, 0x0101);

        ProgramMapTable second = programs.programs().get(2);
        assertThat(second.pcrPid()).as("tstables: PCR PID 0x0102").isEqualTo(0x0102);
        assertThat(second.streams()).extracting(ElementaryStream::pid).containsExactly(0x0102, 0x0103);
    }

    /** A PID must be attributed to the right program, not merely to some program. */
    @Test
    void pidsAreDescribedAgainstTheProgramTheyActuallyBelongTo() throws IOException {
        ProgramMap programs = analyze("/multiprogram.ts").programs();

        assertThat(programs.describe(0x0100)).isEqualTo("program 1 H.264 / AVC");
        assertThat(programs.describe(0x0101)).isEqualTo("program 1 AAC (ADTS)");
        assertThat(programs.describe(0x0102)).isEqualTo("program 2 H.264 / AVC");
        assertThat(programs.describe(0x0103)).isEqualTo("program 2 AAC (ADTS)");
        assertThat(programs.describe(0x1001)).isEqualTo("PMT for program 2");
        assertThat(programs.allStreams()).hasSize(4);
    }

    /** Every track of both programs must be tracked and timed, not just the first program's. */
    @Test
    void allFourTracksAreTrackedIndependently() throws IOException {
        TsStreamStats stats = analyze("/multiprogram.ts").stats();

        for (int pid : new int[]{0x0100, 0x0101, 0x0102, 0x0103}) {
            PidStats track = stats.pid(pid);
            assertThat(track).as("PID 0x%X must have been seen", pid).isNotNull();
            assertThat(track.carriesPes()).as("PID 0x%X carries PES", pid).isTrue();
            assertThat(track.lastPts()).as("PID 0x%X is timed", pid).isPositive();
        }
        assertThat(stats.isHealthy()).isTrue();
    }

    /** Two PMT PIDs means two section assemblers, both of which must work. */
    @Test
    void bothPmtPidsAreAssembledAndNeitherIsMistakenForTheOther() throws IOException {
        TsAnalyzer analyzer = analyze("/multiprogram.ts");

        assertThat(analyzer.stats().crcErrors()).isZero();
        assertThat(analyzer.programs().programs().get(1).streams().get(0).pid()).isEqualTo(0x0100);
        assertThat(analyzer.programs().programs().get(2).streams().get(0).pid()).isEqualTo(0x0102);
    }
}