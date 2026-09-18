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

/**
 * One picture as a fragment carries it: bytes, timing, and whether a decoder
 * could start here.
 *
 * <p>The two timestamps are both needed and are not the same thing. Decode time
 * is when a decoder must have the sample; presentation time is when the picture
 * appears. With B-frames they differ, and MP4 records the difference as an offset
 * rather than storing both — which is why {@link #compositionOffset()} exists and
 * why it may be negative.
 *
 * @param data              the sample payload, framed as the codec's MP4 form requires
 * @param decodeTime        when the sample must be decoded, in the track's timescale
 * @param presentationTime  when the picture appears, in the same timescale
 * @param randomAccess      whether a decoder could begin here
 */
public record Sample(byte[] data, long decodeTime, long presentationTime, boolean randomAccess) {

    /**
     * How far presentation runs ahead of decoding, which is what {@code trun}
     * stores.
     *
     * <p>Zero whenever frames are not reordered. Negative is legal and happens
     * with B-frames, which is why the field is written signed — an unsigned one
     * turns a small negative into an enormous positive and a player jumps.
     */
    public int compositionOffset() {
        return (int) (presentationTime - decodeTime);
    }

    /**
     * The sample flags MP4 uses to describe dependency and sync.
     *
     * <p>A keyframe depends on nothing and is a sync sample; anything else
     * depends on other samples and is not. A player seeking uses exactly this to
     * find somewhere it can start.
     */
    public long flags() {
        // sample_depends_on = 2 (independent), sync sample.
        // sample_depends_on = 1 (dependent), plus sample_is_non_sync_sample.
        return randomAccess ? 0x02000000L : 0x01010000L;
    }
}
