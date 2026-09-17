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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;

import java.util.List;

/**
 * Turns a byte stream into {@link TsPacket}s, for a Netty pipeline.
 *
 * <pre>{@code
 * srtConnection.pipeline().addLast(new MpegTsDecoder(), new TsHealthHandler(analyzer));
 * }</pre>
 *
 * <p><b>Why this is not trivial.</b> Transport packets are 188 bytes, but nothing
 * makes a network read land on a packet boundary: an SRT payload is typically
 * 1316 bytes — seven packets exactly — while a file read or a resized buffer is
 * any size at all. {@link ByteToMessageDecoder} handles the accumulation, and
 * this decoder handles the other half: regaining alignment when the stream does
 * not start on a sync byte, or loses it mid-stream.
 *
 * <p><b>Resynchronisation.</b> On finding a byte that is not {@code 0x47} where a
 * packet should begin, the decoder scans forward for a candidate and requires the
 * sync byte to recur at the expected 188-byte stride before accepting alignment.
 * A single {@code 0x47} inside compressed video would otherwise look like a
 * packet start constantly — it is an ordinary byte value, and a naive scan
 * resynchronises onto noise. Discarded bytes are reported, so a stream that is
 * quietly garbage does not look like a stream that is quietly idle.
 *
 * <p>Attach an {@link TsAnalyzer} to have sync losses counted alongside the rest
 * of the stream's health; without one the decoder still works, it just reports
 * nothing about what it discarded.
 */
public final class MpegTsDecoder extends ByteToMessageDecoder {

    /** How many consecutive packets must line up before alignment is trusted. */
    private static final int SYNC_CONFIRMATIONS = 3;

    private final TsAnalyzer analyzer;
    private boolean synchronised;

    /** A decoder that discards silently. */
    public MpegTsDecoder() {
        this(null);
    }

    /**
     * @param analyzer told about discarded bytes via {@link TsAnalyzer#recordSyncLoss},
     *                 so lost alignment shows up in the stream's health rather than
     *                 vanishing. May be {@code null}.
     */
    public MpegTsDecoder(TsAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            if (!synchronised && !resynchronise(in)) {
                return; // not enough bytes to confirm alignment yet
            }
            if (in.readableBytes() < TsPacket.LENGTH) {
                return;
            }
            if (in.getByte(in.readerIndex()) != TsPacket.SYNC_BYTE) {
                // Alignment was right and has just been lost.
                synchronised = false;
                continue;
            }

            byte[] packet = new byte[TsPacket.LENGTH];
            in.readBytes(packet);
            TsPacket parsed = TsPacket.parse(packet, 0);
            if (parsed != null) {
                out.add(parsed);
            }
        }
    }

    /**
     * Scans for a sync byte that repeats at the packet stride, consuming the
     * bytes before it.
     *
     * @return true once alignment is confirmed and the reader index sits on a packet start
     */
    private boolean resynchronise(ByteBuf in) {
        int needed = TsPacket.LENGTH * SYNC_CONFIRMATIONS;
        int limit = in.readableBytes() - needed;
        for (int candidate = 0; candidate <= limit; candidate++) {
            if (!looksAligned(in, candidate)) {
                continue;
            }
            if (candidate > 0) {
                in.skipBytes(candidate);
                if (analyzer != null) {
                    analyzer.recordSyncLoss(candidate);
                }
            }
            synchronised = true;
            return true;
        }

        // No confirmed alignment in what we hold. Keep the last window's worth -
        // a real packet start may be in it, waiting on bytes that have not
        // arrived - and discard the rest rather than buffering a broken stream
        // without limit.
        int discardable = in.readableBytes() - needed;
        if (discardable > 0) {
            in.skipBytes(discardable);
            if (analyzer != null) {
                analyzer.recordSyncLoss(discardable);
            }
        }
        return false;
    }

    private static boolean looksAligned(ByteBuf in, int offset) {
        int base = in.readerIndex() + offset;
        for (int i = 0; i < SYNC_CONFIRMATIONS; i++) {
            if (in.getByte(base + i * TsPacket.LENGTH) != TsPacket.SYNC_BYTE) {
                return false;
            }
        }
        return true;
    }
}