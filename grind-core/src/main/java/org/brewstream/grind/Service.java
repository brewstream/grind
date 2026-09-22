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

import java.util.List;

/**
 * One service from the SDT: a program, described the way a viewer would
 * recognise it (ETSI EN 300 468 §5.2.3).
 *
 * <p><b>{@code serviceId} is the PAT's {@code program_number}.</b> The standard
 * says so, and that single identity is what the whole table is worth: it is the
 * join that turns "program 2 lost three packets" into "Brewstream Café lost
 * three packets". Nothing else in a transport stream connects a name to a PID.
 *
 * @param serviceId              the service's number, which is also its program number
 * @param runningStatus          whether it is on the air
 * @param scrambled              the {@code free_CA_mode} flag: at least one of the
 *                               service's streams is access-controlled. Independent of the
 *                               per-packet scrambling control bits, which say whether the
 *                               bytes in hand are encrypted; this says whether the service
 *                               is meant to be
 * @param eitSchedule            the service announces a full schedule in the EIT
 * @param eitPresentFollowing    the service announces what is on now and next
 * @param descriptors            everything the SDT said about it, uninterpreted
 */
public record Service(
        int serviceId,
        RunningStatus runningStatus,
        boolean scrambled,
        boolean eitSchedule,
        boolean eitPresentFollowing,
        List<Descriptor> descriptors) {

    /** The {@code service_descriptor}, or {@code null} when the service carries none. */
    public ServiceDescriptor description() {
        return ServiceDescriptor.from(Descriptor.find(descriptors, Descriptor.TAG_SERVICE));
    }

    /**
     * What the service is called, or {@code null} when it did not say.
     *
     * <p>Null rather than an empty string, and the difference matters to a
     * caller deciding whether to fall back to the program number: a service that
     * carries no {@code service_descriptor} has no name, while one carrying an
     * empty one has chosen to be nameless and should not be relabelled.
     */
    public String name() {
        ServiceDescriptor descriptor = description();
        return descriptor == null ? null : descriptor.serviceName();
    }

    /** Who operates it, or {@code null} when the service carries no description. */
    public String providerName() {
        ServiceDescriptor descriptor = description();
        return descriptor == null ? null : descriptor.providerName();
    }
}
