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

import org.brewstream.grind.AdaptationField;

/**
 * One splice section as something a person can read: what it says, when it
 * arrived, and when it takes effect.
 *
 * <p><b>Those last two are different times, and that is the whole point of this
 * type.</b> A splice section is transmitted ahead of the event it describes —
 * two seconds is typical, and the gap exists so downstream equipment can get
 * ready. A view that shows only arrival claims the break is happening while the
 * programme is still running; one that shows only the splice time cannot tell
 * you whether the warning came in time.
 *
 * @param pid        the splice PID this arrived on
 * @param section    what the section says
 * @param arrivalPcr the program clock when it arrived, in 27 MHz units, or -1 if no
 *                   clock had been seen yet
 * @param sequence   position in the order received, from 1. Sections are commonly
 *                   sent more than once for redundancy, so this distinguishes two
 *                   copies of one event
 */
public record SpliceEvent(int pid, SpliceInfoSection section, long arrivalPcr, long sequence) {

    /** When it arrived, in seconds of stream time, or -1 if the clock was not yet running. */
    public double arrivalSeconds() {
        return arrivalPcr < 0 ? -1 : (double) arrivalPcr / AdaptationField.PCR_RATE_HZ;
    }

    /** When the splice takes effect, in seconds, or -1 when the section names no time. */
    public double spliceSeconds() {
        return section.spliceTimeSeconds();
    }

    /**
     * How long the warning gave, in seconds — the gap between arrival and the
     * splice point.
     *
     * <p>Negative means the section arrived after the moment it describes, which
     * is worth surfacing rather than clamping: it means whatever was meant to act
     * on it could not have.
     *
     * @return the pre-roll, or -1 when either time is unknown
     */
    public double preRollSeconds() {
        if (arrivalPcr < 0 || section.spliceTime() < 0) {
            return -1;
        }
        return spliceSeconds() - arrivalSeconds();
    }

    /** A one-line description, the way a dashboard row would read. */
    public String describe() {
        if (section.encrypted()) {
            return "encrypted " + section.commandType().label();
        }
        SpliceInsert insert = section.spliceInsert();
        if (insert == null) {
            return section.commandType().label();
        }
        if (insert.cancelled()) {
            return "cancel event " + insert.eventId();
        }
        String direction = insert.outOfNetwork() ? "out" : "in";
        String length = insert.hasDuration()
                ? String.format(" for %.1fs", insert.durationSeconds())
                : "";
        return "event " + insert.eventId() + " " + direction + length;
    }
}
