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

import java.util.List;

/**
 * An immutable snapshot of a transport stream's health, from
 * {@link TsAnalyzer#stats()}.
 *
 * <p><b>Named after ETSI TR 101 290</b>, the measurement guidelines every DVB
 * monitoring product works from, so these figures can be compared against what a
 * commercial probe says about the same stream rather than only against
 * themselves. Its checks come in three priority tiers; what is implemented here
 * is noted per field, and what is not is listed in the project README rather than
 * left to be discovered.
 *
 * <p>The pull half of Grind's observability, shaped for a periodic sampler: read
 * it on a schedule and publish the gauges, or the deltas between two reads. That
 * is exactly how a Micrometer binding would consume it, which is why nothing here
 * depends on a metrics library — the binding is a few lines in your own code, or
 * a separate module, and never a dependency Grind imposes.
 *
 * @param packets          transport packets seen, including stuffing
 * @param bytes            payload bytes seen
 * @param nullPackets      stuffing packets on the null PID, which carry nothing
 * @param continuityErrors counter jumps across all PIDs that were not announced
 * @param packetsLost      how many packets those jumps account for
 * @param transportErrors  packets flagged corrupt by an upstream demodulator
 * @param duplicates       packets repeating the previous counter, which the spec permits
 * @param pesPackets       PES packets started across all elementary streams
 * @param syncLosses       how many times packet alignment had to be regained.
 *                         TR 101 290 <b>TS_sync_loss</b>, Priority 1
 * @param pcrDiscontinuities unannounced jumps in the program clock, summed across every PID
 *                         carrying one. TR 101 290 <b>PCR_discontinuity_indicator_error</b>,
 *                         Priority 2. A jump the stream flagged deliberately is legal and is
 *                         not counted
 * @param crcErrors        PSI sections discarded for a bad checksum. TR 101 290
 *                         <b>CRC_error</b>, Priority 2 — worse than loss on a video PID,
 *                         because it can leave the stream's structure unknown
 * @param pcrRepetitionErrors intervals between consecutive PCRs longer than TR 101 290's
 *                         40ms, summed across every PID carrying one. <b>Priority 2
 *                         PCR_repetition_error.</b> Counted but deliberately kept out of
 *                         {@link #isHealthy()} and out of errored seconds: it is a
 *                         conformance measure rather than evidence anything was lost, and
 *                         a muxer spacing PCRs at 80ms would otherwise read as broken for
 *                         every second of a perfectly good stream
 * @param ptsErrors        gaps between a track's PTS values longer than TR 101 290's 700ms,
 *                         summed across every track carrying them. <b>Priority 2
 *                         PTS_error.</b> Conformance rather than damage, so like
 *                         {@code pcrRepetitionErrors} it is kept out of {@link #isHealthy()}
 *                         and out of errored seconds
 * @param erroredSeconds   seconds of stream time containing at least one error. <b>The figure
 *                         a broadcast probe reports</b>, and the one that answers "for how long
 *                         was this broken" rather than "how many packets went missing".
 *                         Measured against the stream's own clock, not a wall clock, so a file
 *                         analysed faster than real time still gives the right answer. Zero
 *                         until the first PCR arrives, since errors cannot be placed in time
 *                         before then
 * @param observedSeconds  seconds of stream time seen, so errored seconds has a denominator
 * @param programs         what the stream carries, as far as its tables have revealed
 * @param pids             per-PID detail, in the order the PIDs first appeared
 */
public record TsStreamStats(
        long packets,
        long bytes,
        long nullPackets,
        long continuityErrors,
        long packetsLost,
        long transportErrors,
        long duplicates,
        long pesPackets,
        long syncLosses,
        long crcErrors,
        long pcrDiscontinuities,
        long pcrRepetitionErrors,
        long ptsErrors,
        long erroredSeconds,
        long observedSeconds,
        ProgramMap programs,
        List<PidStats> pids) {

    /**
     * Lost packets as a fraction of what should have arrived — the single number
     * a dashboard leads with.
     */
    public double lossRate() {
        long expected = packets + packetsLost;
        return expected == 0 ? 0 : (double) packetsLost / expected;
    }

    /**
     * Whether anything is wrong: loss, corruption, lost alignment, or a clock
     * that jumped without saying so. A clean stream answers {@code true}, which
     * is the common case and worth making cheap to check.
     *
     * <p>Every condition here also marks an errored second, and deliberately so:
     * a stream reporting healthy beside a non-zero errored-second count would be
     * contradicting itself on the same panel.
     *
     * <p>{@code pcrRepetitionErrors} and {@code ptsErrors} are the deliberate
     * exceptions, and the reason
     * is the distinction this method turns on: these count conditions under which
     * <em>something was lost or corrupted</em>. A PCR or a PTS arriving later
     * than the standard allows loses nothing — it makes the clock harder to
     * recover and presentation harder to schedule. Both are worth reporting, and
     * neither is this.
     */
    public boolean isHealthy() {
        return continuityErrors == 0 && transportErrors == 0 && syncLosses == 0
                && crcErrors == 0 && pcrDiscontinuities == 0;
    }

    /**
     * Errored seconds as a fraction of those observed — the proportion of the
     * stream that was broken.
     */
    public double erroredSecondRate() {
        return observedSeconds == 0 ? 0 : (double) erroredSeconds / observedSeconds;
    }

    /** The stats for one PID, or {@code null} if that PID has not been seen. */
    public PidStats pid(int pid) {
        for (PidStats candidate : pids) {
            if (candidate.pid() == pid) {
                return candidate;
            }
        }
        return null;
    }

    /** Payload bytes excluding stuffing — what the programs actually carry. */
    public long payloadBytes() {
        return bytes;
    }
}