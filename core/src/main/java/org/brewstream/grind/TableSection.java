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

import java.util.Arrays;

/**
 * One complete PSI section, reassembled and checksum-verified
 * (ISO/IEC 13818-1 §2.4.4).
 *
 * <p>A section is the unit tables are carried in. It may span several transport
 * packets, several may share one packet, and the bytes only mean anything once
 * all of it has arrived and the CRC agrees — which is what
 * {@link SectionAssembler} produces.
 *
 * @param tableId         what kind of table this is: 0x00 PAT, 0x02 PMT, 0x42 SDT
 * @param tableIdExtension for a PAT the transport stream id, for a PMT the program number
 * @param version         increments when the table's contents change, wrapping at 32
 * @param current         whether this table is in force now, or describes a future state
 * @param sectionNumber   this section's index within the table
 * @param lastSectionNumber the highest section number the table has
 * @param body            the section's contents: everything after the eight-byte long header
 *                        and before the CRC, copied so the caller owns it
 */
public record TableSection(
        int tableId,
        int tableIdExtension,
        int version,
        boolean current,
        int sectionNumber,
        int lastSectionNumber,
        byte[] body) {

    /** Program Association Table — always on PID 0. */
    public static final int TABLE_ID_PAT = 0x00;

    /** Program Map Table — on whichever PID the PAT names. */
    public static final int TABLE_ID_PMT = 0x02;

    /** The fixed part of a long section: table id, length, extension, version, section numbers. */
    public static final int LONG_HEADER_LENGTH = 8;

    /** Every long section ends with four bytes of CRC-32. */
    public static final int CRC_LENGTH = 4;

    @Override
    public boolean equals(Object other) {
        return other instanceof TableSection that
                && tableId == that.tableId
                && tableIdExtension == that.tableIdExtension
                && version == that.version
                && current == that.current
                && sectionNumber == that.sectionNumber
                && lastSectionNumber == that.lastSectionNumber
                && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * tableId + tableIdExtension) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "TableSection[tableId=0x" + Integer.toHexString(tableId)
                + ", extension=" + tableIdExtension + ", version=" + version
                + ", section=" + sectionNumber + "/" + lastSectionNumber
                + ", body=" + body.length + " bytes]";
    }
}