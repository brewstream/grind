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
import java.util.List;

/**
 * The SDT: what the services in this multiplex are called (ETSI EN 300 468
 * §5.2.3).
 *
 * <p>Carried on PID 0x11 under two table ids — 0x42 for the services of
 * <em>this</em> transport stream and 0x46 for those of another one reachable on
 * the same network. Only the first is parsed into the stream's own picture of
 * itself; see {@link TableSection#TABLE_ID_SDT_OTHER} for why.
 *
 * @param transportStreamId which transport stream these services belong to
 * @param originalNetworkId who originated it, which with the transport stream id
 *                          identifies the multiplex globally
 * @param services          the services, in the order the table lists them
 */
public record ServiceDescriptionTable(
        int transportStreamId,
        int originalNetworkId,
        List<Service> services) {

    /**
     * The fixed part before the service loop: the original network id and a
     * reserved byte. The transport stream id is in the section header, not here.
     */
    private static final int HEADER_LENGTH = 3;

    /** The fixed part of each service entry, before its descriptor loop. */
    private static final int SERVICE_ENTRY_LENGTH = 5;

    /**
     * Parses an SDT section body.
     *
     * @param section a section whose table id is {@link TableSection#TABLE_ID_SDT_ACTUAL}
     *                or {@link TableSection#TABLE_ID_SDT_OTHER}
     * @return the parsed table, or {@code null} if the body is truncated or a
     *         declared length runs past the end
     */
    public static ServiceDescriptionTable parse(TableSection section) {
        byte[] body = section.body();
        if (body.length < HEADER_LENGTH) {
            return null;
        }

        int originalNetworkId = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);

        List<Service> services = new ArrayList<>();
        int cursor = HEADER_LENGTH;
        while (cursor + SERVICE_ENTRY_LENGTH <= body.length) {
            int serviceId = ((body[cursor] & 0xFF) << 8) | (body[cursor + 1] & 0xFF);
            int flags = body[cursor + 2] & 0xFF;
            boolean eitSchedule = (flags & 0x02) != 0;
            boolean eitPresentFollowing = (flags & 0x01) != 0;

            int statusAndLength = ((body[cursor + 3] & 0xFF) << 8) | (body[cursor + 4] & 0xFF);
            RunningStatus runningStatus = RunningStatus.fromCode((statusAndLength >> 13) & 0x07);
            boolean scrambled = (statusAndLength & 0x1000) != 0;
            int descriptorsLength = statusAndLength & 0x0FFF;

            int descriptorsStart = cursor + SERVICE_ENTRY_LENGTH;
            cursor = descriptorsStart + descriptorsLength;
            if (cursor > body.length) {
                return null; // a service's descriptors claim more than the section holds
            }

            services.add(new Service(serviceId, runningStatus, scrambled,
                    eitSchedule, eitPresentFollowing,
                    Descriptor.parseLoop(body, descriptorsStart, descriptorsLength)));
        }

        return new ServiceDescriptionTable(section.tableIdExtension(), originalNetworkId,
                List.copyOf(services));
    }

    /** The service with this id, or {@code null} if the table does not list it. */
    public Service service(int serviceId) {
        for (Service service : services) {
            if (service.serviceId() == serviceId) {
                return service;
            }
        }
        return null;
    }
}
