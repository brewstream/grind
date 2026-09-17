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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Errored seconds, in the sense ETSI TR 101 290 uses the term: how many seconds
 * of stream time contained at least one error, counted once however many errors
 * the second holds.
 *
 * <p>A fixture cannot test this. Real streams do not let you place two errors
 * inside one second and a third just past the boundary, which is precisely the
 * behaviour that distinguishes this measure from an error count. So the clock is
 * driven by hand here, and the errors are placed against it deliberately.
 *
 * <p><b>The time base is the PCR, not the wall clock.</b> A file analysed at a
 * hundred times real speed still reports the errored seconds the stream actually
 * contains, and a live stream is measured in its own time base rather than in
 * the analyser's. That choice is what these tests pin down: nothing here sleeps,
 * yet seconds pass.
 */
class ErroredSecondsTest {

    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_PID = 0x0101;

    /**
     * One clock step, comfortably under the tenth of a second that would be read
     * as a PCR discontinuity — which is itself an error, and would contaminate
     * every count here.
     */
    private static final long PCR_STEP = 2_000_000L;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private long pcr;

    /** Establishes the clock at second zero, without which nothing is counted. */
    private void startClock() {
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
    }

    /**
     * Moves stream time forward by whole seconds, in steps small enough that the
     * analyser sees a continuous clock rather than a jump.
     */
    private void advanceSeconds(int seconds) {
        long target = pcr + (long) seconds * AdaptationField.PCR_RATE_HZ;
        while (pcr < target) {
            pcr = Math.min(target, pcr + PCR_STEP);
            analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        }
    }

    /** A packet flagged corrupt upstream: the simplest error to place precisely. */
    private void injectError(int pid) {
        analyzer.consume(errored(pid, next(pid)));
    }

    private int next(int pid) {
        int counter = (counters.getOrDefault(pid, -1) + 1) & 0x0F;
        counters.put(pid, counter);
        return counter;
    }

