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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * One programme announced in the EIT (ETSI EN 300 468 §5.2.4).
 *
 * @param eventId      the event's id within its service
 * @param start        when it starts, in UTC, or {@code null} when the stream
 *                     declared the time undefined
 * @param duration     how long it runs, or {@link Duration#ZERO} when undefined
 * @param runningStatus whether this is the programme currently going out
 * @param scrambled    the {@code free_CA_mode} flag: the event is access-controlled
 * @param descriptors  everything the EIT said about it, uninterpreted
 */
public record Event(
        int eventId,
        Instant start,
        Duration duration,
        RunningStatus runningStatus,
        boolean scrambled,
        List<Descriptor> descriptors) {

    /**
     * The days between the Modified Julian Date epoch and the Unix one:
     * MJD 40587 is 1970-01-01.
     */
    private static final int MJD_AT_UNIX_EPOCH = 40587;

    /** {@code start_time} is five bytes, and {@code duration} three. */
    static final int START_TIME_LENGTH = 5;
    static final int DURATION_LENGTH = 3;

    /**
     * The first description of the event, or {@code null} when it carries none.
     *
     * <p>First rather than only: an event announced in several languages carries
     * one {@code short_event_descriptor} per language, and which of them is
     * wanted depends on the caller. Filter {@link #descriptors()} to choose.
     */
    public ShortEventDescriptor description() {
        return ShortEventDescriptor.from(
                Descriptor.find(descriptors, Descriptor.TAG_SHORT_EVENT));
    }

    /** The programme's title, or {@code null} when the event carries no description. */
    public String name() {
        ShortEventDescriptor descriptor = description();
        return descriptor == null ? null : descriptor.eventName();
    }

    /** When it ends, or {@code null} when the start time was undefined. */
    public Instant end() {
        return start == null ? null : start.plus(duration);
    }

    /**
     * Decodes a {@code start_time}: two bytes of Modified Julian Date followed by
     * three of BCD time, all UTC.
     *
     * <p>The MJD half is the part that goes wrong quietly. It is a day count
     * with its own epoch, not a year and a month, and the arithmetic EN 300 468
     * gives in Annex C is a chain of truncating divisions that <em>looks</em>
     * like it can be simplified and cannot. Converting through the Unix epoch
     * instead, as here, is both shorter and exact: the two epochs differ by a
     * whole number of days, so it is one subtraction.
     *
     * @return the instant, or {@code null} when all five bytes are 0xFF, which
     *         is how the standard says a time is undefined
     */
    static Instant parseStartTime(byte[] data, int offset) {
        if (offset + START_TIME_LENGTH > data.length) {
            return null;
        }
        boolean undefined = true;
        for (int i = 0; i < START_TIME_LENGTH; i++) {
            if ((data[offset + i] & 0xFF) != 0xFF) {
                undefined = false;
                break;
            }
        }
        if (undefined) {
            return null;
        }

        int mjd = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        int hours = bcd(data[offset + 2]);
        int minutes = bcd(data[offset + 3]);
        int seconds = bcd(data[offset + 4]);
        if (hours > 23 || minutes > 59 || seconds > 59) {
            return null; // not valid BCD, so not a time
        }

        LocalDate date = LocalDate.ofEpochDay(mjd - (long) MJD_AT_UNIX_EPOCH);
        return LocalDateTime.of(date, java.time.LocalTime.of(hours, minutes, seconds))
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Decodes a {@code duration}: three bytes of BCD, hours, minutes and seconds.
     *
     * @return the duration, or {@link Duration#ZERO} when the bytes are not valid
     *         BCD — which includes the all-0xFF form the standard uses for
     *         "undefined"
     */
    static Duration parseDuration(byte[] data, int offset) {
        if (offset + DURATION_LENGTH > data.length) {
            return Duration.ZERO;
        }
        int hours = bcd(data[offset]);
        int minutes = bcd(data[offset + 1]);
        int seconds = bcd(data[offset + 2]);
        if (minutes > 59 || seconds > 59 || hours > 99) {
            return Duration.ZERO;
        }
        return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
    }

    /**
     * One byte of binary-coded decimal.
     *
     * <p>Returns a value above any legal field when a nibble is not a digit, so
     * the callers' range checks reject it. 0xFF becomes 165, which is not a
     * valid hour, minute or second — that is what makes the "undefined" form
     * fall out of the range check rather than needing a case of its own.
     */
    private static int bcd(byte value) {
        int high = (value >> 4) & 0x0F;
        int low = value & 0x0F;
        return high * 10 + low;
    }
}
