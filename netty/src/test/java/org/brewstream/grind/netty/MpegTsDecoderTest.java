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

package org.brewstream.grind.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pipeline path, driven by the same real stream the core tests use.
 *
 * <p>The cases that matter here are the ones a file reader never hits: packets
 * split across reads, a stream that does not begin on a boundary, and sync lost
 * mid-stream. An SRT payload is 1316 bytes — seven packets exactly — so the
 * happy path hides every boundary bug, and only deliberately awkward chunking
 * exposes them.
 */
class MpegTsDecoderTest {

    private static byte[] sample() throws IOException {
        try (InputStream in = MpegTsDecoderTest.class.getResourceAsStream("/sample.ts")) {
            assertThat(in).as("sample.ts must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private static List<TsPacket> drain(EmbeddedChannel channel) {
        List<TsPacket> packets = new ArrayList<>();
        Object message;
        while ((message = channel.readInbound()) != null) {
            packets.add((TsPacket) message);
        }
        return packets;
    }

    @Test
    void decodesEveryPacketWhenFedTheWholeStreamAtOnce() throws IOException {
        byte[] data = sample();
        EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder());

        channel.writeInbound(Unpooled.wrappedBuffer(data));

        assertThat(drain(channel)).hasSize(data.length / TsPacket.LENGTH);
    }

    /** 1316 bytes is SRT's payload size: seven packets, always aligned. */
    @Test
    void decodesAcrossSrtSizedChunks() throws IOException {
        byte[] data = sample();
        EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder());

        for (int offset = 0; offset < data.length; offset += 1316) {
            int length = Math.min(1316, data.length - offset);
            channel.writeInbound(Unpooled.wrappedBuffer(data, offset, length));
        }

        assertThat(drain(channel)).hasSize(data.length / TsPacket.LENGTH);
    }

    /**
     * Deliberately awkward chunk sizes, so packets straddle reads. A decoder that
     * assumed reads land on packet boundaries passes every other test and fails
     * this one.
     */
    @Test
    void decodesWhenPacketsStraddleReadBoundaries() throws IOException {
        byte[] data = sample();
        for (int chunk : new int[]{1, 7, 100, 187, 189, 1000}) {
            EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder());
            for (int offset = 0; offset < data.length; offset += chunk) {
                int length = Math.min(chunk, data.length - offset);
                channel.writeInbound(Unpooled.wrappedBuffer(data, offset, length));
            }

            assertThat(drain(channel))
                    .as("chunk size %d", chunk)
                    .hasSize(data.length / TsPacket.LENGTH);
        }
    }

    /** Joining a stream mid-packet: the leading fragment is discarded and counted. */
    @Test
    void resynchronisesWhenTheStreamDoesNotStartOnAPacketBoundary() throws IOException {
        byte[] data = sample();
        TsAnalyzer analyzer = new TsAnalyzer();
        EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder(analyzer));

        int skew = 77;
        channel.writeInbound(Unpooled.wrappedBuffer(data, skew, data.length - skew));

        List<TsPacket> packets = drain(channel);
        assertThat(packets).as("all but the straddled first packet").hasSize(data.length / TsPacket.LENGTH - 1);
        assertThat(analyzer.stats().syncLosses()).as("the discard is reported, not silent").isEqualTo(1);
    }

    /**
     * A lone 0x47 inside compressed video must not be mistaken for a packet
     * start. 0x47 is an ordinary byte value and appears constantly in payload;
     * requiring it to recur at the 188-byte stride is what separates alignment
     * from coincidence.
     */
    @Test
    void doesNotResynchroniseOntoAStraySyncByteInPayload() throws IOException {
        byte[] data = sample();
        byte[] garbage = new byte[400];
        // Sprinkle sync bytes through the garbage at implausible strides.
        for (int i = 0; i < garbage.length; i += 37) {
            garbage[i] = TsPacket.SYNC_BYTE;
        }

        TsAnalyzer analyzer = new TsAnalyzer();
        EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder(analyzer));
        ByteBuf prefixed = Unpooled.buffer();
        prefixed.writeBytes(garbage);
        prefixed.writeBytes(data);
        channel.writeInbound(prefixed);

        List<TsPacket> packets = drain(channel);
        assertThat(packets).as("real packets found past the noise")
                .hasSize(data.length / TsPacket.LENGTH);
        for (TsPacket packet : packets) {
            assertThat(packet.pid()).as("a false lock produces nonsense PIDs").isLessThanOrEqualTo(0x1FFF);
        }
    }

    /**
     * The point of the Netty module: a downstream handler must be able to read
     * the payload bytes. It could not — the decoder parsed from a local array
     * the packet did not retain, so every TsPacket reaching a handler carried
     * indices into a discarded buffer. Nothing caught it because the analyzer
     * reads payloadLength and never the bytes.
     */
    @Test
    void aDownstreamHandlerCanReadThePayloadBytes() throws IOException {
        byte[] data = sample();
        EmbeddedChannel channel = new EmbeddedChannel(new MpegTsDecoder());

        channel.writeInbound(Unpooled.wrappedBuffer(data));

        int checked = 0;
        long payloadBytes = 0;
        int packetIndex = 0;
        for (TsPacket packet : drain(channel)) {
            if (packet.hasPayload()) {
                byte[] payload = packet.payload();
                assertThat(payload).hasSize(packet.payloadLength());
                // Compare against the same bytes in the original stream: the
                // packet's own offset is relative to its private copy, so locate
                // it by packet index instead.
                int sourceStart = packetIndex * TsPacket.LENGTH
                        + (TsPacket.LENGTH - packet.payloadLength());
                for (int i = 0; i < payload.length; i++) {
                    assertThat(payload[i])
                            .as("packet %d payload byte %d", packetIndex, i)
                            .isEqualTo(data[sourceStart + i]);
                }
                payloadBytes += payload.length;
                checked++;
            }
            packetIndex++;
        }

        assertThat(checked).as("payload-carrying packets examined").isGreaterThan(500);
        assertThat(payloadBytes).as("real bytes, not zeros").isGreaterThan(80_000);
    }

    /** The health handler must observe without altering what flows downstream. */
    @Test
    void theHealthHandlerCountsPacketsAndPassesThemThrough() throws IOException {
        byte[] data = sample();
        TsAnalyzer analyzer = new TsAnalyzer();
        EmbeddedChannel channel = new EmbeddedChannel(
                new MpegTsDecoder(analyzer), new TsHealthHandler(analyzer));

        channel.writeInbound(Unpooled.wrappedBuffer(data));

        int expected = data.length / TsPacket.LENGTH;
        assertThat(drain(channel)).as("pass-through, not a fork").hasSize(expected);
        assertThat(analyzer.stats().packets()).isEqualTo(expected);
        assertThat(analyzer.stats().isHealthy()).isTrue();
        assertThat(analyzer.stats().pids()).hasSize(5);
    }
}