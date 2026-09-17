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

/**
 * A transport stream is a multiplex, and its programs need not share a clock.
 *
 * <p>A DVB transponder carrying unrelated services has a PCR per program, and
 * those clocks are independent: hours apart, drifting against each other, each
 * meaningful only for its own program. Measuring every program against whichever
 * PCR arrived last makes each one's figures depend on its neighbours.
 *
 * <p><b>No fixture can catch this.</b> Both programs in {@code multiprogram.ts}
 * carry the same clock, because ffmpeg built them from one source — their PCRs
 * agree to the millisecond. So the tables come from the fixture, which makes the
 * program structure real, and the timeline is driven by hand, which is the only
 * way to give two programs genuinely separate clocks.
 */
class ProgramClockTest {

    /** An hour apart, so no plausible arithmetic slip could make the two agree. */
    private static final long PROGRAM_TWO_OFFSET = 3600L * AdaptationField.PCR_RATE_HZ;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();

    private int firstProgram;
    private int secondProgram;
    private int firstPcrPid;
    private int secondPcrPid;

    /**
     * Feeds only the fixture's PAT and PMTs, so the analyser knows the program
     * structure while no media packet has yet set a clock.
     */
    @BeforeEach
    void establishProgramStructure() throws IOException {
        byte[] data = read("/multiprogram.ts");

        consumeWhere(data, pid -> pid == TsPacket.PAT_PID);
        Map<Integer, Integer> pmtPids = analyzer.stats().programs().pmtPids();
        assertThat(pmtPids).as("the PAT must name at least two programs").hasSizeGreaterThan(1);
        consumeWhere(data, pmtPids::containsValue);

        ProgramMap programs = analyzer.stats().programs();
        List<Integer> numbers = new ArrayList<>(programs.programs().keySet());
        assertThat(numbers).as("two PMTs must have been parsed").hasSizeGreaterThan(1);

        firstProgram = numbers.get(0);
        secondProgram = numbers.get(1);
        firstPcrPid = programs.programs().get(firstProgram).pcrPid();
        secondPcrPid = programs.programs().get(secondProgram).pcrPid();
        assertThat(firstPcrPid).as("the programs must not share a PCR PID").isNotEqualTo(secondPcrPid);
    }

    /**
     * The case that a shared clock gets wrong.
     *
     * <p>Program one's clock runs on while program two's stands still. A second
     * error on program two must not be counted as a second errored second: no
     * time has passed <em>for program two</em>, however much has passed for its
     * neighbour. Measured against a single global clock this reports two.
     */
    @Test
    void oneProgramsClockMovingDoesNotAgeAnother() {
        tick(firstPcrPid, 0);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET);

        injectError(secondPcrPid);
        assertThat(analyzer.stats().pid(secondPcrPid).erroredSeconds()).isEqualTo(1);

        // Four seconds pass for program one alone.
        for (int i = 1; i <= 4; i++) {
            tick(firstPcrPid, (long) i * AdaptationField.PCR_RATE_HZ);
        }
        injectError(secondPcrPid);

        assertThat(analyzer.stats().pid(secondPcrPid).erroredSeconds())
                .as("program two's own clock never moved, so it is still the same second")
                .isEqualTo(1);
    }

    /** And when a program's own clock does move, its next error is a new second. */
    @Test
    void aProgramsOwnClockMovingDoesAgeIt() {
        tick(firstPcrPid, 0);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET);

        injectError(secondPcrPid);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET + AdaptationField.PCR_RATE_HZ);
        injectError(secondPcrPid);

        assertThat(analyzer.stats().pid(secondPcrPid).erroredSeconds()).isEqualTo(2);
    }

    /** An error on one program is not counted against the other. */
    @Test
    void errorsAreNotAttributedAcrossPrograms() {
        tick(firstPcrPid, 0);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET);

        injectError(secondPcrPid);

        assertThat(analyzer.stats().pid(secondPcrPid).erroredSeconds()).isEqualTo(1);
        assertThat(analyzer.stats().pid(firstPcrPid).erroredSeconds())
                .as("program one was fine")
                .isZero();
    }

    /**
     * Every track of a program shares that program's clock, not just the PID
     * carrying the PCR.
     *
     * <p>This is what the PMT is for: an audio track carries no clock of its own,
     * and its errors still have to land on a timeline.
     */
    @Test
    void everyTrackOfAProgramSharesItsClock() {
        ProgramMap programs = analyzer.stats().programs();
        int nonPcrPid = programs.programs().get(secondProgram).streams().stream()
                .mapToInt(ElementaryStream::pid)
                .filter(pid -> pid != secondPcrPid)
                .findFirst()
                .orElseThrow(() -> new AssertionError("program two needs a track that is not its PCR PID"));

        tick(firstPcrPid, 0);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET);

        injectError(nonPcrPid);
        assertThat(analyzer.stats().pid(nonPcrPid).erroredSeconds()).isEqualTo(1);

        for (int i = 1; i <= 4; i++) {
            tick(firstPcrPid, (long) i * AdaptationField.PCR_RATE_HZ);
        }
        injectError(nonPcrPid);

        assertThat(analyzer.stats().pid(nonPcrPid).erroredSeconds())
                .as("it follows its own program's clock, not the one that happens to be running")
                .isEqualTo(1);
    }

    /**
     * The stream-level figure is measured on the reference clock — the first the
     * stream revealed — because a count that spans programs needs one timeline to
     * be counted on.
     */
    @Test
    void theStreamLevelFigureUsesTheReferenceClock() {
        tick(firstPcrPid, 0);
        tick(secondPcrPid, PROGRAM_TWO_OFFSET);

        injectError(secondPcrPid);
        assertThat(analyzer.stats().erroredSeconds()).isEqualTo(1);

        // The reference clock moves, so the multiplex has entered a new second
        // even though the erring program's clock has not.
        tick(firstPcrPid, AdaptationField.PCR_RATE_HZ);
        injectError(secondPcrPid);

        assertThat(analyzer.stats().erroredSeconds())
                .as("two seconds of the multiplex's timeline contained an error")
                .isEqualTo(2);
        assertThat(analyzer.stats().observedSeconds())
                .as("observed on the reference clock, not the hour-ahead one")
                .isEqualTo(2);
    }

    // --- helpers

    /** Advances one program's clock by delivering a PCR on its own PID. */
    private void tick(int pcrPid, long pcr) {
        analyzer.consume(withPcr(pcrPid, next(pcrPid), pcr));
    }

    private void injectError(int pid) {
        analyzer.consume(errored(pid, next(pid)));
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
        try (InputStream in = ProgramClockTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    private static TsPacket withPcr(int pid, int counter, long pcr) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, counter,
                new AdaptationField(false, false, false, pcr), 12, TsPacket.LENGTH - 12);
    }

    private static TsPacket errored(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], true, false, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }
}
