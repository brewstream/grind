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

import org.brewstream.grind.ProgramMap;
import org.brewstream.grind.SectionAssembler;
import org.brewstream.grind.StreamType;
import org.brewstream.grind.TableSection;
import org.brewstream.grind.TsPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Watches a transport stream for splice information and reports what it finds.
 *
 * <p>Feed it every packet. It notices which PIDs the PMT declares as SCTE-35,
 * assembles the sections they carry, and produces a {@link SpliceEvent} for each
 * — including the program clock at the moment of arrival, which is what makes
 * pre-roll measurable.
 *
 * <p>Stateful and not thread-safe, like {@code TsAnalyzer}: one per stream, used
 * from whichever thread is feeding it.
 *
 * <p><b>A splice PID is normally silent.</b> It is declared in the PMT and
 * carries nothing at all for minutes between breaks. That is not a fault, and
 * anything reporting on these streams has to expect it.
 */
public final class SpliceMonitor {

    private final Map<Integer, SectionAssembler> assemblers = new LinkedHashMap<>();
    private final List<Consumer<SpliceEvent>> listeners = new ArrayList<>();

    private ProgramMap programs = ProgramMap.EMPTY;
    private long lastPcr = -1;
    private long sequence;
    private long sectionsRead;
    private long sectionsUnreadable;

    /**
     * Tells the monitor what the stream contains.
     *
     * <p>Kept separate from packet consumption so this module needs no PSI
     * parsing of its own: a caller that already runs {@code TsAnalyzer} passes
     * {@code analyzer.stats().programs()} and the splice PIDs follow from it.
     */
    public void programs(ProgramMap programs) {
        this.programs = programs == null ? ProgramMap.EMPTY : programs;
    }

    /** Registers a listener, called synchronously as each section is read. */
    public void addListener(Consumer<SpliceEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Accounts for one packet.
     *
     * @return the event this packet completed, or null — which is the usual
     *         answer, since most packets are not splice information
     */
    public SpliceEvent consume(TsPacket packet) {
        if (packet == null) {
            return null;
        }
        // Any PCR will do as a clock here: splice times are in the program's
        // timebase, and a stream carrying splice information has one program's
        // clock running through it.
        if (packet.pcr() >= 0) {
            lastPcr = packet.pcr();
        }
        if (!isSplicePid(packet.pid())) {
            return null;
        }

        SectionAssembler assembler = assemblers.computeIfAbsent(packet.pid(), pid -> new SectionAssembler());
        SpliceEvent last = null;
        for (TableSection section : assembler.consume(packet)) {
            // A short section's body is the whole section, header included, which
            // is what the checksum covers.
            SpliceInfoSection parsed = SpliceInfoParser.parse(section.body());
            if (parsed == null) {
                sectionsUnreadable++;
                continue;
            }
            sectionsRead++;
            last = new SpliceEvent(packet.pid(), parsed, lastPcr, ++sequence);
            for (Consumer<SpliceEvent> listener : listeners) {
                listener.accept(last);
            }
        }
        return last;
    }

    private boolean isSplicePid(int pid) {
        return programs.allStreams().stream()
                .anyMatch(stream -> stream.pid() == pid && stream.streamType() == StreamType.SCTE35);
    }

    /** Splice PIDs the tables declare, which may legitimately be carrying nothing. */
    public List<Integer> splicePids() {
        return programs.allStreams().stream()
                .filter(stream -> stream.streamType() == StreamType.SCTE35)
                .map(stream -> stream.pid())
                .toList();
    }

    /** Sections read successfully. */
    public long sectionsRead() {
        return sectionsRead;
    }

    /** Sections that arrived but could not be read — a bad checksum, or a shape this library does not know. */
    public long sectionsUnreadable() {
        return sectionsUnreadable;
    }
}
