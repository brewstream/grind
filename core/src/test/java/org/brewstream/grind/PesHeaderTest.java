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

package org.brewstream.grind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PES header parsing, checked against what ffprobe independently reports about
 * the same file.
 *
 * <p>{@code ffprobe -show_packets} says of {@code sample.ts}:
 * <pre>
 *   stream 0 (PID 0x100, h264): first pts 128090, stream id 224, 50 packets
 *   stream 1 (PID 0x101, aac):  first pts 126000, stream id 192, 88 packets
 * </pre>
 *
 * <p>Two of those numbers need care, and both caught a wrong expectation here
 * before they caught anything else. ffprobe reports {@code dts} equal to
 * {@code pts} for this stream, but that is a <em>derived</em> value: when no DTS
 * field is present the decode time is the presentation time by definition, and
 * x264 at these settings emits no B-frames, so there is no DTS in the header to
 * read. And ffprobe counts <em>access units</em>, not PES packets — the same
 * thing for video here, where each frame gets its own PES packet, but not for
 * audio, where this file packs all 88 AAC frames into 6 PES packets. Counting
 * PUSI packets directly confirms 50 and 6.
 * Timestamps are the part of this header most easily got wrong — 33 bits split
 * three ways with marker bits between — and a wrong shift produces a number that
 * still looks like a plausible timestamp. Comparing against a tool that decodes
 * the same file is the only way to know.
 */
