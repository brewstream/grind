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
 * Keyframes from real encoder output (fixtures/make-keyframe-ts.sh). Expected
 * codec strings come from an independent implementation: ffmpeg's HLS muxer,
 * which writes RFC 6381 CODECS values into its master playlist (it reports HEVC
 * as hvc1; the fields after the prefix are the same).
 */
class KeyframeExtractorTest {

    @Test
    void takesAnH264KeyframeWithItsParameterSets() throws IOException {
        Keyframe keyframe = run("/keyframe-h264.ts").latest().orElseThrow();

        assertThat(keyframe.codec()).isEqualTo("avc1.64000c");
        List<Integer> types = types(keyframe, StreamType.H264);
        assertThat(types).startsWith(7, 8).contains(5).doesNotContain(9, 1);
        assertThat(keyframe.length()).isLessThanOrEqualTo(KeyframeExtractor.MAX_KEYFRAME_BYTES);
    }

    @Test
    void takesAnHevcKeyframeWithItsParameterSets() throws IOException {
        Keyframe keyframe = run("/keyframe-hevc.ts").latest().orElseThrow();

        assertThat(keyframe.codec()).isEqualTo("hev1.1.6.L60.90");
        List<Integer> types = types(keyframe, StreamType.HEVC);
        assertThat(types).startsWith(32, 33, 34).doesNotContain(35);
        assertThat(types).anyMatch(type -> type >= 19 && type <= 21);
    }

    /** Baseline sets constraint flags, the middle byte of avc1.PPCCLL. */
    @Test
    void readsConstraintFlagsIntoTheH264CodecString() throws IOException {
        assertThat(run("/keyframe-h264-baseline.ts").latest().orElseThrow().codec()).isEqualTo("avc1.42c00b");
    }

    /** Main 10 sets compatibility flag 2, which RFC 6381 writes bit-reversed: 4, where Main's flags 1 and 2 give 6. */
    @Test
    void reversesTheHevcCompatibilityFlags() throws IOException {
        assertThat(run("/keyframe-hevc-main10.ts").latest().orElseThrow().codec()).isEqualTo("hev1.2.4.L30.90");
    }

    @Test
    void takesEveryIdrAndKeepsOnlyTheLatest() throws IOException {
        KeyframeExtractor extractor = run("/keyframe-h264.ts");

        assertThat(extractor.keyframesTaken()).as("one IDR a second for three seconds").isEqualTo(3);
        assertThat(extractor.latest().orElseThrow().pts()).isPositive();
    }

    /**
     * A packet lost inside an IDR must not produce a torn keyframe. Here the
     * stream is cut just after the first IDR completes (the next unit starting
     * is what completes it), with one of its packets dropped: nothing is kept.
     */
    @Test
    void neverKeepsAnIdrThatLostAPacket() throws IOException {
        List<TsPacket> packets = packets("/keyframe-h264.ts");
        KeyframeExtractor extractor = new KeyframeExtractor();
        TsAnalyzer analyzer = analyzerFeeding(extractor);
        extractor.enable();
        int videoPid = videoPid(packets);
        List<Integer> unitStarts = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i).pid() == videoPid && packets.get(i).payloadUnitStart()) {
                unitStarts.add(i);
            }
        }
        int dropped = unitStarts.get(0) + 3;

        for (int i = 0; i <= unitStarts.get(1); i++) {
            analyzer.consume(packets.get(i));
            if (i != dropped) {
                extractor.consume(packets.get(i));
            }
        }

        assertThat(extractor.latest()).isEmpty();
    }

    @Test
    void skipsAKeyframeOverTheSizeLimit() throws IOException {
        KeyframeExtractor extractor = run("/keyframe-h264.ts", new KeyframeExtractor(1000));

        assertThat(extractor.latest()).isEmpty();
        assertThat(extractor.skippedForSize()).isEqualTo(3);
    }

    @Test
    void doesNothingWhileDisabled() throws IOException {
        KeyframeExtractor extractor = new KeyframeExtractor();
        TsAnalyzer analyzer = analyzerFeeding(extractor);
        for (TsPacket packet : packets("/keyframe-h264.ts")) {
            analyzer.consume(packet);
            extractor.consume(packet);
        }

        assertThat(extractor.latest()).isEmpty();
        assertThat(extractor.keyframesTaken()).isZero();
        assertThat(extractor.videoCodec()).as("still follows the program map").contains(StreamType.H264);
    }

    @Test
    void findsTheVideoTrackFromTheProgramMap() throws IOException {
        KeyframeExtractor extractor = run("/keyframe-hevc.ts");

        assertThat(extractor.videoCodec()).contains(StreamType.HEVC);
        assertThat(extractor.videoPid()).isEqualTo(videoPid(packets("/keyframe-hevc.ts")));
    }

    // -------------------------------------------------------------------------

    static KeyframeExtractor run(String resource) throws IOException {
        return run(resource, new KeyframeExtractor());
    }

    private static KeyframeExtractor run(String resource, KeyframeExtractor extractor) throws IOException {
        TsAnalyzer analyzer = analyzerFeeding(extractor);
        extractor.enable();
        for (TsPacket packet : packets(resource)) {
            analyzer.consume(packet);
            extractor.consume(packet);
        }
        return extractor;
    }

    private static TsAnalyzer analyzerFeeding(KeyframeExtractor extractor) {
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onProgramsChanged(ProgramMap programs) {
                extractor.programsChanged(programs);
            }
        });
        return analyzer;
    }

    static List<TsPacket> packets(String resource) throws IOException {
        byte[] ts;
        try (InputStream in = KeyframeExtractorTest.class.getResourceAsStream(resource)) {
            ts = in.readAllBytes();
        }
        List<TsPacket> packets = new ArrayList<>();
        for (int at = 0; at + TsPacket.LENGTH <= ts.length; at += TsPacket.LENGTH) {
            packets.add(TsPacket.parse(ts, at));
        }
        return packets;
    }

    private static int videoPid(List<TsPacket> packets) {
        TsAnalyzer analyzer = new TsAnalyzer();
        packets.forEach(analyzer::consume);
        return analyzer.programs().allStreams().stream()
                .filter(s -> s.streamType() == StreamType.H264 || s.streamType() == StreamType.HEVC)
                .findFirst().orElseThrow().pid();
    }

    private static List<Integer> types(Keyframe keyframe, StreamType codec) {
        return KeyframeExtractor.split(keyframe.data()).stream()
                .map(nal -> codec == StreamType.H264 ? nal[0] & 0x1F : (nal[0] >> 1) & 0x3F)
                .toList();
    }
}