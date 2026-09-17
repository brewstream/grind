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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How far ahead of the clock a track's timestamps run.
 *
 * <p>A PTS says when a frame should be presented and the PCR says what time it
 * is now, so the gap is how long a decoder holds the frame before showing it —
 * the slack the stream is leaving. It is the one measure here that says whether
 * a stream will <em>play well</em> rather than whether it conforms.
 *
 * <p><b>The measurements that shaped this are worth stating.</b> Across every
 * fixture, video runs at about 720ms of skew and audio at about 410ms. That
 * 300ms gap is normal — the two are buffered and interleaved differently — so
 * comparing one track against another is not a lip-sync check and would condemn
 * every working stream. A track is compared against itself over time, and the
 * number that matters is the minimum.
 */
class PtsSkewTest {

    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private long pcr;

    /** Declares the tracks from the fixture's own tables, as PES parsing requires. */
    @BeforeEach
    void declareTheTracks() throws IOException {
        byte[] data = read("/sample.ts");
        consumeWhere(data, pid -> pid == TsPacket.PAT_PID);
        Map<Integer, Integer> pmtPids = analyzer.stats().programs().pmtPids();
        consumeWhere(data, pmtPids::containsValue);
        assertThat(analyzer.stats().programs().allStreams())
                .extracting(ElementaryStream::pid)
                .contains(VIDEO_PID, AUDIO_PID);
    }

    /** Real streams run comfortably ahead of their clock, and never behind it. */
    @Test
    void realFixturesRunAheadOfTheirClock() throws IOException {
        for (String fixture : new String[]{"/sample.ts", "/bframes.ts", "/multiprogram.ts"}) {
            TsAnalyzer real = analyze(fixture);
            assertThat(real.stats().lateTimestamps())
                    .as("%s hands nothing to the decoder late", fixture)
                    .isZero();
            assertThat(real.stats().pid(VIDEO_PID).minPtsSkewMillis())
                    .as("%s video keeps real slack", fixture)
                    .isGreaterThan(100.0);
        }
    }

    /**
     * Video and audio sit at different skews, and that is not a fault.
     *
     * <p>Pinned deliberately: it is the measurement that says a cross-track
     * comparison must never be presented as a sync check. If this ever starts
     * failing, either the fixtures changed or the metric has drifted into
     * measuring something else.
     */
    @Test
    void videoAndAudioSitAtDifferentSkewsAndBothAreHealthy() throws IOException {
        TsStreamStats stats = analyze("/sample.ts").stats();

        double video = stats.pid(VIDEO_PID).minPtsSkewMillis();
        double audio = stats.pid(AUDIO_PID).minPtsSkewMillis();

        assertThat(video).as("video buffers further ahead").isGreaterThan(audio);
        assertThat(video - audio)
                .as("a few hundred milliseconds apart, routinely")
                .isGreaterThan(100.0);
        assertThat(stats.lateTimestamps())
                .as("and neither is late, which is what actually matters")
                .isZero();
    }

    /** The current skew is what the clock said at the moment the timestamp arrived. */
    @Test
    void skewIsMeasuredAgainstTheClockAtArrival() {
        startClock();
        // A timestamp half a second ahead of a clock sitting at zero.
        pesWithPts(VIDEO_PID, 45_000);

        assertThat(analyzer.stats().pid(VIDEO_PID).ptsSkewMillis())
                .isCloseTo(500.0, within(1.0));
        assertThat(analyzer.stats().lateTimestamps()).isZero();
    }

