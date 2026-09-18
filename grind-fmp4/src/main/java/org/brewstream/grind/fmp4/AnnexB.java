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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splitting an Annex&nbsp;B elementary stream into its NAL units, and rewriting
 * it in the length-prefixed form MP4 uses.
 *
 * <p>The two formats carry identical payloads and differ only in how units are
 * delimited. Annex&nbsp;B separates them with a start code — three or four bytes
 * — which a decoder finds by scanning. MP4 prefixes each with its length, which
 * is faster to walk and impossible to confuse with payload.
 *
 * <p>Converting between them is not transcoding. No picture is decoded and no
 * bitstream is interpreted; the bytes inside each unit are untouched.
 */
public final class AnnexB {

    private AnnexB() {
    }

    /** One NAL unit's position within an Annex B buffer. */
    public record Nal(int offset, int length, int type) {

        /** The unit's bytes, copied out. */
        public byte[] copy(byte[] source) {
            return Arrays.copyOfRange(source, offset, offset + length);
        }
    }

    /**
     * Finds every NAL unit, skipping the start codes between them.
     *
     * <p>Start codes are three bytes or four; the four-byte form is the three-byte
     * one with a leading zero, so a scanner that only knows one of them either
     * misses units or includes a stray zero at the front of each.
     */
    public static List<Nal> split(byte[] data) {
        List<Nal> units = new ArrayList<>();
        int at = firstStartCode(data);
        while (at >= 0) {
            int payload = at + startCodeLength(data, at);
            int next = nextStartCode(data, payload);
            int end = next < 0 ? data.length : next;
            if (end > payload) {
                units.add(new Nal(payload, end - payload, data[payload] & 0x1F));
            }
            at = next;
        }
        return units;
    }

    /**
     * Rewrites an Annex B buffer with four-byte length prefixes, dropping the
     * parameter sets.
     *
     * <p>Parameter sets are dropped because they belong in {@code avcC} instead,
     * and a decoder handed them twice is entitled to object. Access unit
     * delimiters go too: they mark boundaries the length prefixes now make
     * explicit.
     *
     * @return the length-prefixed form, or an empty array if nothing was left
     */
    public static byte[] toLengthPrefixed(byte[] annexB) {
        List<Nal> units = split(annexB);
        int size = 0;
        for (Nal nal : units) {
            if (isCarried(nal.type())) {
                size += 4 + nal.length();
            }
        }

        byte[] out = new byte[size];
        int at = 0;
        for (Nal nal : units) {
            if (!isCarried(nal.type())) {
                continue;
            }
            out[at] = (byte) ((nal.length() >>> 24) & 0xFF);
            out[at + 1] = (byte) ((nal.length() >>> 16) & 0xFF);
            out[at + 2] = (byte) ((nal.length() >>> 8) & 0xFF);
            out[at + 3] = (byte) (nal.length() & 0xFF);
            System.arraycopy(annexB, nal.offset(), out, at + 4, nal.length());
            at += 4 + nal.length();
        }
        return out;
    }

    /** Whether a picture carries this unit, or the init segment does. */
    private static boolean isCarried(int type) {
        return type != AvcParameterSets.NAL_SPS
                && type != AvcParameterSets.NAL_PPS
                && type != 9;  // access unit delimiter
    }

    private static int firstStartCode(byte[] data) {
        return nextStartCode(data, 0);
    }

    private static int nextStartCode(byte[] data, int from) {
        for (int at = Math.max(0, from); at + 2 < data.length; at++) {
            if (data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1) {
                // Report the four-byte form from its leading zero, so the payload
                // begins after all of it.
                return at > 0 && data[at - 1] == 0 ? at - 1 : at;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int at) {
        return data[at] == 0 && at + 3 < data.length
                && data[at + 1] == 0 && data[at + 2] == 0 && data[at + 3] == 1 ? 4 : 3;
    }
}
