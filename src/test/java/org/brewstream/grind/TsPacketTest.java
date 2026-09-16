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
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing driven by a real transport stream rather than hand-built bytes.
 *
 * <p>{@code sample.ts} is two seconds of H.264 and AAC muxed by ffmpeg, and the
 * expectations here are cross-checked against what {@code ffprobe} independently
 * reports about it — video on PID 0x100, audio on 0x101. Hand-made packets test
 * only that the parser agrees with whoever wrote the test; a real multiplexer's
 * output tests that it agrees with the world, including the stuffing, adaptation
 * fields and PCR placement a real muxer emits and a hand-written fixture would
 * never think to include.
 *
 * <p>Edge cases that a clean local file cannot produce - a bad sync byte, an
 * over-long adaptation field - are built by hand below, because they have to be.
 */
class TsPacketTest {

    private static final int VIDEO_PID = 0x100;
    private static final int AUDIO_PID = 0x101;

    private static byte[] sampleStream() throws IOException {
        try (InputStream in = TsPacketTest.class.getResourceAsStream("/sample.ts")) {
            assertThat(in).as("sample.ts fixture must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private static List<TsPacket> parseAll() throws IOException {
        byte[] data = sampleStream();
        List<TsPacket> packets = new ArrayList<>();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            assertThat(packet).as("packet at offset %d failed to parse - lost alignment", offset).isNotNull();
            packets.add(packet);
        }
        return packets;
    }

    @Test
    void everyPacketInARealStreamParsesAndIsExactly188Bytes() throws IOException {
        byte[] data = sampleStream();

        assertThat(data.length % TsPacket.LENGTH)
                .as("a transport stream is a whole number of 188-byte packets")
                .isZero();
        assertThat(parseAll()).hasSize(data.length / TsPacket.LENGTH);
    }

    /** The PIDs we find must match what ffprobe reports for the same file. */
    @Test
    void theStreamCarriesThePidsFfprobeReports() throws IOException {
        TreeSet<Integer> pids = new TreeSet<>();
        for (TsPacket packet : parseAll()) {
            pids.add(packet.pid());
        }

        assertThat(pids).as("PAT is always on PID 0").contains(TsPacket.PAT_PID);
        assertThat(pids).as("ffprobe reports video on 0x100, audio on 0x101")
                .contains(VIDEO_PID, AUDIO_PID);
    }

    /**
     * The continuity counter increments per packet on a PID and wraps at 16. On a
     * locally muxed file nothing is lost, so any gap here is a parser bug rather
     * than a stream defect - which makes this the strongest correctness check
     * available without a second implementation to compare against.
     */
    @Test
    void continuityCountersAreUnbrokenThroughoutACleanStream() throws IOException {
        Map<Integer, Integer> previous = new HashMap<>();
        int comparisons = 0;

        for (TsPacket packet : parseAll()) {
            if (packet.isNull() || packet.transportErrorIndicator()) {
                continue; // null packets do not participate; errored ones may legitimately jump
            }
            Integer last = previous.put(packet.pid(), packet.continuityCounter());
            if (last == null) {
                continue;
            }
            // The counter only advances on packets carrying a payload; an
            // adaptation-only packet repeats the previous value (2.4.3.3).
            int expected = packet.hasPayload() ? (last + 1) % 16 : last;
            assertThat(packet.continuityCounter())
                    .as("PID 0x%X: counter jumped from %d to %d", packet.pid(), last,
                            packet.continuityCounter())
                    .isEqualTo(expected);
            comparisons++;
        }

        // Without this the test would pass just as happily on a stream it skipped
        // entirely - the failure mode of every fixture-driven test.
        assertThat(comparisons).as("counters actually compared").isGreaterThan(500);
    }

    /**
     * PCR is the stream's master clock, so it must advance. A decreasing PCR
     * would mean the 33-bit base and 9-bit extension were being assembled wrongly
     * - the easiest thing to get subtly wrong in this whole parser, and invisible
     * unless something checks the values make sense as a clock.
     */
    @Test
    void programClockReferencesAdvanceAcrossTheStream() throws IOException {
        List<Long> pcrs = new ArrayList<>();
        for (TsPacket packet : parseAll()) {
            if (packet.pcr() >= 0) {
                pcrs.add(packet.pcr());
            }
        }

        assertThat(pcrs).as("a two-second stream must carry several PCRs").hasSizeGreaterThan(10);
        for (int i = 1; i < pcrs.size(); i++) {
            assertThat(pcrs.get(i))
                    .as("PCR went backwards at sample %d", i)
                    .isGreaterThan(pcrs.get(i - 1));
        }

        // Two seconds of media, so the clock should span roughly that. Loose
        // bounds deliberately: this is checking the scale is right - that the
        // 27MHz conversion is not out by a factor of 300 - not the exact duration.
        double span = (pcrs.get(pcrs.size() - 1) - pcrs.get(0)) / (double) AdaptationField.PCR_RATE_HZ;
        assertThat(span).as("PCR span in seconds").isBetween(0.5, 5.0);
    }

    @Test
    void adaptationFieldsAndPayloadsAccountForTheWholePacket() throws IOException {
        int withAdaptationField = 0;
        for (TsPacket packet : parseAll()) {
            if (packet.adaptationField() != null) {
                withAdaptationField++;
            }
            if (!packet.hasPayload()) {
                continue;
            }
            assertThat(packet.payloadLength())
                    .as("payload must fit within the packet")
                    .isBetween(1, TsPacket.LENGTH - 4);
        }

        assertThat(withAdaptationField)
                .as("the fixture must actually contain adaptation fields for this to mean anything")
                .isGreaterThan(50);
    }

    // --- cases a clean stream cannot produce, so they are built by hand

    @Test
    void aMissingSyncByteReturnsNullSoTheCallerCanResynchronise() {
        byte[] data = new byte[TsPacket.LENGTH];
        data[0] = 0x21; // anything but 0x47

        assertThat(TsPacket.parse(data, 0)).isNull();
    }

    @Test
    void aTruncatedPacketIsAProgrammingErrorNotAStreamError() {
        byte[] data = new byte[TsPacket.LENGTH - 1];
        data[0] = TsPacket.SYNC_BYTE;

        assertThatThrownBy(() -> TsPacket.parse(data, 0))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("188");
    }

    /** A zero-length adaptation field is legal: one byte of stuffing, no flags. */
    @Test
    void aZeroLengthAdaptationFieldIsStuffingNotAnError() {
        byte[] data = new byte[TsPacket.LENGTH];
        data[0] = TsPacket.SYNC_BYTE;
        data[1] = 0x01;
        data[2] = 0x00; // PID 0x100
        data[3] = 0x30; // adaptation field and payload
        data[4] = 0x00; // adaptation field length 0

        TsPacket packet = TsPacket.parse(data, 0);

        assertThat(packet).isNotNull();
        assertThat(packet.adaptationField()).isSameAs(AdaptationField.EMPTY);
        assertThat(packet.adaptationField().hasPcr()).isFalse();
        assertThat(packet.payloadLength()).isEqualTo(TsPacket.LENGTH - 5);
    }

    /**
     * An adaptation field claiming to be longer than the packet. Malformed rather
     * than merely unaligned, but the caller's recovery is the same, so it is
     * reported the same way.
     */
    @Test
    void anAdaptationFieldLongerThanThePacketReturnsNull() {
        byte[] data = new byte[TsPacket.LENGTH];
        data[0] = TsPacket.SYNC_BYTE;
        data[3] = 0x30;
        data[4] = (byte) 200; // longer than the 184 bytes available

        assertThat(TsPacket.parse(data, 0)).isNull();
    }

    /**
     * A PCR flag on a field too short to hold one. Read naively this would take
     * six bytes from whatever follows, so the length is checked rather than the
     * flag trusted.
     */
    @Test
    void aPcrFlagOnATooShortFieldIsIgnoredRatherThanReadingPastIt() {
        byte[] data = new byte[TsPacket.LENGTH];
        data[0] = TsPacket.SYNC_BYTE;
        data[3] = 0x30;
        data[4] = 0x01;        // one byte of adaptation field: just the flags
        data[5] = (byte) 0x10; // PCR flag set, with no room for a PCR

        TsPacket packet = TsPacket.parse(data, 0);

        assertThat(packet).isNotNull();
        assertThat(packet.adaptationField().hasPcr()).isFalse();
        assertThat(packet.pcr()).isEqualTo(-1);
    }

    /** The 33-bit base and 9-bit extension combine as base * 300 + extension. */
    @Test
    void pcrIsAssembledFromA33BitBaseAndA9BitExtension() {
        byte[] data = new byte[TsPacket.LENGTH];
        data[0] = TsPacket.SYNC_BYTE;
        data[3] = 0x20;        // adaptation field only
        data[4] = 0x07;        // flags + 6 PCR bytes
        data[5] = (byte) 0x10; // PCR flag
        // base = 1, extension = 0: bytes are base<<7 across 33 bits, so the low
        // bit of base lands in bit 7 of the fifth byte.
        data[6] = 0x00;
        data[7] = 0x00;
        data[8] = 0x00;
        data[9] = 0x00;
        data[10] = (byte) 0x80; // base low bit set, extension high bit clear
        data[11] = 0x00;

        TsPacket packet = TsPacket.parse(data, 0);

        assertThat(packet).isNotNull();
        assertThat(packet.adaptationField().pcr()).isEqualTo(300L);
        assertThat(packet.adaptationField().pcrSeconds())
                .isEqualTo(300.0 / AdaptationField.PCR_RATE_HZ);
    }
}