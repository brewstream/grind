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

import java.util.Arrays;

/**
 * A still that decodes on its own: a video track's parameter sets followed by
 * one complete IDR picture, in Annex B form (each NAL unit after a start code).
 * Hand {@link #data()} and {@link #codec()} to a decoder, for example a
 * browser's WebCodecs {@code VideoDecoder} configured with {@code codec}, and it
 * produces one frame with no other context.
 *
 * <p>Equality and hashing compare the bytes, not the array instance.
 *
 * @param pid   the video track it was taken from
 * @param codec the RFC 6381 codec string, e.g. {@code avc1.64000c} or {@code hev1.1.6.L60.90}
 * @param pts   presentation timestamp of the IDR, 90 kHz; -1 if its PES header had none
 * @param data  parameter sets, then the IDR access unit's NAL units, with start codes
 */
public record Keyframe(int pid, String codec, long pts, byte[] data) {

    public int length() {
        return data.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Keyframe that && pid == that.pid && pts == that.pts
                && codec.equals(that.codec) && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Integer.hashCode(pid) + codec.hashCode()) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Keyframe[pid=" + pid + ", codec=" + codec + ", pts=" + pts + ", " + data.length + " bytes]";
    }
}