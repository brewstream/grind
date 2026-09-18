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

import java.io.ByteArrayOutputStream;

/**
 * The few fields of an H.264 sequence parameter set that {@code avcC} cannot do
 * without.
 *
 * <p><b>This is the one place the "no bitstream parsing" line has to be crossed,
 * and it is worth knowing why.</b> For the High profiles — 100, 110, 122, 244 —
 * {@code avcC} carries four extra bytes describing chroma format and bit depth,
 * and those values exist nowhere but inside the sequence parameter set's
 * exp-golomb coded bitstream. Broadcast H.264 is overwhelmingly High profile, so
 * this is the common case rather than an exotic one. A configuration box written
 * without it is four bytes short and wrong.
 *
 * <p>Only the fields needed are read, and reading stops immediately after them.
 * Nothing is decoded: no picture is reconstructed and the parameter set itself is
 * still copied into {@code avcC} whole.
 */
final class SequenceParameterSet {

    /** Profiles whose configuration box carries the chroma and bit depth extension. */
    private static final int[] HIGH_PROFILES =
            {100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135};

    private final int chromaFormat;
    private final int bitDepthLumaMinus8;
    private final int bitDepthChromaMinus8;
    private final boolean hasExtension;

    private SequenceParameterSet(int chromaFormat, int luma, int chroma, boolean hasExtension) {
        this.chromaFormat = chromaFormat;
        this.bitDepthLumaMinus8 = luma;
        this.bitDepthChromaMinus8 = chroma;
        this.hasExtension = hasExtension;
    }

    /** Whether this profile's configuration box carries the extension at all. */
    static boolean profileHasExtension(int profile) {
        for (int high : HIGH_PROFILES) {
            if (high == profile) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads what is needed from a sequence parameter set NAL unit.
     *
     * @param sps the unit including its NAL header byte
     */
    static SequenceParameterSet parse(byte[] sps) {
        int profile = sps[1] & 0xFF;
        if (!profileHasExtension(profile)) {
            // Baseline and Main carry no extension, so nothing here is needed and
            // the bitstream is left unread.
            return new SequenceParameterSet(0, 0, 0, false);
        }

        // Past the NAL header, profile, constraint flags and level.
        BitReader bits = new BitReader(removeEmulationPrevention(sps, 4));
        bits.unsignedExpGolomb();                       // seq_parameter_set_id
        int chromaFormat = bits.unsignedExpGolomb();
        if (chromaFormat == 3) {
            bits.bit();                                 // separate_colour_plane_flag
        }
        int luma = bits.unsignedExpGolomb();
        int chroma = bits.unsignedExpGolomb();
        return new SequenceParameterSet(chromaFormat, luma, chroma, true);
    }

    boolean hasExtension() {
        return hasExtension;
    }

    int chromaFormat() {
        return chromaFormat;
    }

    int bitDepthLumaMinus8() {
        return bitDepthLumaMinus8;
    }

    int bitDepthChromaMinus8() {
        return bitDepthChromaMinus8;
    }

    /**
     * Strips the bytes inserted to stop payload imitating a start code.
     *
     * <p>An encoder writing {@code 00 00 00} or {@code 00 00 01} into the payload
     * would produce something a scanner reads as a start code, so it inserts
     * {@code 03} after two zeroes. Those bytes are not part of the bitstream and
     * reading them as if they were shifts every field after the first one.
     */
    static byte[] removeEmulationPrevention(byte[] data, int from) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length - from);
        int zeroes = 0;
        for (int at = from; at < data.length; at++) {
            int value = data[at] & 0xFF;
            // At least two, not exactly two: 00 00 00 03 carries an escape as much
            // as 00 00 03 does, and a decoder that insists on exactly two leaves it in.
            if (zeroes >= 2 && value == 0x03) {
                zeroes = 0;
                continue;
            }
            out.write(value);
            zeroes = value == 0 ? zeroes + 1 : 0;
        }
        return out.toByteArray();
    }

    /** Reads the bit-level encodings a parameter set is written in. */
    private static final class BitReader {

        private final byte[] data;
        private int at;

        BitReader(byte[] data) {
            this.data = data;
        }

        int bit() {
            int value = (data[at >>> 3] >>> (7 - (at & 7))) & 1;
            at++;
            return value;
        }

        /**
         * An unsigned exp-golomb value: as many zeroes as there are following
         * bits, then a one, then those bits.
         */
        int unsignedExpGolomb() {
            int leadingZeroes = 0;
            while (bit() == 0) {
                leadingZeroes++;
                if (leadingZeroes > 31) {
                    throw new IllegalArgumentException("malformed exp-golomb value");
                }
            }
            int value = 0;
            for (int i = 0; i < leadingZeroes; i++) {
                value = (value << 1) | bit();
            }
            return value + (1 << leadingZeroes) - 1;
        }
    }
}
