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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Splitting Annex&nbsp;B into NAL units, and the parameter sets {@code avcC}
 * needs.
 *
 * <p>The expectations here were produced by Bento4's {@code mp4dump} reading a
 * fragmented MP4 that ffmpeg muxed from the same fixture — an independent
 * toolchain twice over. The parameter sets are quoted from that dump verbatim, so
 * a scanner that finds the wrong bytes fails rather than agreeing with itself.
 *
 * <pre>
 * ffmpeg -i bframes.ts -c copy -an -movflags "+frag_keyframe+empty_moov" -f mp4 ref.mp4
 * mp4dump ref.mp4
 * </pre>
 */
class AnnexBTest {

    /** Exactly what mp4dump reported in the reference muxer's avcC box. */
    private static final String REFERENCE_SPS =
            "67f4000d919b2828 3f60220000030002 00000300641e2853 2c".replace(" ", "");

    private static final String REFERENCE_PPS = "68ebec44 8440".replace(" ", "");

    private static byte[] elementaryStream() throws IOException {
        try (InputStream in = AnnexBTest.class.getResourceAsStream("/bframes.h264")) {
            assertThat(in).as("bframes.h264 must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    /**
     * The scanner finds the same parameter sets the reference muxer put in
     * {@code avcC}.
     *
     * <p>This is the check that matters: the bytes come from an independent
     * toolchain, so a start-code scanner that is off by one produces something
     * different rather than something self-consistent.
     */
    @Test
    void parameterSetsMatchWhatTheReferenceMuxerProduced() throws IOException {
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream());

        assertThat(codec.isConfigured()).isTrue();
        assertThat(HexFormat.of().formatHex(codec.sequenceSets().get(0)))
                .isEqualTo(REFERENCE_SPS);
        assertThat(HexFormat.of().formatHex(codec.pictureSets().get(0)))
                .isEqualTo(REFERENCE_PPS);
    }

    /**
     * Parameter sets repeat ahead of every keyframe, and identical ones are held
     * once.
     *
     * <p>{@code bframes.h264} carries five copies of each. A collector that kept
     * them all would write five identical sequence parameter sets into
     * {@code avcC} — valid, wasteful, and not what the reference produces.
     */
    @Test
    void repeatedParameterSetsAreHeldOnce() throws IOException {
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream());

        assertThat(codec.sequenceSets()).hasSize(1);
        assertThat(codec.pictureSets()).hasSize(1);
    }

    /**
     * The whole configuration box, byte for byte against the reference muxer's.
     *
     * <p>Read out of ffmpeg's own output with a hex dump rather than paraphrased
     * from the specification, so a box that is merely plausible fails.
     *
     * <p>The trailing {@code fff8f800} is the extension High profiles carry:
     * chroma 4:4:4, eight-bit luma and chroma, no parameter set extensions. Those
     * values exist only inside the sequence parameter set's bitstream, which is
     * why this module parses it at all.
     */
    @Test
    void theConfigurationBoxMatchesTheReference() throws IOException {
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream());

        BoxWriter out = new BoxWriter();
        codec.writeConfiguration(out);

        assertThat(HexFormat.of().formatHex(out.toByteArray())).isEqualTo(
                "00000036"                 // box length: 8 + 46
                        + "61766343"       // "avcC"
                        + "01f4000d"       // version 1, profile 244, compat 0, level 13
                        + "ff"             // four-byte length prefixes
                        + "e1"             // one sequence parameter set
                        + "0019" + REFERENCE_SPS
                        + "01"             // one picture parameter set
                        + "0006" + REFERENCE_PPS
                        + "fff8f800");     // chroma 4:4:4, 8-bit, no extensions
    }

    /** Baseline and Main carry no extension, and their bitstream is left unread. */
    @Test
    void profilesWithoutTheExtensionAreNotParsed() {
        assertThat(SequenceParameterSet.profileHasExtension(244)).as("High 4:4:4").isTrue();
        assertThat(SequenceParameterSet.profileHasExtension(100)).as("High").isTrue();
        assertThat(SequenceParameterSet.profileHasExtension(77)).as("Main").isFalse();
        assertThat(SequenceParameterSet.profileHasExtension(66)).as("Baseline").isFalse();
    }

