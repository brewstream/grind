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

/**
 * The CRC-32 every long PSI section ends with (ISO/IEC 13818-1 Annex B).
 *
 * <p>Not {@link java.util.zip.CRC32}, which is the reflected Ethernet variant
 * and produces different output. This is CRC-32/MPEG-2: polynomial
 * {@code 0x04C11DB7}, initialised to all ones, most-significant-bit first, with
 * no final inversion. A section whose checksum does not match must be discarded
 * rather than parsed, which is the whole reason it is there — a table assembled
 * from packets with a gap in them would otherwise be acted on as though it were
 * intact.
 */
public final class Crc32Mpeg {

    private static final int POLYNOMIAL = 0x04C1_1DB7;
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0 ? (crc << 1) ^ POLYNOMIAL : crc << 1;
            }
            TABLE[i] = crc;
        }
    }

    private Crc32Mpeg() {
    }

    /**
     * @param data   the bytes to checksum
     * @param offset where to start
     * @param length how many bytes
     * @return the checksum, to be compared against the four bytes at the end of a section
     */
    public static int of(byte[] data, int offset, int length) {
        int crc = 0xFFFF_FFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc << 8) ^ TABLE[((crc >>> 24) ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc;
    }

    /**
     * Whether a section's trailing checksum matches its contents.
     *
     * @param section the complete section, header through CRC
     * @param offset  index of the section's first byte
     * @param length  the section's total length including the four CRC bytes
     */
    public static boolean isValid(byte[] section, int offset, int length) {
        if (length < 4) {
            return false;
        }
        // Running the CRC over the section *including* its own checksum yields
        // zero for an intact section - a property of this construction, and
        // cheaper than extracting and comparing the stored value.
        return of(section, offset, length) == 0;
    }
}