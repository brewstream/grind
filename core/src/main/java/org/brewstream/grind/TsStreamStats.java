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
 * @param syncLosses       how many times packet alignment had to be regained
 * @param pids             per-PID detail, in the order the PIDs first appeared
 */
public record TsStreamStats(
        long packets,
        long bytes,
        long nullPackets,
        long continuityErrors,
        long packetsLost,
        long transportErrors,
        long syncLosses,
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
     * Whether anything is wrong: loss, corruption, or lost alignment. A stream
     * that has never dropped a packet answers false, and that is the common case
     * worth making cheap to check.
     */
    public boolean isHealthy() {
        return continuityErrors == 0 && transportErrors == 0 && syncLosses == 0;
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