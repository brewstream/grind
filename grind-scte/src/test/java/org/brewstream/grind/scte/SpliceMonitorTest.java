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

package org.brewstream.grind.scte;

import org.brewstream.grind.TsAnalyzer;
import org.brewstream.grind.TsPacket;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Reading splice information out of a real stream.
 *
 * <p>Every expectation here was produced independently by TSDuck, which both
 * built the fixture and read it back with its own {@code splicemonitor}. That
 * matters more for SCTE-35 than for anything else in this project: the spec has
 * enough surface that hand-crafting a fixture would encode the same misreading
 * into the test and the parser at once, and both would agree.
 *
 * <p>{@code splice.ts} is seven seconds of CBR, carrying on PID 500:
 *
 * <ul>
 *   <li>event 1001, out of network at PTS 403,200, lasting two seconds</li>
 *   <li>event 1002, back into network at PTS 583,200</li>
 *   <li>a {@code time_signal} at PTS 673,200</li>
 * </ul>
 *
 * <p>Each is sent twice, which is the muxer's default redundancy rather than an
 * accident of the fixture.
 */
class SpliceMonitorTest {

    private static final int SPLICE_PID = 500;

    /** Drives the fixture through both an analyzer and the monitor, as a caller would. */
    private static List<SpliceEvent> eventsOf(String resource) throws IOException {
        byte[] data;
        try (InputStream in = SpliceMonitorTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            data = in.readAllBytes();
        }

        TsAnalyzer analyzer = new TsAnalyzer();
        SpliceMonitor monitor = new SpliceMonitor();
        List<SpliceEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null) {
                continue;
            }
            analyzer.consume(packet);
            // The tables tell the monitor which PIDs to watch, so it needs no PSI
            // parsing of its own.
            monitor.programs(analyzer.stats().programs());
            monitor.consume(packet);
        }
        return events;
    }

    /** The PMT declares the splice PID, and Grind already knew how to label it. */
    @Test
    void theSplicePidIsFoundFromTheTables() throws IOException {
        byte[] data;
        try (InputStream in = SpliceMonitorTest.class.getResourceAsStream("/splice.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet != null) {
                analyzer.consume(packet);
            }
        }
        SpliceMonitor monitor = new SpliceMonitor();
        monitor.programs(analyzer.stats().programs());

        assertThat(monitor.splicePids()).containsExactly(SPLICE_PID);
        assertThat(analyzer.stats().programs().describe(SPLICE_PID))
                .as("the track is named without this module's help")
                .contains("SCTE-35");
    }

    /** Six sections: three events, each sent twice. */
    @Test
    void everySectionIsReadAndNoneIsUnreadable() throws IOException {
        List<SpliceEvent> events = eventsOf("/splice.ts");

        assertThat(events).hasSize(6);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.pid()).isEqualTo(SPLICE_PID);
            assertThat(event.section().encrypted()).isFalse();
        });
    }

    /** The out point, with the figures TSDuck reported for it. */
    @Test
    void theOutOfNetworkEventMatchesWhatTsduckRead() throws IOException {
        SpliceInsert out = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::spliceInsert)
                .filter(insert -> insert != null && insert.eventId() == 1001)
                .findFirst()
                .orElseThrow();

        assertThat(out.outOfNetwork()).isTrue();
        assertThat(out.immediate()).isFalse();
        assertThat(out.cancelled()).isFalse();
        assertThat(out.spliceTime()).isEqualTo(403_200);
        assertThat(out.duration()).isEqualTo(180_000);
        assertThat(out.durationSeconds()).isCloseTo(2.0, within(0.001));
        assertThat(out.autoReturn()).isTrue();
        assertThat(out.programId()).isEqualTo(1);
        assertThat(out.availNum()).isEqualTo(1);
        assertThat(out.availsExpected()).isEqualTo(2);
    }

    /** The return, which carries no duration — the shorter of the two commands. */
    @Test
    void theReturnEventCarriesNoDuration() throws IOException {
        SpliceInsert in = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::spliceInsert)
                .filter(insert -> insert != null && insert.eventId() == 1002)
                .findFirst()
                .orElseThrow();

        assertThat(in.outOfNetwork()).isFalse();
        assertThat(in.spliceTime()).isEqualTo(583_200);
        assertThat(in.hasDuration()).isFalse();
        assertThat(in.durationSeconds()).isEqualTo(-1);
    }

    /** A time_signal names a moment and nothing else. */
    @Test
    void theTimeSignalIsReadAsAPointInTime() throws IOException {
        SpliceInfoSection signal = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .filter(section -> section.commandType() == SpliceCommandType.TIME_SIGNAL)
                .findFirst()
                .orElseThrow();

        assertThat(signal.timeSignal()).isEqualTo(673_200);
        assertThat(signal.spliceInsert()).isNull();
        assertThat(signal.spliceTime()).isEqualTo(673_200);
    }

    /**
     * The two times, which is what this view exists for.
     *
     * <p>Every section arrives before the moment it describes. TSDuck reported an
     * actual pre-roll of about 1.7 seconds for these, injected two seconds ahead
     * and rounded by where a packet would fit.
     */
    @Test
    void everyEventArrivesBeforeTheSpliceItDescribes() throws IOException {
        List<SpliceEvent> events = eventsOf("/splice.ts");

        assertThat(events).allSatisfy(event -> {
            assertThat(event.arrivalSeconds())
                    .as("the clock was running by the time any of these arrived")
                    .isGreaterThan(0);
            assertThat(event.preRollSeconds())
                    .as("a warning that arrives after the event is no warning: %s", event.describe())
                    .isGreaterThan(0);
            assertThat(event.spliceSeconds())
                    .isGreaterThan(event.arrivalSeconds());
        });

        double firstPreRoll = events.get(0).preRollSeconds();
        assertThat(firstPreRoll)
                .as("TSDuck measured about 1.7s of pre-roll on the first copy")
                .isBetween(1.0, 2.5);
    }

    /** Duplicates are distinguishable, since each event is sent twice. */
    @Test
    void repeatedCopiesOfOneEventAreDistinguishable() throws IOException {
        List<SpliceEvent> copies = eventsOf("/splice.ts").stream()
                .filter(event -> event.section().spliceInsert() != null
                        && event.section().spliceInsert().eventId() == 1001)
                .toList();

        assertThat(copies).as("sent twice for redundancy").hasSize(2);
        assertThat(copies.get(0).sequence()).isLessThan(copies.get(1).sequence());
        assertThat(copies.get(0).arrivalPcr())
                .as("the second copy arrives later, closer to the splice point")
                .isLessThan(copies.get(1).arrivalPcr());
        assertThat(copies.get(0).preRollSeconds())
                .isGreaterThan(copies.get(1).preRollSeconds());
    }

    /** A row a dashboard could print without further work. */
    @Test
    void anEventDescribesItselfInOneLine() throws IOException {
        List<String> lines = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::describe)
                .distinct()
                .toList();

        assertThat(lines).containsExactly(
                "event 1001 out for 2.0s",
                "event 1002 in",
                "time_signal");
    }

    /** A stream with no splice information yields nothing, and says so quietly. */
    @Test
    void aStreamWithoutSpliceInformationYieldsNothing() throws IOException {
        List<SpliceEvent> events = eventsOf("/sample.ts");

        assertThat(events).isEmpty();
    }
}
