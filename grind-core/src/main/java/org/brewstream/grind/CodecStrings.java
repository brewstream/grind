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

/**
 * RFC 6381 codec strings, read from a sequence parameter set: what a decoder
 * such as WebCodecs needs to be configured before it is given a frame.
 */
public final class CodecStrings {

    private CodecStrings() {
    }

    /**
     * {@code avc1.PPCCLL}: profile, constraint flags and level, the three bytes
     * after the NAL header of an H.264 SPS.
     *
     * @param sps an H.264 SPS NAL unit, header included, without a start code
     */
    public static String avc(byte[] sps) {
        if (sps.length < 4) {
            throw new IllegalArgumentException("an H.264 SPS needs at least 4 bytes, got " + sps.length);
        }
        return String.format("avc1.%02x%02x%02x", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
    }

    /**
     * {@code hev1.A.B.C.D} per RFC 6381 §E.4, from the general profile, tier and
     * level of an HEVC SPS: the profile space (a letter, none for 0) and profile;
     * the 32 compatibility flags in reverse bit order, as hex without leading
     * zeros; the tier ({@code L} or {@code H}) and level; then the six constraint
     * bytes, trailing zero bytes left out.
     *
     * <p>{@code hev1} rather than {@code hvc1}: the parameter sets travel in-band,
     * before each keyframe, which is what {@code hev1} declares.
     *
     * @param sps an HEVC SPS NAL unit, header included, without a start code
     */
    public static String hevc(byte[] sps) {
        BitReader bits = new BitReader(unescape(sps, 2));
        bits.skip(4); // sps_video_parameter_set_id
        bits.skip(3); // sps_max_sub_layers_minus1
        bits.skip(1); // sps_temporal_id_nesting_flag
        int profileSpace = bits.read(2);
        boolean highTier = bits.read(1) == 1;
        int profile = bits.read(5);
        long compatibility = bits.readLong(32);
        int[] constraints = new int[6];
        for (int i = 0; i < 6; i++) {
            constraints[i] = bits.read(8);
        }
        int level = bits.read(8);

        StringBuilder codec = new StringBuilder("hev1.");
        if (profileSpace > 0) {
            codec.append((char) ('A' + profileSpace - 1));
        }
        codec.append(profile).append('.');
        codec.append(Integer.toHexString(Integer.reverse((int) compatibility)).toUpperCase());
        codec.append('.').append(highTier ? 'H' : 'L').append(level);
        int last = constraints.length - 1;
        while (last >= 0 && constraints[last] == 0) {
            last--;
        }
        for (int i = 0; i <= last; i++) {
            codec.append('.').append(String.format("%02X", constraints[i]));
        }
        return codec.toString();
    }

    /** The NAL payload after {@code headerBytes}, with emulation-prevention bytes removed. */
    private static byte[] unescape(byte[] nal, int headerBytes) {
        byte[] out = new byte[nal.length];
        int length = 0;
        int zeros = 0;
        for (int i = headerBytes; i < nal.length; i++) {
            int b = nal[i] & 0xFF;
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue; // 00 00 03 xx: the 03 is not data
            }
            zeros = b == 0 ? zeros + 1 : 0;
            out[length++] = (byte) b;
        }
        return java.util.Arrays.copyOf(out, length);
    }

    private static final class BitReader {
        private final byte[] data;
        private int position;

        BitReader(byte[] data) {
            this.data = data;
        }

        int read(int count) {
            return (int) readLong(count);
        }

        long readLong(int count) {
            if (position + count > data.length * 8L) {
                throw new IllegalArgumentException("SPS ended before its profile, tier and level");
            }
            long value = 0;
            for (int i = 0; i < count; i++) {
                int bit = (data[position >> 3] >> (7 - (position & 7))) & 1;
                value = value << 1 | bit;
                position++;
            }
            return value;
        }

        void skip(int count) {
            readLong(count);
        }
    }
}