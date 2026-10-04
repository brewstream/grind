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

package org.brewstream.grind.fmp4.cli;

import org.brewstream.grind.AccessUnit;
import org.brewstream.grind.AccessUnitAssembler;
import org.brewstream.grind.ElementaryStream;
import org.brewstream.grind.ProgramMapTable;
import org.brewstream.grind.StreamType;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.brewstream.grind.fmp4.AacCodec;
import org.brewstream.grind.fmp4.AacFragmenter;
import org.brewstream.grind.fmp4.AvcCodec;
import org.brewstream.grind.fmp4.Fragmenter;
import org.brewstream.grind.fmp4.InitSegment;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Repackages a transport stream file as fragmented MP4, so the result can be
 * opened in a browser.
 *
 * <p>The shortest way to find out whether this actually works. Every other check
 * in this module is a tool reading the output and reporting what it sees; a
 * browser is the audience, and it either plays or it does not.
 *
 * <pre>
 * java -cp ... org.brewstream.grind.fmp4.cli.TsToMp4 input.ts output.mp4
 * </pre>
 *
 * <p>Selects the first H.264 track and the first ADTS AAC track from the same
 * program. Other audio formats are reported and omitted. This file-oriented
 * diagnostic tool buffers its input and output; it is not a live relay.
 */
public final class TsToMp4 {

    private TsToMp4() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: TsToMp4 <input.ts> <output.mp4>");
            System.exit(2);
        }
        byte[] transportStream = Files.readAllBytes(Path.of(args[0]));

        Tracks tracks = findTracks(transportStream);
        int videoPid = tracks.videoPid();
        if (videoPid < 0) {
            System.err.println("no video track found: the tables name none, or none arrived");
            System.exit(1);
        }

        AccessUnitAssembler assembler = new AccessUnitAssembler(videoPid);
        AvcCodec videoCodec = new AvcCodec();
        Fragmenter fragmenter = new Fragmenter(videoCodec, 1);
        AacCodec audioCodec = new AacCodec();
        AacFragmenter audio = new AacFragmenter(audioCodec, 2);
        AccessUnitAssembler audioAssembler = new AccessUnitAssembler(tracks.audioPid());
        long audioLoss = 0;
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        int count = 0;

        for (int at = 0; at + TsPacket.LENGTH <= transportStream.length; at += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(transportStream, at);
            if (packet == null) {
                continue;
            }
            if (tracks.audioPid() >= 0) {
                AccessUnit audioUnit = audioAssembler.consume(packet);
                long discarded = audioAssembler.discardedForLoss() + audioAssembler.discardedForSize();
                if (discarded != audioLoss) {
                    audio.discontinuity();
                    audioLoss = discarded;
                }
                if (audioUnit != null) {
                    append(fragments, audio.add(audioUnit));
                }
            }
            AccessUnit unit = assembler.consume(packet);
            if (unit != null) {
                count += emit(fragmenter, fragments, unit);
            }
        }
        AccessUnit last = assembler.flush();
        if (last != null) {
            count += emit(fragmenter, fragments, last);
        }
        if (tracks.audioPid() >= 0) {
            AccessUnit lastAudio = audioAssembler.flush();
            if (audioAssembler.discardedForLoss() + audioAssembler.discardedForSize() != audioLoss) {
                audio.discontinuity();
            }
            if (lastAudio != null) {
                append(fragments, audio.add(lastAudio));
            }
            audio.finish();
            if (!audioCodec.isConfigured()) {
                throw new IllegalArgumentException("AAC track was announced but no complete frame arrived");
            }
        }
        byte[] trailing = fragmenter.flush();
        if (trailing != null) {
            fragments.writeBytes(trailing);
            count++;
        }

        byte[] initSegment = fragmenter.initSegment();
        if (initSegment == null) {
            System.err.println("no parameter sets arrived, so there is nothing to describe the "
                    + "pictures with");
            System.exit(1);
        }

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(audioCodec.isConfigured()
                ? InitSegment.forAudioVideo(videoCodec, 1, audioCodec, 2) : initSegment);
        // Track fragmenters have independent counters. A combined file uses one
        // increasing mfhd sequence across both tracks (moof begins with mfhd).
        byte[] media = fragments.toByteArray();
        java.nio.ByteBuffer boxes = java.nio.ByteBuffer.wrap(media);
        int sequence = 0;
        for (int at = 0; at < media.length;) {
            int moofSize = boxes.getInt(at);
            boxes.putInt(at + 20, ++sequence);
            at += moofSize;
            at += boxes.getInt(at); // mdat
        }
        file.writeBytes(media);
        Files.write(Path.of(args[1]), file.toByteArray());

        System.out.printf("%s: pid 0x%04X, %d video fragments, %d bytes%n",
                args[1], videoPid, count, file.size());
        if (audioCodec.isConfigured()) {
            System.out.printf("  AAC pid 0x%04X: %d Hz, %d channels, %d fragments%n",
                    tracks.audioPid(), audioCodec.sampleRate(), audioCodec.channels(), audio.fragmentCount());
            System.out.printf("  %d audio PES payloads discarded for loss or size%n",
                    audioAssembler.discardedForLoss() + audioAssembler.discardedForSize());
        }
        if (fragmenter.droppedBeforeConfiguration() > 0) {
            System.out.printf("  %d pictures dropped before the first parameter sets%n",
                    fragmenter.droppedBeforeConfiguration());
        }
        if (assembler.discardedForLoss() > 0) {
            System.out.printf("  %d pictures discarded for missing packets%n",
                    assembler.discardedForLoss());
        }
    }

    private static int emit(Fragmenter fragmenter, ByteArrayOutputStream out, AccessUnit unit) {
        byte[] fragment = fragmenter.add(unit);
        if (fragment == null) {
            return 0;
        }
        out.writeBytes(fragment);
        return 1;
    }

    private static void append(ByteArrayOutputStream out, byte[] fragment) {
        if (fragment != null) {
            out.writeBytes(fragment);
        }
    }

    private record Tracks(int videoPid, int audioPid) {
    }

    /** Select audio only from the program containing the selected H.264 track. */
    private static Tracks findTracks(byte[] transportStream) {
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int at = 0; at + TsPacket.LENGTH <= transportStream.length; at += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(transportStream, at);
            if (packet != null) {
                analyzer.consume(packet);
            }
        }
        for (ProgramMapTable program : analyzer.stats().programs().programs().values()) {
            int video = -1;
            int audio = -1;
            boolean otherAudio = false;
            for (ElementaryStream stream : program.streams()) {
                if (video < 0 && stream.streamType() == StreamType.H264) {
                    video = stream.pid();
                }
                if (audio < 0 && stream.streamType() == StreamType.ADTS_AAC) {
                    audio = stream.pid();
                }
                otherAudio |= stream.streamType().kind() == StreamType.Kind.AUDIO;
            }
            if (video >= 0) {
                if (audio < 0 && otherAudio) {
                    System.err.println("selected program has no supported ADTS AAC track; audio omitted");
                }
                return new Tracks(video, audio);
            }
        }
        return new Tracks(-1, -1);
    }
}
