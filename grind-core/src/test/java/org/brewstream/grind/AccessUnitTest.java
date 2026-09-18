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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reassembling a track's elementary stream from the packets that carried it.
 *
 * <p>The check that matters here is total rather than sampled: ffmpeg extracts
 * the same elementary stream with {@code -c:v copy -f h264}, and what this
 * assembler produces is compared against it <b>byte for byte</b>. Both fixtures
 * match exactly, which is a stronger guarantee than any assertion written by
 * hand — and one this implementation cannot fake, since ffmpeg has no reason to
 * share its mistakes.
 *
 * <p>Regenerate the references with:
 *
 * <pre>
 * ffmpeg -v error -y -i sample.ts  -c:v copy -f h264 sample.h264
 * ffmpeg -v error -y -i bframes.ts -c:v copy -f h264 bframes.h264
 * </pre>
 */
class AccessUnitTest {

    private static final int VIDEO_PID = 0x0100;

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = AccessUnitTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    /** Every unit a fixture's video track yields, in order. */
    private static List<AccessUnit> unitsOf(String fixture) throws IOException {
        byte[] data = read(fixture);
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        List<AccessUnit> units = new ArrayList<>();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null) {
                continue;
            }
            AccessUnit unit = assembler.consume(packet);
            if (unit != null) {
                units.add(unit);
            }
        }
        AccessUnit last = assembler.flush();
        if (last != null) {
            units.add(last);
        }
        return units;
    }

    /**
     * The whole elementary stream, identical to what ffmpeg extracts.
     *
     * <p>Not a count, not a spot check — every byte of both fixtures.
     */
    @Test
    void reassemblyMatchesFfmpegByteForByte() throws IOException {
        for (String name : new String[]{"sample", "bframes"}) {
            ByteArrayOutputStream ours = new ByteArrayOutputStream();
            for (AccessUnit unit : unitsOf("/" + name + ".ts")) {
                ours.writeBytes(unit.data());
            }

            assertThat(ours.toByteArray())
                    .as("%s reassembled differs from ffmpeg's own extraction", name)
                    .isEqualTo(read("/" + name + ".h264"));
        }
    }

    /** Fifty pictures over two seconds at 25fps, which is what was encoded. */
    @Test
    void everyPictureIsRecovered() throws IOException {
        assertThat(unitsOf("/sample.ts")).hasSize(50);
        assertThat(unitsOf("/bframes.ts")).hasSize(50);
    }

    /** Timing comes from the PES header and survives reassembly. */
    @Test
    void unitsCarryTheirTiming() throws IOException {
        List<AccessUnit> units = unitsOf("/sample.ts");

        assertThat(units).allSatisfy(unit -> {
            assertThat(unit.pid()).isEqualTo(VIDEO_PID);
            assertThat(unit.hasPts()).isTrue();
            assertThat(unit.length()).isPositive();
        });
        assertThat(units.get(0).pts()).isEqualTo(128_090);
    }

    /**
     * With B-frames the decode order differs from the presentation order, so a
     * DTS is carried and is not the PTS.
     */
    @Test
    void reorderedFramesCarryADistinctDecodeTimestamp() throws IOException {
        List<AccessUnit> units = unitsOf("/bframes.ts");

        assertThat(units)
                .as("at least one frame is presented later than it is decoded")
                .anySatisfy(unit -> assertThat(unit.dts()).isNotEqualTo(unit.pts()));

        AccessUnit noDts = unitsOf("/sample.ts").get(0);
        assertThat(noDts.dts()).as("without reordering none is carried").isEqualTo(-1);
        assertThat(noDts.decodeTimestamp())
                .as("absent means it equals the PTS, not that there is none")
                .isEqualTo(noDts.pts());
    }

    /**
     * Keyframes are marked, which is what a consumer segments on.
     *
     * <p>Read from {@code bframes.ts}, which flags five. {@code sample.ts} flags
     * only its first packet despite containing several coded keyframes — the
     * random access indicator is an adaptation-field flag a muxer may or may not
     * set, not something derivable from the pictures. A consumer that segments on
     * it has to cope with a stream that barely uses it, which is worth knowing
     * before building one.
     */
    @Test
    void keyframesAreMarked() throws IOException {
        long marked = unitsOf("/bframes.ts").stream().filter(AccessUnit::randomAccess).count();
        assertThat(marked).isEqualTo(5);

        List<AccessUnit> sparse = unitsOf("/sample.ts");
        assertThat(sparse.stream().filter(AccessUnit::randomAccess).count())
                .as("the same encoder, flagging only the first")
                .isEqualTo(1);
        assertThat(sparse.get(0).randomAccess()).as("a stream starts at one").isTrue();
    }

    /**
     * A unit missing packets is dropped rather than handed over incomplete.
     *
     * <p>Half a picture is worse than none: a decoder given one produces
     * artefacts rather than an error, so the damage would surface as something a
     * viewer sees instead of something a log records.
     */
    @Test
    void aUnitMissingPacketsIsDiscarded() throws IOException {
        byte[] data = read("/sample.ts");
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        int seenOnPid = 0;
        int kept = 0;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null) {
                continue;
            }
            if (packet.pid() == VIDEO_PID) {
                seenOnPid++;
                // Drop one continuation packet from the middle of the third unit.
                if (seenOnPid == 12 && !packet.payloadUnitStart()) {
                    continue;
                }
            }
            if (assembler.consume(packet) != null) {
                kept++;
            }
        }
        if (assembler.flush() != null) {
            kept++;
        }

        assertThat(assembler.discardedForLoss())
                .as("the unit the gap fell inside")
                .isEqualTo(1);
        assertThat(kept).as("the others still come through").isEqualTo(49);
        assertThat(assembler.completed()).isEqualTo(49);
    }

    /** Loss costs one unit, not the rest of the stream. */
    @Test
    void assemblyRecoversAtTheNextUnit() throws IOException {
        byte[] data = read("/sample.ts");
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        int seenOnPid = 0;
        List<AccessUnit> units = new ArrayList<>();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet == null) {
                continue;
            }
            if (packet.pid() == VIDEO_PID && ++seenOnPid == 12 && !packet.payloadUnitStart()) {
                continue;
            }
            AccessUnit unit = assembler.consume(packet);
            if (unit != null) {
                units.add(unit);
            }
        }
        AccessUnit last = assembler.flush();
        if (last != null) {
            units.add(last);
        }

        assertThat(units).as("one lost, the rest intact").hasSize(49);
        assertThat(units).allSatisfy(unit -> assertThat(unit.length()).isPositive());
    }

    /** A packet flagged corrupt upstream damages the unit it falls in. */
    @Test
    void aCorruptPacketDamagesItsUnit() {
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        assembler.consume(pesStart(0, 90_000));
        assembler.consume(corrupt(1));
        AccessUnit finished = assembler.consume(pesStart(2, 93_600));

        assertThat(finished).as("the damaged unit is not handed back").isNull();
        assertThat(assembler.discardedForLoss()).isEqualTo(1);
    }

    /**
     * A duplicate packet adds nothing, and must not be appended twice.
     *
     * <p>ISO/IEC 13818-1 section 2.4.3.3 permits a packet to be sent twice with
     * the same continuity counter and identical payload. It is not a gap — but
     * appending it again would corrupt the unit with a repeated run of bytes,
     * quietly, since nothing about the result looks wrong until a decoder sees it.
     */
    @Test
    void aDuplicatePacketIsNotAppendedTwice() {
        // The same stream twice: once with a duplicated packet, once without.
        // Comparing them says the duplicate contributed nothing, without this
        // test having to know how many bytes a PES header leaves behind.
        AccessUnitAssembler withDuplicate = new AccessUnitAssembler(VIDEO_PID);
        withDuplicate.consume(pesStart(0, 90_000));
        withDuplicate.consume(payload(1, (byte) 0xAB));
        withDuplicate.consume(payload(1, (byte) 0xAB));
        AccessUnit duplicated = withDuplicate.consume(pesStart(2, 93_600));

        AccessUnitAssembler clean = new AccessUnitAssembler(VIDEO_PID);
        clean.consume(pesStart(0, 90_000));
        clean.consume(payload(1, (byte) 0xAB));
        AccessUnit expected = clean.consume(pesStart(2, 93_600));

        assertThat(duplicated)
                .as("a repeated packet carries no new bytes")
                .isEqualTo(expected);
        assertThat(withDuplicate.discardedForLoss())
                .as("and is not a gap either")
                .isZero();
    }

    /** The final unit needs a flush, since only a following unit would end it. */
    @Test
    void theLastUnitNeedsAFlush() {
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        assertThat(assembler.consume(pesStart(0, 90_000))).isNull();
        assertThat(assembler.isAssembling()).isTrue();

        AccessUnit last = assembler.flush();

        assertThat(last).as("nothing in the stream marks it finished").isNotNull();
        assertThat(last.pts()).isEqualTo(90_000);
        assertThat(assembler.isAssembling()).isFalse();
        assertThat(assembler.flush()).as("and it is not returned twice").isNull();
    }

    /** Packets on other PIDs are ignored, so one assembler follows one track. */
    @Test
    void otherTracksAreIgnored() throws IOException {
        byte[] data = read("/multiprogram.ts");
        AccessUnitAssembler assembler = new AccessUnitAssembler(VIDEO_PID);

        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet != null) {
                AccessUnit unit = assembler.consume(packet);
                if (unit != null) {
                    assertThat(unit.pid()).isEqualTo(VIDEO_PID);
                }
            }
        }
        assertThat(assembler.completed()).isPositive();
    }

    /** Units compare by content, so a record holding an array does not fool a test. */
    @Test
    void unitsCompareByValue() {
        AccessUnit one = new AccessUnit(1, 100, -1, true, new byte[]{1, 2, 3});
        AccessUnit same = new AccessUnit(1, 100, -1, true, new byte[]{1, 2, 3});
        AccessUnit other = new AccessUnit(1, 100, -1, true, new byte[]{1, 2, 4});

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(other);
    }

    // --- hand-built packets, for damage a clean fixture cannot supply

    private static TsPacket pesStart(int counter, long pts) {
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
        data[at] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        data[at + 1] = (byte) ((pts >> 22) & 0xFF);
        data[at + 2] = (byte) (0x01 | ((pts >> 14) & 0xFE));
        data[at + 3] = (byte) ((pts >> 7) & 0xFF);
        data[at + 4] = (byte) (0x01 | ((pts << 1) & 0xFE));
        return new TsPacket(data, false, true, false, VIDEO_PID, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket payload(int counter, byte fill) {
        byte[] data = new byte[TsPacket.LENGTH];
        java.util.Arrays.fill(data, 4, TsPacket.LENGTH, fill);
        return new TsPacket(data, false, false, false, VIDEO_PID, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }

    private static TsPacket corrupt(int counter) {
        return new TsPacket(new byte[TsPacket.LENGTH], true, false, false, VIDEO_PID, 0,
                AdaptationFieldControl.PAYLOAD_ONLY, counter, null, 4, TsPacket.LENGTH - 4);
    }
}
