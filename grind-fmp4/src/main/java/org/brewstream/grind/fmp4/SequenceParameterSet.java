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
    private final int width;
    private final int height;

    private SequenceParameterSet(int chromaFormat, int luma, int chroma, boolean hasExtension,
            int width, int height) {
        this.chromaFormat = chromaFormat;
        this.bitDepthLumaMinus8 = luma;
        this.bitDepthChromaMinus8 = chroma;
        this.hasExtension = hasExtension;
        this.width = width;
        this.height = height;
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
        boolean extended = profileHasExtension(profile);

        // Past the NAL header, profile, constraint flags and level.
        BitReader bits = new BitReader(removeEmulationPrevention(sps, 4));
        bits.unsignedExpGolomb();                       // seq_parameter_set_id

        int chromaFormat = 1;                           // 4:2:0 unless said otherwise
        int luma = 0;
        int chroma = 0;
        if (extended) {
            chromaFormat = bits.unsignedExpGolomb();
            if (chromaFormat == 3) {
                bits.bit();                             // separate_colour_plane_flag
            }
            luma = bits.unsignedExpGolomb();
            chroma = bits.unsignedExpGolomb();
            bits.bit();                                 // qpprime_y_zero_transform_bypass_flag
            if (bits.bit() == 1) {
                skipScalingMatrix(bits, chromaFormat);
            }
        }

        bits.unsignedExpGolomb();                       // log2_max_frame_num_minus4
        int pictureOrderType = bits.unsignedExpGolomb();
        if (pictureOrderType == 0) {
            bits.unsignedExpGolomb();                   // log2_max_pic_order_cnt_lsb_minus4
        } else if (pictureOrderType == 1) {
            bits.bit();                                 // delta_pic_order_always_zero_flag
            bits.signedExpGolomb();                     // offset_for_non_ref_pic
            bits.signedExpGolomb();                     // offset_for_top_to_bottom_field
            int cycle = bits.unsignedExpGolomb();
            for (int i = 0; i < cycle; i++) {
                bits.signedExpGolomb();                 // offset_for_ref_frame
            }
        }
        bits.unsignedExpGolomb();                       // max_num_ref_frames
        bits.bit();                                     // gaps_in_frame_num_value_allowed_flag

        int widthInMacroblocks = bits.unsignedExpGolomb() + 1;
        int heightInMapUnits = bits.unsignedExpGolomb() + 1;
        int frameMbsOnly = bits.bit();
        if (frameMbsOnly == 0) {
            bits.bit();                                 // mb_adaptive_frame_field_flag
        }
        bits.bit();                                     // direct_8x8_inference_flag

        int cropLeft = 0;
        int cropRight = 0;
        int cropTop = 0;
        int cropBottom = 0;
        if (bits.bit() == 1) {                          // frame_cropping_flag
            cropLeft = bits.unsignedExpGolomb();
            cropRight = bits.unsignedExpGolomb();
            cropTop = bits.unsignedExpGolomb();
            cropBottom = bits.unsignedExpGolomb();
        }

        // Cropping is counted in chroma samples, so how many luma samples each
        // unit is worth depends on the sampling. A 4:2:0 picture crops two luma
        // columns per unit; a 4:4:4 one crops a single column.
        int cropUnitX = chromaFormat == 3 ? 1 : 2;
        int cropUnitY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);

        int width = widthInMacroblocks * 16 - (cropLeft + cropRight) * cropUnitX;
        int height = (2 - frameMbsOnly) * heightInMapUnits * 16
                - (cropTop + cropBottom) * cropUnitY;

        return new SequenceParameterSet(chromaFormat, luma, chroma, extended, width, height);
    }

    /**
     * Steps over the scaling lists without reading them.
     *
     * <p>They describe quantisation, which matters to a decoder and not at all to
     * a container. Skipping them still means walking them: each list is a run of
     * exp-golomb deltas whose length is only known by reading it, so there is no
     * offset to jump to.
     */
    private static void skipScalingMatrix(BitReader bits, int chromaFormat) {
        int lists = chromaFormat == 3 ? 12 : 8;
        for (int i = 0; i < lists; i++) {
            if (bits.bit() == 0) {
                continue;                               // this list is not present
            }
            int size = i < 6 ? 16 : 64;
            int last = 8;
            int next = 8;
            for (int j = 0; j < size; j++) {
                if (next != 0) {
                    next = (last + bits.signedExpGolomb() + 256) % 256;
                }
                last = next == 0 ? last : next;
            }
        }
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

    /** The coded width in luma samples, after cropping. */
    int width() {
        return width;
    }

    /** The coded height in luma samples, after cropping. */
    int height() {
        return height;
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

        /** A signed exp-golomb value, which zigzags around zero as the format does. */
        int signedExpGolomb() {
            int value = unsignedExpGolomb();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
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
