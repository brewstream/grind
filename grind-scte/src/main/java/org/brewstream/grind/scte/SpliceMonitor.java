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

import org.brewstream.grind.ElementaryStream;
import org.brewstream.grind.PesHeader;
import org.brewstream.grind.ProgramMap;
import org.brewstream.grind.ProgramMapTable;
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
    private final byte[] payload = new byte[TsPacket.LENGTH];
    private final List<Consumer<SpliceEvent>> listeners = new ArrayList<>();

    private ProgramMap programs = ProgramMap.EMPTY;
    // Derived from the program map, so a packet is classified without allocating:
    // splice PIDs, the video PID of each one's program (index into videoPids, or -1),
    // and the latest PTS seen on each video PID.
    private int[] splicePids = new int[0];
    private int[] spliceVideo = new int[0];
    private int[] videoPids = new int[0];
    private long[] videoPts = new long[0];
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
        ProgramMap next = programs == null ? ProgramMap.EMPTY : programs;
        if (next.equals(this.programs)) {
            return;
        }
        this.programs = next;
        List<Integer> splice = new ArrayList<>();
        List<Integer> spliceVideoPid = new ArrayList<>();
        List<Integer> video = new ArrayList<>();
        for (ProgramMapTable table : next.programs().values()) {
            int videoPid = table.streams().stream()
                    .filter(stream -> stream.streamType().kind() == StreamType.Kind.VIDEO)
                    .mapToInt(ElementaryStream::pid)
                    .findFirst()
                    .orElse(-1);
            for (ElementaryStream stream : table.streams()) {
                if (stream.streamType() == StreamType.SCTE35) {
                    splice.add(stream.pid());
                    spliceVideoPid.add(videoPid);
                    if (videoPid >= 0 && !video.contains(videoPid)) {
                        video.add(videoPid);
                    }
                }
            }
        }
        long[] pts = new long[video.size()];
        for (int i = 0; i < pts.length; i++) {
            int previous = indexOf(videoPids, video.get(i));
            pts[i] = previous < 0 ? -1 : videoPts[previous];
        }
        splicePids = splice.stream().mapToInt(Integer::intValue).toArray();
        spliceVideo = spliceVideoPid.stream().mapToInt(pid -> pid < 0 ? -1 : video.indexOf(pid)).toArray();
        videoPids = video.stream().mapToInt(Integer::intValue).toArray();
        videoPts = pts;
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
        int video = indexOf(videoPids, packet.pid());
        if (video >= 0) {
            trackPts(packet, video);
            return null;
        }
        int splice = indexOf(splicePids, packet.pid());
        if (splice < 0) {
            return null;
        }
        long arrivalVideoPts = spliceVideo[splice] < 0 ? -1 : videoPts[spliceVideo[splice]];

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
            last = new SpliceEvent(packet.pid(), parsed, lastPcr, ++sequence, arrivalVideoPts);
            for (Consumer<SpliceEvent> listener : listeners) {
                listener.accept(last);
            }
        }
        return last;
    }

    /**
     * Notes the PTS a video PES starts with. In transmission order, so with B-frames
     * it steps back and forth by a frame or two; that is what "the latest PTS" means,
     * and it is what TSDuck measures against too.
     */
    private void trackPts(TsPacket packet, int video) {
        if (!packet.payloadUnitStart() || !packet.hasPayload()) {
            return;
        }
        int length = packet.payloadInto(payload, 0);
        PesHeader header = PesHeader.parse(payload, 0, length);
        if (header != null && header.hasPts()) {
            videoPts[video] = header.pts();
        }
    }

    private static int indexOf(int[] pids, int pid) {
        for (int i = 0; i < pids.length; i++) {
            if (pids[i] == pid) {
                return i;
            }
        }
        return -1;
    }

    /** Splice PIDs the tables declare, which may legitimately be carrying nothing. */
    public List<Integer> splicePids() {
        return java.util.Arrays.stream(splicePids).boxed().toList();
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
