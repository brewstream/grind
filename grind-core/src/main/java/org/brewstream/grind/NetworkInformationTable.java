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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NIT: what network this multiplex belongs to, and what else is on it
 * (ETSI EN 300 468 §5.2.1).
 *
 * <p>The one table here that describes things <em>outside</em> the stream being
 * read. Its transport stream loop lists sibling multiplexes and the delivery
 * systems they arrive on — frequencies, modulation, orbital positions — so a
 * receiver can retune. Nothing in that loop can be verified from the stream in
 * hand, which is why the delivery descriptors are kept uninterpreted: parsing a
 * satellite frequency Grind can never check is a liability rather than a
 * feature.
 *
 * <p>What is worth reading is the network name and the service list, and both
 * are.
 *
 * @param networkId          this network's id, from the section header
 * @param networkDescriptors what the table says about the network itself
 * @param transportStreams   the multiplexes the network carries, in table order
 */
public record NetworkInformationTable(
        int networkId,
        List<Descriptor> networkDescriptors,
        List<TransportStream> transportStreams) {

    /** The fixed part of each transport stream entry, before its descriptor loop. */
    private static final int ENTRY_LENGTH = 6;

    /**
     * One multiplex the network carries.
     *
     * @param transportStreamId its id within the network
     * @param originalNetworkId who originated it
     * @param descriptors       how to find it and what it carries, uninterpreted
     */
    public record TransportStream(
            int transportStreamId,
            int originalNetworkId,
            List<Descriptor> descriptors) {

        /**
         * The services this multiplex carries, from its
         * {@code service_list_descriptor}: service id to service type.
         *
         * <p>Empty when the entry carries no such descriptor, which is common —
         * the descriptor is optional and many networks announce only the tuning
         * parameters.
         */
        public Map<Integer, Integer> services() {
            Descriptor list = Descriptor.find(descriptors, Descriptor.TAG_SERVICE_LIST);
            if (list == null) {
                return Map.of();
            }
            byte[] payload = list.payload();
            Map<Integer, Integer> services = new LinkedHashMap<>();
            for (int cursor = 0; cursor + 3 <= payload.length; cursor += 3) {
                services.put(((payload[cursor] & 0xFF) << 8) | (payload[cursor + 1] & 0xFF),
                        payload[cursor + 2] & 0xFF);
            }
            return java.util.Collections.unmodifiableMap(services);
        }
    }

    /**
     * Parses a NIT section body.
     *
     * @param section a section whose table id is {@link TableSection#TABLE_ID_NIT_ACTUAL}
     *                or {@link TableSection#TABLE_ID_NIT_OTHER}
     * @return the parsed table, or {@code null} if the body is truncated or a
     *         declared length runs past the end
     */
    public static NetworkInformationTable parse(TableSection section) {
        byte[] body = section.body();
        if (body.length < 2) {
            return null;
        }

        int networkDescriptorsLength = ((body[0] & 0x0F) << 8) | (body[1] & 0xFF);
        int loopLengthAt = 2 + networkDescriptorsLength;
        if (loopLengthAt + 2 > body.length) {
            return null; // the network descriptors claim more than the section holds
        }
        List<Descriptor> networkDescriptors =
                Descriptor.parseLoop(body, 2, networkDescriptorsLength);

        int streamLoopLength =
                ((body[loopLengthAt] & 0x0F) << 8) | (body[loopLengthAt + 1] & 0xFF);
        int cursor = loopLengthAt + 2;
        int end = cursor + streamLoopLength;
        if (end > body.length) {
            return null;
        }

        List<TransportStream> streams = new ArrayList<>();
        while (cursor + ENTRY_LENGTH <= end) {
            int transportStreamId = ((body[cursor] & 0xFF) << 8) | (body[cursor + 1] & 0xFF);
            int originalNetworkId = ((body[cursor + 2] & 0xFF) << 8) | (body[cursor + 3] & 0xFF);
            int descriptorsLength =
                    ((body[cursor + 4] & 0x0F) << 8) | (body[cursor + 5] & 0xFF);
            int descriptorsStart = cursor + ENTRY_LENGTH;
            cursor = descriptorsStart + descriptorsLength;
            if (cursor > end) {
                return null;
            }
            streams.add(new TransportStream(transportStreamId, originalNetworkId,
                    Descriptor.parseLoop(body, descriptorsStart, descriptorsLength)));
        }

        return new NetworkInformationTable(section.tableIdExtension(),
                networkDescriptors, List.copyOf(streams));
    }

    /**
     * What the network calls itself, or {@code null} when it carries no
     * {@code network_name_descriptor}.
     */
    public String networkName() {
        Descriptor name = Descriptor.find(networkDescriptors, Descriptor.TAG_NETWORK_NAME);
        if (name == null) {
            return null;
        }
        byte[] payload = name.payload();
        return DvbText.decode(payload, 0, payload.length);
    }
}