class PesHeaderTest {

    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    private static TsAnalyzer analyzeSample() throws IOException {
        byte[] data;
        try (InputStream in = PesHeaderTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    /** The first PES header on each track, read straight out of the stream. */
    private static PesHeader firstHeaderOn(int pid) throws IOException {
        byte[] data;
        try (InputStream in = PesHeaderTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == pid && packet.payloadUnitStart() && packet.hasPayload()) {
                byte[] payload = packet.payload();
                PesHeader header = PesHeader.parse(payload, 0, payload.length);
                if (header != null) {
                    return header;
                }
            }
        }
        return null;
    }

    @Test
    void theFirstVideoHeaderMatchesFfprobe() throws IOException {
        PesHeader header = firstHeaderOn(VIDEO_PID);

        assertThat(header).isNotNull();
        assertThat(header.streamId()).as("ffprobe: stream id 224").isEqualTo(224);
        assertThat(header.isVideo()).isTrue();
        assertThat(header.pts()).as("ffprobe: pts 128090").isEqualTo(128_090L);
        assertThat(header.ptsSeconds()).isCloseTo(128_090.0 / 90_000, within(1e-9));
        // No B-frames at these encoder settings, so no DTS field is carried and
        // decode time equals presentation time. ffprobe prints that equality as
        // a dts value, which is not evidence the field exists.
        assertThat(header.hasDts()).isFalse();
    }

    @Test
    void theFirstAudioHeaderMatchesFfprobe() throws IOException {
        PesHeader header = firstHeaderOn(AUDIO_PID);

        assertThat(header).isNotNull();
        assertThat(header.streamId()).as("ffprobe: stream id 192").isEqualTo(192);
        assertThat(header.isAudio()).isTrue();
        assertThat(header.pts()).as("ffprobe: pts 126000").isEqualTo(126_000L);
    }

    /**
     * PES packets, which is not the same as frames.
     *
     * <p>Video carries one frame per PES packet here, so 50 matches ffprobe's
     * frame count. Audio does not: this file packs 88 AAC frames into 6 PES
     * packets, and counting PUSI packets on each PID directly confirms both
     * numbers. A dashboard labelling PES counts as "frames" would be right for
     * video and wrong by a factor of fifteen for audio.
     */
    @Test
    void pesPacketsAreCountedPerPidAndAreNotFrames() throws IOException {
        TsStreamStats stats = analyzeSample().stats();

        assertThat(stats.pid(VIDEO_PID).pesPackets()).as("50 PUSI packets on the video PID").isEqualTo(50);
        assertThat(stats.pid(AUDIO_PID).pesPackets()).as("6 PUSI packets on the audio PID").isEqualTo(6);
        assertThat(stats.pesPackets()).isEqualTo(56);
    }

    /** For video, where one PES packet is one frame, the count gives the frame rate. */
    @Test
    void theVideoPesCountImpliesTheRightFrameRate() throws IOException {
        TsStreamStats stats = analyzeSample().stats();
        PidStats video = stats.pid(VIDEO_PID);

        assertThat(video.carriesPes()).isTrue();
        assertThat(video.streamId()).isEqualTo(224);
        assertThat(video.pesPackets() / 2.0).as("25 frames per second, as generated").isEqualTo(25.0);
    }

    @Test
    void timingIsTrackedPerPidAndReachesTheStats() throws IOException {
        TsStreamStats stats = analyzeSample().stats();

        assertThat(stats.pid(VIDEO_PID).lastPts()).isGreaterThan(128_090L);
        assertThat(stats.pid(VIDEO_PID).lastPtsSeconds()).isGreaterThan(1.4);
        assertThat(stats.pid(TsPacket.PAT_PID).carriesPes())
                .as("a table PID carries no PES and must not be scanned for one").isFalse();
    }

    /**
     * A PID no PMT named must not be scanned for PES headers, even when its bytes
     * happen to look like one.
     *
     * <p>{@code 00 00 01} is three bytes and occurs by chance constantly — inside
     * compressed video it is the start code prefix of the video's own syntax, and
     * on a data PID it could be anything. Scanning every PID would invent
     * timestamps out of picture data and attribute them to a track that does not
     * exist. The fixture cannot show this, because none of its table PIDs happen
     * to begin that way, so it is built by hand.
     */
    @Test
    void aPidOutsideTheProgramMapIsNotScannedForPesHeaders() throws IOException {
        byte[] data;
        try (InputStream in = PesHeaderTest.class.getResourceAsStream("/sample.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        // The tables are known by now, and 0x1FFE is in none of them.
        int strangerPid = 0x1FFE;
        assertThat(analyzer.programs().describe(strangerPid)).isNull();

        byte[] packet = new byte[TsPacket.LENGTH];
        java.util.Arrays.fill(packet, (byte) 0xFF);
        packet[0] = TsPacket.SYNC_BYTE;
        packet[1] = (byte) (0x40 | ((strangerPid >> 8) & 0x1F)); // payload unit start
        packet[2] = (byte) strangerPid;
        packet[3] = 0x10; // payload only
        // A perfectly well-formed video PES header, on a PID that carries no video.
        byte[] header = pesHeader(0xE0, 0b10, 777_777L, -1);
        System.arraycopy(header, 0, packet, 4, header.length);

        analyzer.consume(TsPacket.parse(packet, 0));

        PidStats stranger = analyzer.stats().pid(strangerPid);
        assertThat(stranger.pesPackets()).as("not a track, so not a PES packet").isZero();
        assertThat(stranger.lastPts()).as("no timestamp invented from unrelated bytes").isEqualTo(-1);
        assertThat(stranger.carriesPes()).isFalse();
    }

    // --- hand-built headers, for shapes the fixture does not contain

    /** '11' means a PTS followed by a DTS, which is how reordered frames are expressed. */
    @Test
    void aHeaderWithBothTimestampsReadsThemSeparately() {
        byte[] data = pesHeader(0xE0, 0b11, 900_000L, 450_000L);

        PesHeader header = PesHeader.parse(data, 0, data.length);

        assertThat(header).isNotNull();
        assertThat(header.pts()).isEqualTo(900_000L);
        assertThat(header.dts()).isEqualTo(450_000L);
        assertThat(header.hasDts()).isTrue();
    }

    @Test
    void aHeaderWithOnlyAPresentationTimestampHasNoDecodeTimestamp() {
        byte[] data = pesHeader(0xC0, 0b10, 12_345L, -1);

        PesHeader header = PesHeader.parse(data, 0, data.length);

        assertThat(header).isNotNull();
        assertThat(header.pts()).isEqualTo(12_345L);
        assertThat(header.hasDts()).isFalse();
        assertThat(header.dts()).isEqualTo(-1);
    }

    /** The full 33-bit range must survive: a 32-bit read would silently truncate. */
    @Test
    void aTimestampNearTheTopOfItsRangeIsNotTruncated() {
        long large = (1L << 33) - 1;
        byte[] data = pesHeader(0xE0, 0b10, large, -1);

        assertThat(PesHeader.parse(data, 0, data.length).pts()).isEqualTo(large);
    }

    @Test
    void paddingStreamsHaveNoOptionalHeaderAtAll() {
        byte[] data = new byte[]{0x00, 0x00, 0x01, (byte) 0xBE, 0x00, 0x10, 0x00, 0x00};

        PesHeader header = PesHeader.parse(data, 0, data.length);

        assertThat(header).isNotNull();
        assertThat(header.streamId()).isEqualTo(0xBE);
        assertThat(header.hasPts()).isFalse();
        assertThat(header.headerLength()).as("payload starts straight after the length").isEqualTo(6);
    }

    @Test
    void bytesWithoutAStartCodeAreNotAPesHeader() {
        byte[] data = new byte[]{0x47, 0x00, 0x11, 0x10, 0x00, 0x00, 0x00, 0x00};

        assertThat(PesHeader.parse(data, 0, data.length)).isNull();
    }

    @Test
    void aHeaderTruncatedBeforeItsTimestampIsRejected() {
        byte[] full = pesHeader(0xE0, 0b10, 5_000L, -1);

        assertThat(PesHeader.parse(full, 0, 10)).matches(
                header -> header == null || !header.hasPts(),
                "a timestamp that has not arrived must not be invented");
    }

    /** Builds a PES header with the given timestamps, marker bits and all. */
    private static byte[] pesHeader(int streamId, int ptsDtsFlags, long pts, long dts) {
        int timestampBytes = ptsDtsFlags == 0b11 ? 10 : ptsDtsFlags == 0b10 ? 5 : 0;
        byte[] out = new byte[9 + timestampBytes];
        out[0] = 0x00;
        out[1] = 0x00;
        out[2] = 0x01;
        out[3] = (byte) streamId;
        out[4] = 0x00;
        out[5] = (byte) (3 + timestampBytes);
        out[6] = (byte) 0x80; // '10' marker
        out[7] = (byte) (ptsDtsFlags << 6);
        out[8] = (byte) timestampBytes;
        if (timestampBytes >= 5) {
            writeTimestamp(out, 9, pts, ptsDtsFlags == 0b11 ? 0b0011 : 0b0010);
        }
        if (timestampBytes == 10) {
            writeTimestamp(out, 14, dts, 0b0001);
        }
        return out;
    }

    private static void writeTimestamp(byte[] out, int offset, long value, int prefix) {
        out[offset] = (byte) ((prefix << 4) | (((value >> 30) & 0x07) << 1) | 1);
        out[offset + 1] = (byte) ((value >> 22) & 0xFF);
        out[offset + 2] = (byte) ((((value >> 15) & 0x7F) << 1) | 1);
        out[offset + 3] = (byte) ((value >> 7) & 0xFF);
        out[offset + 4] = (byte) (((value & 0x7F) << 1) | 1);
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}