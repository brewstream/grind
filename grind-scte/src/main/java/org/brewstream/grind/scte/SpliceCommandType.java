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

/**
 * The command a splice information section carries, from SCTE 35 table 6.
 *
 * <p>Every defined value is named, including the ones this library does not
 * parse. A stream carrying {@code splice_schedule} should be reported as
 * carrying a schedule rather than as carrying something unreadable — describing
 * what is there is useful even when the contents are not yet understood.
 */
public enum SpliceCommandType {

    /** A section carrying no command, used to keep an otherwise idle PID alive. */
    SPLICE_NULL(0x00, "splice_null"),

    /** Splices scheduled in UTC rather than against the stream clock. Not parsed. */
    SPLICE_SCHEDULE(0x04, "splice_schedule"),

    /** The classic ad break: out of network and back again. Parsed. */
    SPLICE_INSERT(0x05, "splice_insert"),

    /** A point in time, given meaning by the descriptors beside it. Parsed. */
    TIME_SIGNAL(0x06, "time_signal"),

    /** Reserves multiplex bandwidth; carries no timing. Not parsed. */
    BANDWIDTH_RESERVATION(0x07, "bandwidth_reservation"),

    /** Vendor-specific, identified by a registration value. Not parsed. */
    PRIVATE_COMMAND(0xFF, "private_command"),

    /** A value SCTE 35 does not define, or a later revision added. */
    UNKNOWN(-1, "unknown");

    private final int value;
    private final String label;

    SpliceCommandType(int value, String label) {
        this.value = value;
        this.label = label;
    }

    /** The on-the-wire value, or -1 for {@link #UNKNOWN}. */
    public int value() {
        return value;
    }

    /** The name SCTE 35 gives this command. */
    public String label() {
        return label;
    }

    /** Whether this library reads the command's contents, as opposed to naming it. */
    public boolean isParsed() {
        return this == SPLICE_INSERT || this == TIME_SIGNAL;
    }

    /** The command for an on-the-wire value, or {@link #UNKNOWN}. */
    public static SpliceCommandType of(int value) {
        for (SpliceCommandType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
