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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DVB tables, checked against what TSDuck independently reports about the
 * same file.
 *
 * <p>{@code tstables dvb.ts} says:
 * <pre>
 *   NIT Actual, TID 0x40, PID 0x0010 — Network Id 0xFF01
 *                                      Network Name "FFmpeg"
 *                                      TS 1, ONID 0xFF01, services 1 and 2, both type 1
 *   SDT Actual, TID 0x42, PID 0x0011 — TS Id 1, ONID 0xFF01
 *                                      Service 1 "Brewstream One" / "FFmpeg", running
 *                                      Service 2 "Brewstream Café" / "FFmpeg", running
 *   EIT p/f Actual, TID 0x4E, PID 0x0012 — Service 1, TS 1, ONID 0xFF01
 *                                      Event 1 "The Evening News"
 *                                              2026/09/22 20:00:00 UTC, 00:30:00, running
 *                                      Event 2 "Film: The Long Afternoon"
 *                                              2026/09/22 20:30:00 UTC, 01:00:00, not running
 *   PMT, PID 0x1000 — Program 1, PCR 0x0100
 *                     0x1B AVC 0x0100; 0x0F AAC 0x0101 (eng); 0x0F AAC 0x0102 (fra)
 *   PMT, PID 0x1001 — Program 2, PCR 0x0103; 0x1B AVC 0x0103
 * </pre>
 *
 * Every expectation below is one of those numbers. A parser agreeing with its
 * own test proves nothing; agreeing with a mature external tool on what ffmpeg
 * and TSDuck actually wrote is the claim worth making.
 */
class DvbTablesTest {

    private static final int VIDEO_PID = 0x0100;
    private static final int AUDIO_ENGLISH_PID = 0x0101;
    private static final int AUDIO_FRENCH_PID = 0x0102;
    private static final int SECOND_PROGRAM_VIDEO_PID = 0x0103;

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data;
        try (InputStream in = DvbTablesTest.class.getResourceAsStream(resource)) {
            data = in.readAllBytes();
        }
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    private static TsAnalyzer analyzeDvb() throws IOException {
        return analyze("/dvb.ts");
    }

    // --- SDT ------------------------------------------------------------

    @Test
    void theSdtNamesEveryServiceTheMultiplexCarries() throws IOException {
        ProgramMap programs = analyzeDvb().programs();

        assertThat(programs.services()).containsOnlyKeys(1, 2);
        assertThat(programs.serviceName(1)).isEqualTo("Brewstream One");
        assertThat(programs.serviceName(2)).isEqualTo("Brewstream Café");
    }

    @Test
    void aServiceCarriesItsProviderTypeAndRunningStatus() throws IOException {
        Service service = analyzeDvb().programs().services().get(1);

        assertThat(service.serviceId()).isEqualTo(1);
        assertThat(service.providerName()).isEqualTo("FFmpeg");
        assertThat(service.runningStatus()).isEqualTo(RunningStatus.RUNNING);
        assertThat(service.scrambled()).as("tstables: CA mode free").isFalse();
        assertThat(service.eitSchedule()).as("tstables: EITs no").isFalse();
        assertThat(service.eitPresentFollowing()).as("tstables: EITp/f no").isFalse();
        assertThat(service.description().serviceType())
                .isEqualTo(ServiceDescriptor.TYPE_DIGITAL_TELEVISION);
    }

    /**
     * The whole point of reading an SDT, and the reason the fixture carries two
     * programs: a name has to reach the <em>right</em> one.
     *
     * <p>A lookup that ignored the service id and took whichever service came
     * first would label both programs "Brewstream One" and pass any test written
     * against a single-service stream.
     */
    @Test
    void aPidIsNamedByTheServiceItActuallyBelongsTo() throws IOException {
        ProgramMap programs = analyzeDvb().programs();

        assertThat(programs.describe(VIDEO_PID)).isEqualTo("Brewstream One H.264 / AVC");
        assertThat(programs.describe(AUDIO_ENGLISH_PID))
                .isEqualTo("Brewstream One AAC (ADTS) [eng]");
        assertThat(programs.describe(SECOND_PROGRAM_VIDEO_PID))
                .isEqualTo("Brewstream Café H.264 / AVC");
        assertThat(programs.describe(0x1001)).isEqualTo("PMT for Brewstream Café");
    }

