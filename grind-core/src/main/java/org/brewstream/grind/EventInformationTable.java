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

import java.util.ArrayList;
import java.util.List;

/**
 * The EIT present/following: what is on one service now, and what is on next
 * (ETSI EN 300 468 §5.2.4).
 *
 * <p><b>This is one section's worth of events, and a p/f subtable has two.</b>
 * EN 300 468 puts the present event in section 0 and the following one in
 * section 1, so neither section alone answers "what is on now and next" — which
 * is why {@link #present()} and {@link #following()} are only meaningful on the
 * merged view {@link TsAnalyzer#events(int)} assembles, and why the analyzer
 * keeps sections rather than the latest one to arrive.
 *
 * <p>Not every writer splits them. TSDuck, compiling two events into one
 * subtable, produces a single section carrying both — which is what {@code
 * dvb.ts} contains. Ordering sections by number and concatenating their events
 * is correct for both shapes, where taking the last section to arrive would
 * report the following programme as the present one on real broadcast streams
 * and pass every test written against the fixture.
 *
 * <p><b>EIT schedule is deliberately not read.</b> The same PID carries table
 * ids 0x50 to 0x6F, holding the full several-day EPG spread across hundreds of
 * segmented sections, and on a real DVB multiplex it is the largest thing in the
 * stream by some margin. Grind exists to say whether a stream is healthy and
 * what it carries; a week of programme synopses answers neither question, and
 * holding it would cost memory proportional to the broadcaster's ambition
 * rather than to the stream.
 *
 * @param serviceId         which service these events belong to
 * @param transportStreamId the multiplex carrying it
 * @param originalNetworkId who originated that multiplex
 * @param events            the events this section announced, in table order
 */
public record EventInformationTable(
        int serviceId,
        int transportStreamId,
        int originalNetworkId,
        List<Event> events) {

    /** Before the event loop: transport stream id, network id, and two section fields. */
    private static final int HEADER_LENGTH = 6;

    /** The fixed part of each event entry, before its descriptor loop. */
    private static final int EVENT_ENTRY_LENGTH =
            2 + Event.START_TIME_LENGTH + Event.DURATION_LENGTH + 2;

    /**
     * Parses an EIT section body.
     *
     * @param section a section whose table id is {@link TableSection#TABLE_ID_EIT_PF_ACTUAL}
     *                or {@link TableSection#TABLE_ID_EIT_PF_OTHER}
     * @return the parsed table, or {@code null} if the body is truncated or a
     *         declared length runs past the end
     */
    public static EventInformationTable parse(TableSection section) {
        byte[] body = section.body();
        if (body.length < HEADER_LENGTH) {
            return null;
        }

        int transportStreamId = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        int originalNetworkId = ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
        // body[4] is segment_last_section_number and body[5] last_table_id. Both
        // describe how a schedule is split into segments, which is a question
        // only EIT schedule asks, so neither is carried further.

        List<Event> events = new ArrayList<>();
        int cursor = HEADER_LENGTH;
        while (cursor + EVENT_ENTRY_LENGTH <= body.length) {
            int eventId = ((body[cursor] & 0xFF) << 8) | (body[cursor + 1] & 0xFF);
            int timesAt = cursor + 2;
            int statusAt = timesAt + Event.START_TIME_LENGTH + Event.DURATION_LENGTH;

            int statusAndLength = ((body[statusAt] & 0xFF) << 8) | (body[statusAt + 1] & 0xFF);
            RunningStatus runningStatus = RunningStatus.fromCode((statusAndLength >> 13) & 0x07);
            boolean scrambled = (statusAndLength & 0x1000) != 0;
            int descriptorsLength = statusAndLength & 0x0FFF;

            int descriptorsStart = cursor + EVENT_ENTRY_LENGTH;
            cursor = descriptorsStart + descriptorsLength;
            if (cursor > body.length) {
                return null; // an event's descriptors claim more than the section holds
            }

            events.add(new Event(eventId,
                    Event.parseStartTime(body, timesAt),
                    Event.parseDuration(body, timesAt + Event.START_TIME_LENGTH),
                    runningStatus, scrambled,
                    Descriptor.parseLoop(body, descriptorsStart, descriptorsLength)));
        }

        return new EventInformationTable(section.tableIdExtension(), transportStreamId,
                originalNetworkId, List.copyOf(events));
    }

    /** What is on now, or {@code null} when the table announced nothing. */
    public Event present() {
        return events.isEmpty() ? null : events.get(0);
    }

    /** What is on next, or {@code null} when the table announced only the present event. */
    public Event following() {
        return events.size() < 2 ? null : events.get(1);
    }
}
