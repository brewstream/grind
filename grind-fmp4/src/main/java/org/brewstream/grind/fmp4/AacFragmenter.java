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

import org.brewstream.grind.AccessUnit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits ADTS from PES payloads into raw AAC samples, emitting one fragment per
 * call containing complete frames. Audio has no GOP: every frame is a sync sample.
 *
 * <p>A frame may cross PES boundaries. At most one incomplete ADTS frame (8191
 * bytes) is retained. The PES PTS anchors the first frame beginning in that PES;
 * subsequent frames advance by exactly 1024 audio samples, avoiding 90 kHz
 * rounding drift. Missing timestamps continue the established sample clock.
 *
 * <p>Call {@link #discontinuity()} after upstream packet loss to discard any
 * incomplete frame and require a fresh PTS. Normal PTS wrap is unwrapped; a
 * timestamp reset needs a new track/timeline. Stateful, one instance per track.
 */
public final class AacFragmenter {
    private final AacCodec codec;
    private final int trackId;
    private byte[] pending = new byte[0];
    private long pendingPts = -1;
    private long nextTime = -1;
    private long lastPts = -1;
    private int sequence;

    public AacFragmenter(AacCodec codec, int trackId) {
        if (trackId <= 0) {
            throw new IllegalArgumentException("track id must be positive");
        }
        this.codec = java.util.Objects.requireNonNull(codec);
        this.trackId = trackId;
    }

    /** Returns a fragment of completed frames, or null while a frame is incomplete. */
    public byte[] add(AccessUnit unit) {
        int prefix = pending.length;
        byte[] bytes = Arrays.copyOf(pending, prefix + unit.data().length);
        System.arraycopy(unit.data(), 0, bytes, prefix, unit.data().length);
        List<Sample> samples = new ArrayList<>();
        boolean anchored = false;
        int at = 0;
        long anchor = pendingPts;
        while (at < bytes.length) {
            if (at >= prefix && !anchored) {
                anchor = unit.hasPts() ? unit.pts() : -1;
                anchored = true;
            }
            if (bytes.length - at < 7) {
                break;
            }
            AacCodec.Header header = AacCodec.header(bytes, at);
            if (bytes.length - at < header.length()) {
                break;
            }
            codec.configure(header);
            long time = anchor >= 0 ? rescale(anchor) : nextTime;
            if (time < 0) {
                throw new IllegalArgumentException("AAC needs a PTS to establish its sample clock");
            }
            if (nextTime >= 0 && time < nextTime - 1) {
                throw new IllegalArgumentException("AAC timestamps moved backwards; start a new timeline");
            }
            // A 90 kHz timestamp may round by one audio tick at a PES boundary.
            if (nextTime >= 0 && Math.abs(time - nextTime) <= 1) {
                time = nextTime;
            }
            if (!samples.isEmpty() && time != nextTime) {
                throw new IllegalArgumentException("AAC discontinuity within a PES; start a new timeline");
            }
            samples.add(new Sample(Arrays.copyOfRange(bytes, at + header.headerLength(),
                    at + header.length()), time, time, true));
            nextTime = time + 1024;
            at += header.length();
            anchor = -1;
        }
        pending = Arrays.copyOfRange(bytes, at, bytes.length);
        pendingPts = anchor;
        return samples.isEmpty() ? null : MediaFragment.of(trackId, ++sequence, samples, 1024);
    }

    /** Standalone audio init segment, or null before the first complete frame. */
    public byte[] initSegment() {
        return codec.isConfigured() ? InitSegment.forAudio(codec, trackId) : null;
    }

    /** End of input must not silently discard a truncated ADTS frame. */
    public void finish() {
        if (pending.length != 0) {
            throw new IllegalArgumentException("truncated ADTS frame at end of input");
        }
    }

    /** Discards a partial frame after loss; the next input must start at an ADTS frame with PTS. */
    public void discontinuity() {
        pending = new byte[0];
        pendingPts = -1;
        nextTime = -1;
    }

    /** Fragments emitted by this track. */
    public int fragmentCount() {
        return sequence;
    }

    private long rescale(long pts) {
        long unwrapped = pts;
        if (lastPts >= 0) {
            long wrap = 1L << 33;
            unwrapped += Math.floorDiv(lastPts - pts + wrap / 2, wrap) * wrap;
        }
        lastPts = unwrapped;
        return Math.floorDiv(unwrapped * codec.sampleRate() + 45_000, 90_000);
    }
}
