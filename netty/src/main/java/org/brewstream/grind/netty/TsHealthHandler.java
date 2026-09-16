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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;

/**
 * Feeds every {@link TsPacket} passing through the pipeline to a
 * {@link TsAnalyzer}, then forwards it unchanged.
 *
 * <pre>{@code
 * TsAnalyzer analyzer = new TsAnalyzer();
 * analyzer.addListener(dashboard);
 *
 * srtConnection.pipeline().addLast(
 *         new MpegTsDecoder(analyzer),
 *         new TsHealthHandler(analyzer),
 *         yourHandler);
 * }</pre>
 *
 * <p>Pass-through by design: monitoring must not be a fork in the pipeline that
 * a consumer has to remember to rejoin. Place it directly after the decoder and
 * everything downstream sees exactly what it would have anyway.
 *
 * <p>The analyzer is called on the event loop, so its listeners are too — see
 * {@code TsStreamListener} on why they must not block.
 */
public final class TsHealthHandler extends ChannelInboundHandlerAdapter {

    private final TsAnalyzer analyzer;

    public TsHealthHandler(TsAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof TsPacket packet) {
            analyzer.consume(packet);
        }
        ctx.fireChannelRead(message);
    }
}