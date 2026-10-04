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
 * A {@code service_descriptor}: what a service is called and what kind of thing
 * it is (ETSI EN 300 468 §6.2.33).
 *
 * <p>This is the descriptor that pays for phase 3. Everything else in a
 * transport stream identifies a service by number, and a number is what a
 * dashboard has to show until this has been read.
 *
 * @param serviceType  the {@code service_type} byte: 0x01 digital television,
 *                     0x02 digital radio, and a long tail beyond. Kept raw rather
 *                     than resolved to an enum, because broadcasters use values the
 *                     standard has not allocated and losing them is worse than not
 *                     naming them
 * @param providerName who operates the service, often left empty or set to
 *                     whatever muxed the stream
 * @param serviceName  what the service is called — the string a viewer would
 *                     recognise
 */
public record ServiceDescriptor(int serviceType, String providerName, String serviceName) {

    /** Digital television, the value nearly every video service carries. */
    public static final int TYPE_DIGITAL_TELEVISION = 0x01;

    /** Digital radio. */
    public static final int TYPE_DIGITAL_RADIO = 0x02;

    /**
     * Reads one from its descriptor.
     *
     * @param descriptor a descriptor whose tag is {@link Descriptor#TAG_SERVICE}
     * @return the parsed descriptor, or {@code null} when the tag is wrong or the
     *         payload is too short for the lengths it declares
     */
    public static ServiceDescriptor from(Descriptor descriptor) {
        if (descriptor == null || descriptor.tag() != Descriptor.TAG_SERVICE) {
            return null;
        }
        byte[] payload = descriptor.payload();
        if (payload.length < 2) {
            return null;
        }

        int serviceType = payload[0] & 0xFF;
        int providerLength = payload[1] & 0xFF;
        int providerStart = 2;
        int nameLengthAt = providerStart + providerLength;
        if (nameLengthAt >= payload.length) {
            return null; // the provider name claims more than the descriptor holds
        }

        int nameLength = payload[nameLengthAt] & 0xFF;
        int nameStart = nameLengthAt + 1;
        if (nameStart + nameLength > payload.length) {
            return null;
        }

        return new ServiceDescriptor(serviceType,
                DvbText.decode(payload, providerStart, providerLength),
                DvbText.decode(payload, nameStart, nameLength));
    }
}