    /**
     * The escape bytes are removed wherever they fall, not only where this
     * fixture happens to put them.
     *
     * <p>Tested on the transformation directly because the fixture cannot reach
     * it: its escape sequences sit after every field {@code avcC} needs, so
     * skipping the strip entirely still yields the right answer for this one
     * stream. A parameter set with an escape among the early fields would decode
     * to plausible nonsense instead — and hand-crafting bytes is safe here, since
     * removing {@code 03} after two zeroes is a mechanical rule rather than an
     * interpretation.
     */
    @Test
    void escapeBytesAreRemovedWhereverTheyFall() {
        // from = 0 so the whole input is scanned.
        assertThat(SequenceParameterSet.removeEmulationPrevention(
                HexFormat.of().parseHex("00000300"), 0))
                .as("the 03 between two zeroes and a zero")
                .containsExactly(0x00, 0x00, 0x00);

        assertThat(SequenceParameterSet.removeEmulationPrevention(
                HexFormat.of().parseHex("aabb000003cc"), 0))
                .containsExactly(0xAA, 0xBB, 0x00, 0x00, 0xCC);

        assertThat(SequenceParameterSet.removeEmulationPrevention(
                HexFormat.of().parseHex("0003"), 0))
                .as("one zero is not two, so this 03 is payload")
                .containsExactly(0x00, 0x03);

        assertThat(SequenceParameterSet.removeEmulationPrevention(
                HexFormat.of().parseHex("00000003000003"), 0))
                .as("the counter resets after each removal")
                .containsExactly(0x00, 0x00, 0x00, 0x00, 0x00);

        assertThat(SequenceParameterSet.removeEmulationPrevention(
                HexFormat.of().parseHex("ffff000003aa"), 2))
                .as("scanning starts where it is told to, past the two ff bytes")
                .containsExactly(0x00, 0x00, 0xAA);
    }

    /**
     * The bytes inserted to stop payload imitating a start code are removed
     * before the bitstream is read.
     *
     * <p>The fixture's own parameter set contains two such sequences. Reading them
     * as bitstream shifts every field after the first and yields a plausible wrong
     * answer rather than an error — this one decodes to 4:4:4 and eight bits,
     * which is what the reference independently reports.
     */
    @Test
    void emulationPreventionIsStrippedBeforeParsing() throws IOException {
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream());
        byte[] sps = codec.sequenceSets().get(0);

        assertThat(HexFormat.of().formatHex(sps))
                .as("the fixture really does contain the escape sequence")
                .contains("000003");

