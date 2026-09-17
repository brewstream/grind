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
 * The PMT: the tracks of one program, and which of them carries its clock
 * (ISO/IEC 13818-1 §2.4.4.8).
 *
 * <p>Descriptors are skipped rather than parsed — correctly skipped, since their
 * declared lengths are what locate the next stream entry, but not surfaced.
 * Reading them is phase 3; getting the track list right is what a health view
 * needs.
 *
 * @param programNumber which program this describes
 * @param pcrPid        the PID carrying this program's clock, or 0x1FFF when it has none
 * @param streams       the program's tracks, in the order the table lists them
 */
public record ProgramMapTable(int programNumber, int pcrPid, List<ElementaryStream> streams) {

    /**
     * Parses a PMT section body.
     *
     * @param section a section whose table id is {@link TableSection#TABLE_ID_PMT}
     * @return the parsed table, or {@code null} if the body is truncated or its
     *         declared lengths run past the end
     */
    public static ProgramMapTable parse(TableSection section) {
        byte[] body = section.body();
        if (body.length < 4) {
            return null;
        }

        int pcrPid = ((body[0] & 0x1F) << 8) | (body[1] & 0xFF);
        int programInfoLength = ((body[2] & 0x0F) << 8) | (body[3] & 0xFF);
        int cursor = 4 + programInfoLength;
        if (cursor > body.length) {
            return null; // program descriptors claim more than the section holds
        }

        List<ElementaryStream> streams = new ArrayList<>();
        while (cursor + 5 <= body.length) {
            int rawType = body[cursor] & 0xFF;
            int pid = ((body[cursor + 1] & 0x1F) << 8) | (body[cursor + 2] & 0xFF);
            int esInfoLength = ((body[cursor + 3] & 0x0F) << 8) | (body[cursor + 4] & 0xFF);
            cursor += 5 + esInfoLength;
            if (cursor > body.length) {
                return null; // an ES descriptor length ran past the section
            }
            streams.add(new ElementaryStream(pid, StreamType.fromCode(rawType), rawType));
        }

        return new ProgramMapTable(section.tableIdExtension(), pcrPid, List.copyOf(streams));
    }

    /** The track carrying video, or {@code null} — what a dashboard leads with. */
    public ElementaryStream video() {
        return streams.stream()
                .filter(s -> s.streamType().kind() == StreamType.Kind.VIDEO)
                .findFirst()
                .orElse(null);
    }
}