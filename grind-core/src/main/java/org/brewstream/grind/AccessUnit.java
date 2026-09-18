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
 * One elementary stream payload, reassembled from the transport packets that
 * carried it — a coded picture, or a run of audio frames.
 *
 * <p>This is the PES payload with the PES header removed: for H.264 it is
 * Annex&nbsp;B with start codes, for AAC it is ADTS frames. Grind does not look
 * inside. The v1 line still holds — everything needed to inspect, nothing needed
 * to decode — and reassembly is not decoding: no bitstream is parsed, nothing is
 * interpreted, the bytes come out as the encoder produced them.
 *
 * <p><b>The array is owned by this unit</b>, unlike {@link TsPacket}, which
 * borrows one. A unit is built by copying out of many packets, so there is no
 * caller's buffer to borrow from — and a consumer that will hold a fragment for
 * a while needs bytes that outlive the receive buffer anyway.
 *
 * @param pid          the track this came from
 * @param pts          presentation timestamp in 90 kHz units, or -1 if absent
 * @param dts          decode timestamp, or -1 when it equals the PTS
 * @param randomAccess whether a decoder could start here — a keyframe, for video
 * @param data         the elementary stream bytes
 */
public record AccessUnit(int pid, long pts, long dts, boolean randomAccess, byte[] data) {

    /** Whether a presentation timestamp was carried. */
    public boolean hasPts() {
        return pts >= 0;
    }

    /**
     * The decode timestamp, falling back to the presentation one.
     *
     * <p>A PES header carries a DTS only when it differs from the PTS, which is
     * to say only when frames are reordered. Absent means the two are the same,
     * not that there is no decode time.
     */
    public long decodeTimestamp() {
        return dts >= 0 ? dts : pts;
    }

    /** How many bytes the payload occupies. */
    public int length() {
        return data.length;
    }

    /**
     * Value equality, comparing the payload by content.
     *
     * <p>A record holding an array would otherwise get identity equality, which
     * fails silently when every field matches. The same trap made {@code
     * TsPacket} a class and is written out by hand on {@code
     * SegmentationDescriptor}.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AccessUnit that
                && pid == that.pid && pts == that.pts && dts == that.dts
                && randomAccess == that.randomAccess
                && java.util.Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(pid, pts, dts, randomAccess)
                + java.util.Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "AccessUnit[pid=0x" + Integer.toHexString(pid).toUpperCase()
                + ", pts=" + pts + ", bytes=" + data.length
                + (randomAccess ? ", random access]" : "]");
    }
}
