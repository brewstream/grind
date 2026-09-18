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
import org.brewstream.grind.StreamType;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.brewstream.grind.fmp4.AvcCodec;
import org.brewstream.grind.fmp4.Fragmenter;

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
 * <p>Video only. Audio is a separate track and is not built yet, so a stream
 * carrying both comes out silent rather than broken.
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

        int videoPid = findVideoPid(transportStream);
        if (videoPid < 0) {
            System.err.println("no video track found: the tables name none, or none arrived");
            System.exit(1);
        }

        AccessUnitAssembler assembler = new AccessUnitAssembler(videoPid);
        Fragmenter fragmenter = new Fragmenter(new AvcCodec(), 1);
        ByteArrayOutputStream fragments = new ByteArrayOutputStream();
        int count = 0;

        for (int at = 0; at + TsPacket.LENGTH <= transportStream.length; at += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(transportStream, at);
            if (packet == null) {
                continue;
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
        file.writeBytes(initSegment);
        file.writeBytes(fragments.toByteArray());
        Files.write(Path.of(args[1]), file.toByteArray());

        System.out.printf("%s: pid 0x%04X, %d fragments, %d bytes%n",
                args[1], videoPid, count, file.size());
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

    /** The first H.264 track the tables name. */
    private static int findVideoPid(byte[] transportStream) {
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int at = 0; at + TsPacket.LENGTH <= transportStream.length; at += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(transportStream, at);
            if (packet != null) {
                analyzer.consume(packet);
            }
        }
        for (ElementaryStream stream : analyzer.stats().programs().allStreams()) {
            if (stream.streamType() == StreamType.H264) {
                return stream.pid();
            }
        }
        return -1;
    }
}
