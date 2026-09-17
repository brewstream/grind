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

import org.brewstream.grind.Crc32Mpeg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads a splice information section, as defined by SCTE 35 section 9.
 *
 * <p>These sections are short-form — no version, no section numbering — yet they
 * carry a CRC-32 anyway, which the base transport stream standard does not expect
 * of a short section. {@code SectionAssembler} therefore hands them over whole
 * and this validates the checksum itself.
 *
 * <p>Returns {@code null} rather than throwing on anything malformed. A splice
 * PID can carry a section this library does not understand, and the useful
 * response is to say so and carry on rather than to fail the stream.
 */
public final class SpliceInfoParser {

    /** table_id, section length, then 11 bytes before the command begins. */
    private static final int HEADER_LENGTH = 11;

    /** Enough bytes for the descriptor loop length and the CRC that follow a command. */
    private static final int TRAILER_LENGTH = 6;

    private static final byte[] EMPTY = new byte[0];

    private SpliceInfoParser() {
    }

    /**
     * Parses a complete splice information section, checksum included.
     *
     * @param section the whole section as the assembler produced it, table id first
     * @return the parsed section, or null if it is not one, is truncated, or fails CRC
     */
    public static SpliceInfoSection parse(byte[] section) {
        if (section == null || section.length < HEADER_LENGTH + TRAILER_LENGTH) {
            return null;
        }
        if ((section[0] & 0xFF) != SpliceInfoSection.TABLE_ID) {
            return null;
        }
        if (!Crc32Mpeg.isValid(section, 0, section.length)) {
            return null;
        }

        int at = 3; // past table_id and the two length bytes
        at++;       // protocol_version, not acted on

        boolean encrypted = (section[at] & 0x80) != 0;
        long ptsAdjustment = ((long) (section[at] & 0x01) << 32)
                | ((long) (section[at + 1] & 0xFF) << 24)
                | ((long) (section[at + 2] & 0xFF) << 16)
                | ((long) (section[at + 3] & 0xFF) << 8)
                | (section[at + 4] & 0xFF);
        at += 5;
        at++;       // cw_index, only meaningful when encrypted

        int tier = ((section[at] & 0xFF) << 4) | ((section[at + 1] & 0xF0) >> 4);
        int commandLength = ((section[at + 1] & 0x0F) << 8) | (section[at + 2] & 0xFF);
        at += 3;

        SpliceCommandType type = SpliceCommandType.of(section[at] & 0xFF);
        at++;

        if (encrypted) {
            // The command and everything after it is ciphertext. Reported as a
            // section that exists and cannot be read, which is the honest answer:
            // parsing on would produce plausible-looking nonsense.
            return new SpliceInfoSection(type, ptsAdjustment, tier, true, null, -1, List.of());
        }

        // 0xFFF means "unknown length" in SCTE 35; trust the section length instead.
        int commandEnd = commandLength == 0x0FFF
                ? section.length - TRAILER_LENGTH + 2
                : at + commandLength;
        if (commandEnd > section.length - 4 || commandEnd < at) {
            return null;
        }

        SpliceInsert insert = null;
        long timeSignal = -1;
        if (type == SpliceCommandType.SPLICE_INSERT) {
            insert = parseInsert(section, at, commandEnd);
            if (insert == null) {
                return null;
            }
        } else if (type == SpliceCommandType.TIME_SIGNAL) {
            timeSignal = parseSpliceTime(section, at, commandEnd);
        }
        return new SpliceInfoSection(type, ptsAdjustment, tier, false, insert, timeSignal,
                parseDescriptors(section, commandEnd));
    }

    /**
     * Reads the descriptor loop that follows the command.
     *
     * <p>Only segmentation descriptors are interpreted. Others are stepped over
     * by their own length rather than skipped wholesale, so one descriptor this
     * library does not read cannot hide the ones after it.
     */
    private static List<SegmentationDescriptor> parseDescriptors(byte[] s, int at) {
        if (at + 2 > s.length - 4) {
            return List.of();
        }
        int loopLength = ((s[at] & 0xFF) << 8) | (s[at + 1] & 0xFF);
        at += 2;
        int loopEnd = Math.min(at + loopLength, s.length - 4);

        List<SegmentationDescriptor> found = new ArrayList<>();
        while (at + 2 <= loopEnd) {
            int tag = s[at] & 0xFF;
            int length = s[at + 1] & 0xFF;
            int payload = at + 2;
            int next = payload + length;
            if (next > loopEnd) {
                break; // a length running past the loop: stop rather than guess
            }
            if (tag == SegmentationDescriptor.TAG) {
                SegmentationDescriptor descriptor = parseSegmentation(s, payload, next);
                if (descriptor != null) {
                    found.add(descriptor);
                }
            }
            at = next;
        }
        return List.copyOf(found);
    }

