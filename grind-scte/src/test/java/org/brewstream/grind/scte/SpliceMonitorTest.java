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

import org.brewstream.grind.ProgramMap;
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
 *   <li>{@code splice_insert} 1001, out of network at PTS 403,200, lasting two seconds</li>
 *   <li>{@code time_signal} at PTS 493,200 with a Provider Placement Opportunity
 *       Start, two seconds long, carrying an Ad-ID and forbidding web delivery</li>
 *   <li>{@code splice_insert} 1002, back into network at PTS 583,200</li>
 *   <li>{@code time_signal} at PTS 628,200 with the matching Placement Opportunity End</li>
 *   <li>a bare {@code time_signal} at PTS 673,200, carrying no descriptor</li>
 * </ul>
 *
 * <p>Each is sent twice, which is the muxer's default redundancy rather than an
 * accident of the fixture. The two styles sit side by side deliberately: the
 * older {@code splice_insert} and the modern {@code time_signal} with a
 * segmentation descriptor beside it.
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

    /** Ten sections: five events, each sent twice. */
    @Test
    void everySectionIsReadAndNoneIsUnreadable() throws IOException {
        List<SpliceEvent> events = eventsOf("/splice.ts");

        assertThat(events).as("five events, each sent twice").hasSize(10);
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
                .filter(section -> section.segmentations().isEmpty())
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

    }

    /**
     * Pre-roll is measured against the latest video PTS, as TSDuck's {@code splicemonitor}
     * does: its "time to event" for every copy that carries an event id, in arrival order
     * (the two bare {@code time_signal}s carry none and TSDuck does not list them). Within
     * one frame, 40 ms at 25 fps: with B-frames the latest PTS steps by a frame either way.
     */
    @Test
    void preRollMatchesWhatTsduckMeasured() throws IOException {
        double[] tsduck = {1.714, 0.794, 1.789, 1.114, 1.714, 1.974, 1.114, 1.174};
        List<SpliceEvent> events = eventsOf("/splice.ts");

        assertThat(events).allSatisfy(event ->
                assertThat(event.preRollBasis()).isEqualTo(PreRollBasis.VIDEO_PTS));
        List<SpliceEvent> withIds = events.stream()
                .filter(event -> event.section().spliceInsert() != null || event.section().segmentation() != null)
                .toList();
        assertThat(withIds).hasSize(tsduck.length);
        for (int i = 0; i < tsduck.length; i++) {
            assertThat(withIds.get(i).preRollSeconds()).as("%s, copy %d", withIds.get(i).describe(), i)
                    .isCloseTo(tsduck[i], within(0.040));
        }
    }

    /**
     * With no video in the program, pre-roll falls back to the PCR. {@code splice-audio.ts}
     * carries event 1001 out twice; 403,200 / 90 kHz minus the PCR at arrival is 2.651122 s
     * and 1.147122 s, computed from the raw packets outside Grind.
     */
    @Test
    void fallsBackToThePcrWithoutVideo() throws IOException {
        List<SpliceEvent> events = eventsOf("/splice-audio.ts");

        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.arrivalVideoPts()).isEqualTo(-1);
            assertThat(event.preRollBasis()).isEqualTo(PreRollBasis.PCR);
        });
        assertThat(events.get(0).preRollSeconds()).isCloseTo(2.651_122, within(0.000_01));
        assertThat(events.get(1).preRollSeconds()).isCloseTo(1.147_122, within(0.000_01));
    }

    /**
     * A program map that changes without touching the video track, as when the SDT
     * arrives, keeps the video PTS already seen: a cue right after it is still measured
     * against video.
     */
    @Test
    void keepsTheVideoPtsAcrossAMapChange() throws IOException {
        byte[] data;
        try (InputStream in = SpliceMonitorTest.class.getResourceAsStream("/splice.ts")) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        SpliceMonitor monitor = new SpliceMonitor();
        List<SpliceEvent> events = new ArrayList<>();
        monitor.addListener(events::add);
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length && events.isEmpty(); offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            analyzer.consume(packet);
            ProgramMap map = analyzer.stats().programs();
            monitor.programs(map);
            if (packet.pid() == SPLICE_PID) {
                // The same tracks under a different transport stream id: a change the
                // video track is not part of.
                monitor.programs(new ProgramMap(map.transportStreamId() + 1, map.programs(), map.pmtPids(),
                        map.services(), map.network()));
            }
            monitor.consume(packet);
        }

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().preRollBasis()).isEqualTo(PreRollBasis.VIDEO_PTS);
    }

    /** Both clocks wrap at 2^33; the pre-roll is the short way round, either direction. */
    @Test
    void preRollIsTakenAcrossATimestampWrap() {
        long wrap = 1L << 33;
        SpliceEvent ahead = new SpliceEvent(SPLICE_PID, timeSignal(900), -1, 1, wrap - 90_000);
        assertThat(ahead.preRollSeconds()).as("1 s before the wrap, splice 10 ms after it").isCloseTo(1.01, within(1e-9));

        SpliceEvent late = new SpliceEvent(SPLICE_PID, timeSignal(wrap - 900), -1, 2, 900);
        assertThat(late.preRollSeconds()).as("arrived 20 ms after its splice").isCloseTo(-0.02, within(1e-9));

        SpliceEvent byPcr = new SpliceEvent(SPLICE_PID, timeSignal(900), (wrap - 90_000) * 300, 3);
        assertThat(byPcr.preRollBasis()).isEqualTo(PreRollBasis.PCR);
        assertThat(byPcr.preRollSeconds()).isCloseTo(1.01, within(1e-9));
    }

    @Test
    void noTimeMeansNoPreRoll() {
        SpliceEvent untimed = new SpliceEvent(SPLICE_PID, timeSignal(-1), 27_000_000, 1, 90_000);
        assertThat(untimed.preRollSeconds()).isEqualTo(-1);
        assertThat(untimed.preRollBasis()).isEqualTo(PreRollBasis.NONE);

        SpliceEvent noClock = new SpliceEvent(SPLICE_PID, timeSignal(900), -1, 2);
        assertThat(noClock.preRollBasis()).isEqualTo(PreRollBasis.NONE);
    }

    private static SpliceInfoSection timeSignal(long pts) {
        return new SpliceInfoSection(SpliceCommandType.TIME_SIGNAL, 0, 0xFFF, false, null, pts, List.of());
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
                "Provider Placement Opportunity Start [ABCD0123456H] for 2.0s",
                "event 1002 in",
                "Provider Placement Opportunity End [ABCD0123456H]",
                "time_signal");
    }

    /**
     * The descriptor is what gives a {@code time_signal} its meaning.
     *
     * <p>Every figure here was read back by TSDuck from the same bytes.
     */
    @Test
    void theSegmentationDescriptorCarriesTheMeaning() throws IOException {
        SegmentationDescriptor start = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::segmentation)
                .filter(segmentation -> segmentation != null
                        && segmentation.type() == SegmentationType.PROVIDER_PLACEMENT_OPPORTUNITY_START)
                .findFirst()
                .orElseThrow();

        assertThat(start.eventId()).isEqualTo(2001);
        assertThat(start.cancelled()).isFalse();
        assertThat(start.durationSeconds()).isCloseTo(2.0, within(0.001));
        assertThat(start.segmentNum()).isEqualTo(1);
        assertThat(start.segmentsExpected()).isEqualTo(1);
        assertThat(start.type().isStart()).isTrue();
        assertThat(start.type().isPlacementOpportunity())
                .as("this is the type a downstream ad server acts on")
                .isTrue();
    }

    /** An Ad-ID is ASCII on the wire, and comes back as text rather than hex. */
    @Test
    void anAdIdUpidReadsAsText() throws IOException {
        SegmentationDescriptor start = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::segmentation)
                .filter(segmentation -> segmentation != null && !segmentation.cancelled())
                .findFirst()
                .orElseThrow();

        assertThat(start.upidType()).as("Ad-ID").isEqualTo(0x03);
        assertThat(start.upidText()).isEqualTo("ABCD0123456H");
    }

    /**
     * Delivery restrictions, stated positively.
     *
     * <p>The fixture forbids web delivery and allows everything else. On the wire
     * {@code no_regional_blackout_flag} is set, meaning no blackout applies, and
     * this reports {@code regionalBlackout} false — the same fact the other way
     * up, so that every flag here reads as a restriction.
     */
    @Test
    void deliveryRestrictionsAreReportedAsRestrictions() throws IOException {
        SegmentationDescriptor start = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::segmentation)
                .filter(segmentation -> segmentation != null && !segmentation.cancelled())
                .findFirst()
                .orElseThrow();

        assertThat(start.deliveryRestricted()).isTrue();
        assertThat(start.webDeliveryAllowed()).as("the fixture forbids it").isFalse();
        assertThat(start.regionalBlackout()).isFalse();
        assertThat(start.archiveAllowed()).isTrue();
    }

    /** Start and end share an event id, which is how a break is matched to its return. */
    @Test
    void aPlacementOpportunityPairsByEventId() throws IOException {
        List<SegmentationDescriptor> pair = eventsOf("/splice.ts").stream()
                .map(SpliceEvent::section)
                .map(SpliceInfoSection::segmentation)
                .filter(segmentation -> segmentation != null && segmentation.eventId() == 2001)
                .distinct()
                .toList();

        assertThat(pair).extracting(SegmentationDescriptor::type)
                .containsExactly(SegmentationType.PROVIDER_PLACEMENT_OPPORTUNITY_START,
                        SegmentationType.PROVIDER_PLACEMENT_OPPORTUNITY_END);
        assertThat(pair.get(0).type().isStart()).isTrue();
        assertThat(pair.get(1).type().isStart()).isFalse();
        assertThat(pair.get(1).hasDuration())
                .as("the end carries no duration; the start already said how long")
                .isFalse();
    }

    /** A stream with no splice information yields nothing, and says so quietly. */
    @Test
    void aStreamWithoutSpliceInformationYieldsNothing() throws IOException {
        List<SpliceEvent> events = eventsOf("/sample.ts");

        assertThat(events).isEmpty();
    }
}
