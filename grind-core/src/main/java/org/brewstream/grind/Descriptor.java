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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One descriptor: a tag, a length, and bytes whose meaning the tag decides
 * (ISO/IEC 13818-1 §2.6, ETSI EN 300 468 §6).
 *
 * <p>Descriptors are how MPEG-TS and DVB carry everything the table formats
 * themselves have no field for — what language a track is in, what a service is
 * called, which private format a stream follows. They appear in loops, each loop
 * introduced by its own length, in the PMT, the SDT, the NIT and the EIT alike.
 *
 * <p>This type is deliberately the <em>uninterpreted</em> form. Tags are
 * allocated by several bodies and privately extended by many broadcasters, so
 * any parser will meet tags it does not know; keeping the raw pair means an
 * unknown descriptor survives as itself rather than being dropped on the floor.
 * The handful worth interpreting have their own readers — {@link
 * ServiceDescriptor}, {@link ShortEventDescriptor}, and the single-field ones
 * read where they are used, such as {@link ElementaryStream#language()}.
 *
 * @param tag     the {@code descriptor_tag}, which is what gives the payload meaning
 * @param payload the {@code descriptor_data}, copied so the caller owns it. Empty
 *                for a descriptor whose length is zero, never null
 */
public record Descriptor(int tag, byte[] payload) {

    /** {@code registration_descriptor} — a four-character format identifier, such as {@code CUEI}. */
    public static final int TAG_REGISTRATION = 0x05;

    /** {@code ISO_639_language_descriptor} — three-letter language codes and audio types. */
    public static final int TAG_LANGUAGE = 0x0A;

    /** {@code network_name_descriptor} — what the network calls itself. */
    public static final int TAG_NETWORK_NAME = 0x40;

    /** {@code service_list_descriptor} — the services a transport stream carries, and their types. */
    public static final int TAG_SERVICE_LIST = 0x41;

    /** {@code service_descriptor} — service type, provider name and service name. */
    public static final int TAG_SERVICE = 0x48;

    /** {@code short_event_descriptor} — an event's name and a one-line description. */
    public static final int TAG_SHORT_EVENT = 0x4D;

    /** The fixed part of every descriptor: one tag byte and one length byte. */
    static final int HEADER_LENGTH = 2;

    /**
     * Copies the payload in, so a descriptor cannot be changed from outside
     * after it has been handed out.
     */
    public Descriptor {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
     * Identity equality on the payload, written out by hand.
     *
     * <p>A record holding a {@code byte[]} gets reference equality from the
     * compiler, which would make two readings of the same descriptor compare
     * unequal while every field matched. It fails silently, which is why this is
     * written out here and in {@code SegmentationDescriptor} rather than left to
     * the default.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Descriptor that
                && tag == that.tag
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * tag + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Descriptor[tag=0x" + Integer.toHexString(tag).toUpperCase()
                + ", " + payload.length + " bytes]";
    }

    /** The payload's bytes, copied so the descriptor stays immutable. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * Reads a descriptor loop.
     *
     * <p>Loops are length-delimited by whichever table contains them, so the
     * caller passes the span rather than this hunting for an end.
     *
     * <p>A descriptor whose declared length runs past the end of the loop stops
     * the read, and what was parsed up to that point is returned. The section's
     * CRC has already vouched for these bytes, so an overrun is not loss — it is
     * a malformed descriptor, and the bytes after it cannot be located, since a
     * loop is only navigable by trusting each length to find the next tag.
     *
     * @param data   the section body the loop sits in
     * @param offset where the loop starts
     * @param length how many bytes the loop was declared to occupy
     * @return the descriptors, in the order the loop lists them; empty if the
     *         span is empty or lies outside {@code data}
     */
    public static List<Descriptor> parseLoop(byte[] data, int offset, int length) {
        if (length <= 0 || offset < 0 || offset + length > data.length) {
            return List.of();
        }

        List<Descriptor> found = new ArrayList<>();
        int cursor = offset;
        int end = offset + length;
        while (cursor + HEADER_LENGTH <= end) {
            int tag = data[cursor] & 0xFF;
            int payloadLength = data[cursor + 1] & 0xFF;
            int payloadStart = cursor + HEADER_LENGTH;
            if (payloadStart + payloadLength > end) {
                break; // declared longer than the loop holds
            }
            found.add(new Descriptor(tag,
                    Arrays.copyOfRange(data, payloadStart, payloadStart + payloadLength)));
            cursor = payloadStart + payloadLength;
        }
        return List.copyOf(found);
    }

    /**
     * The first descriptor in a loop carrying this tag, or {@code null}.
     *
     * <p>First rather than only: nothing forbids a loop from carrying two
     * descriptors of the same tag, and several tags are defined to repeat — a
     * multilingual service carries one {@code short_event_descriptor} per
     * language. Callers wanting all of them should filter the list themselves.
     */
    public static Descriptor find(List<Descriptor> descriptors, int tag) {
        for (Descriptor descriptor : descriptors) {
            if (descriptor.tag == tag) {
                return descriptor;
            }
        }
        return null;
    }
}
