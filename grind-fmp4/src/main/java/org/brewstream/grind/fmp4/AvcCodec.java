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
 * H.264, carried as {@code avc1} with an {@code avcC} configuration box.
 *
 * <p>The sequence and picture parameter sets travel in band in a transport
 * stream, repeated ahead of keyframes; here they are collected and moved into
 * {@code avcC}, and removed from the samples.
 *
 * <p>Profile, compatibility and level are read positionally from the sequence
 * parameter set's first bytes rather than decoded from its bitstream. The
 * reference muxer arrives at the same three values that way, which is checked
 * against Bento4's reading of its output.
 */
public final class AvcCodec implements VideoCodec {

    /** Sequence parameter set. */
    static final int NAL_SPS = 7;

    /** Picture parameter set. */
    static final int NAL_PPS = 8;

    /** A coded slice of a picture a decoder can begin at. */
    static final int NAL_IDR = 5;

    /** Access unit delimiter, which the length prefixes make redundant. */
    static final int NAL_DELIMITER = 9;

    private final List<byte[]> sequenceSets = new ArrayList<>();
    private final List<byte[]> pictureSets = new ArrayList<>();

    /** The type in an AVC NAL header: five bits of a single byte. */
    static int typeOf(byte[] data, AnnexB.Nal nal) {
        return data[nal.offset()] & 0x1F;
    }

    @Override
    public String sampleEntryType() {
        return "avc1";
    }

    @Override
    public void offer(byte[] accessUnit) {
        for (AnnexB.Nal nal : AnnexB.split(accessUnit)) {
            switch (typeOf(accessUnit, nal)) {
                case NAL_SPS -> remember(sequenceSets, nal.copy(accessUnit));
                case NAL_PPS -> remember(pictureSets, nal.copy(accessUnit));
                default -> { }
            }
        }
    }

    /**
     * Keeps a parameter set unless an identical one is already held.
     *
     * <p>They are repeated ahead of every keyframe, so a list that grew on each
     * would carry fifty copies of the same bytes into {@code avcC} over a
     * two-second stream. A set that genuinely differs is kept, because a stream
     * may change them mid-flight.
     */
    private static void remember(List<byte[]> held, byte[] candidate) {
        for (byte[] existing : held) {
            if (java.util.Arrays.equals(existing, candidate)) {
                return;
            }
        }
        held.add(candidate);
    }

    @Override
    public boolean isConfigured() {
        return !sequenceSets.isEmpty() && !pictureSets.isEmpty();
    }

    @Override
    public void writeConfiguration(BoxWriter out) {
        if (!isConfigured()) {
            throw new IllegalStateException("no parameter sets have been seen yet");
        }
        byte[] sps = sequenceSets.get(0);
        out.box("avcC", box -> {
            box.u8(1);              // configuration version
            box.u8(sps[1] & 0xFF);  // profile
            box.u8(sps[2] & 0xFF);  // profile compatibility
            box.u8(sps[3] & 0xFF);  // level
            // Six reserved bits set, then the length-prefix size less one. Four
            // bytes is what this writes, so three.
            box.u8(0xFC | 3);
            box.u8(0xE0 | sequenceSets.size());  // three reserved bits, then the count
            for (byte[] set : sequenceSets) {
                box.u16(set.length).bytes(set);
            }
            box.u8(pictureSets.size());
            for (byte[] set : pictureSets) {
                box.u16(set.length).bytes(set);
            }

            // High profiles carry four more bytes describing chroma format and
            // bit depth. Broadcast H.264 is overwhelmingly High, so omitting them
            // is wrong for the common case rather than an exotic one.
            SequenceParameterSet parsed = SequenceParameterSet.parse(sps);
            if (parsed.hasExtension()) {
                box.u8(0xFC | parsed.chromaFormat());
                box.u8(0xF8 | parsed.bitDepthLumaMinus8());
                box.u8(0xF8 | parsed.bitDepthChromaMinus8());
                box.u8(0);  // no sequence parameter set extensions
            }
        });
    }

    @Override
    public byte[] sample(byte[] accessUnit) {
        List<AnnexB.Nal> carried = new ArrayList<>();
        for (AnnexB.Nal nal : AnnexB.split(accessUnit)) {
            int type = typeOf(accessUnit, nal);
            if (type != NAL_SPS && type != NAL_PPS && type != NAL_DELIMITER) {
                carried.add(nal);
            }
        }
        return AnnexB.lengthPrefix(accessUnit, carried);
    }

    @Override
    public boolean isRandomAccess(byte[] accessUnit) {
        for (AnnexB.Nal nal : AnnexB.split(accessUnit)) {
            if (typeOf(accessUnit, nal) == NAL_IDR) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int width() {
        return SequenceParameterSet.parse(sequenceSets.get(0)).width();
    }

    @Override
    public int height() {
        return SequenceParameterSet.parse(sequenceSets.get(0)).height();
    }

    /** The sequence parameter sets held, for inspection and testing. */
    public List<byte[]> sequenceSets() {
        return List.copyOf(sequenceSets);
    }

    /** The picture parameter sets held. */
    public List<byte[]> pictureSets() {
        return List.copyOf(pictureSets);
    }
}
