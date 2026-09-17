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

/**
 * What one PID has carried. Counters accumulate for the life of the analyzer, so
 * two snapshots give a rate.
 *
 * @param pid                 the PID these figures describe
 * @param packets             transport packets seen
 * @param bytes               payload bytes seen, excluding headers and adaptation fields
 * @param continuityErrors    counter jumps that were not announced as discontinuities.
 *                            TR 101 290 <b>Continuity_count_error</b>, Priority 1
 * @param packetsLost         how many packets those jumps account for
 * @param transportErrors     packets an upstream demodulator flagged as known corrupt.
 *                            TR 101 290 <b>Transport_error</b>, Priority 2
 * @param scrambled           whether the PID was carrying scrambled payload at snapshot time
 * @param lastPcr             the most recent PCR in 27 MHz units, or -1 if this PID carries none
 * @param pcrCount            how many PCRs this PID has carried
 * @param pcrDiscontinuities  unannounced jumps in that clock. TR 101 290
 *                            <b>PCR_discontinuity_indicator_error</b>, Priority 2
 * @param pcrRepetitionErrors intervals between consecutive PCRs longer than the 40ms ETSI
 *                            TR 101 290 allows. <b>Priority 2 PCR_repetition_error.</b> A
 *                            conformance measure, not a damage one: such a stream decodes
 *                            perfectly, the receiver's clock recovery simply has less to
 *                            work with. Deliberately excluded from errored seconds and from
 *                            {@link TsStreamStats#isHealthy()} for that reason — ffmpeg
 *                            emits a PCR every 80ms by default, so folding this in would
 *                            report most working streams as broken throughout
 * @param maxPcrInterval    the widest gap seen between consecutive PCRs, in 27 MHz units,
 *                          or 0 before two have arrived. More useful than the count: it
 *                          says by how much rather than how often
 * @param pesPackets        PES packets started on this PID. One frame per packet for video,
 *                          but audio commonly packs many frames into one, so this is a frame
 *                          count only for video and undercounts audio badly
 * @param lastPts           the most recent presentation timestamp in 90 kHz units, or -1
 * @param lastDts           the most recent decode timestamp, or -1 when frames are not reordered
 * @param streamId          the PES stream id last seen, or -1 if this PID carries no PES
 * @param randomAccessPoints packets flagged as somewhere a decoder could start — the start of
 *                          a GOP, for video
 * @param packetsSinceRandomAccess how far past the most recent one we are, or -1 if none has
 *                          been seen yet
 * @param damagedGops       groups of pictures that contained at least one error, counted
 *                          once per GOP however many it took. Not a TR 101 290 measure —
 *                          that standard counts errors and seconds, not media structure —
 *                          but the figure closest to what a viewer saw, since everything in
 *                          a GOP depends on the frame that begins it. Meaningful on video;
 *                          on audio nearly every frame is a random-access point, so this
 *                          degenerates into an error count
 * @param erroredSeconds    seconds of stream time in which this PID had at least one error.
 *                          The per-PID form of the figure a broadcast probe reports, counted
 *                          once per second however many errors it holds
 */
public record PidStats(
        int pid,
        long packets,
        long bytes,
        long continuityErrors,
        long packetsLost,
        long transportErrors,
        long duplicates,
        boolean scrambled,
        long lastPcr,
        long pcrCount,
        long pcrDiscontinuities,
        long pcrRepetitionErrors,
        long maxPcrInterval,
        long pesPackets,
        long lastPts,
        long lastDts,
        int streamId,
        long randomAccessPoints,
        long packetsSinceRandomAccess,
        long erroredSeconds,
        long damagedGops) {

    /** The widest gap between consecutive PCRs in milliseconds, or 0 before two have arrived. */
    public double maxPcrIntervalMillis() {
        return maxPcrInterval / (AdaptationField.PCR_RATE_HZ / 1000.0);
    }

    /** Whether this PID carries the program clock. */
    public boolean carriesPcr() {
        return pcrCount > 0;
    }

    /**
     * Average packets per GOP — the stretch between two points a decoder could
     * start from, and so how long damage to one persists before a keyframe
     * resets it. Zero when no random-access point has been seen.
     *
     * <p>Meaningful for video. Nearly every audio frame is independently
     * decodable and therefore its own random-access point, so this reads as a
     * very short interval there and carries no comparable meaning.
     */
    /**
     * Damaged GOPs as a fraction of those seen, or zero before the first
     * random-access point.
     *
     * <p>Closer to "how much of what was shown was broken" than any packet
     * figure: a stream losing one percent of packets in a single burst damages
     * one GOP, while the same one percent spread evenly damages every one.
     */
    public double damagedGopRate() {
        return randomAccessPoints == 0 ? 0.0 : (double) damagedGops / randomAccessPoints;
    }

    public double gopLengthPackets() {
        return randomAccessPoints == 0 ? 0 : (double) packets / randomAccessPoints;
    }

    /** Whether this PID carries an elementary stream, as opposed to tables or stuffing. */
    public boolean carriesPes() {
        return pesPackets > 0;
    }

    /** The most recent presentation timestamp in seconds, or -1. */
    public double lastPtsSeconds() {
        return lastPts < 0 ? -1 : (double) lastPts / PesHeader.TIMESTAMP_RATE_HZ;
    }

    /**
     * Lost packets as a fraction of what should have arrived. Zero for a clean
     * PID; this is the figure a dashboard graphs.
     */
    public double lossRate() {
        long expected = packets + packetsLost;
        return expected == 0 ? 0 : (double) packetsLost / expected;
    }
}