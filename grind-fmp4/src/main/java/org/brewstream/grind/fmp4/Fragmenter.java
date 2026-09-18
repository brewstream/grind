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
import java.util.List;

/**
 * Turns a track's access units into an init segment and a run of fragments.
 *
 * <p>The whole of {@code grind-fmp4} above the boxes. Feed it access units from
 * {@code AccessUnitAssembler}; it works out when a fragment has ended, describes
 * the samples, and hands back bytes a player can use.
 *
 * <p><b>Fragments begin at keyframes.</b> Anywhere else and a subscriber joining
 * at a fragment boundary receives pictures that depend on ones it never got. That
 * also makes a fragment exactly the unit MoQ wants for a group, which is not a
 * coincidence — both are answering "where can somebody start?".
 *
 * <p><b>It starts silent.</b> Until a keyframe carrying parameter sets arrives
 * there is nothing to describe the pictures with, so nothing can be emitted.
 * Units before that are counted and dropped rather than held: a player cannot
 * use them, and holding them would grow without bound on a stream whose muxer
 * never sends parameter sets at all.
 *
 * <p>Stateful and not thread-safe: one per track.
 */
public final class Fragmenter {

    private final VideoCodec codec;
    private final int trackId;
    private final List<Sample> pending = new ArrayList<>();

    private byte[] initSegment;
    private int sequence;
    private long droppedBeforeConfiguration;

    public Fragmenter(VideoCodec codec, int trackId) {
        this.codec = codec;
        this.trackId = trackId;
    }

    /**
     * Accounts for one access unit.
     *
     * @return the fragment this unit completed, or null — which is the usual
     *         answer, since a fragment spans a whole group of pictures
     */
    public byte[] add(AccessUnit unit) {
        codec.offer(unit.data());
        if (!codec.isConfigured()) {
            droppedBeforeConfiguration++;
            return null;
        }

        boolean startsHere = codec.isRandomAccess(unit.data());
        byte[] finished = startsHere ? flush() : null;

        pending.add(new Sample(
                codec.sample(unit.data()),
                unit.decodeTimestamp(),
                unit.hasPts() ? unit.pts() : unit.decodeTimestamp(),
                startsHere));
        return finished;
    }

    /**
     * The init segment, once there is enough to write one.
     *
     * <p>Built on first request and kept, because every subscriber needs the same
     * bytes and a stream's configuration rarely changes.
     *
     * @return the segment, or null while the codec is still unconfigured
     */
    public byte[] initSegment() {
        if (!codec.isConfigured()) {
            return null;
        }
        if (initSegment == null) {
            initSegment = InitSegment.forVideo(codec, trackId);
        }
        return initSegment;
    }

    /**
     * Closes the fragment being built.
     *
     * <p>Needed at end of stream, since only the next keyframe would otherwise
     * end one — the same reason {@code AccessUnitAssembler} needs a flush.
     *
     * @return the fragment, or null if nothing was pending
     */
    public byte[] flush() {
        if (pending.isEmpty()) {
            return null;
        }
        byte[] fragment = MediaFragment.of(trackId, ++sequence, List.copyOf(pending));
        pending.clear();
        return fragment;
    }

    /** How many fragments have been produced. */
    public int fragmentCount() {
        return sequence;
    }

    /** Access units discarded because nothing yet described how to decode them. */
    public long droppedBeforeConfiguration() {
        return droppedBeforeConfiguration;
    }

    /** Whether samples are waiting for a keyframe or a flush to close their fragment. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
