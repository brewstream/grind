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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Health tracking over a real stream, cross-checked against TSDuck's own
 * analysis of the same file: {@code tsanalyze} reports 5 PIDs, 0 transport
 * errors and exactly one PID carrying PCRs. Anything this class claims about
 * {@code sample.ts} has an independent second opinion.
 *
 * <p>Damage is injected by hand, because a locally muxed file has none.
 */
class TsAnalyzerTest {

    private static final int VIDEO_PID = 0x100;

    private static byte[] sample() throws IOException {
        try (InputStream in = TsAnalyzerTest.class.getResourceAsStream("/sample.ts")) {
            return in.readAllBytes();
        }
    }

    private static TsAnalyzer analyzeSample() throws IOException {
        byte[] data = sample();
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    @Test
    void aCleanStreamIsReportedHealthyAndMatchesTsduckSNumbers() throws IOException {
        TsStreamStats stats = analyzeSample().stats();

        assertThat(stats.isHealthy()).as("a locally muxed file has no loss").isTrue();
        assertThat(stats.continuityErrors()).isZero();
        assertThat(stats.transportErrors()).as("tsanalyze: with transport error 0").isZero();
        assertThat(stats.lossRate()).isZero();
        assertThat(stats.pids()).as("tsanalyze: PID's total 5").hasSize(5);

        long pidsWithPcr = stats.pids().stream().filter(PidStats::carriesPcr).count();
        assertThat(pidsWithPcr).as("tsanalyze: with PCR's 1").isEqualTo(1);
    }

    @Test
    void perPidCountsAddUpToTheStreamTotals() throws IOException {
        TsStreamStats stats = analyzeSample().stats();

        long packetsAcrossPids = stats.pids().stream().mapToLong(PidStats::packets).sum();
        assertThat(packetsAcrossPids + stats.nullPackets()).isEqualTo(stats.packets());
        assertThat(stats.pid(VIDEO_PID).packets())
                .as("video carries the bulk of a 400kbps stream").isGreaterThan(400);
    }

    /** Dropping a packet must show up as loss of exactly the right size. */
    @Test
    void aDroppedPacketIsReportedAsOneLostPacketOnItsPid() throws IOException {
        byte[] data = sample();
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Integer> lostCounts = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onContinuityError(int pid, int expected, int actual, int lost) {
                lostCounts.add(lost);
            }
        });

