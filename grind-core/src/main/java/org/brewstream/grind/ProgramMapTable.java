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
 * <p>Both descriptor loops are read: the program-level one, which is where a
 * registration identifier such as {@code CUEI} declares what a private stream
 * type means, and each track's own, which is where its language is.
 *
 * @param programNumber      which program this describes
 * @param pcrPid             the PID carrying this program's clock, or 0x1FFF when it has none
 * @param programDescriptors the descriptor loop describing the program as a whole
 * @param streams            the program's tracks, in the order the table lists them
 */
public record ProgramMapTable(
        int programNumber,
        int pcrPid,
        List<Descriptor> programDescriptors,
        List<ElementaryStream> streams) {

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
        List<Descriptor> programDescriptors = Descriptor.parseLoop(body, 4, programInfoLength);

        List<ElementaryStream> streams = new ArrayList<>();
        while (cursor + 5 <= body.length) {
            int rawType = body[cursor] & 0xFF;
            int pid = ((body[cursor + 1] & 0x1F) << 8) | (body[cursor + 2] & 0xFF);
            int esInfoLength = ((body[cursor + 3] & 0x0F) << 8) | (body[cursor + 4] & 0xFF);
            int esInfoStart = cursor + 5;
            cursor = esInfoStart + esInfoLength;
            if (cursor > body.length) {
                return null; // an ES descriptor length ran past the section
            }
            streams.add(new ElementaryStream(pid, StreamType.fromCode(rawType), rawType,
                    Descriptor.parseLoop(body, esInfoStart, esInfoLength)));
        }

        return new ProgramMapTable(section.tableIdExtension(), pcrPid,
                programDescriptors, List.copyOf(streams));
    }

    /**
     * The program's {@code registration_descriptor} identifier, or {@code null}
     * when it carries none.
     *
     * <p>Four characters naming the body that defines what the program's private
     * stream types mean — {@code CUEI} for SCTE 35, which is what tells a reader
     * that stream type 0x86 is splice information rather than whatever else a
     * private type might be. Returned as text because every identifier in
     * practice is four printable characters, and as the raw bytes it would be
     * unreadable in exactly the place it is meant to be recognised.
     */
    public String registrationIdentifier() {
        Descriptor registration =
                Descriptor.find(programDescriptors, Descriptor.TAG_REGISTRATION);
        if (registration == null) {
            return null;
        }
        byte[] payload = registration.payload();
        if (payload.length < 4) {
            return null;
        }
        return new String(payload, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * The first track carrying this language, or {@code null} when none does.
     *
     * <p>What a repackager should use to choose an audio track, instead of
     * taking the first one the table lists and being right only on the streams
     * it was tested against.
     *
     * @param language an ISO 639-2 three-letter code, matched case-insensitively
     *                 because nothing enforces the case a muxer writes
     */
    public ElementaryStream streamWithLanguage(String language) {
        if (language == null) {
            return null;
        }
        for (ElementaryStream stream : streams) {
            for (String candidate : stream.languages()) {
                if (candidate.equalsIgnoreCase(language)) {
                    return stream;
                }
            }
        }
        return null;
    }

    /** The track carrying video, or {@code null} — what a dashboard leads with. */
    public ElementaryStream video() {
        return streams.stream()
                .filter(s -> s.streamType().kind() == StreamType.Kind.VIDEO)
                .findFirst()
                .orElse(null);
    }
}