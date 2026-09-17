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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ETSI TR 101 290's {@code PAT_error} and {@code PMT_error}: the tables that let
 * a receiver find the programs must arrive at least every 500ms.
 *
 * <p>Priority 1, where PCR and PTS repetition are Priority 2 — without these a
 * receiver cannot find anything at all. They are still reported apart from the
 * health figures, and the argument is in {@link TsStreamStats#isHealthy()}: when
 * a gap between tables is caused by loss, that loss is already counted as
 * continuity errors where it happened.
 *
 * <p>Like the PTS check and unlike the PCR one, this is quiet on ordinary
 * streams. The fixtures carry their tables every 117ms on average and 160ms at
 * worst, so a breach means something.
 *
 * <p>Driving a breach needs real PSI — the analyser counts a table as having
 * arrived only when a complete, current, CRC-valid section is assembled, which no
 * hand-built byte array in this project can produce. So these tests replay the
 * fixture's own table packets and control the clock in between.
 */
class TableRepetitionTest {

    private static final int VIDEO_PID = 0x0100;

    private final TsAnalyzer analyzer = new TsAnalyzer();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private long pcr;

    /** Real streams, and none of them is anywhere near the limit. */
    @Test
    void realFixturesCarryTheirTablesOftenEnough() throws IOException {
        for (String fixture : new String[]{"/sample.ts", "/bframes.ts", "/multiprogram.ts"}) {
            TsStreamStats stats = analyze(fixture).stats();
            assertThat(stats.patRepetitionErrors())
                    .as("%s carries a PAT far more often than every 500ms", fixture)
                    .isZero();
            assertThat(stats.pmtRepetitionErrors())
                    .as("%s carries its PMTs far more often than every 500ms", fixture)
                    .isZero();
        }
    }

    /**
     * The widest real gap, pinned. If a change starts making this check fire on
     * ordinary streams, it is caught here rather than on someone's dashboard.
     */
    @Test
    void theWidestRealGapIsWellInsideTheLimit() throws IOException {
        TsStreamStats stats = analyze("/sample.ts").stats();

        assertThat(stats.maxTableIntervalMillis())
                .as("a muxer emits these several times a second")
                .isBetween(100.0, 300.0);
    }

    /**
     * A muxer that stops emitting tables for longer than the limit is reported,
     * on the PAT and on the PMT independently.
     */
    @Test
    void aGapBetweenTablesIsReported() throws IOException {
        List<TsPacket> tables = tablePacketsOf("/sample.ts");

        startClock();
        replayOnce(tables);

        advanceClock(700);
        replayOnce(tables);

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.patRepetitionErrors()).as("the PAT was 700ms late").isEqualTo(1);
        assertThat(stats.pmtRepetitionErrors()).as("and so was the PMT").isEqualTo(1);
        assertThat(stats.maxTableIntervalMillis()).isCloseTo(700.0, within(50.0));
    }

    /** A gap inside the limit is not a breach. */
    @Test
    void aGapInsideTheLimitIsNotABreach() throws IOException {
        List<TsPacket> tables = tablePacketsOf("/sample.ts");

        startClock();
        replayOnce(tables);

        advanceClock(400);
        replayOnce(tables);

        assertThat(analyzer.stats().patRepetitionErrors()).isZero();
        assertThat(analyzer.stats().pmtRepetitionErrors()).isZero();
        assertThat(analyzer.stats().maxTableIntervalMillis()).isCloseTo(400.0, within(50.0));
    }

    /** The first table starts the measurement rather than being judged against nothing. */
    @Test
    void theFirstTableIsNotABreachHoweverLateItArrives() throws IOException {
        List<TsPacket> tables = tablePacketsOf("/sample.ts");

        startClock();
        advanceClock(3000);
        replayOnce(tables);

        assertThat(analyzer.stats().patRepetitionErrors())
                .as("three seconds before the stream's first table is not a gap between tables")
                .isZero();
        assertThat(analyzer.stats().pmtRepetitionErrors()).isZero();
    }

    /**
     * Conformance, not damage — the rule the other repetition checks follow, and
     * the closest call of the four because these are Priority 1.
     *
     * <p>If this fails, a stream whose muxer is merely slow now reports as
     * damaged, and a gap caused by real loss is counted twice: once here and once
     * as the continuity errors that caused it.
     */
    @Test
    void aLateTableDoesNotMakeTheStreamUnhealthy() throws IOException {
        List<TsPacket> tables = tablePacketsOf("/sample.ts");

        startClock();
        replayOnce(tables);
        advanceClock(700);
        replayOnce(tables);

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.patRepetitionErrors()).isPositive();
        assertThat(stats.isHealthy()).as("nothing was lost").isTrue();
        assertThat(stats.erroredSeconds()).as("and no second was errored").isZero();
    }

    /**
     * A section thrown away for a bad checksum does not count as the table having
     * arrived.
     *
     * <p>A receiver cannot use a table it had to discard, so as far as it is
     * concerned the table did not come — and the clock should keep running toward
     * the limit rather than being reset by it.
     */
    @Test
    void aTableDiscardedForBadCrcDoesNotCountAsArriving() throws IOException {
        List<TsPacket> tables = tablePacketsOf("/sample.ts");

        startClock();
        replayOnce(tables);

        // Halfway through the gap, a corrupt copy arrives and is discarded.
        advanceClock(400);
        replayCorrupted(tables);

        advanceClock(400);
        replayOnce(tables);

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.crcErrors()).as("the corrupt copy was rejected").isPositive();
        assertThat(stats.patRepetitionErrors())
                .as("800ms passed with no usable PAT, however many broken ones arrived")
                .isEqualTo(1);
    }

    /**
     * The breach reaches a listener, naming which table was late.
     *
     * <p>Both tables are reported separately, which is the point of tracking them
     * separately: a PAT that keeps arriving while a PMT stalls is a different
     * fault from both of them stopping.
     */
    @Test
    void aBreachReachesAListener() throws IOException {
        List<Integer> late = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onTableRepetitionError(int pid, long interval) {
                late.add(pid);
            }
        });

        List<TsPacket> tables = tablePacketsOf("/sample.ts");
        startClock();
        replayOnce(tables);
        advanceClock(700);
        replayOnce(tables);

        assertThat(late)
                .as("the PAT and the one PMT, each reported on its own PID")
                .containsExactly(TsPacket.PAT_PID, 0x1000);
    }

    // --- helpers

    /** Every packet carrying PAT or PMT sections, in the order the fixture has them. */
    private List<TsPacket> tablePacketsOf(String resource) throws IOException {
        byte[] data = read(resource);

        TsAnalyzer structure = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet != null) {
                structure.consume(packet);
            }
        }
        Map<Integer, Integer> pmtPids = structure.stats().programs().pmtPids();
        assertThat(pmtPids).as("the fixture must declare at least one program").isNotEmpty();

        List<TsPacket> tables = new ArrayList<>();
        boolean seenPat = false;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null) {
                continue;
            }
            boolean isTable = packet.pid() == TsPacket.PAT_PID || pmtPids.containsValue(packet.pid());
            if (!isTable) {
                continue;
            }
            if (packet.pid() == TsPacket.PAT_PID) {
                if (seenPat) {
                    break; // one round of each table is enough to replay
                }
                seenPat = true;
            }
            tables.add(packet);
        }
        return tables;
    }

    /**
     * Replays one round of the tables.
     *
     * <p>Continuity counters are rewritten so repeated rounds do not look like
     * gaps: the same section arriving twice is what a muxer does, and it must not
     * register as loss.
     */
    private void replayOnce(List<TsPacket> tables) {
        for (TsPacket table : tables) {
            analyzer.consume(renumbered(table, next(table.pid())));
        }
    }

    /** The same round, with one payload byte flipped so the CRC fails. */
    private void replayCorrupted(List<TsPacket> tables) {
        for (TsPacket table : tables) {
            byte[] bytes = renumberedBytes(table, next(table.pid()));
            bytes[20] ^= (byte) 0xFF;
            analyzer.consume(TsPacket.parse(bytes, 0));
        }
    }

    private TsPacket renumbered(TsPacket table, int counter) {
        return TsPacket.parse(renumberedBytes(table, counter), 0);
    }

    private byte[] renumberedBytes(TsPacket table, int counter) {
        byte[] bytes = new byte[TsPacket.LENGTH];
        table.payloadInto(bytes, 0);
        // Rebuild the four-byte header around the copied payload.
        byte[] rebuilt = new byte[TsPacket.LENGTH];
        System.arraycopy(bytes, 0, rebuilt, 4, TsPacket.LENGTH - 4);
        rebuilt[0] = 0x47;
        rebuilt[1] = (byte) (0x40 | ((table.pid() >> 8) & 0x1F)); // payload unit start
        rebuilt[2] = (byte) (table.pid() & 0xFF);
        rebuilt[3] = (byte) (0x10 | (counter & 0x0F));            // payload only
        return rebuilt;
    }

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

    private int next(int pid) {
        int counter = (counters.getOrDefault(pid, -1) + 1) & 0x0F;
        counters.put(pid, counter);
        return counter;
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = TableRepetitionTest.class.getResourceAsStream(resource)) {
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
}