        // Dropped mid-stream, not at the start: loss on a PID whose counter has
        // never been seen is undetectable by definition, since there is no
        // previous value to compare against. A test that dropped the first video
        // packet would be asserting the impossible.
        int videoSeen = 0;
        boolean dropped = false;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == VIDEO_PID && packet.hasPayload()) {
                videoSeen++;
                if (videoSeen == 10 && !dropped) {
                    dropped = true;
                    continue;
                }
            }
            analyzer.consume(packet);
        }
        assertThat(dropped).as("the fixture must have had a tenth video packet to drop").isTrue();

        assertThat(lostCounts).as("exactly one gap, of exactly one packet").containsExactly(1);
        TsStreamStats stats = analyzer.stats();
        assertThat(stats.isHealthy()).isFalse();
        assertThat(stats.packetsLost()).isEqualTo(1);
        assertThat(stats.pid(VIDEO_PID).packetsLost()).isEqualTo(1);
        assertThat(stats.pid(VIDEO_PID).lossRate()).isGreaterThan(0);
    }

    /**
     * An announced discontinuity is a splice, not damage. Reporting it as loss
     * would flag an error on every well-formed ad insertion.
     */
    @Test
    void anAnnouncedDiscontinuityIsNotCountedAsLoss() {
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.consume(packet(VIDEO_PID, 5, true, false));
        analyzer.consume(packet(VIDEO_PID, 9, true, true)); // jump, but announced

        assertThat(analyzer.stats().continuityErrors()).isZero();
        assertThat(analyzer.stats().isHealthy()).isTrue();
    }

    /** The same jump without the flag is loss. */
    @Test
    void anUnannouncedJumpIsCountedAsLoss() {
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.consume(packet(VIDEO_PID, 5, true, false));
        analyzer.consume(packet(VIDEO_PID, 9, true, false));

        assertThat(analyzer.stats().continuityErrors()).isEqualTo(1);
        assertThat(analyzer.stats().packetsLost()).as("5 -> 9 skips 6, 7, 8").isEqualTo(3);
    }

    /**
     * A packet flagged corrupt upstream must not seed a comparison, or one bad
     * packet manufactures two errors: the one it causes and the one it hides.
     */
    @Test
    void aTransportErrorDoesNotAlsoManufactureAContinuityError() {
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.consume(packet(VIDEO_PID, 5, true, false));
        analyzer.consume(errored(VIDEO_PID, 11));
        analyzer.consume(packet(VIDEO_PID, 12, true, false));

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.transportErrors()).isEqualTo(1);
        assertThat(stats.continuityErrors()).as("the flagged packet is excluded, not doubled").isZero();
    }

    @Test
    void scramblingChangesAreAnnouncedOnce() {
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Boolean> changes = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onScramblingChanged(int pid, boolean scrambled) {
                changes.add(scrambled);
            }
        });

        analyzer.consume(packet(VIDEO_PID, 0, true, false));
        analyzer.consume(scrambled(VIDEO_PID, 1));
        analyzer.consume(scrambled(VIDEO_PID, 2));
        analyzer.consume(packet(VIDEO_PID, 3, true, false));

        assertThat(changes).as("on and off, not once per packet").containsExactly(true, false);
    }

    @Test
    void newPidsAreAnnouncedAsTheyAppear() throws IOException {
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Integer> discovered = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onPidDiscovered(int pid) {
                discovered.add(pid);
            }
        });

        byte[] data = sample();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }

        assertThat(discovered).as("one event per PID, not per packet").hasSize(5);
        assertThat(discovered).contains(TsPacket.PAT_PID, VIDEO_PID);
    }

    /** A listener that throws must not take the stream down with it. */
    @Test
    void aThrowingListenerDoesNotStopTheStreamOrTheOtherListeners() {
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Integer> survivor = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onPidDiscovered(int pid) {
                throw new IllegalStateException("deliberately broken listener");
            }
        });
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onPidDiscovered(int pid) {
                survivor.add(pid);
            }
        });

        analyzer.consume(packet(VIDEO_PID, 0, true, false));

        assertThat(survivor).containsExactly(VIDEO_PID);
        assertThat(analyzer.stats().packets()).isEqualTo(1);
    }

    @Test
    void consumingNullIsRejectedRatherThanSilentlyIgnored() {
        assertThatThrownBy(() -> new TsAnalyzer().consume(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recordSyncLoss");
    }

    @Test
    void syncLossesAreCountedAndAnnounced() {
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Integer> discarded = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onSyncLost(int bytesDiscarded) {
                discarded.add(bytesDiscarded);
            }
        });

        analyzer.recordSyncLoss(47);

        assertThat(discarded).containsExactly(47);
        assertThat(analyzer.stats().syncLosses()).isEqualTo(1);
        assertThat(analyzer.stats().isHealthy()).isFalse();
    }

    /**
     * A duplicate packet repeats the previous counter, which §2.4.3.3 permits.
     * Read as a gap it computes (previous - expected + 16) % 16 == 15, so one
     * duplicate used to be reported as fifteen lost packets.
     */
    @Test
    void aDuplicatePacketIsNotFifteenLostPackets() {
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Integer> lostCounts = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onContinuityError(int pid, int expected, int actual, int lost) {
                lostCounts.add(lost);
            }
        });

        analyzer.consume(packet(VIDEO_PID, 5, true, false));
        analyzer.consume(packet(VIDEO_PID, 5, true, false)); // the same packet again

        assertThat(lostCounts).as("a duplicate is not loss").isEmpty();
        TsStreamStats stats = analyzer.stats();
        assertThat(stats.packetsLost()).isZero();
        assertThat(stats.continuityErrors()).isZero();
        assertThat(stats.duplicates()).as("counted as what it is").isEqualTo(1);
        assertThat(stats.pid(VIDEO_PID).duplicates()).isEqualTo(1);
    }

    /**
     * An adaptation field can fill a packet exactly, leaving the payload flag set
     * with no bytes behind it. The counter still advances on the flag, so keying
     * the expectation off payload *bytes* reported a spurious error every time.
     */
    @Test
    void aPayloadFlagWithNoPayloadBytesStillAdvancesTheCounter() {
        TsAnalyzer analyzer = new TsAnalyzer();

        analyzer.consume(adaptationFillsPacket(VIDEO_PID, 5));
        analyzer.consume(adaptationFillsPacket(VIDEO_PID, 6));
        analyzer.consume(adaptationFillsPacket(VIDEO_PID, 7));

        assertThat(analyzer.stats().continuityErrors())
                .as("the counter advanced as the flag requires, so nothing is wrong")
                .isZero();
        assertThat(analyzer.stats().isHealthy()).isTrue();
    }

    /** ...and a genuine gap between such packets is still caught. */
    @Test
    void aGapBetweenPayloadFlaggedPacketsIsStillLoss() {
        TsAnalyzer analyzer = new TsAnalyzer();

        analyzer.consume(adaptationFillsPacket(VIDEO_PID, 5));
        analyzer.consume(adaptationFillsPacket(VIDEO_PID, 8));

        assertThat(analyzer.stats().packetsLost()).isEqualTo(2);
    }

    /**
     * The 33-bit PCR base wraps roughly every 26.5 hours. Read naively that is a
     * huge backwards jump, and a 24/7 stream would report a clock discontinuity
     * once a day that never happened.
     */
    @Test
    void thePcrWraparoundIsNotAClockDiscontinuity() {
        long wrap = (1L << 33) * 300;
        TsAnalyzer analyzer = new TsAnalyzer();
        List<Long> discontinuities = new ArrayList<>();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onPcrDiscontinuity(int pid, long previous, long current) {
                discontinuities.add(current);
            }
        });

        // Just before the wrap, then just after it: 40ms later in real time.
        analyzer.consume(withPcr(VIDEO_PID, 0, wrap - 27_000_000L / 50));
        analyzer.consume(withPcr(VIDEO_PID, 1, 27_000_000L / 50));

        assertThat(discontinuities).as("a wrap is not a discontinuity").isEmpty();
        assertThat(analyzer.stats().pid(VIDEO_PID).pcrDiscontinuities()).isZero();
    }

    /** A genuine backwards jump, far from the wrap, is still reported. */
    @Test
    void aRealBackwardsClockJumpIsStillReported() {
        TsAnalyzer analyzer = new TsAnalyzer();

        analyzer.consume(withPcr(VIDEO_PID, 0, 10_000_000_000L));
        analyzer.consume(withPcr(VIDEO_PID, 1, 9_000_000_000L));

        assertThat(analyzer.stats().pid(VIDEO_PID).pcrDiscontinuities()).isEqualTo(1);
    }

    // --- hand-built packets, for damage a clean stream cannot supply

    private static TsPacket packet(int pid, int counter, boolean hasPayload, boolean discontinuity) {
        AdaptationField field = discontinuity ? new AdaptationField(true, false, false, -1) : null;
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                hasPayload ? AdaptationFieldControl.PAYLOAD_ONLY : AdaptationFieldControl.ADAPTATION_ONLY,
                counter, field, 4, hasPayload ? TsPacket.LENGTH - 4 : 0);
    }

    private static TsPacket errored(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], true, false, false, pid, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket scrambled(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 2,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket withPcr(int pid, int counter, long pcr) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, counter,
                new AdaptationField(false, false, false, pcr), 12, TsPacket.LENGTH - 12);
    }

    /** A packet whose control field claims payload while the adaptation field leaves no room. */
    private static TsPacket adaptationFillsPacket(int pid, int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], false, false, false, pid, 0,
                AdaptationFieldControl.ADAPTATION_AND_PAYLOAD, counter, AdaptationField.EMPTY,
                TsPacket.LENGTH, 0);
    }
}