    /**
     * The whole point of the measure. Three errors, one second, one errored
     * second — on the PID and on the stream alike.
     */
    @Test
    void severalErrorsInOneSecondCountAsOneErroredSecond() {
        startClock();

        injectError(VIDEO_PID);
        injectError(VIDEO_PID);
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().erroredSeconds())
                .as("three errors inside a single second of stream time")
                .isEqualTo(1);
        assertThat(analyzer.stats().pid(VIDEO_PID).erroredSeconds()).isEqualTo(1);
        assertThat(analyzer.stats().pid(VIDEO_PID).transportErrors())
                .as("the underlying errors are still counted individually")
                .isEqualTo(3);
    }

    /** And the converse: the same error either side of a boundary is two. */
    @Test
    void errorsInDifferentSecondsAreCountedSeparately() {
        startClock();
        injectError(VIDEO_PID);

        advanceSeconds(1);
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().erroredSeconds()).isEqualTo(2);
        assertThat(analyzer.stats().pid(VIDEO_PID).erroredSeconds()).isEqualTo(2);
    }

    /** Seconds that pass cleanly between two bad ones are not counted. */
    @Test
    void cleanSecondsBetweenErrorsAreNotCounted() {
        startClock();
        injectError(VIDEO_PID);

        advanceSeconds(4);
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().erroredSeconds())
                .as("five seconds observed, the first and last of them bad")
                .isEqualTo(2);
        assertThat(analyzer.stats().observedSeconds()).isEqualTo(5);
    }

    /** A stream that never errs reports none, over however long. */
    @Test
    void aCleanStreamHasNoErroredSeconds() {
        startClock();
        advanceSeconds(3);

        assertThat(analyzer.stats().erroredSeconds()).isZero();
        assertThat(analyzer.stats().observedSeconds()).isEqualTo(4);
        assertThat(analyzer.stats().erroredSecondRate()).isZero();
    }

    /**
     * Errors on two PIDs in the same second: one errored second each, and one for
     * the stream — not two. The stream-level figure answers "was this stream
     * broken", which two simultaneous failures do not make twice as true.
     */
    @Test
    void errorsOnDifferentPidsMarkTheStreamSecondOnce() {
        startClock();

        injectError(VIDEO_PID);
        injectError(AUDIO_PID);

        assertThat(analyzer.stats().erroredSeconds())
                .as("one broken second, however many PIDs were broken in it")
                .isEqualTo(1);
        assertThat(analyzer.stats().pid(VIDEO_PID).erroredSeconds()).isEqualTo(1);
        assertThat(analyzer.stats().pid(AUDIO_PID).erroredSeconds()).isEqualTo(1);
    }

    /** An error on one PID does not mark a second against a PID that was fine. */
    @Test
    void aHealthyPidIsNotBlamedForAnotherPidsError() {
        startClock();

        analyzer.consume(packet(AUDIO_PID, next(AUDIO_PID)));
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().pid(AUDIO_PID).erroredSeconds()).isZero();
        assertThat(analyzer.stats().pid(VIDEO_PID).erroredSeconds()).isEqualTo(1);
    }

    /**
     * A known and deliberate limit: before any PCR has arrived there is no time
     * base, so errors cannot be attributed to a second and are not counted as
     * one. They are still counted as errors.
     *
     * <p>Counting them against second zero would be worse — it would invent a
     * second the stream has not been observed for, and make {@code
     * erroredSecondRate()} exceed one.
     */
    @Test
    void errorsBeforeTheFirstPcrAreNotAttributedToASecond() {
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().erroredSeconds())
                .as("no clock yet, so no second to blame")
                .isZero();
        assertThat(analyzer.stats().observedSeconds()).isZero();
        assertThat(analyzer.stats().pid(VIDEO_PID).transportErrors())
                .as("the error itself is not lost, only its place in time")
                .isEqualTo(1);
    }

    /** Continuity errors mark a second too, not just flagged-corrupt packets. */
    @Test
    void continuityErrorsMarkTheSecondTheyFallIn() {
        startClock();

        analyzer.consume(packet(AUDIO_PID, 0));
        analyzer.consume(packet(AUDIO_PID, 5)); // four packets missing

        assertThat(analyzer.stats().pid(AUDIO_PID).continuityErrors()).isEqualTo(1);
        assertThat(analyzer.stats().erroredSeconds()).isEqualTo(1);
        assertThat(analyzer.stats().pid(AUDIO_PID).erroredSeconds()).isEqualTo(1);
    }

    /** The rate is errored seconds over observed ones, which a reader can check by eye. */
    @Test
    void erroredSecondRateIsTheProportionOfSecondsThatWereBad() {
        startClock();
        injectError(VIDEO_PID);
        advanceSeconds(3);

        assertThat(analyzer.stats().observedSeconds()).isEqualTo(4);
        assertThat(analyzer.stats().erroredSeconds()).isEqualTo(1);
        assertThat(analyzer.stats().erroredSecondRate()).isEqualTo(0.25);
    }

    /**
     * Health and errored seconds must never disagree. Every condition that marks
     * a second also clears the health flag, so the dashboard cannot show a green
     * badge beside a non-zero count.
     *
     * <p>A PCR discontinuity is the one that used to slip through: it is counted,
     * and it marks a second, but {@code isHealthy()} did not consider it.
     */
    @Test
    void aPcrDiscontinuityMakesTheStreamUnhealthyAsWellAsErrored() {
        startClock();
        advanceSeconds(1);

        // A backwards jump no discontinuity indicator announced.
        pcr = pcr - AdaptationField.PCR_RATE_HZ;
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.pcrDiscontinuities()).isEqualTo(1);
        assertThat(stats.erroredSeconds()).isEqualTo(1);
        assertThat(stats.isHealthy())
                .as("counted as an error, so it must not report healthy")
                .isFalse();
    }

    /** The general form: nothing marks a second without also clearing health. */
    @Test
    void anErroredSecondAlwaysMeansUnhealthy() {
        startClock();
        injectError(VIDEO_PID);

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.erroredSeconds()).isPositive();
        assertThat(stats.isHealthy()).isFalse();
    }

    /**
     * A looping file, which is what a demonstration actually runs.
     *
     * <p>Each wrap sends the clock backwards, and for a long time that made the
     * observed span <em>shrink</em> while errored seconds kept accumulating —
     * reporting twelve errored seconds out of four observed, which is not a
     * number that can be true. It showed up on a dashboard rather than here,
     * because a single pass never revisits a second.
     */
    @Test
    void aLoopingStreamNeverReportsMoreErroredSecondsThanObserved() {
        startClock();

        for (int loop = 0; loop < 4; loop++) {
            advanceSeconds(3);
            injectError(VIDEO_PID);
            // Back to the beginning, as replaying a file does. Unannounced, so
            // the analyser sees exactly what a receiver would.
            pcr = 0;
            analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        }

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.erroredSeconds())
                .as("a second cannot be errored more often than it was observed")
                .isLessThanOrEqualTo(stats.observedSeconds());
        assertThat(stats.erroredSecondRate()).isBetween(0.0, 1.0);
    }

    /** Replayed seconds are counted again, because they are more stream time. */
    @Test
    void timeObservedKeepsGrowingAcrossAWrap() {
        startClock();
        advanceSeconds(3);
        long before = analyzer.stats().observedSeconds();

        pcr = 0;
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        advanceSeconds(3);

        assertThat(analyzer.stats().observedSeconds())
                .as("the second pass is more stream time, not a return to earlier time")
                .isGreaterThan(before);
    }

    /**
     * An error in a replayed second is still an errored second.
     *
     * <p>Identity comes from the running count rather than the clock's value, so
     * a second the clock has used before does not look like one already counted.
     */
    @Test
    void anErrorInAReplayedSecondIsCountedAgain() {
        startClock();
        injectError(VIDEO_PID);
        assertThat(analyzer.stats().erroredSeconds()).isEqualTo(1);

        // Back to the same second number the clock has already been through.
        pcr = 0;
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));
        advanceSeconds(1);
        injectError(VIDEO_PID);

        assertThat(analyzer.stats().erroredSeconds())
                .as("the same second number, but a different second of stream time")
                .isEqualTo(2);
    }

    /**
     * A clock that skips forward did not thereby observe the time it skipped.
     *
     * <p>The mirror of the wrap case: an hour-long jump counts as the one second
     * actually entered, not as an hour of stream nobody saw.
     */
    @Test
    void aForwardJumpDoesNotInventTimeThatWasNotObserved() {
        startClock();
        advanceSeconds(2);
        long before = analyzer.stats().observedSeconds();

        pcr += 3600L * AdaptationField.PCR_RATE_HZ;
        analyzer.consume(withPcr(VIDEO_PID, next(VIDEO_PID), pcr));

        assertThat(analyzer.stats().observedSeconds())
                .as("one more second entered, not an hour of it")
                .isEqualTo(before + 1);
    }

    // --- hand-built packets, so errors land where the test wants them

    private static TsPacket packet(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket errored(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], true, false, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket withPcr(int pid, int counter, long pcr) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, counter,
                new AdaptationField(false, false, false, pcr), 12, TsPacket.LENGTH - 12);
    }
}
