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
 * The header a player reads before any media: what the track is and how to
 * decode it.
 *
 * <p>In MoQ terms this is what a subscriber needs before the first group can mean
 * anything, so it is sent once when a subscription opens and again to anyone
 * joining later. It carries no samples and no timing — only the description.
 *
 * <p><b>The sample tables are present and empty, which is not a mistake.</b> A
 * plain MP4 keeps a table of every sample's size and position, built once the
 * file is complete. A fragmented one cannot: the samples have not happened yet.
 * The tables stay because the format requires them, holding zero entries, and
 * {@code mvex} says the real information arrives later in fragments.
 *
 * <p>The timescale is 90 kHz throughout, matching the transport stream's own
 * clock. Rescaling would introduce rounding into every timestamp for no benefit,
 * and the reference muxer makes the same choice.
 */
public final class InitSegment {

    /** The clock everything here is counted in, matching PTS. */
    public static final int TIMESCALE = 90_000;

    private InitSegment() {
    }

    /**
     * Builds the init segment for one video track.
     *
     * @param codec configured — see {@link VideoCodec#isConfigured()}
     * @param trackId the track's identifier, which fragments repeat
     * @throws IllegalStateException if the codec has not seen its configuration
     */
    public static byte[] forVideo(VideoCodec codec, int trackId) {
        if (!codec.isConfigured()) {
            throw new IllegalStateException(
                    "the codec has not seen enough configuration to describe a track");
        }
        int width = codec.width();
        int height = codec.height();

        BoxWriter out = new BoxWriter();
        out.box("ftyp", ftyp -> ftyp
                .type("isom").u32(0x200)
                .type("isom").type("iso6").type("iso2").type("avc1").type("mp41"));

        out.box("moov", moov -> {
            moov.fullBox("mvhd", 0, 0, mvhd -> mvhd
                    .u32(0).u32(0)              // created, modified
                    .u32(TIMESCALE)
                    .u32(0)                     // duration: unknown, and never filled in
                    .fixed16_16(1.0)            // rate
                    .u16(0x0100)                // volume
                    .zeros(2 + 8)               // reserved
                    .bytes(unityMatrix())
                    .zeros(24)                  // predefined
                    .u32(trackId + 1));         // the next free track id

            moov.box("trak", trak -> {
                // Flags 3: the track is enabled and is part of the presentation.
                trak.fullBox("tkhd", 0, 3, tkhd -> tkhd
                        .u32(0).u32(0)          // created, modified
                        .u32(trackId)
                        .zeros(4)               // reserved
                        .u32(0)                 // duration
                        .zeros(8)               // reserved
                        .u16(0)                 // layer
                        .u16(0)                 // alternate group
                        .u16(0)                 // volume: silent, this is video
                        .zeros(2)
                        .bytes(unityMatrix())
                        .fixed16_16(width)
                        .fixed16_16(height));

                trak.box("mdia", mdia -> {
                    mdia.fullBox("mdhd", 0, 0, mdhd -> mdhd
                            .u32(0).u32(0)
                            .u32(TIMESCALE)
                            .u32(0)             // duration
                            .u16(0x55C4)        // language: "und"
                            .u16(0));
                    mdia.fullBox("hdlr", 0, 0, hdlr -> hdlr
                            .u32(0)
                            .type("vide")
                            .zeros(12)
                            .bytes("VideoHandler".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                            .u8(0));

                    mdia.box("minf", minf -> {
                        minf.fullBox("vmhd", 0, 1, vmhd -> vmhd.u16(0).zeros(6));
                        minf.box("dinf", dinf -> dinf.fullBox("dref", 0, 0, dref -> {
                            dref.u32(1);
                            // Flags 1: the media is in this file, so there is no
                            // location to record.
                            dref.fullBox("url ", 0, 1, url -> { });
                        }));
                        minf.box("stbl", stbl -> {
                            stbl.fullBox("stsd", 0, 0, stsd -> {
                                stsd.u32(1);
                                stsd.box(codec.sampleEntryType(), entry -> {
                                    entry.zeros(6).u16(1);      // reserved, data reference index
                                    entry.zeros(16);            // predefined and reserved
                                    entry.u16(width).u16(height);
                                    entry.u32(0x00480000).u32(0x00480000);  // 72 dpi
                                    entry.u32(0);               // reserved
                                    entry.u16(1);               // frame count
                                    entry.zeros(32);            // compressor name
                                    entry.u16(0x0018);          // depth
                                    entry.u16(0xFFFF);          // predefined, -1
                                    codec.writeConfiguration(entry);
                                });
                            });
                            // Required, and empty: the samples they would describe
                            // have not happened yet.
                            stbl.fullBox("stts", 0, 0, stts -> stts.u32(0));
                            stbl.fullBox("stsc", 0, 0, stsc -> stsc.u32(0));
                            stbl.fullBox("stsz", 0, 0, stsz -> stsz.u32(0).u32(0));
                            stbl.fullBox("stco", 0, 0, stco -> stco.u32(0));
                        });
                    });
                });
            });

            // Says the sample information arrives in fragments rather than here.
            moov.box("mvex", mvex -> mvex.fullBox("trex", 0, 0, trex -> trex
                    .u32(trackId)
                    .u32(1)     // default sample description index
                    .u32(0)     // default sample duration
                    .u32(0)     // default sample size
                    .u32(0)));  // default sample flags
        });
        return out.toByteArray();
    }

    /** The identity transform, which every one of these boxes carries. */
    private static byte[] unityMatrix() {
        BoxWriter matrix = new BoxWriter();
        matrix.u32(0x00010000).u32(0).u32(0);
        matrix.u32(0).u32(0x00010000).u32(0);
        matrix.u32(0).u32(0).u32(0x40000000);
        return matrix.toByteArray();
    }
}