    /**
     * A name that is not ASCII survives.
     *
     * <p>ffmpeg writes a pure-ASCII name with no character-table selector and
     * prefixes a non-ASCII one with {@code 0x15} for UTF-8, so these two
     * services take different paths through {@link DvbText}. The accented one is
     * in the fixture for exactly this: read as the default table the selector
     * byte would be dropped as a control code and the name would come back as
     * "Brewstream CafÃ©".
     */
    @Test
    void aServiceNameOutsideAsciiIsDecodedThroughItsSelector() throws IOException {
        ProgramMap programs = analyzeDvb().programs();

        assertThat(programs.serviceName(2)).isEqualTo("Brewstream Café");
        assertThat(programs.serviceName(2)).doesNotContain("Ã");
    }

    /**
     * A service the SDT stops listing is gone, not remembered.
     *
     * <p>Each SDT section is a complete statement about the services it lists,
     * so a later one that omits a service has decommissioned it. Merging instead
     * of replacing would leave it on a dashboard for as long as the process ran,
     * and no fixture can show that: a three-second capture of a muxer's output
     * never changes its mind. Driven directly for that reason — this is a pure
     * function over a record, not stream bytes a hand-built fixture could teach
     * to agree with a misreading.
     */
    @Test
    void aServiceDroppedFromALaterSdtIsDropped() throws IOException {
        ProgramMap both = analyzeDvb().programs();
        assertThat(both.services()).containsOnlyKeys(1, 2);

        ProgramMap afterwards = both.withServices(List.of(both.services().get(2)));

        assertThat(afterwards.services()).containsOnlyKeys(2);
        assertThat(afterwards.serviceName(1)).isNull();
        assertThat(afterwards.describe(VIDEO_PID))
                .as("program 1 is still carried, and is no longer named")
                .isEqualTo("program 1 H.264 / AVC");
    }

    // --- NIT ------------------------------------------------------------

    @Test
    void theNitNamesTheNetworkAndListsItsTransportStreams() throws IOException {
        NetworkInformationTable nit = analyzeDvb().programs().network();

        assertThat(nit).isNotNull();
        assertThat(nit.networkId()).isEqualTo(0xFF01);
        assertThat(nit.networkName()).isEqualTo("FFmpeg");
        assertThat(nit.transportStreams()).hasSize(1);

        NetworkInformationTable.TransportStream stream = nit.transportStreams().get(0);
        assertThat(stream.transportStreamId()).isEqualTo(1);
        assertThat(stream.originalNetworkId()).isEqualTo(0xFF01);
        assertThat(stream.services())
                .as("tstables: service 1 type 1, service 2 type 1")
                .containsExactly(java.util.Map.entry(1, 1), java.util.Map.entry(2, 1));
    }

    // --- EIT ------------------------------------------------------------

    @Test
    void theEitSaysWhatIsOnNowAndNext() throws IOException {
        EventInformationTable events = analyzeDvb().events(1);

        assertThat(events).isNotNull();
        assertThat(events.serviceId()).isEqualTo(1);
        assertThat(events.transportStreamId()).isEqualTo(1);
        assertThat(events.originalNetworkId()).isEqualTo(0xFF01);

        assertThat(events.present().name()).isEqualTo("The Evening News");
        assertThat(events.present().description().description())
                .isEqualTo("The day's headlines.");
        assertThat(events.following().name()).isEqualTo("Film: The Long Afternoon");
    }

