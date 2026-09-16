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
import java.util.Map;

/**
 * The PAT: which programs the stream carries and which PID describes each one
 * (ISO/IEC 13818-1 §2.4.4.3). Always on PID 0, so it is the entry point to
 * everything else.
 *
 * @param transportStreamId identifies this stream among others from the same source
 * @param programs          program number to the PID carrying its PMT, in the order listed
 * @param networkPid        the PID carrying network information, or -1 if none was listed.
 *                          Program number 0 means network rather than a program, which is
 *                          why it is not in {@code programs}
 */
public record ProgramAssociationTable(
        int transportStreamId,
        Map<Integer, Integer> programs,
        int networkPid) {

    /**
     * Parses a PAT section body: repeated four-byte entries of program number
     * and PID.
     *
     * @param section a section whose table id is {@link TableSection#TABLE_ID_PAT}
     * @return the parsed table, or {@code null} if the body is not a whole number
     *         of entries
     */
    public static ProgramAssociationTable parse(TableSection section) {
        byte[] body = section.body();
        if (body.length % 4 != 0) {
            return null;
        }

        Map<Integer, Integer> programs = new LinkedHashMap<>();
        int networkPid = -1;
        for (int i = 0; i < body.length; i += 4) {
            int programNumber = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF);
            int pid = ((body[i + 2] & 0x1F) << 8) | (body[i + 3] & 0xFF);
            if (programNumber == 0) {
                networkPid = pid;
            } else {
                programs.put(programNumber, pid);
            }
        }
        return new ProgramAssociationTable(section.tableIdExtension(), Map.copyOf(programs), networkPid);
    }
}