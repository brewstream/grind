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
 * A {@code splice_insert} command: the classic ad break signal.
 *
 * <p>Breaks come in pairs. One command with {@link #outOfNetwork()} true says
 * "leave the programme here", and either a matching command with it false says
 * "come back", or {@link #duration()} says how long to be away and the return is
 * automatic.
 *
 * @param eventId       identifies this splice, and pairs the out with its in
 * @param cancelled     whether this cancels a previously signalled event of the same id
 * @param outOfNetwork  true to leave the programme, false to return to it
 * @param immediate     splice now, with no time given. {@link #spliceTime()} is -1
 * @param spliceTime    when to splice, in 90 kHz units before {@code pts_adjustment}
 *                      is applied, or -1 when immediate or cancelled
 * @param duration      how long the break lasts in 90 kHz units, or -1 when not given
 * @param autoReturn    whether the stream returns on its own when the duration expires,
 *                      rather than waiting for a matching command
 * @param programId     the broadcaster's identifier for the programme being spliced
 * @param availNum      which break this is in a sequence, or 0 when not used
 * @param availsExpected how many breaks that sequence holds, or 0 when not used
 */
public record SpliceInsert(
        long eventId,
        boolean cancelled,
        boolean outOfNetwork,
        boolean immediate,
        long spliceTime,
        long duration,
        boolean autoReturn,
        int programId,
        int availNum,
        int availsExpected) {

    /** Whether a break length was signalled. */
    public boolean hasDuration() {
        return duration >= 0;
    }

    /** The break length in seconds, or -1 when none was signalled. */
    public double durationSeconds() {
        return duration < 0 ? -1 : (double) duration / SpliceInfoSection.TIMESTAMP_RATE_HZ;
    }
}
