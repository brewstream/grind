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
 * A {@code short_event_descriptor}: an event's name and a one-line description
 * (ETSI EN 300 468 §6.2.37).
 *
 * <p>What an EPG shows in a grid cell. A programme carries one of these per
 * language it is announced in, which is why {@link Event} keeps the whole
 * descriptor list and offers this as the first of them rather than the only one.
 *
 * @param language    the ISO 639-2 three-letter code this text is in
 * @param eventName   the programme's title
 * @param description the one-line synopsis beneath it, often empty
 */
public record ShortEventDescriptor(String language, String eventName, String description) {

    /** The language code's fixed width, in bytes. */
    private static final int LANGUAGE_LENGTH = 3;

    /**
     * Reads one from its descriptor.
     *
     * @param descriptor a descriptor whose tag is {@link Descriptor#TAG_SHORT_EVENT}
     * @return the parsed descriptor, or {@code null} when the tag is wrong or the
     *         payload is too short for the lengths it declares
     */
    public static ShortEventDescriptor from(Descriptor descriptor) {
        if (descriptor == null || descriptor.tag() != Descriptor.TAG_SHORT_EVENT) {
            return null;
        }
        byte[] payload = descriptor.payload();
        if (payload.length < LANGUAGE_LENGTH + 1) {
            return null;
        }

        String language = new String(payload, 0, LANGUAGE_LENGTH,
                java.nio.charset.StandardCharsets.US_ASCII);

        int nameLengthAt = LANGUAGE_LENGTH;
        int nameLength = payload[nameLengthAt] & 0xFF;
        int nameStart = nameLengthAt + 1;
        int textLengthAt = nameStart + nameLength;
        if (textLengthAt >= payload.length) {
            return null; // the name claims more than the descriptor holds
        }

        int textLength = payload[textLengthAt] & 0xFF;
        int textStart = textLengthAt + 1;
        if (textStart + textLength > payload.length) {
            return null;
        }

        return new ShortEventDescriptor(language,
                DvbText.decode(payload, nameStart, nameLength),
                DvbText.decode(payload, textStart, textLength));
    }
}
