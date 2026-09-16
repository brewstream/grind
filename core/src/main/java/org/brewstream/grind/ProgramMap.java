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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the stream contains, assembled from its PAT and PMTs: programs, their
 * tracks, and which PID belongs to what.
 *
 * <p>This is the piece that turns transport-level health into something a person
 * can read. "PID 0x100 lost three packets" needs a lookup to become "the H.264
 * video of program 1 lost three packets", and that lookup is here.
 *
 * @param transportStreamId from the PAT, or -1 before one has arrived
 * @param programs          program number to its map table, in PAT order. A program the
 *                          PAT announced but whose PMT has not arrived yet is absent
 * @param pmtPids           program number to the PID carrying its PMT, known as soon as the
 *                          PAT arrives and therefore ahead of {@code programs}
 */
public record ProgramMap(
        int transportStreamId,
        Map<Integer, ProgramMapTable> programs,
        Map<Integer, Integer> pmtPids) {

    /** The state before any PAT has been seen. */
    public static final ProgramMap EMPTY = new ProgramMap(-1, Map.of(), Map.of());

    /** Whether a PAT has arrived and been parsed. */
    public boolean isKnown() {
        return transportStreamId >= 0;
    }

    /**
     * Describes what a PID carries, for labelling a health figure.
     *
     * @return a description such as {@code "program 1 H.264 / AVC"}, or a
     *         structural label for PSI PIDs, or {@code null} when the PID is not
     *         one this map accounts for
     */
    public String describe(int pid) {
        if (pid == TsPacket.PAT_PID) {
            return "PAT";
        }
        if (pid == TsPacket.NULL_PID) {
            return "null packets";
        }
        for (Map.Entry<Integer, Integer> entry : pmtPids.entrySet()) {
            if (entry.getValue() == pid) {
                return "PMT for program " + entry.getKey();
            }
        }
        for (Map.Entry<Integer, ProgramMapTable> entry : programs.entrySet()) {
            for (ElementaryStream stream : entry.getValue().streams()) {
                if (stream.pid() == pid) {
                    return "program " + entry.getKey() + " " + stream.label();
                }
            }
        }
        return null;
    }

    /** Every track across every known program. */
    public List<ElementaryStream> allStreams() {
        List<ElementaryStream> all = new java.util.ArrayList<>();
        for (ProgramMapTable table : programs.values()) {
            all.addAll(table.streams());
        }
        return List.copyOf(all);
    }

    /** This map with one program's table added or replaced. */
    ProgramMap withProgram(int programNumber, ProgramMapTable table) {
        Map<Integer, ProgramMapTable> updated = new LinkedHashMap<>(programs);
        updated.put(programNumber, table);
        return new ProgramMap(transportStreamId, Map.copyOf(updated), pmtPids);
    }

    /**
     * This map rebuilt around a new PAT, dropping any program the PAT no longer
     * lists. A program that disappears from the PAT is gone, and keeping its
     * stale tracks would have the dashboard reporting on a program that is no
     * longer being carried.
     */
    static ProgramMap fromPat(ProgramAssociationTable pat, ProgramMap previous) {
        Map<Integer, ProgramMapTable> retained = new LinkedHashMap<>();
        for (Integer programNumber : pat.programs().keySet()) {
            ProgramMapTable existing = previous.programs().get(programNumber);
            if (existing != null) {
                retained.put(programNumber, existing);
            }
        }
        return new ProgramMap(pat.transportStreamId(), Map.copyOf(retained), pat.programs());
    }
}