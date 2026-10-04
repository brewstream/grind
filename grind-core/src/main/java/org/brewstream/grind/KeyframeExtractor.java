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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the latest self-contained keyframe of a stream's video: its parameter
 * sets and one complete IDR picture, ready for a decoder that has seen nothing
 * else. Nothing is decoded here; a browser decoding one frame with WebCodecs is
 * the intended consumer, which is how a relay shows a thumbnail without a video
 * decoder in the JVM.
 *
 * <p><b>Why this works.</b> In MPEG-TS the parameter sets (H.264 SPS and PPS,
 * HEVC VPS, SPS and PPS) travel in-band and are repeated before every IDR, so
 * parameter sets plus one IDR decode on their own: no reference frames, no
 * history. Measured on ffmpeg's output for both codecs before this was written.
 *
 * <p><b>Use.</b> Tell it the program map whenever it changes, and give it every
 * packet. It picks the first H.264 or HEVC track from the map, never a hardcoded
 * PID. Access units are reassembled by {@link AccessUnitAssembler}, so a unit
 * missing packets is dropped, and a torn picture is never kept.
 *
 * <pre>{@code
 * KeyframeExtractor keyframes = new KeyframeExtractor();
 * analyzer.addListener(new TsStreamListener() {
 *     public void onProgramsChanged(ProgramMap programs) { keyframes.programsChanged(programs); }
 * });
 * keyframes.enable();
 * // for each packet: analyzer.consume(packet); keyframes.consume(packet);
 * keyframes.latest();   // Optional<Keyframe>
 * }</pre>
 *
 * <p><b>Off by default, and free while off.</b> A relay wants keyframes only
 * while someone is looking. Disabled, {@link #consume} returns at once: no
 * reassembly, no allocation. Enabling starts afresh at the next unit start.
 *
 * <p>Not thread-safe: one instance per stream, fed from one thread.
 */
public final class KeyframeExtractor {

    /** Largest keyframe kept. A bigger one is skipped rather than buffered. */
    public static final int MAX_KEYFRAME_BYTES = 2 * 1024 * 1024;

    private static final byte[] START_CODE = {0, 0, 0, 1};

    private final int maxBytes;
    private boolean enabled;
    private int pid = -1;
    private StreamType codec;
    private AccessUnitAssembler assembler;
    private final List<byte[]> parameterSets = new ArrayList<>();
    private byte[] latestSps;
    private volatile Keyframe latest;
    private long keyframesTaken;
    private long skippedForSize;

    public KeyframeExtractor() {
        this(MAX_KEYFRAME_BYTES);
    }

    KeyframeExtractor(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Starts extracting from the next access unit. */
    public void enable() {
        enabled = true;
    }

    /** Stops extracting and drops any unit in progress. The latest keyframe is kept. */
    public void disable() {
        enabled = false;
        assembler = null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Follows the program map to the first H.264 or HEVC track. A different
     * track, or none, discards what was being assembled and the parameter sets
     * seen so far.
     */
    public void programsChanged(ProgramMap programs) {
        ElementaryStream video = programs.allStreams().stream()
                .filter(s -> s.streamType() == StreamType.H264 || s.streamType() == StreamType.HEVC)
                .findFirst()
                .orElse(null);
        int nextPid = video == null ? -1 : video.pid();
        StreamType nextCodec = video == null ? null : video.streamType();
        if (nextPid != pid || nextCodec != codec) {
            pid = nextPid;
            codec = nextCodec;
            assembler = null;
            parameterSets.clear();
            latestSps = null;
        }
    }

    /** Feeds one packet of any PID. Free while disabled. */
    public void consume(TsPacket packet) {
        if (!enabled || pid < 0 || packet.pid() != pid) {
            return;
        }
        if (assembler == null) {
            assembler = new AccessUnitAssembler(pid);
        }
        AccessUnit unit = assembler.consume(packet);
        if (unit != null) {
            inspect(unit);
        }
    }

    /** The most recent keyframe, if one has been taken. Safe to read from any thread. */
    public Optional<Keyframe> latest() {
        return Optional.ofNullable(latest);
    }

    /** The video track being followed, or -1 when the program map has none. */
    public int videoPid() {
        return pid;
    }

    /** The video codec being followed, or empty when the program map has none. */
    public Optional<StreamType> videoCodec() {
        return Optional.ofNullable(codec);
    }

    public long keyframesTaken() {
        return keyframesTaken;
    }

    /** IDRs not kept because they were larger than the limit. */
    public long skippedForSize() {
        return skippedForSize;
    }

    // -------------------------------------------------------------------------

    private void inspect(AccessUnit unit) {
        List<byte[]> nals = split(unit.data());
        List<byte[]> picture = new ArrayList<>();
        boolean idr = false;
        boolean freshParameterSets = false;
        for (byte[] nal : nals) {
            int type = type(nal);
            if (isParameterSet(type)) {
                if (!freshParameterSets) {
                    parameterSets.clear(); // a unit that carries them carries the whole set
                    freshParameterSets = true;
                }
                parameterSets.add(nal);
                if (isSps(type)) {
                    latestSps = nal;
                }
            } else if (!isDelimiter(type)) {
                picture.add(nal);
                idr |= isIdr(type);
            }
        }
        if (!idr || latestSps == null || parameterSets.isEmpty()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(unit.length() + 256);
        for (byte[] nal : parameterSets) {
            out.writeBytes(START_CODE);
            out.writeBytes(nal);
        }
        for (byte[] nal : picture) {
            out.writeBytes(START_CODE);
            out.writeBytes(nal);
        }
        if (out.size() > maxBytes) {
            skippedForSize++;
            return;
        }
        String codecString = codec == StreamType.H264 ? CodecStrings.avc(latestSps) : CodecStrings.hevc(latestSps);
        latest = new Keyframe(pid, codecString, unit.hasPts() ? unit.pts() : -1, out.toByteArray());
        keyframesTaken++;
    }

    private int type(byte[] nal) {
        return codec == StreamType.H264 ? nal[0] & 0x1F : (nal[0] >> 1) & 0x3F;
    }

    private boolean isParameterSet(int type) {
        return codec == StreamType.H264 ? type == 7 || type == 8 : type == 32 || type == 33 || type == 34;
    }

    private boolean isSps(int type) {
        return codec == StreamType.H264 ? type == 7 : type == 33;
    }

    /** Access unit delimiters say nothing a lone keyframe needs. */
    private boolean isDelimiter(int type) {
        return codec == StreamType.H264 ? type == 9 : type == 35;
    }

    /** H.264 IDR slice (5); HEVC IDR_W_RADL (19), IDR_N_LP (20) or CRA (21). */
    private boolean isIdr(int type) {
        return codec == StreamType.H264 ? type == 5 : type >= 19 && type <= 21;
    }

    /** Splits Annex B bytes into NAL units, without their start codes. */
    static List<byte[]> split(byte[] annexB) {
        List<byte[]> nals = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i + 2 < annexB.length) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                if (start >= 0) {
                    nals.add(trimmed(annexB, start, i));
                }
                i += 3;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < annexB.length) {
            nals.add(trimmed(annexB, start, annexB.length));
        }
        nals.removeIf(nal -> nal.length == 0);
        return nals;
    }

    /** A NAL unit's bytes, without the trailing zeros that belong to the next start code. */
    private static byte[] trimmed(byte[] data, int from, int to) {
        while (to > from && data[to - 1] == 0) {
            to--;
        }
        return java.util.Arrays.copyOfRange(data, from, to);
    }
}