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
 * What a fragmenter needs to know about one video codec, and nothing else.
 *
 * <p>Everything above this — {@code moov}, {@code moof}, {@code trun},
 * {@code tfdt}, fragment boundaries, timestamps — is the same whatever the
 * pictures are encoded with. What differs is narrow: how a sample is framed, what
 * the sample entry is called, and what shape its configuration box takes.
 *
 * <p>The pattern every codec here follows is the same one: a transport stream
 * carries configuration <em>in band</em>, repeated ahead of keyframes, and MP4
 * hoists it into the init segment and removes it from the samples. That is why
 * this is stateful — a fragmenter cannot write an init segment until it has seen
 * the configuration, so it has a startup period during which it has pictures it
 * cannot yet describe.
 *
 * <p><b>Nothing here decodes.</b> Configuration is copied whole and samples are
 * re-framed; no bitstream is interpreted and no picture is reconstructed. That is
 * what keeps this repackaging rather than transcoding, and what makes it cheap.
 *
 * <p>One instance per track, used from whichever thread feeds it.
 */
public interface VideoCodec {

    /** The sample entry this codec's track carries — {@code avc1}, {@code hvc1}, {@code av01}. */
    String sampleEntryType();

    /**
     * Offers an access unit for whatever configuration it carries.
     *
     * <p>Called for every unit, not only the ones expected to carry any. A stream
     * may change parameter sets mid-flight, and a codec that stopped looking after
     * the first would describe later pictures with the wrong configuration.
     */
    void offer(byte[] accessUnit);

    /**
     * Whether enough configuration has been seen to write an init segment.
     *
     * <p>Until this is true a fragmenter has pictures it cannot describe. What it
     * does with them — hold or drop — is its decision, not this one's.
     */
    boolean isConfigured();

    /**
     * Writes the codec configuration box, which goes inside the sample entry.
     *
     * @throws IllegalStateException if {@link #isConfigured()} is false
     */
    void writeConfiguration(BoxWriter out);

    /**
     * The sample payload for an access unit, framed as MP4 carries it.
     *
     * <p>For the NAL codecs this means length prefixes instead of start codes,
     * with the parameter sets removed because they now live in the configuration
     * box — a decoder handed them in both places is entitled to object.
     */
    byte[] sample(byte[] accessUnit);

    /**
     * Whether a decoder could begin at this access unit.
     *
     * <p>Read from the bitstream's own unit types rather than from the transport
     * stream's random access indicator. That indicator is a muxer's choice and is
     * unreliable — one fixture in this project flags a single packet across fifty
     * pictures — whereas an IDR or IRAP unit is what a decoder actually acts on.
     */
    boolean isRandomAccess(byte[] accessUnit);
}
