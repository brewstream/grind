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
import org.brewstream.grind.AccessUnitAssembler;
import org.brewstream.grind.TsPacket;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A transport stream in, a file a player can decode out.
 *
 * <p>This is the end of the repackaging path, so it is checked outside as well as
 * here. Building {@code bframes.ts} into a file and handing it to two independent
 * tools:
 *
 * <pre>
 * MP4Box -info out.mp4
 *   Fragmented: yes - 5 fragments
 *   Fragmented track: 50 samples - Media Duration 00:00:02.000 - First TFDT 126000
 *
 * ffprobe -count_frames out.mp4
 *   h264, 320, 240, 50 frames read
 * </pre>
 *
 * <p><b>The decisive check is the frame table.</b> Every presentation time,
 * decode time and keyframe flag ffprobe reads back is identical to what it reads
 * from the source transport stream — all fifty of them, for both fixtures. Frames
 * decoded means the pictures survived; timestamps identical means the timing did.
 *
 * <pre>
 * ffprobe -select_streams v -show_entries frame=pts,pkt_dts,key_frame out.mp4
 * ffprobe -select_streams v -show_entries frame=pts,pkt_dts,key_frame bframes.ts
 * </pre>
 */
class FragmenterTest {

    /** Everything the fragmenter produced for a fixture, in order. */
    private record Output(byte[] initSegment, List<byte[]> fragments, Fragmenter fragmenter) {

        byte[] whole() {
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.writeBytes(initSegment);
            fragments.forEach(file::writeBytes);
            return file.toByteArray();
        }
    }

    private static Output fragment(String fixture) throws IOException {
        byte[] transportStream;
        try (InputStream in = FragmenterTest.class.getResourceAsStream("/" + fixture + ".ts")) {
            assertThat(in).as("%s must be on the test classpath", fixture).isNotNull();
            transportStream = in.readAllBytes();
        }

        AccessUnitAssembler assembler = new AccessUnitAssembler(0x0100);
        Fragmenter fragmenter = new Fragmenter(new AvcCodec(), 1);
        List<byte[]> fragments = new ArrayList<>();

        for (int at = 0; at + TsPacket.LENGTH <= transportStream.length; at += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(transportStream, at);
            if (packet == null) {
                continue;
            }
            AccessUnit unit = assembler.consume(packet);
            if (unit != null) {
                add(fragmenter, fragments, unit);
            }
        }
        AccessUnit last = assembler.flush();
        if (last != null) {
            add(fragmenter, fragments, last);
        }
        byte[] trailing = fragmenter.flush();
        if (trailing != null) {
            fragments.add(trailing);
        }
        return new Output(fragmenter.initSegment(), fragments, fragmenter);
    }

    private static void add(Fragmenter fragmenter, List<byte[]> fragments, AccessUnit unit) {
        byte[] finished = fragmenter.add(unit);
        if (finished != null) {
            fragments.add(finished);
        }
    }

    /** One fragment per keyframe, which is where a player is allowed to join. */
    @Test
    void fragmentsBeginAtKeyframes() throws IOException {
        Output output = fragment("bframes");

        assertThat(output.fragments())
                .as("the fixture flags five random access points")
                .hasSize(5);
        assertThat(output.fragmenter().droppedBeforeConfiguration())
                .as("parameter sets arrive with the first keyframe, so nothing is lost")
                .isZero();
    }

    /**
     * A stream with a single keyframe yields a single fragment.
     *
     * <p>Worth pinning because it is the awkward case: {@code sample.ts} flags one
     * random access point across fifty pictures, so everything lands in one
     * fragment. A subscriber joining that stream waits for the next keyframe,
     * which is a property of the stream rather than of this code.
     */
    @Test
    void aStreamWithOneKeyframeYieldsOneFragment() throws IOException {
        assertThat(fragment("sample").fragments()).hasSize(1);
    }

    /** Every fragment is a moof followed by an mdat, and nothing else. */
    @Test
    void eachFragmentIsMoofThenMdat() throws IOException {
        for (byte[] fragment : fragment("bframes").fragments()) {
            assertThat(typeAt(fragment, 0)).isEqualTo("moof");
            int moofLength = lengthAt(fragment, 0);
            assertThat(typeAt(fragment, moofLength)).isEqualTo("mdat");
            assertThat(moofLength + lengthAt(fragment, moofLength))
                    .as("the two boxes account for every byte")
                    .isEqualTo(fragment.length);
        }
    }

    /**
     * Fragment sequence numbers count from one and never repeat.
     *
     * <p>A player uses them to notice it missed one, so a counter that restarted
     * or repeated would make loss invisible.
     */
    @Test
    void sequenceNumbersCountFromOne() throws IOException {
        List<byte[]> fragments = fragment("bframes").fragments();

        for (int i = 0; i < fragments.size(); i++) {
            // moof header, then mfhd's header, version and flags.
            assertThat(readU32(fragments.get(i), 8 + 12))
                    .as("fragment %d", i + 1)
                    .isEqualTo(i + 1);
        }
    }

    /**
     * Composition offsets are written signed, which B-frames require.
     *
     * <p>A reordered frame is presented before something decoded earlier, so its
     * offset is negative. Written unsigned it becomes an enormous positive number
     * and a player jumps forward by hours instead of reordering two pictures —
     * which is why {@code trun} is written at version 1.
     */
    @Test
    void compositionOffsetsAreSigned() throws IOException {
        byte[] first = fragment("bframes").fragments().get(0);

        // trun version 1 says the offsets that follow are signed.
        int trunAt = indexOf(first, "trun");
        assertThat(first[trunAt + 8]).as("trun version").isEqualTo((byte) 1);

        assertThat(new Sample(new byte[0], 1000, 940, false).compositionOffset())
                .as("presented before it is decoded")
                .isEqualTo(-60);
    }

