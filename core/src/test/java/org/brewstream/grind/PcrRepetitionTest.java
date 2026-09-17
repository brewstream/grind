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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ETSI TR 101 290's {@code PCR_repetition_error}: a PCR at least every 40ms.
 *
 * <p>The interesting thing about this check is that <b>every fixture in this
 * project fails it</b>, on every single interval. ffmpeg's muxer spaces PCRs
 * 80ms apart by default, exactly twice the limit, and those streams are
 * perfectly watchable. That is the whole argument for keeping this out of
 * errored seconds and out of {@link TsStreamStats#isHealthy()}: it is a
 * conformance measure, and conformance and damage are different questions.
 *
 * <p>So these tests pin both halves — that the breach is detected and counted,
 * and that detecting it changes nothing about whether the stream reads as
 * healthy.
 */
class PcrRepetitionTest {

    private static final int VIDEO_PID = 0x0100;

    /** 20ms in 27 MHz units: half the limit, so a conformant spacing. */
    private static final long CONFORMANT_STEP = 27_000_000L * 20 / 1000;

    /** 80ms: what ffmpeg actually produces, and twice the limit. */
    private static final long FFMPEG_STEP = 27_000_000L * 80 / 1000;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private long pcr;

    /**
     * Real data, exact numbers. {@code sample.ts} carries 25 PCRs at a rigidly
     * regular 80ms, so there are 24 intervals and every one of them breaches.
     */
    @Test
    void everyIntervalInARealFixtureBreachesTheLimit() throws IOException {
        TsAnalyzer real = analyze("/sample.ts");
        PidStats video = real.stats().pid(VIDEO_PID);

        assertThat(video.pcrCount()).isEqualTo(25);
        assertThat(video.pcrRepetitionErrors())
                .as("24 intervals between 25 PCRs, all of them too wide")
                .isEqualTo(24);
        assertThat(video.maxPcrIntervalMillis())
                .as("ffmpeg's default spacing, twice the 40ms limit")
                .isCloseTo(80.0, within(0.5));
    }

    /**
     * And the point of separating the two questions: that stream is fine.
     *
     * <p>If this ever fails, the check has been folded into the health figures
     * and every ordinary ffmpeg stream now reads as broken for its whole
     * duration.
     */
    @Test
    void aNonConformantStreamIsStillHealthy() throws IOException {
        TsStreamStats stats = analyze("/sample.ts").stats();

        assertThat(stats.pcrRepetitionErrors()).isPositive();
        assertThat(stats.isHealthy())
                .as("late PCRs lose nothing, so nothing was damaged")
                .isTrue();
        assertThat(stats.erroredSeconds())
                .as("and no second of it was errored")
                .isZero();
    }

    /** A muxer inside the limit reports nothing. */
    @Test
    void aConformantStreamReportsNoBreach() {
        for (int i = 0; i < 20; i++) {
            tick(CONFORMANT_STEP);
        }

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.pcrRepetitionErrors()).isZero();
        assertThat(video.maxPcrIntervalMillis()).isCloseTo(20.0, within(0.5));
        assertThat(analyzer.stats().pcrRepetitionErrors()).isZero();
    }

    /** Exactly at the limit is conformant; a step beyond it is not. */
    @Test
    void theLimitItselfIsAllowed() {
        long exactly40ms = 27_000_000L * 40 / 1000;
        tick(exactly40ms);
        tick(exactly40ms);
        assertThat(analyzer.stats().pid(VIDEO_PID).pcrRepetitionErrors())
                .as("40ms is the limit, not the first breach of it")
                .isZero();

        tick(exactly40ms + 27_000);  // one millisecond over
        assertThat(analyzer.stats().pid(VIDEO_PID).pcrRepetitionErrors()).isEqualTo(1);
    }

    /** The widest gap is kept, not the most recent one. */
    @Test
    void theWidestIntervalIsRemembered() {
        tick(CONFORMANT_STEP);
        tick(FFMPEG_STEP);
        tick(CONFORMANT_STEP);

        assertThat(analyzer.stats().pid(VIDEO_PID).maxPcrIntervalMillis())
                .as("the 80ms gap, not the 20ms one that followed it")
                .isCloseTo(80.0, within(0.5));
    }

    /**
     * A clock that jumped is not also a muxer that was late.
     *
     * <p>An unannounced jump forward is a discontinuity, and the interval it
     * produces describes the jump rather than the spacing. Counting it as both
     * would report one fault twice, on two different TR 101 290 checks.
     */
    @Test
    void aDiscontinuityIsNotAlsoCountedAsALatePcr() {
        tick(CONFORMANT_STEP);
        tick(CONFORMANT_STEP);

        // Ten seconds forward with no discontinuity indicator: a clock break.
        tick(10L * AdaptationField.PCR_RATE_HZ);

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.pcrDiscontinuities()).isEqualTo(1);
        assertThat(video.pcrRepetitionErrors())
                .as("counted as a broken clock, not as a late PCR")
                .isZero();
    }

    /** An announced discontinuity is legal, and is neither kind of error. */
    @Test
    void anAnnouncedDiscontinuityIsNeitherError() {
        tick(CONFORMANT_STEP);
        tick(CONFORMANT_STEP);

        pcr += 10L * AdaptationField.PCR_RATE_HZ;
        analyzer.consume(new TsPacket(new byte[TsPacket.LENGTH], false, false, false, VIDEO_PID, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, next(VIDEO_PID),
                new AdaptationField(true, false, false, pcr), 12, TsPacket.LENGTH - 12));

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.pcrDiscontinuities()).isZero();
        assertThat(video.pcrRepetitionErrors()).isZero();
    }

    /** Each program's PCR PID is measured on its own, not against the other's. */
    @Test
    void eachPcrPidIsMeasuredSeparately() {
        int otherPid = 0x0102;
        for (int i = 0; i < 4; i++) {
            tick(CONFORMANT_STEP);
        }
        // A second clock, spaced far too widely.
        long other = 0;
        for (int i = 0; i < 4; i++) {
            other += FFMPEG_STEP;
            analyzer.consume(withPcr(otherPid, next(otherPid), other));
        }

        assertThat(analyzer.stats().pid(VIDEO_PID).pcrRepetitionErrors()).isZero();
        assertThat(analyzer.stats().pid(otherPid).pcrRepetitionErrors()).isEqualTo(3);
        assertThat(analyzer.stats().pcrRepetitionErrors())
                .as("the stream total sums both PIDs")
                .isEqualTo(3);
    }

    // --- helpers

    private void tick(long step) {
        pcr += step;
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
    }

    private int next(int pid) {
        int counter = (counters.getOrDefault(pid, -1) + 1) & 0x0F;
        counters.put(pid, counter);
        return counter;
    }

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data;
        try (InputStream in = PcrRepetitionTest.class.getResourceAsStream(resource)) {
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
}
