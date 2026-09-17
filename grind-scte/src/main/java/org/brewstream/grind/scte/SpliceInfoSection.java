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

/**
 * One splice information section: SCTE 35's unit of signalling.
 *
 * <p>These ride in PSI-style sections on their own PID, which the PMT declares
 * with stream type {@code 0x86}. They share the section syntax and the CRC-32 of
 * the PAT and PMT, which is why Grind needed no new assembly machinery to read
 * them.
 *
 * <p><b>The splice point is not where this section sits.</b> A section is
 * transmitted early — a couple of seconds is typical — and names the presentation
 * time at which the splice happens. {@link #spliceTime()} is that time, already
 * adjusted; see {@link SpliceEvent} for the pair of times a reader usually wants.
 *
 * @param commandType     what the section carries, named even when not parsed
 * @param ptsAdjustment   the offset added to every time in this section, in 90 kHz
 *                        units. Downstream equipment sets this when it re-times a
 *                        stream, rather than rewriting each command and its CRC
 * @param tier            the authorization tier, or 0xFFF when unrestricted
 * @param encrypted       whether the command is encrypted. When true nothing beyond
 *                        this header could be read, and the command fields are absent
 * @param spliceInsert    the parsed command when it is a {@code splice_insert}, else null
 * @param timeSignal      the time named by a {@code time_signal}, in 90 kHz units,
 *                        or -1 when this is not one or it carries no time
 */
public record SpliceInfoSection(
        SpliceCommandType commandType,
        long ptsAdjustment,
        int tier,
        boolean encrypted,
        SpliceInsert spliceInsert,
        long timeSignal) {

    /** The table id every splice information section carries. */
    public static final int TABLE_ID = 0xFC;

    /** The clock splice times are counted in, shared with PTS. */
    public static final int TIMESTAMP_RATE_HZ = 90_000;

    /**
     * When the splice happens, in 90 kHz units, with {@link #ptsAdjustment}
     * already applied — or -1 when this section names no time.
     *
     * <p>A {@code splice_insert} marked immediate names no time either: it means
     * "now", and there is nothing to schedule against.
     */
    public long spliceTime() {
        long raw = switch (commandType) {
            case SPLICE_INSERT -> spliceInsert == null ? -1 : spliceInsert.spliceTime();
            case TIME_SIGNAL -> timeSignal;
            default -> -1;
        };
        if (raw < 0) {
            return -1;
        }
        // 33 bits, so the sum wraps rather than growing without bound.
        return (raw + ptsAdjustment) & 0x1FFFFFFFFL;
    }

    /** {@link #spliceTime()} in seconds, or -1 when this section names no time. */
    public double spliceTimeSeconds() {
        long time = spliceTime();
        return time < 0 ? -1 : (double) time / TIMESTAMP_RATE_HZ;
    }
}