    /** The minimum is kept, not the most recent value. */
    @Test
    void theSmallestSkewIsRemembered() {
        startClock();
        pesWithPts(VIDEO_PID, 90_000);   // a second ahead
        pesWithPts(VIDEO_PID, 18_000);   // then only 200ms ahead
        pesWithPts(VIDEO_PID, 90_000);   // and comfortable again

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.minPtsSkewMillis())
                .as("the moment it nearly ran out, not where it ended up")
                .isCloseTo(200.0, within(1.0));
        assertThat(video.ptsSkewMillis()).isCloseTo(1000.0, within(1.0));
    }

    /**
     * A timestamp due before it arrived is counted, because a decoder can only
     * stall or drop it.
     */
    @Test
    void aTimestampPastItsDeadlineIsCountedAsLate() {
        startClock();
        advanceClock(2000);
        // Due half a second before the clock reached this point.
        pesWithPts(VIDEO_PID, -45_000);

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.lateTimestamps()).isEqualTo(1);
        assertThat(video.ptsSkewMillis()).isCloseTo(-500.0, within(1.0));
        assertThat(analyzer.stats().lateTimestamps()).isEqualTo(1);
    }

    /** Arriving exactly on the deadline counts as late: there is no time left to decode. */
    @Test
    void arrivingExactlyOnTheDeadlineIsLate() {
        startClock();
        advanceClock(1000);
        pesWithPts(VIDEO_PID, 0);

        assertThat(analyzer.stats().pid(VIDEO_PID).lateTimestamps()).isEqualTo(1);
    }

    /** Late timestamps reach a listener, with the track and how late it was. */
    @Test
    void alatenessReachesAListener() {
        List<String> seen = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onLateTimestamp(int pid, long skew) {
                seen.add(String.format("0x%04X %.0fms", pid, skew / 90.0));
            }
        });

        startClock();
        advanceClock(2000);
        pesWithPts(VIDEO_PID, -18_000);

        assertThat(seen).containsExactly("0x0100 -200ms");
    }

    /**
     * Slack running out is visible before anything is actually late.
     *
     * <p>The reason the minimum is reported rather than only a late count: a
     * stream whose skew is falling is heading for trouble, and that is worth
     * seeing while there is still time to change something.
     */
    @Test
    void shrinkingSlackIsVisibleBeforeAnythingIsLate() {
        startClock();
        for (int aheadMillis = 900; aheadMillis >= 100; aheadMillis -= 200) {
            advanceClock(500);
            pesWithPts(VIDEO_PID, aheadMillis * 90L);
        }

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.lateTimestamps()).as("nothing has actually missed yet").isZero();
        assertThat(video.minPtsSkewMillis())
                .as("but the margin has fallen to a tenth of a second")
                .isCloseTo(100.0, within(1.0));
    }

    /** Nothing is reported for a track that has carried no timestamp. */
    @Test
    void aTrackWithoutTimestampsReportsNoSkew() {
        startClock();

        assertThat(analyzer.stats().pid(AUDIO_PID)).isNull();
        assertThat(analyzer.stats().lateTimestamps()).isZero();
    }

    // --- helpers

    private void startClock() {
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
    }

    private void advanceClock(long millis) {
        long target = pcr + millis * (AdaptationField.PCR_RATE_HZ / 1000);
        long step = 40L * (AdaptationField.PCR_RATE_HZ / 1000);
        while (pcr < target) {
            pcr = Math.min(target, pcr + step);
            analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        }
    }

    /** A PES packet whose PTS sits {@code ahead} ticks past the clock's present value. */
    private void pesWithPts(int pid, long ahead) {
        long pts = pcr / 300 + ahead;
        analyzer.consume(pesStart(pid, next(pid), pts));
    }

    private int next(int pid) {
        int counter = (counters.getOrDefault(pid, -1) + 1) & 0x0F;
        counters.put(pid, counter);
        return counter;
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
        try (InputStream in = PtsSkewTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data = read(resource);
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

    private static TsPacket pesStart(int pid, int counter, long pts) {
        byte[] data = new byte[TsPacket.LENGTH];
        int at = 4;
        data[at++] = 0x00;
        data[at++] = 0x00;
        data[at++] = 0x01;
        data[at++] = (byte) 0xE0;
        data[at++] = 0x00;
        data[at++] = 0x00;
        data[at++] = (byte) 0x80;
        data[at++] = (byte) 0x80;
        data[at++] = 0x05;
        long value = pts & 0x1FFFFFFFFL;
        data[at] = (byte) (0x21 | ((value >> 29) & 0x0E));
        data[at + 1] = (byte) ((value >> 22) & 0xFF);
        data[at + 2] = (byte) (0x01 | ((value >> 14) & 0xFE));
        data[at + 3] = (byte) ((value >> 7) & 0xFF);
        data[at + 4] = (byte) (0x01 | ((value << 1) & 0xFE));
        return new TsPacket(data, false, true, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }
}
