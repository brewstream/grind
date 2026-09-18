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

import java.util.List;

/**
 * One run of samples, as {@code moof} describing them and {@code mdat} holding
 * them.
 *
 * <p>A fragment is self-describing: everything needed to decode its samples is
 * here, so a player joining mid-stream needs only the init segment and whichever
 * fragment arrives next. That is what makes the format suit a live pub/sub
 * transport, where a subscriber cannot be assumed to have seen anything before.
 *
 * <p><b>Offsets are relative to the fragment itself.</b> The
 * {@code default-base-is-moof} flag says so, and it is what makes a fragment
 * movable — its bytes mean the same thing wherever they land, which they must
 * when each one travels as its own MoQ group rather than at a known position in
 * a file.
 */
public final class MediaFragment {

    /** Offsets are measured from the start of this {@code moof}. */
    private static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

    /** {@code trun} carries a data offset, then per-sample duration, size, flags and offset. */
    private static final int TRUN_DATA_OFFSET = 0x000001;
    private static final int TRUN_SAMPLE_DURATION = 0x000100;
    private static final int TRUN_SAMPLE_SIZE = 0x000200;
    private static final int TRUN_SAMPLE_FLAGS = 0x000400;
    private static final int TRUN_COMPOSITION_OFFSET = 0x000800;

    private MediaFragment() {
    }

    /**
     * Builds one fragment.
     *
     * @param trackId  the track these samples belong to
     * @param sequence the fragment's number, counting from one
     * @param samples  in decode order, which is the order they are carried
     * @throws IllegalArgumentException if there are no samples
     */
    public static byte[] of(int trackId, int sequence, List<Sample> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("a fragment must carry at least one sample");
        }

        // The data offset counts from the moof's own start, so it depends on the
        // moof's length - which depends on nothing else here. Built once to
        // measure, then again with the answer, rather than reserving space and
        // patching it afterwards.
        int provisional = moof(trackId, sequence, samples, 0).length;
        byte[] moof = moof(trackId, sequence, samples, provisional + 8);

        int payload = 0;
        for (Sample sample : samples) {
            payload += sample.data().length;
        }

        BoxWriter out = new BoxWriter();
        out.bytes(moof);
        out.u32(8 + payload).type("mdat");
        for (Sample sample : samples) {
            out.bytes(sample.data());
        }
        return out.toByteArray();
    }

    private static byte[] moof(int trackId, int sequence, List<Sample> samples, int dataOffset) {
        BoxWriter out = new BoxWriter();
        out.box("moof", moof -> {
            moof.fullBox("mfhd", 0, 0, mfhd -> mfhd.u32(sequence));
            moof.box("traf", traf -> {
                traf.fullBox("tfhd", 0, TFHD_DEFAULT_BASE_IS_MOOF, tfhd -> tfhd.u32(trackId));

                // Version 1 so the decode time is 64 bits. A 32-bit one wraps
                // after about thirteen hours at 90 kHz, which a live stream
                // reaches.
                traf.fullBox("tfdt", 1, 0, tfdt -> tfdt.u64(samples.get(0).decodeTime()));

                int flags = TRUN_DATA_OFFSET | TRUN_SAMPLE_DURATION | TRUN_SAMPLE_SIZE
                        | TRUN_SAMPLE_FLAGS | TRUN_COMPOSITION_OFFSET;
                // Version 1 so composition offsets are signed. With version 0 a
                // B-frame's negative offset becomes an enormous positive one and
                // a player jumps rather than reorders.
                traf.fullBox("trun", 1, flags, trun -> {
                    trun.u32(samples.size());
                    trun.s32(dataOffset);
                    for (int i = 0; i < samples.size(); i++) {
                        Sample sample = samples.get(i);
                        trun.u32(durationOf(samples, i));
                        trun.u32(sample.data().length);
                        trun.u32(sample.flags());
                        trun.s32(sample.compositionOffset());
                    }
                });
            });
        });
        return out.toByteArray();
    }

    /**
     * How long a sample is shown for: the gap to the next one's decode time.
     *
     * <p>The last sample in a fragment has no next, so it borrows the duration
     * before it. Guessing wrong there costs one frame's worth of timing at a
     * boundary, and there is nothing in the stream that says better.
     */
    private static long durationOf(List<Sample> samples, int index) {
        if (index + 1 < samples.size()) {
            return samples.get(index + 1).decodeTime() - samples.get(index).decodeTime();
        }
        if (samples.size() >= 2) {
            return samples.get(index).decodeTime() - samples.get(index - 1).decodeTime();
        }
        return 0;
    }
}
