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
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracking where a decoder could start, and whether the span since took damage.
 *
 * <p>This is the figure closest to what someone watching would actually notice.
 * Everything between two random-access points depends on the frame that begins
 * it, so a gap anywhere in a span damages all of it — five packets lost inside
 * one span is a single glitch, while five spread across five spans is five.
 *
 * <p>Counts are cross-checked by walking the fixtures' bytes directly: {@code
 * bframes.ts} was encoded with {@code -g 12} at 25fps over two seconds, which is
 * about four GOPs, and carries five flagged packets on its video PID.
 */
class RandomAccessTest {

    private static final int VIDEO_PID = 0x0100;

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = RandomAccessTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the test classpath", resource).isNotNull();
            return in.readAllBytes();
        }
    }

    private static TsAnalyzer analyze(String resource) throws IOException {
        byte[] data = read(resource);
        TsAnalyzer analyzer = new TsAnalyzer();
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            analyzer.consume(TsPacket.parse(data, offset));
        }
        return analyzer;
    }

    @Test
    void randomAccessPointsAreCountedOnTheVideoTrack() throws IOException {
        PidStats video = analyze("/bframes.ts").stats().pid(VIDEO_PID);

        assertThat(video.randomAccessPoints())
                .as("-g 12 at 25fps over 2s, so roughly four GOPs")
                .isEqualTo(5);
        assertThat(video.averageRandomAccessInterval())
                .as("packets per span, which is how long damage to one persists")
                .isBetween(40.0, 60.0);
    }

    /** A clean stream has spans but no damaged ones. */
    @Test
    void aCleanStreamHasNoDamagedIntervals() throws IOException {
        PidStats video = analyze("/bframes.ts").stats().pid(VIDEO_PID);

        assertThat(video.damagedIntervals()).isZero();
        assertThat(video.damagedIntervalRate()).isZero();
        assertThat(video.packetsSinceRandomAccess())
                .as("we are somewhere inside the last span, not before the first")
                .isNotNegative();
    }

    /**
     * Two gaps inside one span count as one damaged span, not two. A GOP that is
     * already broken does not become twice as broken.
     */
    @Test
    void severalGapsInOneSpanCountAsOneDamagedSpan() throws IOException {
        byte[] data = read("/bframes.ts");
        TsAnalyzer analyzer = new TsAnalyzer();

        int videoSeen = 0;
        boolean afterFirstKeyframe = false;
        int dropped = 0;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == VIDEO_PID) {
                boolean rai = packet.adaptationField() != null && packet.adaptationField().randomAccess();
                if (rai) {
                    // Only start dropping after a keyframe has been seen, so the
                    // damage is attributable to a span rather than to the time
                    // before any span existed.
                    afterFirstKeyframe = true;
                    videoSeen = 0;
                } else if (afterFirstKeyframe) {
                    videoSeen++;
                    // Two separate gaps, both well inside the same span.
                    if ((videoSeen == 5 || videoSeen == 15) && dropped < 2) {
                        dropped++;
                        continue;
                    }
                }
            }
            analyzer.consume(packet);
        }

        assertThat(dropped).as("both gaps must have been injected").isEqualTo(2);
        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.continuityErrors()).as("two separate gaps").isEqualTo(2);
        assertThat(video.damagedIntervals()).as("but one damaged span").isEqualTo(1);
    }

    /** Gaps in different spans damage each of them. */
    @Test
    void gapsInDifferentSpansDamageEachOfThem() throws IOException {
        byte[] data = read("/bframes.ts");
        TsAnalyzer analyzer = new TsAnalyzer();

        int keyframes = 0;
        int sinceKeyframe = 0;
        int dropped = 0;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == VIDEO_PID) {
                boolean rai = packet.adaptationField() != null && packet.adaptationField().randomAccess();
                if (rai) {
                    keyframes++;
                    sinceKeyframe = 0;
                } else if (keyframes >= 1) {
                    sinceKeyframe++;
                    // One gap early in each of the second and third spans.
                    if (sinceKeyframe == 4 && (keyframes == 1 || keyframes == 2)) {
                        dropped++;
                        continue;
                    }
                }
            }
            analyzer.consume(packet);
        }

        assertThat(dropped).isEqualTo(2);
        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.damagedIntervals()).as("one span each").isEqualTo(2);
        assertThat(video.damagedIntervalRate()).isGreaterThan(0.0);
    }

    /**
     * A gap on the keyframe packet belongs to the span that just ended — proved
     * by a span that was already damaged before it.
     *
     * <p>Order of operations, and getting to a case that can tell the two apart
     * took a second attempt. With a single gap both orderings count one damaged
     * span; they disagree only about which span that is, and a total hides that.
     * Two gaps in one span, the second landing exactly on the keyframe that
     * closes it, is the case that separates them: handled in the right order the
     * span is already marked and the count stays at one, while resetting first
     * makes the second gap look like fresh damage to a span whose own packets all
     * arrived intact.
     */
    @Test
    void aGapOnTheKeyframePacketDoesNotDoubleCountAnAlreadyDamagedSpan() throws IOException {
        byte[] data = read("/bframes.ts");

        // Find the keyframe that closes the span we will damage.
        int keyframesSeen = 0;
        int closingKeyframeOffset = -1;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == VIDEO_PID && packet.adaptationField() != null
                    && packet.adaptationField().randomAccess()) {
                keyframesSeen++;
                if (keyframesSeen == 3) {
                    closingKeyframeOffset = offset;
                    break;
                }
            }
        }
        assertThat(closingKeyframeOffset).as("the fixture must have a third keyframe").isPositive();

        TsAnalyzer analyzer = new TsAnalyzer();
        int keyframes = 0;
        int sinceKeyframe = 0;
        int earlyGap = 0;
        int lateGap = 0;
        for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
            TsPacket packet = TsPacket.parse(data, offset);
            if (packet.pid() == VIDEO_PID) {
                boolean rai = packet.adaptationField() != null && packet.adaptationField().randomAccess();
                if (rai) {
                    keyframes++;
                    sinceKeyframe = 0;
                } else if (keyframes == 2) {
                    sinceKeyframe++;
                    // One gap early in the second span...
                    if (sinceKeyframe == 3 && earlyGap == 0) {
                        earlyGap++;
                        continue;
                    }
                    // ...and another in the two packets right before the keyframe
                    // that closes it, so the gap is noticed on the keyframe itself.
                    if (lateGap < 2 && offset >= closingKeyframeOffset - 3 * TsPacket.LENGTH
                            && offset < closingKeyframeOffset) {
                        lateGap++;
                        continue;
                    }
                }
            }
            analyzer.consume(packet);
        }

        assertThat(earlyGap).as("early gap injected").isEqualTo(1);
        assertThat(lateGap).as("late gap injected on the closing keyframe").isPositive();

        PidStats video = analyzer.stats().pid(VIDEO_PID);
        assertThat(video.continuityErrors()).as("two separate gaps").isEqualTo(2);
        assertThat(video.damagedIntervals())
                .as("both are in the same span, so it is damaged once")
                .isEqualTo(1);
    }

    /**
     * Audio carries random-access points constantly, and that is not a defect.
     *
     * <p>I expected the audio track to have none, and it has six across 95
     * packets. Every AAC frame is independently decodable, so nearly every audio
     * PES packet is somewhere a decoder could start, and ffmpeg flags them
     * accordingly. The consequence matters for how these figures are read:
     * damage does not propagate through audio the way it does through a video
     * GOP, so a damaged span means something quite different on each. The metric
     * is worth watching on video and close to noise on audio.
     */
    @Test
    void audioCarriesRandomAccessPointsOnNearlyEveryFrame() throws IOException {
        TsStreamStats stats = analyze("/bframes.ts").stats();
        PidStats audio = stats.pid(0x0101);
        PidStats video = stats.pid(VIDEO_PID);

        assertThat(audio.randomAccessPoints()).isEqualTo(6);
        assertThat(audio.randomAccessPoints())
                .as("one per PES packet, since every AAC frame stands alone")
                .isEqualTo(audio.pesPackets());
        assertThat(audio.averageRandomAccessInterval())
                .as("a far shorter span than video's")
                .isLessThan(video.averageRandomAccessInterval());
    }

    /** A track with no keyframes at all reports no spans rather than dividing by zero. */
    @Test
    void aTrackWithoutRandomAccessPointsReportsNoIntervals() throws IOException {
        // A table PID: no adaptation fields, so no flags of any kind.
        PidStats tables = analyze("/bframes.ts").stats().pid(TsPacket.PAT_PID);

        assertThat(tables.randomAccessPoints()).isZero();
        assertThat(tables.averageRandomAccessInterval()).isZero();
        assertThat(tables.damagedIntervalRate()).isZero();
        assertThat(tables.packetsSinceRandomAccess()).as("never seen one").isEqualTo(-1);
    }
}