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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the stream contains, assembled from its tables: programs, their tracks,
 * which PID belongs to what, and — where the stream is DVB — what its services
 * are called.
 *
 * <p>This is the piece that turns transport-level health into something a person
 * can read. "PID 0x100 lost three packets" needs a lookup to become "the H.264
 * video of Brewstream One lost three packets", and that lookup is here.
 *
 * <p>The PSI half (PAT and PMT) and the DVB half (SDT and NIT) are filled
 * independently and neither waits for the other. A stream with no SDT is
 * complete without one — service names are a DVB convention, and plenty of
 * contribution feeds carry none — so every accessor here degrades to the program
 * number rather than to null.
 *
 * @param transportStreamId from the PAT, or -1 before one has arrived
 * @param programs          program number to its map table, in PAT order. A program the
 *                          PAT announced but whose PMT has not arrived yet is absent
 * @param pmtPids           program number to the PID carrying its PMT, known as soon as the
 *                          PAT arrives and therefore ahead of {@code programs}
 * @param services          service id to what the SDT said about it. A service id is the
 *                          same number as a program number (EN 300 468 §5.2.3), which is
 *                          what makes this joinable with {@code programs}. Empty until an
 *                          SDT arrives, and on a stream that carries none it stays empty
 * @param network           what the NIT said about the network carrying this multiplex,
 *                          or {@code null} before one has arrived
 */
public record ProgramMap(
        int transportStreamId,
        Map<Integer, ProgramMapTable> programs,
        Map<Integer, Integer> pmtPids,
        Map<Integer, Service> services,
        NetworkInformationTable network) {

    /** The state before any PAT has been seen. */
    public static final ProgramMap EMPTY = new ProgramMap(-1, Map.of(), Map.of(), Map.of(), null);

    /** Whether a PAT has arrived and been parsed. */
    public boolean isKnown() {
        return transportStreamId >= 0;
    }

    /**
     * Describes what a PID carries, for labelling a health figure.
     *
     * <p>Named by its service where the SDT has supplied a name, and by its
     * program number otherwise. The shape of the string changes with it —
     * {@code "Brewstream One H.264 / AVC"} against {@code "program 1 H.264 /
     * AVC"} — because a name and a number want different framing, and
     * {@code "program Brewstream One"} reads like a mistake.
     *
     * <p>A track whose PMT announces a language carries it in brackets:
     * {@code "Brewstream One AAC (ADTS) [eng]"}. Without it a multilingual
     * service's audio tracks describe identically and a dashboard cannot tell
     * them apart — which is the whole of what an ISO 639 descriptor is for.
     * Brackets rather than parentheses because a codec label may already end in
     * them, and {@code "AAC (ADTS) (eng)"} reads as one nested thing rather than
     * two separate facts. The code is not translated to a language name: the
     * three letters are what the stream actually said, and every other figure
     * here reports the wire rather than an interpretation of it.
     *
     * <p>The first code where a track announces several — the rare case, and
     * {@link ElementaryStream#languages()} is the accessor that does not guess.
     *
     * @return a description such as {@code "Brewstream One H.264 / AVC"}, or a
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
        if (pid == TsPacket.NIT_PID) {
            return "NIT";
        }
        if (pid == TsPacket.SDT_PID) {
            return "SDT";
        }
        if (pid == TsPacket.EIT_PID) {
            return "EIT";
        }
        for (Map.Entry<Integer, Integer> entry : pmtPids.entrySet()) {
            if (entry.getValue() == pid) {
                return "PMT for " + describeProgram(entry.getKey());
            }
        }
        for (Map.Entry<Integer, ProgramMapTable> entry : programs.entrySet()) {
            for (ElementaryStream stream : entry.getValue().streams()) {
                if (stream.pid() == pid) {
                    return describeProgram(entry.getKey()) + " " + describeStream(stream);
                }
            }
        }
        return null;
    }

    /** A track's own part of the description: what it carries, and in what language. */
    private static String describeStream(ElementaryStream stream) {
        String language = stream.language();
        return language == null ? stream.label() : stream.label() + " [" + language + "]";
    }

    /**
     * What to call a program: its service name where there is one, and
     * {@code "program N"} where there is not.
     */
    public String describeProgram(int programNumber) {
        String name = serviceName(programNumber);
        return name != null ? name : "program " + programNumber;
    }

    /**
     * The name of the service carrying this program, or {@code null} when no SDT
     * has named it.
     *
     * <p>Null rather than a fallback, so a caller can tell "the stream says it is
     * called this" from "nothing has said". {@link #describeProgram} is the one
     * that falls back.
     */
    public String serviceName(int programNumber) {
        Service service = services.get(programNumber);
        return service == null ? null : service.name();
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
        return new ProgramMap(transportStreamId, Collections.unmodifiableMap(updated), pmtPids,
                services, network);
    }

    /**
     * This map with the services an SDT announced.
     *
     * <p>Replaced wholesale rather than merged: an SDT section is a complete
     * statement about the services it lists, and a service dropped from it has
     * gone. Merging would leave a decommissioned service on a dashboard for as
     * long as the process ran.
     */
    ProgramMap withServices(List<Service> announced) {
        Map<Integer, Service> updated = new LinkedHashMap<>();
        for (Service service : announced) {
            updated.put(service.serviceId(), service);
        }
        return new ProgramMap(transportStreamId, programs, pmtPids,
                Collections.unmodifiableMap(updated), network);
    }

    /** This map with what a NIT said about the network. */
    ProgramMap withNetwork(NetworkInformationTable announced) {
        return new ProgramMap(transportStreamId, programs, pmtPids, services, announced);
    }

    /**
     * This map rebuilt around a new PAT, dropping any program the PAT no longer
     * lists. A program that disappears from the PAT is gone, and keeping its
     * stale tracks would have the dashboard reporting on a program that is no
     * longer being carried.
     *
     * <p>The services and the network survive it. They are announced by their own
     * tables on their own schedules, and a PAT arriving says nothing at all about
     * whether the SDT's contents are still true.
     */
    static ProgramMap fromPat(ProgramAssociationTable pat, ProgramMap previous) {
        Map<Integer, ProgramMapTable> retained = new LinkedHashMap<>();
        for (Integer programNumber : pat.programs().keySet()) {
            ProgramMapTable existing = previous.programs().get(programNumber);
            if (existing != null) {
                retained.put(programNumber, existing);
            }
        }
        // Collections.unmodifiableMap over a LinkedHashMap, not Map.copyOf, which
        // randomises iteration order - see ProgramAssociationTable.parse.
        return new ProgramMap(pat.transportStreamId(), Collections.unmodifiableMap(retained),
                pat.programs(), previous.services(), previous.network());
    }

}
