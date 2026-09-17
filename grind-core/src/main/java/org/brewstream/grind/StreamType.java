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
 * What an elementary stream carries, from the {@code stream_type} byte in a PMT
 * (ISO/IEC 13818-1 Table 2-34, extended by later amendments and by DVB).
 *
 * <p>Only the types a live stream realistically carries are named. The rest are
 * reported as {@link #UNKNOWN} with their raw value preserved — a dashboard
 * should say "type 0x87" rather than silently omit a track it did not
 * recognise, because an unrecognised track is exactly the thing worth seeing.
 */
public enum StreamType {

    MPEG1_VIDEO(0x01, Kind.VIDEO, "MPEG-1 Video"),
    MPEG2_VIDEO(0x02, Kind.VIDEO, "MPEG-2 Video"),
    MPEG1_AUDIO(0x03, Kind.AUDIO, "MPEG-1 Audio"),
    MPEG2_AUDIO(0x04, Kind.AUDIO, "MPEG-2 Audio"),
    PRIVATE_SECTIONS(0x05, Kind.DATA, "Private sections"),
    PRIVATE_PES(0x06, Kind.DATA, "Private PES"),
    ADTS_AAC(0x0F, Kind.AUDIO, "AAC (ADTS)"),
    MPEG4_VIDEO(0x10, Kind.VIDEO, "MPEG-4 Video"),
    LATM_AAC(0x11, Kind.AUDIO, "AAC (LATM)"),
    H264(0x1B, Kind.VIDEO, "H.264 / AVC"),
    MPEG4_AUDIO(0x1C, Kind.AUDIO, "MPEG-4 Audio"),
    HEVC(0x24, Kind.VIDEO, "H.265 / HEVC"),
    VVC(0x33, Kind.VIDEO, "H.266 / VVC"),
    AC3(0x81, Kind.AUDIO, "AC-3"),
    SCTE35(0x86, Kind.DATA, "SCTE-35 splice"),
    EAC3(0x87, Kind.AUDIO, "E-AC-3"),
    UNKNOWN(-1, Kind.UNKNOWN, "unknown");

    /** The broad category a dashboard groups by. */
    public enum Kind { VIDEO, AUDIO, DATA, UNKNOWN }

    private final int code;
    private final Kind kind;
    private final String description;

    StreamType(int code, Kind kind, String description) {
        this.code = code;
        this.kind = kind;
        this.description = description;
    }

    /** The {@code stream_type} value as it appears on the wire, or -1 for {@link #UNKNOWN}. */
    public int code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    /** A human-readable name, for display. */
    public String description() {
        return description;
    }

    /**
     * @param code the {@code stream_type} byte
     * @return the matching constant, or {@link #UNKNOWN} — never null, because a
     *         stream carrying something we do not recognise is still a stream
     */
    public static StreamType fromCode(int code) {
        for (StreamType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}