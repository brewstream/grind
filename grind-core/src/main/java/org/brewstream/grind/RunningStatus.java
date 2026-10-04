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
 * Whether a service or an event is on the air right now (ETSI EN 300 468
 * table 6).
 *
 * <p>Carried in both the SDT and the EIT, three bits in each, and it means
 * slightly different things in the two: on a service it says whether the service
 * is broadcasting, and on an event whether that particular programme is the one
 * currently going out.
 *
 * <p><b>{@link #UNDEFINED} is the common case on a contribution feed and is not
 * a fault.</b> A muxer that has nothing to say about running status says this,
 * so an alert built on "status is not RUNNING" fires constantly on streams that
 * are perfectly fine.
 */
public enum RunningStatus {

    /** The muxer said nothing. Not an error, and the usual value outside broadcast. */
    UNDEFINED,

    /** Announced, but not on the air. An event later today reads this. */
    NOT_RUNNING,

    /** About to start — within seconds, used to prompt a receiver to get ready. */
    STARTS_IN_A_FEW_SECONDS,

    /** Interrupted, and expected to resume. */
    PAUSING,

    /** On the air now. */
    RUNNING,

    /** Off the air, for a service that exists but is not broadcasting. */
    SERVICE_OFF_AIR,

    /** A value the standard reserves and this stream used anyway. */
    RESERVED;

    private static final RunningStatus[] BY_CODE = {
            UNDEFINED, NOT_RUNNING, STARTS_IN_A_FEW_SECONDS, PAUSING,
            RUNNING, SERVICE_OFF_AIR, RESERVED, RESERVED,
    };

    /**
     * Resolves the three-bit field.
     *
     * @param code the raw value, 0 to 7
     * @return the status, or {@link #RESERVED} for the two values the standard
     *         has not allocated
     */
    public static RunningStatus fromCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : RESERVED;
    }
}
