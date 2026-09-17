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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ETSI TR 101 290's {@code PTS_error}: a PTS at least every 700ms.
 *
 * <p>The counterpart to {@link PcrRepetitionTest}, and the contrast is the
 * point. Every fixture here breaches the PCR limit on every interval, and none
 * of them breaches this one: video carries a PTS about every 39ms and audio
 * every 320ms. So a breach of this check means something, where a breach of that
 * one mostly means ffmpeg.
 *
 * <p>Measured against the program's clock rather than by subtracting timestamps.
 * TR 101 290 asks how often a PTS <em>appears</em> — a question about arrival —
 * and PTS values run backwards with B-frames, so their difference answers
 * something else.
 */
class PtsRepetitionTest {

    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private long pcr;

    /**
     * Declares the tracks, using the fixture's own tables.
     *
     * <p>Necessary rather than decorative: {@code TsAnalyzer} only reads PES
     * headers on PIDs a PMT named, because a PES start code is three bytes and
     * occurs by chance inside compressed video. Without this the hand-built
     * packets below are parsed as nothing at all.
     */
    @BeforeEach
    void declareTheTracks() throws IOException {
        byte[] data = read("/sample.ts");
        consumeWhere(data, pid -> pid == TsPacket.PAT_PID);
        Map<Integer, Integer> pmtPids = analyzer.stats().programs().pmtPids();
        consumeWhere(data, pmtPids::containsValue);

        assertThat(analyzer.stats().programs().allStreams())
                .as("the fixture must declare the PIDs these tests drive")
                .extracting(ElementaryStream::pid)
                .contains(VIDEO_PID, AUDIO_PID);
    }