    /**
     * The data offset points at the samples, which is how a player finds them.
     *
     * <p>Measured from the start of the {@code moof} because
     * {@code default-base-is-moof} says so — which is what lets a fragment mean
     * the same thing wherever it lands, as it must when each travels as its own
     * MoQ group. Get this wrong and a player reads from the wrong place: not a
     * crash, just noise, and nothing in the box structure looks amiss.
     */
    @Test
    void theDataOffsetPointsAtTheFirstSample() throws IOException {
        for (byte[] fragment : fragment("bframes").fragments()) {
            int moofLength = lengthAt(fragment, 0);
            int trunAt = indexOf(fragment, "trun");

            // trun: box header, version and flags, sample count, then the offset.
            long dataOffset = readU32(fragment, trunAt + 8 + 4 + 4);

            assertThat(dataOffset)
                    .as("past the moof and the mdat header, where the payload begins")
                    .isEqualTo(moofLength + 8);
            assertThat(typeAt(fragment, moofLength))
                    .as("and that really is where mdat starts")
                    .isEqualTo("mdat");
        }
    }

    /**
     * Sample sizes account for the mdat payload exactly.
     *
     * <p>Walking the sizes from the data offset must land precisely on the end of
     * the fragment. A size that is wrong anywhere leaves a player reading one
     * sample's bytes as another's from that point on.
     */
    @Test
    void sampleSizesAccountForThePayloadExactly() throws IOException {
        for (byte[] fragment : fragment("bframes").fragments()) {
            int trunAt = indexOf(fragment, "trun");
            int count = (int) readU32(fragment, trunAt + 8 + 4);
            long dataOffset = readU32(fragment, trunAt + 8 + 4 + 4);

            // Each entry is duration, size, flags and composition offset.
            long total = 0;
            int entry = trunAt + 8 + 4 + 4 + 4;
            for (int i = 0; i < count; i++) {
                total += readU32(fragment, entry + 4);
                entry += 16;
            }

            assertThat(dataOffset + total)
                    .as("the samples end exactly where the fragment does")
                    .isEqualTo(fragment.length);
        }
    }

    /** A sample's flags say whether a player may start there. */
    @Test
    void keyframesAreMarkedAsSyncSamples() {
        Sample keyframe = new Sample(new byte[0], 0, 0, true);
        Sample other = new Sample(new byte[0], 0, 0, false);

        assertThat(keyframe.flags()).as("depends on nothing, is a sync sample").isEqualTo(0x02000000L);
        assertThat(other.flags()).as("depends on others, not a sync sample").isEqualTo(0x01010000L);
    }

    /** The whole file is an init segment followed by its fragments. */
    @Test
    void theFileOpensWithAnInitSegment() throws IOException {
        byte[] file = fragment("bframes").whole();

        assertThat(typeAt(file, 0)).isEqualTo("ftyp");
        assertThat(typeAt(file, lengthAt(file, 0))).isEqualTo("moov");
    }

    /**
     * Nothing is emitted before the configuration arrives.
     *
     * <p>The fragmenter's startup state: a player handed pictures it has no
     * description for cannot use them, so they are counted and dropped rather
     * than held — holding would grow without bound on a stream whose muxer never
     * sends parameter sets.
     */
    @Test
    void nothingIsEmittedBeforeTheCodecIsConfigured() {
        Fragmenter fragmenter = new Fragmenter(new AvcCodec(), 1);

        // A slice with no parameter sets ahead of it.
        AccessUnit orphan = new AccessUnit(0x0100, 90_000, -1, false,
                java.util.HexFormat.of().parseHex("0000000141aabbcc"));

        assertThat(fragmenter.add(orphan)).isNull();
        assertThat(fragmenter.initSegment()).as("nothing to describe it with").isNull();
        assertThat(fragmenter.droppedBeforeConfiguration()).isEqualTo(1);
        assertThat(fragmenter.hasPending()).isFalse();
    }

    /** An empty fragment is refused rather than written as a moof describing nothing. */
    @Test
    void anEmptyFragmentIsRefused() {
        assertThatThrownBy(() -> MediaFragment.of(1, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one sample");
    }

    /** Flushing twice yields nothing the second time. */
    @Test
    void flushingAnEmptyFragmenterYieldsNothing() throws IOException {
        Fragmenter fragmenter = fragment("bframes").fragmenter();

        assertThat(fragmenter.hasPending()).isFalse();
        assertThat(fragmenter.flush()).isNull();
    }

    private static String typeAt(byte[] data, int at) {
        return new String(data, at + 4, 4, StandardCharsets.US_ASCII);
    }

    private static int lengthAt(byte[] data, int at) {
        return (int) readU32(data, at);
    }

    private static long readU32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static int indexOf(byte[] data, String type) {
        byte[] needle = type.getBytes(StandardCharsets.US_ASCII);
        for (int at = 0; at + 4 <= data.length; at++) {
            if (data[at] == needle[0] && data[at + 1] == needle[1]
                    && data[at + 2] == needle[2] && data[at + 3] == needle[3]) {
                return at - 4;
            }
        }
        throw new AssertionError("no " + type + " box");
    }
}
