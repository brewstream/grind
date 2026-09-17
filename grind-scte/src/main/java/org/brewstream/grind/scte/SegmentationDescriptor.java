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

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A {@code segmentation_descriptor}: what a splice actually means.
 *
 * <p>A {@code time_signal} on its own names a moment and nothing more. This is
 * the descriptor that gives the moment meaning — a programme boundary, an
 * advertisement, or an opportunity for a downstream system to insert content of
 * its own. Modern broadcast signalling is mostly {@code time_signal} plus one of
 * these, rather than the older {@code splice_insert}.
 *
 * @param eventId     identifies this segment, and pairs a start with its end
 * @param cancelled   whether this cancels a previously signalled segment of the same id
 * @param type        what is being marked
 * @param duration    how long the segment lasts in 90 kHz units, or -1 when not given
 * @param upidType    the kind of identifier in {@link #upid()}, from SCTE 35 table 22
 * @param upid        the identifier's raw bytes, never null and often empty
 * @param segmentNum  which segment this is in a sequence, or 0 when unused
 * @param segmentsExpected how many the sequence holds, or 0 when unused
 * @param deliveryRestricted whether the flags below carry meaning. When false the stream
 *                    places no restriction and the three that follow are not signalled
 * @param webDeliveryAllowed  whether this segment may go out over the open internet
 * @param regionalBlackout    whether a regional blackout applies. Note this is the inverse
 *                    of the {@code no_regional_blackout_flag} on the wire, stated
 *                    positively so that true means restricted like its neighbours
 * @param archiveAllowed      whether the segment may be recorded
 */
public record SegmentationDescriptor(
        long eventId,
        boolean cancelled,
        SegmentationType type,
        long duration,
        int upidType,
        byte[] upid,
        int segmentNum,
        int segmentsExpected,
        boolean deliveryRestricted,
        boolean webDeliveryAllowed,
        boolean regionalBlackout,
        boolean archiveAllowed) {

    /**
     * Copies the identifier in, so a descriptor cannot be changed from outside
     * after it has been handed over.
     */
    public SegmentationDescriptor {
        upid = upid == null ? new byte[0] : upid.clone();
    }

    /**
     * Compares the identifier by content.
     *
     * <p>A record holding an array gets identity equality by default, which would
     * make two readings of the same descriptor unequal — and silently, since
     * everything else about them matches. The same trap made {@code TsPacket} a
     * class rather than a record; here the record is worth keeping and the three
     * methods are worth writing out.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SegmentationDescriptor that)) {
            return false;
        }
        return eventId == that.eventId && cancelled == that.cancelled && type == that.type
                && duration == that.duration && upidType == that.upidType
                && segmentNum == that.segmentNum && segmentsExpected == that.segmentsExpected
                && deliveryRestricted == that.deliveryRestricted
                && webDeliveryAllowed == that.webDeliveryAllowed
                && regionalBlackout == that.regionalBlackout
                && archiveAllowed == that.archiveAllowed
                && java.util.Arrays.equals(upid, that.upid);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(eventId, cancelled, type, duration, upidType,
                segmentNum, segmentsExpected, deliveryRestricted, webDeliveryAllowed,
                regionalBlackout, archiveAllowed);
        return 31 * result + java.util.Arrays.hashCode(upid);
    }

    /** Renders the identifier readably rather than as an array reference. */
    @Override
    public String toString() {
        return "SegmentationDescriptor[eventId=" + eventId + ", type=" + type
                + ", upid=" + upidText() + ", duration=" + duration + ']';
    }

    /** The identifier's bytes, copied so the descriptor stays immutable. */
    @Override
    public byte[] upid() {
        return upid.clone();
    }

    /** The tag identifying this descriptor among SCTE 35's others. */
    public static final int TAG = 0x02;

    /** The registration identifier every SCTE 35 descriptor carries: "CUEI". */
    public static final int IDENTIFIER_CUEI = 0x43554549;

    /** Whether a segment length was signalled. */
    public boolean hasDuration() {
        return duration >= 0;
    }

    /** The segment length in seconds, or -1 when none was signalled. */
    public double durationSeconds() {
        return duration < 0 ? -1 : (double) duration / SpliceInfoSection.TIMESTAMP_RATE_HZ;
    }

    /**
     * The identifier as text, for the types that carry text.
     *
     * <p>Ad-ID, ISCI, TID, ADI, URI and the other string forms are ASCII on the
     * wire. Binary forms — UUID, EIDR, MPU and the rest — are rendered as hex
     * rather than decoded into mojibake.
     *
     * @return a readable form, or an empty string when there is no identifier
     */
    public String upidText() {
        if (upid.length == 0) {
            return "";
        }
        return switch (upidType) {
            case 0x01, 0x02, 0x03, 0x07, 0x09, 0x0E, 0x0F ->
                    new String(upid, StandardCharsets.US_ASCII).trim();
            default -> HexFormat.of().withUpperCase().formatHex(upid);
        };
    }

    /** A one-line description, the way a dashboard row would read. */
    public String describe() {
        if (cancelled) {
            return "cancel segment " + eventId;
        }
        StringBuilder text = new StringBuilder(type.label());
        String id = upidText();
        if (!id.isEmpty()) {
            text.append(" [").append(id).append(']');
        }
        if (hasDuration()) {
            text.append(String.format(" for %.1fs", durationSeconds()));
        }
        return text.toString();
    }
}