    /**
     * A start time is a Modified Julian Date and three bytes of BCD, neither of
     * which resembles what it means.
     *
     * <p>Pinned to the exact instant rather than to a component of it: the MJD
     * arithmetic is a day count against its own epoch, and getting it wrong by a
     * day or by a leap year produces a date that still looks like a date.
     */
    @Test
    void anEventCarriesItsStartTimeAndDuration() throws IOException {
        EventInformationTable events = analyzeDvb().events(1);

        assertThat(events.present().start()).isEqualTo(Instant.parse("2026-09-22T20:00:00Z"));
        assertThat(events.present().duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(events.present().end()).isEqualTo(Instant.parse("2026-09-22T20:30:00Z"));
        assertThat(events.present().runningStatus()).isEqualTo(RunningStatus.RUNNING);

        assertThat(events.following().start()).isEqualTo(Instant.parse("2026-09-22T20:30:00Z"));
        assertThat(events.following().duration()).isEqualTo(Duration.ofHours(1));
        assertThat(events.following().runningStatus()).isEqualTo(RunningStatus.NOT_RUNNING);
    }

    /**
     * Present and following arrive in separate sections, and both are kept.
     *
     * <p>ETSI TS 101 211 puts the present event in section 0 and the following
     * one in section 1, which is why the fixture is built with TSDuck's
     * {@code --eit-normalization}. A reader holding only the most recent section
     * would answer "what is on now" with the following programme — and would
     * pass every other test here, because each section on its own parses
     * perfectly.
     */
    @Test
    void presentAndFollowingAreMergedAcrossTheirSections() throws IOException {
        EventInformationTable events = analyzeDvb().events(1);

        assertThat(events.events()).hasSize(2);
        assertThat(events.events()).extracting(Event::eventId).containsExactly(1, 2);
        assertThat(events.present().eventId())
                .as("section 0, not whichever section arrived last")
                .isEqualTo(1);
        assertThat(events.following().eventId()).isEqualTo(2);
    }

    /**
     * Joining the stream after section 0 has gone past still answers "what is on
     * now" with the present event.
     *
     * <p>The EIT sections in this fixture arrive interleaved — 0, 1, 1, 0, 0, 0,
     * 1 — so a reader that merged them in arrival order gets the right answer
     * from the start of the file by luck, because section 0 happens to land
     * first. Starting partway through removes the luck: section 1 arrives first,
     * and only ordering by section number still puts the present event first.
     *
     * <p>Which is exactly what a receiver tuning into a live multiplex does.
     * Nothing waits for a table to begin.
     */
    @Test
    void joiningAfterTheFirstSectionStillIdentifiesThePresentEvent() throws IOException {
        byte[] data;
        try (InputStream in = DvbTablesTest.class.getResourceAsStream("/dvb.ts")) {
            data = in.readAllBytes();
        }

        TsAnalyzer analyzer = new TsAnalyzer();
        int joinAt = 40_000 / TsPacket.LENGTH * TsPacket.LENGTH;
        for (int offset = joinAt; offset + TsPacket.LENGTH <= data.length;
                offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }

        EventInformationTable events = analyzer.events(1);
        assertThat(events.events()).extracting(Event::eventId)
                .as("section 1 arrived first, and section order is what decides")
                .containsExactly(1, 2);
        assertThat(events.present().name()).isEqualTo("The Evening News");
        assertThat(events.following().name()).isEqualTo("Film: The Long Afternoon");
    }

    @Test
    void aServiceWithNoEventsAnnouncedHasNone() throws IOException {
        TsAnalyzer analyzer = analyzeDvb();

        assertThat(analyzer.events(2)).as("only service 1 has an EIT in this stream").isNull();
        assertThat(analyzer.events()).containsOnlyKeys(1);
    }

    // --- descriptors on the PMT ------------------------------------------

    @Test
    void anAudioTrackCarriesItsLanguage() throws IOException {
        ProgramMapTable program = analyzeDvb().programs().programs().get(1);

        assertThat(program.streams()).extracting(ElementaryStream::pid)
                .containsExactly(VIDEO_PID, AUDIO_ENGLISH_PID, AUDIO_FRENCH_PID);
        assertThat(program.streams().get(1).language()).isEqualTo("eng");
        assertThat(program.streams().get(2).language()).isEqualTo("fra");
        assertThat(program.streams().get(0).language())
                .as("the video track carries no language descriptor")
                .isNull();
    }

    /**
     * The reason a language belongs in the label at all.
     *
     * <p>Both audio tracks are AAC in the same service, so described by service
     * and codec alone they are the same string, and a dashboard listing one row
     * per PID prints it twice with nothing to choose between them. That is what
     * this fixture reproduces and what the bracketed code fixes.
     */
    @Test
    void twoAudioTracksInOneServiceAreToldApart() throws IOException {
        ProgramMap programs = analyzeDvb().programs();

        assertThat(programs.describe(AUDIO_ENGLISH_PID))
                .isEqualTo("Brewstream One AAC (ADTS) [eng]");
        assertThat(programs.describe(AUDIO_FRENCH_PID))
                .isEqualTo("Brewstream One AAC (ADTS) [fra]");
        assertThat(programs.describe(VIDEO_PID))
                .as("a track the PMT gives no language keeps the description it had")
                .isEqualTo("Brewstream One H.264 / AVC");
    }

    /**
     * Choosing a track by language rather than by position.
     *
     * <p>The French track is second, so anything taking the first audio track
     * gets English and looks correct until the day a stream lists them the other
     * way round.
     */
    @Test
    void aTrackCanBeChosenByLanguage() throws IOException {
        ProgramMapTable program = analyzeDvb().programs().programs().get(1);

        assertThat(program.streamWithLanguage("fra").pid()).isEqualTo(AUDIO_FRENCH_PID);
        assertThat(program.streamWithLanguage("eng").pid()).isEqualTo(AUDIO_ENGLISH_PID);
        assertThat(program.streamWithLanguage("ENG").pid())
                .as("nothing enforces the case a muxer writes")
                .isEqualTo(AUDIO_ENGLISH_PID);
        assertThat(program.streamWithLanguage("deu")).isNull();
        assertThat(program.streamWithLanguage(null)).isNull();
    }

    /**
     * The registration descriptor, which is what says a private stream type is
     * SCTE 35 rather than something else private.
     *
     * <p>Read from {@code splice.ts}, where TSDuck put a real {@code CUEI}
     * identifier at program level alongside the 0x86 stream it describes.
     */
    @Test
    void aProgramsRegistrationIdentifierIsRead() throws IOException {
        ProgramMapTable program = analyze("/splice.ts").programs().programs().get(1);

        assertThat(program.registrationIdentifier()).isEqualTo("CUEI");
        assertThat(program.programDescriptors()).hasSize(1);
        assertThat(program.programDescriptors().get(0).tag())
                .isEqualTo(Descriptor.TAG_REGISTRATION);
    }

    @Test
    void aProgramWithNoProgramLevelDescriptorsHasNoRegistration() throws IOException {
        ProgramMapTable program = analyzeDvb().programs().programs().get(1);

        assertThat(program.programDescriptors()).isEmpty();
        assertThat(program.registrationIdentifier()).isNull();
    }

    // --- health -----------------------------------------------------------

    /**
     * Reading the DVB tables must not change what the stream's health says.
     *
     * <p>Three new PIDs are now assembled and CRC-checked, and if their failures
     * fed the existing counter a corrupt EIT would clear {@code isHealthy()} on
     * a stream that had lost nothing a viewer could see. The counters are
     * separate for that reason; this pins it on a clean stream, and
     * {@link #aCorruptDvbSectionIsCountedWithoutBreakingHealth} pins it on a
     * damaged one.
     */
    @Test
    void readingTheDvbTablesLeavesTheStreamHealthy() throws IOException {
        TsStreamStats stats = analyzeDvb().stats();

        assertThat(stats.isHealthy()).isTrue();
        assertThat(stats.crcErrors()).isZero();
        assertThat(stats.dvbCrcErrors()).isZero();
        assertThat(stats.erroredSeconds()).isZero();
    }

    /**
     * A corrupt SDT section is counted, and costs the stream nothing else.
     *
     * <p>The corruption is applied to a real section's bytes rather than
     * invented: one payload byte of every packet on the SDT PID is flipped,
     * which is what loss on that PID looks like once the CRC is consulted. The
     * name goes away, because a table that failed its checksum is a table the
     * receiver never had — and nothing else moves.
     */
    @Test
    void aCorruptDvbSectionIsCountedWithoutBreakingHealth() throws IOException {
        byte[] data;
        try (InputStream in = DvbTablesTest.class.getResourceAsStream("/dvb.ts")) {
            data = in.readAllBytes();
        }

        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == TsPacket.SDT_PID && packet.hasPayload()) {
                // Flip a byte inside the section body, leaving the header and the
                // transport layer intact so that only the checksum notices.
                // payloadOffset indexes the array handed to parse, not the packet.
                data[packet.payloadOffset() + 10] ^= 0xFF;
                packet = TsPacket.parse(data, offset);
            }
            analyzer.consume(packet);
        }