        SequenceParameterSet parsed = SequenceParameterSet.parse(sps);
        assertThat(parsed.chromaFormat()).as("4:4:4").isEqualTo(3);
        assertThat(parsed.bitDepthLumaMinus8()).isZero();
        assertThat(parsed.bitDepthChromaMinus8()).isZero();
    }

    /** Keyframes are found from the bitstream, not from the transport stream's flag. */
    @Test
    void randomAccessIsReadFromTheBitstream() throws IOException {
        AvcCodec codec = new AvcCodec();
        byte[] es = elementaryStream();

        assertThat(codec.isRandomAccess(es))
                .as("the whole stream contains IDR slices")
                .isTrue();
        assertThat(codec.isRandomAccess(HexFormat.of().parseHex("0000000141aabb")))
                .as("a non-IDR slice is not one")
                .isFalse();
    }

    /**
     * Profile, compatibility and level are read positionally, and agree with the
     * reference: 244, 0, 13.
     *
     * <p>They are the three bytes after the sequence parameter set's NAL header.
     * Decoding the bitstream to find them would be more code and the same answer.
     */
    @Test
    void profileAndLevelComeStraightFromTheSequenceParameterSet() throws IOException {
        AvcCodec codec = new AvcCodec();
        codec.offer(elementaryStream());
        byte[] sps = codec.sequenceSets().get(0);

        assertThat(sps[1] & 0xFF).as("profile").isEqualTo(244);
        assertThat(sps[2] & 0xFF).as("compatibility").isZero();
        assertThat(sps[3] & 0xFF).as("level").isEqualTo(13);
    }

    /**
     * The unit breakdown of a real stream, which says the scanner is not merely
     * self-consistent.
     *
     * <p>Fifty pictures, so fifty access unit delimiters. Five keyframes, so five
     * IDR slices — matching the five random access points {@code bframes.ts}
     * flags — with a parameter set pair repeated ahead of each.
     */
    @Test
    void aRealStreamSplitsIntoTheUnitsItShould() throws IOException {
        byte[] es = elementaryStream();
        Map<Integer, Integer> byType = new TreeMap<>();
        for (AnnexB.Nal nal : AnnexB.split(es)) {
            byType.merge(AvcCodec.typeOf(es, nal), 1, Integer::sum);
        }

        assertThat(byType.get(9)).as("access unit delimiters, one per picture").isEqualTo(50);
        assertThat(byType.get(5)).as("IDR slices, one per keyframe").isEqualTo(5);
        assertThat(byType.get(1)).as("the remaining slices").isEqualTo(45);
        assertThat(byType.get(7)).as("sequence parameter sets, repeated per keyframe").isEqualTo(5);
        assertThat(byType.get(8)).as("picture parameter sets").isEqualTo(5);
    }

    /**
     * Both start code lengths are recognised.
     *
     * <p>The four-byte form is the three-byte one with a leading zero, so a
     * scanner that knows only one either misses units or leaves a stray zero at
     * the front of every payload — which corrupts the NAL type it then reads.
     */
    @Test
    void bothStartCodeLengthsAreRecognised() {
        byte[] mixed = HexFormat.of().parseHex(
                "00000001" + "6501020304"      // four-byte start code, then a slice
                        + "000001" + "0605060708");  // three-byte, then SEI

        List<AnnexB.Nal> units = AnnexB.split(mixed);

        assertThat(units).hasSize(2);
        assertThat(AvcCodec.typeOf(mixed, units.get(0))).as("0x65 is a slice, type 5").isEqualTo(5);
        assertThat(AvcCodec.typeOf(mixed, units.get(1))).as("0x06 is SEI, type 6").isEqualTo(6);
        assertThat(units.get(0).copy(mixed)).startsWith((byte) 0x65);
        assertThat(units.get(1).copy(mixed)).startsWith((byte) 0x06);
    }

    /** Length prefixes replace start codes, leaving the payload untouched. */
    @Test
    void lengthPrefixesReplaceStartCodes() {
        byte[] annexB = HexFormat.of().parseHex("00000001" + "65aabbcc");

        byte[] prefixed = new AvcCodec().sample(annexB);

        assertThat(prefixed).containsExactly(
                0x00, 0x00, 0x00, 0x04,   // four bytes follow
                0x65, 0xAA, 0xBB, 0xCC);  // unchanged
    }

    /**
     * Parameter sets are dropped from pictures, because they move into
     * {@code avcC}.
     *
     * <p>A decoder handed them in both places is entitled to object, and access
     * unit delimiters go too — the length prefixes make the boundaries they marked
     * explicit.
     */
    @Test
    void parameterSetsAndDelimitersAreNotCarriedInPictures() {
        byte[] annexB = HexFormat.of().parseHex(
                "00000001" + "09f0"          // access unit delimiter
                        + "00000001" + "6742c0"      // SPS
                        + "00000001" + "68ce"        // PPS
                        + "00000001" + "65aabb");    // the picture itself

        byte[] prefixed = new AvcCodec().sample(annexB);

        assertThat(prefixed).containsExactly(
                0x00, 0x00, 0x00, 0x03,
                0x65, 0xAA, 0xBB);
    }

    /** Every picture in a real stream survives the rewrite with its bytes intact. */
    @Test
    void rewritingARealStreamKeepsEverySlice() throws IOException {
        byte[] prefixed = new AvcCodec().sample(elementaryStream());

        int units = 0;
        int at = 0;
        while (at + 4 <= prefixed.length) {
            int length = ((prefixed[at] & 0xFF) << 24) | ((prefixed[at + 1] & 0xFF) << 16)
                    | ((prefixed[at + 2] & 0xFF) << 8) | (prefixed[at + 3] & 0xFF);
            assertThat(length).as("a length must not run past the buffer").isPositive();
            at += 4 + length;
            units++;
        }

        assertThat(at).as("the lengths account for every byte exactly").isEqualTo(prefixed.length);
        assertThat(units)
                .as("50 slices and one SEI; the delimiters and parameter sets are gone")
                .isEqualTo(51);
    }
}