    private void consumeWhere(byte[] data, java.util.function.IntPredicate wanted) {
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet != null && wanted.test(packet.pid())) {
                analyzer.consume(packet);
            }
        }
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = PtsRepetitionTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Real streams, and none of them breaches. */
    @Test
    void realFixturesCarryTimestampsOftenEnough() throws IOException {
        for (String fixture : new String[]{"/sample.ts", "/bframes.ts", "/multiprogram.ts"}) {
            TsStreamStats stats = analyze(fixture).stats();
            assertThat(stats.ptsErrors())
                    .as("%s carries a PTS far more often than every 700ms", fixture)
                    .isZero();
        }
    }

    /**
     * The widest real gap is the audio track's, and it is comfortably inside the
     * limit — pinned so that a change making this check fire on ordinary streams
     * is caught here rather than on someone's dashboard.
     */
    @Test
    void theWidestRealGapIsWellInsideTheLimit() throws IOException {
        PidStats audio = analyze("/sample.ts").stats().pid(AUDIO_PID);

        assertThat(audio.maxPtsIntervalMillis())
                .as("ffmpeg packs several AAC frames per PES, so audio is the sparser track")
                .isBetween(200.0, 500.0);
        assertThat(audio.ptsErrors()).isZero();
    }

    /** A track that goes quiet for longer than the limit is reported. */
    @Test
    void aTrackThatGoesQuietTooLongIsReported() {
        startClock();
        pesWithPts(VIDEO_PID);

        advanceClock(800);
        pesWithPts(VIDEO_PID);

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.ptsErrors()).isEqualTo(1);
        assertThat(video.maxPtsIntervalMillis()).isCloseTo(800.0, within(40.0));
        assertThat(analyzer.stats().ptsErrors()).isEqualTo(1);
    }

    /** Just inside the limit is not a breach. */
    @Test
    void aGapInsideTheLimitIsNotABreach() {
        startClock();
        pesWithPts(VIDEO_PID);

        advanceClock(600);
        pesWithPts(VIDEO_PID);

        assertThat(analyzer.stats().pid(VIDEO_PID).ptsErrors()).isZero();
        assertThat(analyzer.stats().pid(VIDEO_PID).maxPtsIntervalMillis())
                .isCloseTo(600.0, within(40.0));
    }

    /**
     * The widest gap is kept, not the most recent one.
     *
     * <p>Needs three timestamps and two intervals of different widths: with one
     * interval the two readings agree and the distinction is invisible.
     */
    @Test
    void theWidestIntervalIsRemembered() {
        startClock();
        pesWithPts(VIDEO_PID);

        advanceClock(500);
        pesWithPts(VIDEO_PID);

        advanceClock(100);
        pesWithPts(VIDEO_PID);

        assertThat(analyzer.stats().pid(VIDEO_PID).maxPtsIntervalMillis())
                .as("the 500ms gap, not the 100ms one that followed it")
                .isCloseTo(500.0, within(40.0));
    }

    /** Each track is measured on its own: one going quiet does not implicate another. */
    @Test
    void tracksAreMeasuredSeparately() {
        startClock();
        pesWithPts(VIDEO_PID);
        pesWithPts(AUDIO_PID);

        // Video keeps talking throughout; audio does not.
        for (int i = 0; i < 9; i++) {
            advanceClock(100);
            pesWithPts(VIDEO_PID);
        }
        pesWithPts(AUDIO_PID);

        assertThat(analyzer.stats().pid(VIDEO_PID).ptsErrors())
                .as("video never went quiet for 700ms")
                .isZero();
        assertThat(analyzer.stats().pid(AUDIO_PID).ptsErrors())
                .as("audio was silent for 900ms")
                .isEqualTo(1);
    }

    /**
     * The first PTS on a track starts the measurement rather than being compared
     * against a stream that had not begun.
     */
    @Test
    void theFirstTimestampIsNotABreachHoweverLateItArrives() {
        startClock();
        advanceClock(5000);

        pesWithPts(VIDEO_PID);

        assertThat(analyzer.stats().pid(VIDEO_PID).ptsErrors())
                .as("five seconds passed before the track began, which is not a gap in it")
                .isZero();
    }

    /**
     * Conformance, not damage — the rule this check inherits from PCR repetition.
     *
     * <p>A track whose timestamps are sparse has lost nothing. If this ever
     * fails, PTS_error has been folded into the health figures and a stream that
     * merely schedules awkwardly now reads as broken.
     */
    @Test
    void aBreachDoesNotMakeTheStreamUnhealthy() {
        startClock();
        pesWithPts(VIDEO_PID);
        advanceClock(800);
        pesWithPts(VIDEO_PID);

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.ptsErrors()).isPositive();
        assertThat(stats.isHealthy()).as("nothing was lost").isTrue();
        assertThat(stats.erroredSeconds()).as("and no second was errored").isZero();
    }

    // --- helpers

    private void startClock() {
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
    }

    /** Moves the clock on in PCR steps small enough not to read as a discontinuity. */
    private void advanceClock(long millis) {
        long target = pcr + millis * (AdaptationField.PCR_RATE_HZ / 1000);
        long step = 40L * (AdaptationField.PCR_RATE_HZ / 1000);
        while (pcr < target) {
            pcr = Math.min(target, pcr + step);
            analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        }
    }

    /**
     * A PES packet carrying a PTS on a track the tables never named.
     *
     * <p>Only meaningful because {@link #declareTheTracks()} has already told the
     * analyzer that this PID carries an elementary stream.
     */
    private void pesWithPts(int pid) {
        analyzer.consume(pesStart(pid, next(pid), pcr * 90_000 / AdaptationField.PCR_RATE_HZ));
    }

    private int next(int pid) {
        int counter = (counters.getOrDefault(pid, -1) + 1) & 0x0F;
        counters.put(pid, counter);
        return counter;
    }

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data;
        try (InputStream in = PtsRepetitionTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet != null) {
                analyzer.consume(packet);
            }
        }
        return analyzer;
    }

    private static TsPacket withPcr(int pid, int counter, long pcr) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, counter,
                new AdaptationField(false, false, false, pcr), 12, TsPacket.LENGTH - 12);
    }

    /** A packet whose payload begins a PES packet carrying the given PTS. */
    private static TsPacket pesStart(int pid, int counter, long pts) {
        byte[] data = new byte[TsPacket.LENGTH];
        int at = 4;
        data[at++] = 0x00;
        data[at++] = 0x00;
        data[at++] = 0x01;
        data[at++] = (byte) 0xE0;           // stream id: video
        data[at++] = 0x00;                  // PES packet length: unbounded
        data[at++] = 0x00;
        data[at++] = (byte) 0x80;           // '10' marker, no scrambling
        data[at++] = (byte) 0x80;           // PTS present, no DTS
        data[at++] = 0x05;                  // PES header data length
        writePts(data, at, pts);
        return rawPesPacket(data, pid, counter);
    }

    /** Writes a 33-bit timestamp in the five-byte form PES headers use. */
    private static void writePts(byte[] data, int at, long pts) {
        data[at] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        data[at + 1] = (byte) ((pts >> 22) & 0xFF);
        data[at + 2] = (byte) (0x01 | ((pts >> 14) & 0xFE));
        data[at + 3] = (byte) ((pts >> 7) & 0xFF);
        data[at + 4] = (byte) (0x01 | ((pts << 1) & 0xFE));
    }

    private static TsPacket rawPesPacket(byte[] payload, int pid, int counter) {
        return new TsPacket(payload, false, true, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }
}