        TsStreamStats stats = analyzer.stats();
        assertThat(stats.dvbCrcErrors()).isPositive();
        assertThat(stats.crcErrors()).as("the PAT and PMTs are untouched").isZero();
        assertThat(stats.isHealthy()).as("nothing a viewer could see was lost").isTrue();
        assertThat(stats.erroredSeconds()).isZero();
        assertThat(stats.programs().services()).isEmpty();
        assertThat(stats.programs().describe(VIDEO_PID))
                .as("with no usable SDT the program falls back to its number")
                .isEqualTo("program 1 H.264 / AVC");
    }

    // --- listeners ---------------------------------------------------------

    @Test
    void serviceInformationChangesAreAnnouncedSeparatelyFromStructure() throws IOException {
        byte[] data;
        try (InputStream in = DvbTablesTest.class.getResourceAsStream("/dvb.ts")) {
            data = in.readAllBytes();
        }

        List<String> announcements = new ArrayList<>();
        List<Integer> programCounts = new ArrayList<>();
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onServiceInformationChanged(ProgramMap programs) {
                announcements.add(programs.services().size()
                        + " services, network " + (programs.network() == null
                                ? "unknown" : programs.network().networkName()));
            }

            @Override
            public void onProgramsChanged(ProgramMap programs) {
                programCounts.add(programs.programs().size());
            }
        });
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }

        // The SDT lands before the NIT in this stream, so the first announcement
        // already carries both services and the second only adds the network.
        // Both PIDs repeat seven times over three seconds and neither says
        // anything new again - which is the property under test, since a change
        // notification that fires on every repeat is a poll with extra steps.
        assertThat(announcements).containsExactly(
                "2 services, network unknown",
                "2 services, network FFmpeg");
        assertThat(programCounts)
                .as("the PAT, then each PMT, and nothing after")
                .containsExactly(0, 1, 2);
    }

    @Test
    void eitSectionsAreAnnouncedAsTheyArrive() throws IOException {
        byte[] data;
        try (InputStream in = DvbTablesTest.class.getResourceAsStream("/dvb.ts")) {
            data = in.readAllBytes();
        }

        List<String> present = new ArrayList<>();
        TsAnalyzer analyzer = new TsAnalyzer();
        analyzer.addListener(new TsStreamListener() {
            @Override
            public void onEventsChanged(int serviceId, EventInformationTable events) {
                present.add(serviceId + ":" + events.present().name());
            }
        });
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }

        assertThat(present)
                .as("repeated rather than sent on change, so every section fires")
                .isNotEmpty()
                .allMatch(entry -> entry.equals("1:The Evening News"));
    }
}
