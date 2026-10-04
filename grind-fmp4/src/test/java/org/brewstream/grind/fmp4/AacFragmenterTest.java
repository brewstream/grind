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

package org.brewstream.grind.fmp4;

import org.brewstream.grind.AccessUnit;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AacFragmenterTest {
    private static byte[] fixture() throws Exception {
        try (var in = AacFragmenterTest.class.getResourceAsStream("/sample.aac")) {
            return in.readAllBytes();
        }
    }

    private static AccessUnit unit(byte[] data, long pts) {
        return new AccessUnit(257, pts, -1, true, data);
    }

    private static int box(byte[] data, String name) {
        // Names used below are structural boxes, before the mdat payload.
        byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
        for (int at = 4; at + 4 <= data.length; at++) {
            if (Arrays.equals(data, at, at + 4, needle, 0, 4)) {
                return at - 4;
            }
        }
        throw new AssertionError("missing " + name);
    }

    private static int count(byte[] fragment) {
        return ByteBuffer.wrap(fragment).getInt(box(fragment, "trun") + 12);
    }

    private static byte[] payload(byte[] fragment) {
        return Arrays.copyOfRange(fragment, box(fragment, "mdat") + 8, fragment.length);
    }

    private static long time(byte[] fragment) {
        return ByteBuffer.wrap(fragment).getLong(box(fragment, "tfdt") + 12);
    }

    @Test
    void realFramesBecomeRawSamplesWithExactDurationsAndConfiguration() throws Exception {
        AacCodec codec = new AacCodec();
        AacFragmenter f = new AacFragmenter(codec, 2);
        byte[] fragment = f.add(unit(fixture(), 126000));
        f.finish();
        assertThat(count(fragment)).isEqualTo(88); // independently measured by ffprobe
        assertThat(payload(fragment)).hasSize(16419); // ffmpeg's ADTS extraction, minus headers
        assertThat(time(fragment)).isEqualTo(61740); // 1.4 seconds at 44100 Hz
        assertThat(codec.audioSpecificConfig()).containsExactly((byte) 0x12, (byte) 0x08);
        assertThat(codec.sampleRate()).isEqualTo(44100);
        assertThat(codec.channels()).isEqualTo(1);
        ByteBuffer b = ByteBuffer.wrap(fragment);
        int trun = box(fragment, "trun");
        int offset = b.getInt(trun + 16);
        int total = 0;
        for (int i = 0; i < 88; i++) {
            int entry = trun + 20 + i * 16;
            assertThat(b.getInt(entry)).isEqualTo(1024);
            total += b.getInt(entry + 4);
            assertThat(b.getInt(entry + 8)).isEqualTo(0x02000000);
            assertThat(b.getInt(entry + 12)).isZero();
        }
        assertThat(offset).isEqualTo(box(fragment, "mdat") + 8);
        assertThat(offset + total).isEqualTo(fragment.length);
        byte[] init = f.initSegment();
        assertThat(ByteBuffer.wrap(init).getInt(box(init, "mdhd") + 20)).isEqualTo(44100);
        assertThat(box(init, "smhd")).isPositive();
        assertThat(box(init, "mp4a")).isPositive();
        assertThat(ByteBuffer.wrap(init).getInt(box(init, "trex") + 12)).isEqualTo(2);
    }

    @Test
    void everySplitPositionIncludingInsideTheHeaderKeepsTheSameSamples() throws Exception {
        byte[] input = fixture();
        byte[] first = Arrays.copyOf(input, 287); // ffprobe: first ADTS frame is 287 bytes
        byte[] expected = Arrays.copyOfRange(first, 7, first.length);
        for (int cut = 1; cut < first.length; cut++) {
            AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
            assertThat(f.add(unit(Arrays.copyOf(first, cut), 126000))).isNull();
            byte[] completed = f.add(unit(Arrays.copyOfRange(first, cut, first.length), -1));
            assertThat(payload(completed)).as("split at %d", cut).isEqualTo(expected);
            assertThat(time(completed)).isEqualTo(61740);
            assertThat(count(completed)).isEqualTo(1);
            f.finish();
        }
    }

    @Test
    void completedFrameAndPartialNextFrameKeepTheirSeparateTimestampAnchors() throws Exception {
        byte[] input = fixture();
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        byte[] first = f.add(unit(Arrays.copyOf(input, 290), 126000));
        assertThat(count(first)).isEqualTo(1);
        byte[] rest = f.add(unit(Arrays.copyOfRange(input, 290, input.length), 130179));
        assertThat(count(rest)).isEqualTo(87);
        assertThat(time(rest)).isEqualTo(61740 + 1024);
        assertThat(payload(first).length + payload(rest).length).isEqualTo(16419);
        f.finish();
    }

    @Test
    void framesWithoutFurtherPtsAdvanceWithoutRoundingDrift() throws Exception {
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        byte[] frame = Arrays.copyOf(fixture(), 287);
        for (int i = 0; i < 10000; i++) {
            byte[] output = f.add(unit(frame, i == 0 ? 126000 : -1));
            assertThat(time(output)).isEqualTo(61740L + i * 1024L);
            assertThat(ByteBuffer.wrap(output).getInt(box(output, "trun") + 20)).isEqualTo(1024);
        }
    }

    @Test
    void timestampWrapAndLossDoNotCompressTheTimeline() throws Exception {
        byte[] frame = Arrays.copyOf(fixture(), 287);
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        long beforeWrap = (1L << 33) - 1000;
        byte[] before = f.add(unit(frame, beforeWrap));
        byte[] after = f.add(unit(frame, 1090));
        assertThat(time(after) - time(before)).isEqualTo(1024);
        f.discontinuity();
        byte[] later = f.add(unit(frame, 90000));
        assertThat(time(later)).isGreaterThan(time(after) + 1024);
        AacFragmenter partial = new AacFragmenter(new AacCodec(), 2);
        partial.add(unit(Arrays.copyOf(frame, 10), 126000));
        partial.discontinuity();
        assertThat(payload(partial.add(unit(frame, 216000)))).isEqualTo(Arrays.copyOfRange(frame, 7, 287));
    }

    @Test
    void incompleteInputAndMissingInitialTimestampAreRejected() throws Exception {
        byte[] data = fixture();
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        f.add(unit(Arrays.copyOf(data, data.length - 1), 126000));
        assertThatThrownBy(f::finish).hasMessageContaining("truncated ADTS");
        assertThatThrownBy(() -> new AacFragmenter(new AacCodec(), 2).add(unit(data, -1)))
                .hasMessageContaining("needs a PTS");
    }

    @Test
    void malformedAndUnsupportedHeadersFailExplicitly() throws Exception {
        byte[] original = Arrays.copyOf(fixture(), 287);
        for (int variant = 0; variant < 7; variant++) {
            byte[] altered = original.clone();
            switch (variant) {
                case 0 -> altered[0] = 0;
                case 1 -> altered[1] |= 2; // nonzero layer
                case 2 -> altered[2] &= 0x3f; // AAC Main
                case 3 -> altered[2] |= 0x3c; // reserved frequency
                case 4 -> { altered[2] &= 0xfe; altered[3] &= 0x3f; } // PCE
                case 5 -> altered[6] |= 1; // multiple raw blocks
                case 6 -> { altered[3] &= 0xfc; altered[4] = 0; altered[5] = 0; }
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> new AacFragmenter(new AacCodec(), 2).add(unit(altered, 126000)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void configurationChangesRequireANewTrack() throws Exception {
        byte[] frame = Arrays.copyOf(fixture(), 287);
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        f.add(unit(frame, 126000));
        frame[2] = (byte) ((frame[2] & 0xc3) | (3 << 2)); // change 44100 to 48000
        assertThatThrownBy(() -> f.add(unit(frame, 128090))).hasMessageContaining("configuration changed");
    }

    @Test
    void standaloneAudioIsUnavailableUntilConfiguredAndTrackIdsCannotCollide() throws Exception {
        AacCodec codec = new AacCodec();
        AacFragmenter f = new AacFragmenter(codec, 2);
        assertThat(f.initSegment()).isNull();
        assertThatThrownBy(codec::audioSpecificConfig).isInstanceOf(IllegalStateException.class);
        f.add(unit(fixture(), 126000));
        assertThatThrownBy(() -> InitSegment.forAudioVideo(new AvcCodec(), 2, codec, 2))
                .hasMessageContaining("distinct");
        assertThatThrownBy(() -> InitSegment.forAudio(codec, 0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> MediaFragment.of(2, 1,
                List.of(new Sample(new byte[1], 0, 0, true)), -1)).hasMessageContaining("negative");
    }
    @Test
    void crcHeaderIsRemovedButNotValidated() throws Exception {
        byte[] original = Arrays.copyOf(fixture(), 287);
        // Change a real frame's transport header, keeping the coded AAC unchanged.
        // The CRC bytes are placeholders: this parser deliberately does not validate CRC.
        byte[] protectedFrame = new byte[289];
        System.arraycopy(original, 0, protectedFrame, 0, 7);
        System.arraycopy(original, 7, protectedFrame, 9, 280);
        protectedFrame[1] &= 0xfe;
        protectedFrame[4] = (byte) (289 >>> 3);
        protectedFrame[5] = (byte) ((protectedFrame[5] & 31) | ((289 & 7) << 5));
        byte[] fragment = new AacFragmenter(new AacCodec(), 2).add(unit(protectedFrame, 126000));
        assertThat(payload(fragment)).isEqualTo(Arrays.copyOfRange(original, 7, 287));
    }

    @Test
    void timestampGapsWithinOneFragmentAreRejectedInsteadOfHidden() throws Exception {
        byte[] input = fixture();
        AacFragmenter f = new AacFragmenter(new AacCodec(), 2);
        f.add(unit(Arrays.copyOf(input, 10), 126000));
        assertThatThrownBy(() -> f.add(unit(Arrays.copyOfRange(input, 10, input.length), 216000)))
                .hasMessageContaining("discontinuity within a PES");
    }

}
