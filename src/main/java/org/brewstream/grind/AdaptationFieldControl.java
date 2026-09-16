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
 * Which of the adaptation field and the payload a packet carries
 * (ISO/IEC 13818-1 Table 2-5).
 */
public enum AdaptationFieldControl {

    /**
     * Reserved by the spec and not produced by conforming multiplexers. Parsed
     * rather than rejected: a stream containing it is unusual, not unreadable,
     * and refusing to describe it would make this library useless for the
     * inspection job it exists to do.
     */
    RESERVED(0, false, false),

    /** Payload only — the common case for the body of a PES packet. */
    PAYLOAD_ONLY(1, false, true),

    /**
     * Adaptation field only, no payload. Used to carry a PCR when there is no
     * data due, and as whole-packet stuffing.
     */
    ADAPTATION_ONLY(2, true, false),

    /**
     * Both. Typically the first packet of a PES packet, where the adaptation
     * field carries a PCR or a random-access indicator.
     */
    ADAPTATION_AND_PAYLOAD(3, true, true);

    private final int code;
    private final boolean hasAdaptationField;
    private final boolean hasPayload;

    AdaptationFieldControl(int code, boolean hasAdaptationField, boolean hasPayload) {
        this.code = code;
        this.hasAdaptationField = hasAdaptationField;
        this.hasPayload = hasPayload;
    }

    /** The two-bit value as it appears on the wire. */
    public int code() {
        return code;
    }

    public boolean hasAdaptationField() {
        return hasAdaptationField;
    }

    public boolean hasPayload() {
        return hasPayload;
    }

    /**
     * @param code the two-bit field value
     * @return the matching constant; never null, since all four values are defined
     */
    public static AdaptationFieldControl fromCode(int code) {
        return switch (code) {
            case 0 -> RESERVED;
            case 1 -> PAYLOAD_ONLY;
            case 2 -> ADAPTATION_ONLY;
            case 3 -> ADAPTATION_AND_PAYLOAD;
            default -> throw new IllegalArgumentException("not a two-bit value: " + code);
        };
    }
}