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
import java.util.List;

/**
 * The sequence and picture parameter sets a decoder needs before any picture.
 *
 * <p>In a transport stream these travel in-band, repeated ahead of keyframes. In
 * MP4 they move into the {@code avcC} box of the init segment and are removed
 * from the pictures — which is why an init segment cannot be written until one
 * has been seen, and why a fragmenter has a startup state rather than producing
 * output from its first access unit.
 *
 * <p><b>Nothing is decoded here.</b> The parameter sets are copied whole, and the
 * three bytes {@code avcC} needs beyond them — profile, compatibility, level —
 * are read straight out of the sequence parameter set's first bytes rather than
 * parsed from its bitstream. The reference muxer produces the same values that
 * way, which is checked.
 */
public final class AvcParameterSets {

    /** NAL unit type 7: sequence parameter set. */
    public static final int NAL_SPS = 7;

    /** NAL unit type 8: picture parameter set. */
    public static final int NAL_PPS = 8;

    /** NAL unit type 5: a coded slice of a picture a decoder can start at. */
    public static final int NAL_IDR = 5;

    private final List<byte[]> sequenceSets;
    private final List<byte[]> pictureSets;

    private AvcParameterSets(List<byte[]> sequenceSets, List<byte[]> pictureSets) {
        this.sequenceSets = List.copyOf(sequenceSets);
        this.pictureSets = List.copyOf(pictureSets);
    }

    /**
     * Finds the parameter sets in an Annex&nbsp;B access unit.
     *
     * @return the sets found, which may be empty — most pictures carry none
     */
    public static AvcParameterSets from(byte[] annexB) {
        List<byte[]> sequence = new ArrayList<>();
        List<byte[]> picture = new ArrayList<>();
        for (AnnexB.Nal nal : AnnexB.split(annexB)) {
            byte[] unit = nal.copy(annexB);
            switch (nal.type()) {
                case NAL_SPS -> sequence.add(unit);
                case NAL_PPS -> picture.add(unit);
                default -> { }
            }
        }
        return new AvcParameterSets(sequence, picture);
    }

    /** Whether both kinds are present, which is what an init segment needs. */
    public boolean isComplete() {
        return !sequenceSets.isEmpty() && !pictureSets.isEmpty();
    }

    /** The sequence parameter sets, in the order they appeared. */
    public List<byte[]> sequenceSets() {
        return sequenceSets;
    }

    /** The picture parameter sets, in the order they appeared. */
    public List<byte[]> pictureSets() {
        return pictureSets;
    }

    /**
     * The profile, compatibility and level bytes {@code avcC} carries.
     *
     * <p>These are the three bytes following the sequence parameter set's NAL
     * header, used as they are. Reading them positionally rather than decoding
     * the bitstream is what the reference muxer does, and is checked against it.
     */
    public byte[] profileLevel() {
        byte[] sps = sequenceSets.get(0);
        if (sps.length < 4) {
            throw new IllegalStateException("sequence parameter set too short to carry a profile");
        }
        return new byte[]{sps[1], sps[2], sps[3]};
    }
}
