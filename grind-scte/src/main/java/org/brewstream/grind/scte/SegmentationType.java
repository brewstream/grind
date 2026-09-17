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
 * What a segmentation descriptor is marking, from SCTE 35 table 23.
 *
 * <p>This is where the meaning of a modern splice lives. A {@code time_signal}
 * on its own says only "this moment"; the segmentation type beside it says
 * whether the moment is a programme boundary, an advertisement, a chapter, or an
 * opportunity for a downstream system to insert something of its own.
 *
 * <p>The types come in start and end pairs, and {@link #isStart()} distinguishes
 * them — which is what lets a reader match a break to its return.
 */
public enum SegmentationType {

    NOT_INDICATED(0x00, "Not Indicated"),
    CONTENT_IDENTIFICATION(0x01, "Content Identification"),

    PROGRAM_START(0x10, "Program Start"),
    PROGRAM_END(0x11, "Program End"),
    PROGRAM_EARLY_TERMINATION(0x12, "Program Early Termination"),
    PROGRAM_BREAKAWAY(0x13, "Program Breakaway"),
    PROGRAM_RESUMPTION(0x14, "Program Resumption"),
    PROGRAM_RUNOVER_PLANNED(0x15, "Program Runover Planned"),
    PROGRAM_RUNOVER_UNPLANNED(0x16, "Program Runover Unplanned"),
    PROGRAM_OVERLAP_START(0x17, "Program Overlap Start"),
    PROGRAM_BLACKOUT_OVERRIDE(0x18, "Program Blackout Override"),
    PROGRAM_JOIN(0x19, "Program Join"),

    CHAPTER_START(0x20, "Chapter Start"),
    CHAPTER_END(0x21, "Chapter End"),
    BREAK_START(0x22, "Break Start"),
    BREAK_END(0x23, "Break End"),
    OPENING_CREDIT_START(0x24, "Opening Credit Start"),
    OPENING_CREDIT_END(0x25, "Opening Credit End"),
    CLOSING_CREDIT_START(0x26, "Closing Credit Start"),
    CLOSING_CREDIT_END(0x27, "Closing Credit End"),

    PROVIDER_ADVERTISEMENT_START(0x30, "Provider Advertisement Start"),
    PROVIDER_ADVERTISEMENT_END(0x31, "Provider Advertisement End"),
    DISTRIBUTOR_ADVERTISEMENT_START(0x32, "Distributor Advertisement Start"),
    DISTRIBUTOR_ADVERTISEMENT_END(0x33, "Distributor Advertisement End"),

    /** The one a downstream ad server acts on: an opportunity to insert its own content. */
    PROVIDER_PLACEMENT_OPPORTUNITY_START(0x34, "Provider Placement Opportunity Start"),
    PROVIDER_PLACEMENT_OPPORTUNITY_END(0x35, "Provider Placement Opportunity End"),
    DISTRIBUTOR_PLACEMENT_OPPORTUNITY_START(0x36, "Distributor Placement Opportunity Start"),
    DISTRIBUTOR_PLACEMENT_OPPORTUNITY_END(0x37, "Distributor Placement Opportunity End"),
    PROVIDER_OVERLAY_PLACEMENT_OPPORTUNITY_START(0x38, "Provider Overlay Placement Opportunity Start"),
    PROVIDER_OVERLAY_PLACEMENT_OPPORTUNITY_END(0x39, "Provider Overlay Placement Opportunity End"),
    DISTRIBUTOR_OVERLAY_PLACEMENT_OPPORTUNITY_START(0x3A, "Distributor Overlay Placement Opportunity Start"),
    DISTRIBUTOR_OVERLAY_PLACEMENT_OPPORTUNITY_END(0x3B, "Distributor Overlay Placement Opportunity End"),

    UNSCHEDULED_EVENT_START(0x40, "Unscheduled Event Start"),
    UNSCHEDULED_EVENT_END(0x41, "Unscheduled Event End"),
    ALTERNATE_CONTENT_OPPORTUNITY_START(0x42, "Alternate Content Opportunity Start"),
    ALTERNATE_CONTENT_OPPORTUNITY_END(0x43, "Alternate Content Opportunity End"),

    NETWORK_START(0x50, "Network Start"),
    NETWORK_END(0x51, "Network End"),

    /** A value this library does not name — a later revision, or a private use. */
    UNKNOWN(-1, "Unknown");

    private final int value;
    private final String label;

    SegmentationType(int value, String label) {
        this.value = value;
        this.label = label;
    }

    /** The on-the-wire value, or -1 for {@link #UNKNOWN}. */
    public int value() {
        return value;
    }

    /** The name SCTE 35 gives this type. */
    public String label() {
        return label;
    }

    /**
     * Whether this opens a segment rather than closing one.
     *
     * <p>SCTE 35 pairs these by making the end one more than the start, so the
     * low bit answers it for every pair. {@link #NOT_INDICATED} and
     * {@link #CONTENT_IDENTIFICATION} are neither, and answer false.
     */
    public boolean isStart() {
        return value > 0x01 && (value & 0x01) == 0;
    }

    /** Whether a downstream system is expected to insert its own content here. */
    public boolean isPlacementOpportunity() {
        return value >= 0x34 && value <= 0x3B;
    }

    /** The type for an on-the-wire value, or {@link #UNKNOWN}. */
    public static SegmentationType of(int value) {
        for (SegmentationType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
