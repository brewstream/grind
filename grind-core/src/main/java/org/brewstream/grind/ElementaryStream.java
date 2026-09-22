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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One track within a program: a PID, what it carries, and what the PMT said
 * about it.
 *
 * @param pid         the PID this track's packets arrive on
 * @param streamType  what it carries, resolved where recognised
 * @param rawType     the {@code stream_type} byte, preserved so an unrecognised track
 *                    can still be named precisely
 * @param descriptors the track's own descriptor loop from the PMT, in table order
 */
public record ElementaryStream(
        int pid, StreamType streamType, int rawType, List<Descriptor> descriptors) {

    /** A language code is three letters, and every entry in the descriptor is four bytes. */
    private static final int LANGUAGE_ENTRY_LENGTH = 4;

    /**
     * A track with no descriptors, for callers constructing one directly.
     *
     * <p>Kept so that code predating descriptor parsing still compiles and means
     * the same thing: a PMT entry whose descriptor loop was empty and one whose
     * descriptors were never read are the same track as far as anything but
     * {@link #language()} is concerned.
     */
    public ElementaryStream(int pid, StreamType streamType, int rawType) {
        this(pid, streamType, rawType, List.of());
    }

    /** A label for display: the known name, or the raw type in hex when it is not known. */
    public String label() {
        return streamType == StreamType.UNKNOWN
                ? "type 0x" + Integer.toHexString(rawType).toUpperCase()
                : streamType.description();
    }

    /**
     * The track's language as an ISO 639-2 three-letter code, or {@code null}
     * when the PMT did not say.
     *
     * <p>This is what lets a repackager pick the English audio rather than
     * whichever audio track the muxer happened to list first — the difference
     * between a correct output and one that is correct on the streams that were
     * tested.
     *
     * <p>The first code, where the descriptor carries several. A track announced
     * in more than one language is rare and means something specific — a
     * commentary mix, usually — and a caller that cares should read
     * {@link #languages()} rather than have this guess.
     */
    public String language() {
        List<String> languages = languages();
        return languages.isEmpty() ? null : languages.get(0);
    }

    /**
     * Every language the track's {@code ISO_639_language_descriptor} announces,
     * in order. Empty when it carries none.
     *
     * <p>The audio type accompanying each code — clean effects, hearing
     * impaired, visual impairment commentary — is not surfaced. It is a property
     * of the mix rather than of the track's identity, and nothing here can check
     * it against the audio.
     */
    public List<String> languages() {
        Descriptor descriptor = Descriptor.find(descriptors, Descriptor.TAG_LANGUAGE);
        if (descriptor == null) {
            return List.of();
        }
        byte[] payload = descriptor.payload();
        List<String> languages = new java.util.ArrayList<>();
        for (int cursor = 0; cursor + LANGUAGE_ENTRY_LENGTH <= payload.length;
                cursor += LANGUAGE_ENTRY_LENGTH) {
            languages.add(new String(payload, cursor, 3, StandardCharsets.US_ASCII));
        }
        return List.copyOf(languages);
    }
}
