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
 * @param continuityErrors    counter jumps that were not announced as discontinuities
 * @param packetsLost         how many packets those jumps account for
 * @param transportErrors     packets an upstream demodulator flagged as known corrupt
 * @param scrambled           whether the PID was carrying scrambled payload at snapshot time
 * @param lastPcr             the most recent PCR in 27 MHz units, or -1 if this PID carries none
 * @param pcrCount            how many PCRs this PID has carried
 * @param pcrDiscontinuities  unannounced jumps in that clock
 * @param pesPackets        PES packets started on this PID. One frame per packet for video,
 *                          but audio commonly packs many frames into one, so this is a frame
 *                          count only for video and undercounts audio badly
 * @param lastPts           the most recent presentation timestamp in 90 kHz units, or -1
 * @param lastDts           the most recent decode timestamp, or -1 when frames are not reordered
 * @param streamId          the PES stream id last seen, or -1 if this PID carries no PES
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
        long pesPackets,
        long lastPts,
        long lastDts,
        int streamId) {

    /** Whether this PID carries the program clock. */
    public boolean carriesPcr() {
        return pcrCount > 0;
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