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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The init segment: what a player reads before any media.
 *
 * <p>Checked here by walking the boxes, and checked outside by two independent
 * parsers that have no reason to share this implementation's assumptions. Both
 * accept what this produces and report the values below:
 *
 * <pre>
 * mp4dump init.mp4          # Bento4 — the full box tree
 * MP4Box -info init.mp4     # GPAC   — a second opinion
 * </pre>
 *
 * <p>GPAC's reading, which is the useful summary:
 *
 * <pre>
 * # Movie Info - 1 track - TimeScale 90000
 * Track flags: Enabled In Movie
 * Fragmented track: 0 samples - Media Duration 00:00:00.000 - First TFDT 0
 * AVC/H264 Video - Visual Size 320 x 240
 * AVC Info: 1 SPS - 1 PPS - Profile High 4:4:4 @ Level 1.3
 * </pre>
 *
 * <p>"Fragmented track" is the line worth noticing: it means {@code mvex} was
 * understood, so a reader knows to expect samples later rather than concluding
 * the track is empty.
 */
class InitSegmentTest {

    private static AvcCodec configuredCodec() throws IOException {
        byte[] elementaryStream;
        try (InputStream in = InitSegmentTest.class.getResourceAsStream("/bframes.h264")) {
            assertThat(in).isNotNull();
            elementaryStream = in.readAllBytes();
        }
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream);
        return codec;
    }

    /** One box in a parsed tree, with its children. */
    private record Box(String type, int offset, int length, List<Box> children) {

        Box child(String... path) {
            Box at = this;
            for (String step : path) {
                at = at.children.stream()
                        .filter(box -> box.type.equals(step))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no " + step + " inside " + this.type));
            }
            return at;
        }
    }

    /** Walks a box tree, descending into the containers that have children. */
    private static List<Box> parse(byte[] data, int from, int to) {
        List<String> containers = List.of("moov", "trak", "mdia", "minf", "stbl",
                "dinf", "mvex", "stsd", "avc1");
        List<Box> boxes = new ArrayList<>();
        int at = from;
        while (at + 8 <= to) {
            int length = ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                    | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
            if (length < 8 || at + length > to) {
                throw new AssertionError("box " + type + " has an impossible length " + length);
            }
            int bodyAt = at + 8;
            // stsd and avc1 carry fields before their children rather than
            // starting with them, so descending needs the right offset.
            if (type.equals("stsd")) {
                bodyAt += 8;
            } else if (type.equals("avc1")) {
                bodyAt += 78;
            }
            List<Box> children = containers.contains(type)
                    ? parse(data, bodyAt, at + length)
                    : List.of();
            boxes.add(new Box(type, at, length, children));
            at += length;
        }
        return boxes;
    }

    private static Box tree(byte[] segment, String type) {
        return parse(segment, 0, segment.length).stream()
                .filter(box -> box.type.equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no top-level " + type));
    }

    /** Every box accounts for its own length, which is what a parser relies on. */
    @Test
    void theBoxTreeIsWellFormed() throws IOException {
        byte[] segment = InitSegment.forVideo(configuredCodec(), 1);

        List<Box> top = parse(segment, 0, segment.length);

        assertThat(top).extracting(Box::type).containsExactly("ftyp", "moov");
        assertThat(top.get(0).length() + top.get(1).length())
                .as("the boxes account for every byte")
                .isEqualTo(segment.length);
    }

    /** The structure a fragmented file needs, nested as the format requires. */
    @Test
    void theTrackIsDescribedWhereAPlayerLooks() throws IOException {
        Box moov = tree(InitSegment.forVideo(configuredCodec(), 1), "moov");

        assertThat(moov.child("trak", "mdia", "minf", "stbl", "stsd", "avc1", "avcC"))
                .isNotNull();
        assertThat(moov.child("trak", "mdia", "hdlr")).isNotNull();
        assertThat(moov.child("trak", "mdia", "minf", "dinf", "dref")).isNotNull();
    }

    /**
     * {@code mvex} is present, which is what says samples arrive later.
     *
     * <p>Without it a reader sees empty sample tables and concludes the track has
     * no media, rather than waiting for fragments. GPAC reporting "Fragmented
     * track" is this box being understood.
     */
    @Test
    void movieExtendsSaysFragmentsAreComing() throws IOException {
        Box moov = tree(InitSegment.forVideo(configuredCodec(), 1), "moov");

        assertThat(moov.child("mvex", "trex")).isNotNull();
    }

    /**
     * The sample tables are present and empty, which is required rather than
     * accidental.
     *
     * <p>A plain MP4 builds them once the file is complete. A fragmented one
     * cannot, because the samples have not happened — but the boxes must still be
     * there, holding nothing.
     */
    @Test
    void theSampleTablesArePresentAndEmpty() throws IOException {
        Box stbl = tree(InitSegment.forVideo(configuredCodec(), 1), "moov")
                .child("trak", "mdia", "minf", "stbl");

        assertThat(stbl.children()).extracting(Box::type)
                .containsExactly("stsd", "stts", "stsc", "stsz", "stco");
        assertThat(stbl.child("stts").length()).as("a header and a zero count").isEqualTo(16);
        assertThat(stbl.child("stco").length()).isEqualTo(16);
    }

    /** Dimensions reach the two places a player reads them from. */
    @Test
    void dimensionsAppearInBothTheTrackHeaderAndTheSampleEntry() throws IOException {
        byte[] segment = InitSegment.forVideo(configuredCodec(), 1);
        Box moov = tree(segment, "moov");

        // tkhd: width and height are the last eight bytes, as 16.16 fixed point.
        Box tkhd = moov.child("trak", "tkhd");
        int end = tkhd.offset() + tkhd.length();
        assertThat(readU16(segment, end - 8)).as("tkhd width").isEqualTo(320);
        assertThat(readU16(segment, end - 4)).as("tkhd height").isEqualTo(240);

        // avc1: plain 16-bit values, 24 bytes into the entry's body.
        Box avc1 = moov.child("trak", "mdia", "minf", "stbl", "stsd", "avc1");
        assertThat(readU16(segment, avc1.offset() + 8 + 24)).as("avc1 width").isEqualTo(320);
        assertThat(readU16(segment, avc1.offset() + 8 + 26)).as("avc1 height").isEqualTo(240);
    }

    /**
     * A picture whose height is not a whole number of macroblocks is cropped, and
     * the crop has to be subtracted.
     *
     * <p><b>This is the normal case, not an edge one.</b> 1080p is coded as 1088
     * rows and cropped to 1080, because 1080 is not divisible by sixteen. An
     * implementation that ignores cropping reports every 1080p stream eight rows
     * too tall — and a player that believes it stretches the picture.
     *
     * <p>{@code cropped.ts} is 320x180: eleven and a quarter macroblocks high, so
     * the encoder codes 192 rows and crops twelve. The reference muxer reports
     * 320x180 for it, which is what this must agree with.
     */
    @Test
    void croppedDimensionsSubtractTheCrop() throws IOException {
        byte[] elementaryStream;
        try (InputStream in = InitSegmentTest.class.getResourceAsStream("/cropped.h264")) {
            assertThat(in).isNotNull();
            elementaryStream = in.readAllBytes();
        }
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream);

        assertThat(codec.width()).isEqualTo(320);
        assertThat(codec.height())
                .as("192 coded rows less a twelve-row crop, not 192")
                .isEqualTo(180);

        byte[] segment = InitSegment.forVideo(codec, 1);
        Box avc1 = tree(segment, "moov").child("trak", "mdia", "minf", "stbl", "stsd", "avc1");
        assertThat(readU16(segment, avc1.offset() + 8 + 26)).isEqualTo(180);
    }

    /** The timescale matches the transport stream's clock, so nothing is rescaled. */
    @Test
    void theTimescaleIs90kHzThroughout() throws IOException {
        byte[] segment = InitSegment.forVideo(configuredCodec(), 1);
        Box moov = tree(segment, "moov");

        Box mvhd = moov.child("mvhd");
        assertThat(readU32(segment, mvhd.offset() + 12 + 8)).isEqualTo(InitSegment.TIMESCALE);

        Box mdhd = moov.child("trak", "mdia", "mdhd");
        assertThat(readU32(segment, mdhd.offset() + 12 + 8)).isEqualTo(InitSegment.TIMESCALE);
    }

    /** The brands say what this is, matching what the reference muxer declares. */
    @Test
    void theBrandsMatchTheReference() throws IOException {
        byte[] segment = InitSegment.forVideo(configuredCodec(), 1);

        assertThat(new String(segment, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("isom");
        assertThat(new String(segment, 16, 20, StandardCharsets.US_ASCII))
                .isEqualTo("isomiso6iso2avc1mp41");
    }

    /**
     * A codec that has seen no configuration cannot describe a track.
     *
     * <p>This is the fragmenter's startup state made explicit: until parameter
     * sets arrive there is nothing to say about the pictures, and failing here is
     * better than emitting a segment a player cannot use.
     */
    @Test
    void anUnconfiguredCodecCannotProduceASegment() {
        assertThatThrownBy(() -> InitSegment.forVideo(new AvcCodec(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");
    }

    private static int readU16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static long readU32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
