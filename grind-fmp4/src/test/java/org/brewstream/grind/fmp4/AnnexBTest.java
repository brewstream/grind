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
        AvcParameterSets sets = AvcParameterSets.from(elementaryStream());

        assertThat(sets.isComplete()).isTrue();
        assertThat(HexFormat.of().formatHex(sets.sequenceSets().get(0)))
                .isEqualTo(REFERENCE_SPS);
        assertThat(HexFormat.of().formatHex(sets.pictureSets().get(0)))
                .isEqualTo(REFERENCE_PPS);
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
        byte[] profileLevel = AvcParameterSets.from(elementaryStream()).profileLevel();

        assertThat(profileLevel[0] & 0xFF).as("profile").isEqualTo(244);
        assertThat(profileLevel[1] & 0xFF).as("compatibility").isZero();
        assertThat(profileLevel[2] & 0xFF).as("level").isEqualTo(13);
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
        Map<Integer, Integer> byType = new TreeMap<>();
        for (AnnexB.Nal nal : AnnexB.split(elementaryStream())) {
            byType.merge(nal.type(), 1, Integer::sum);
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
        assertThat(units.get(0).type()).as("0x65 is a slice, type 5").isEqualTo(5);
        assertThat(units.get(1).type()).as("0x06 is SEI, type 6").isEqualTo(6);
        assertThat(units.get(0).copy(mixed)).startsWith((byte) 0x65);
        assertThat(units.get(1).copy(mixed)).startsWith((byte) 0x06);
    }

    /** Length prefixes replace start codes, leaving the payload untouched. */
    @Test
    void lengthPrefixesReplaceStartCodes() {
        byte[] annexB = HexFormat.of().parseHex("00000001" + "65aabbcc");

        byte[] prefixed = AnnexB.toLengthPrefixed(annexB);

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

        byte[] prefixed = AnnexB.toLengthPrefixed(annexB);

        assertThat(prefixed).containsExactly(
                0x00, 0x00, 0x00, 0x03,
                0x65, 0xAA, 0xBB);
    }

    /** Every picture in a real stream survives the rewrite with its bytes intact. */
    @Test
    void rewritingARealStreamKeepsEverySlice() throws IOException {
        byte[] prefixed = AnnexB.toLengthPrefixed(elementaryStream());

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