    private static SegmentationDescriptor parseSegmentation(byte[] s, int at, int end) {
        // identifier, which should read "CUEI"; a descriptor claiming this tag
        // under another authority is not one of these.
        if (at + 4 > end) {
            return null;
        }
        int identifier = ((s[at] & 0xFF) << 24) | ((s[at + 1] & 0xFF) << 16)
                | ((s[at + 2] & 0xFF) << 8) | (s[at + 3] & 0xFF);
        if (identifier != SegmentationDescriptor.IDENTIFIER_CUEI) {
            return null;
        }
        at += 4;

        if (at + 5 > end) {
            return null;
        }
        long eventId = ((long) (s[at] & 0xFF) << 24) | ((s[at + 1] & 0xFF) << 16)
                | ((s[at + 2] & 0xFF) << 8) | (s[at + 3] & 0xFF);
        at += 4;
        boolean cancelled = (s[at] & 0x80) != 0;
        at++;
        if (cancelled) {
            return new SegmentationDescriptor(eventId, true, SegmentationType.NOT_INDICATED,
                    -1, 0, EMPTY, 0, 0, false, true, false, true);
        }
        if (at >= end) {
            return null;
        }

        boolean programSegmentation = (s[at] & 0x80) != 0;
        boolean hasDuration = (s[at] & 0x40) != 0;
        boolean notRestricted = (s[at] & 0x20) != 0;
        // On the wire these say what is *allowed*; no_regional_blackout is
        // inverted here so that every flag reads as a restriction.
        boolean webDelivery = notRestricted || (s[at] & 0x10) != 0;
        boolean regionalBlackout = !notRestricted && (s[at] & 0x08) == 0;
        boolean archiveAllowed = notRestricted || (s[at] & 0x04) != 0;
        at++;

        if (!programSegmentation) {
            // Per-component offsets, which this library does not read. Stepped
            // over so the fields after them still line up.
            if (at >= end) {
                return null;
            }
            int components = s[at] & 0xFF;
            at += 1 + components * 6;
        }

        long duration = -1;
        if (hasDuration) {
            if (at + 5 > end) {
                return null;
            }
            duration = ((long) (s[at] & 0xFF) << 32) | ((long) (s[at + 1] & 0xFF) << 24)
                    | ((long) (s[at + 2] & 0xFF) << 16) | ((long) (s[at + 3] & 0xFF) << 8)
                    | (s[at + 4] & 0xFF);
            at += 5;
        }

        if (at + 2 > end) {
            return null;
        }
        int upidType = s[at] & 0xFF;
        int upidLength = s[at + 1] & 0xFF;
        at += 2;
        if (at + upidLength > end) {
            return null;
        }
        byte[] upid = upidLength == 0 ? EMPTY : Arrays.copyOfRange(s, at, at + upidLength);
        at += upidLength;

        if (at + 3 > end) {
            return null;
        }
        SegmentationType type = SegmentationType.of(s[at] & 0xFF);
        int segmentNum = s[at + 1] & 0xFF;
        int segmentsExpected = s[at + 2] & 0xFF;

        return new SegmentationDescriptor(eventId, false, type, duration, upidType, upid,
                segmentNum, segmentsExpected, !notRestricted, webDelivery, regionalBlackout,
                archiveAllowed);
    }

    private static SpliceInsert parseInsert(byte[] s, int at, int end) {
        if (at + 5 > end) {
            return null;
        }
        long eventId = ((long) (s[at] & 0xFF) << 24) | ((s[at + 1] & 0xFF) << 16)
                | ((s[at + 2] & 0xFF) << 8) | (s[at + 3] & 0xFF);
        at += 4;
        boolean cancelled = (s[at] & 0x80) != 0;
        at++;
        if (cancelled) {
            // A cancellation carries nothing but the id of what it cancels.
            return new SpliceInsert(eventId, true, false, false, -1, -1, false, 0, 0, 0);
        }
        if (at >= end) {
            return null;
        }

        boolean outOfNetwork = (s[at] & 0x80) != 0;
        boolean programSplice = (s[at] & 0x40) != 0;
        boolean hasDuration = (s[at] & 0x20) != 0;
        boolean immediate = (s[at] & 0x10) != 0;
        at++;

        long spliceTime = -1;
        if (programSplice && !immediate) {
            spliceTime = parseSpliceTime(s, at, end);
            at += spliceTimeLength(s, at, end);
        } else if (!programSplice) {
            // Per-component splice times, which this library does not read. The
            // component list is skipped so the fields after it still line up.
            if (at >= end) {
                return null;
            }
            int components = s[at] & 0xFF;
            at++;
            for (int i = 0; i < components && at < end; i++) {
                at++; // component_tag
                if (!immediate) {
                    at += spliceTimeLength(s, at, end);
                }
            }
        }

        long duration = -1;
        boolean autoReturn = false;
        if (hasDuration) {
            if (at + 5 > end) {
                return null;
            }
            autoReturn = (s[at] & 0x80) != 0;
            duration = ((long) (s[at] & 0x01) << 32) | ((long) (s[at + 1] & 0xFF) << 24)
                    | ((long) (s[at + 2] & 0xFF) << 16) | ((long) (s[at + 3] & 0xFF) << 8)
                    | (s[at + 4] & 0xFF);
            at += 5;
        }

        if (at + 4 > end) {
            return null;
        }
        int programId = ((s[at] & 0xFF) << 8) | (s[at + 1] & 0xFF);
        int availNum = s[at + 2] & 0xFF;
        int availsExpected = s[at + 3] & 0xFF;
        return new SpliceInsert(eventId, false, outOfNetwork, immediate, spliceTime,
                duration, autoReturn, programId, availNum, availsExpected);
    }

    /**
     * A splice_time is one flag byte, plus four more when a time is present.
     *
     * <p>A time_signal with no time is legal: it carries meaning only through the
     * descriptors beside it.
     */
    private static long parseSpliceTime(byte[] s, int at, int end) {
        if (at >= end || (s[at] & 0x80) == 0) {
            return -1;
        }
        if (at + 5 > end) {
            return -1;
        }
        return ((long) (s[at] & 0x01) << 32) | ((long) (s[at + 1] & 0xFF) << 24)
                | ((long) (s[at + 2] & 0xFF) << 16) | ((long) (s[at + 3] & 0xFF) << 8)
                | (s[at + 4] & 0xFF);
    }

    private static int spliceTimeLength(byte[] s, int at, int end) {
        return at < end && (s[at] & 0x80) != 0 ? 5 : 1;
    }
}
