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
 * Notable things that happen to a transport stream, as they happen.
 *
 * <p>The push half of Grind's observability, deliberately separate from
 * {@link TsStreamStats}, which counts volume. High-frequency activity — every
 * packet, every byte — is counted rather than announced, because emitting an
 * event per packet at broadcast rates is pure garbage and a throughput hazard.
 * Events are for occurrences worth reacting to.
 *
 * <p><b>Called synchronously, on whichever thread fed the packet in.</b> Unlike
 * Roast's connection listeners, which are dispatched on a thread the connection
 * owns, Grind owns no threads: it is a parser invoked by whoever holds the bytes.
 * Blocking here blocks that caller — which, if the packets arrive from a Netty
 * pipeline, means blocking an event loop. Keep these short, and hand off to your
 * own executor if the work is not trivial. Bolting a thread pool onto a
 * dependency-free parser would hide that cost from the only code positioned to
 * manage it.
 *
 * <p>All methods are {@code default}, so implement only what you need.
 */
public interface TsStreamListener {

    /**
     * A continuity counter jumped, so packets were lost on this PID.
     *
     * <p>This is the signal that matters most for stream health: it is the point
     * where transport loss becomes visible media damage. Packets flagged with a
     * transport error, and jumps announced by an adaptation field's discontinuity
     * indicator, are excluded — neither is loss.
     *
     * @param pid      the PID the gap appeared on
     * @param expected the counter value that should have followed
     * @param actual   what arrived instead
     * @param lost     how many packets the gap accounts for, given the counter wraps at 16
     */
    default void onContinuityError(int pid, int expected, int actual, int lost) {
    }

    /**
     * The program clock jumped by more than a stream should ever jump, without
     * an adaptation field declaring the discontinuity. Usually a splice, a
     * restart upstream, or a genuinely broken multiplexer.
     *
     * @param pid      the PID carrying the clock
     * @param previous the previous PCR, in 27 MHz units
     * @param current  the PCR that arrived
     */
    default void onPcrDiscontinuity(int pid, long previous, long current) {
    }

    /**
     * A PID started or stopped carrying scrambled payload. Worth surfacing
     * because a stream that silently becomes encrypted looks identical to one
     * that has become corrupt, until you check this.
     */
    /**
     * A gap between consecutive PCRs longer than TR 101 290's 40ms limit.
     *
     * <p>Fired on a stream that is non-conformant but not damaged, so it is
     * usually worth sampling rather than logging: a muxer spacing PCRs at 80ms
     * fires this on every interval, several times a second, for the life of a
     * perfectly watchable stream.
     *
     * @param interval the gap in 27 MHz units; divide by 27,000 for milliseconds
     */
    default void onPcrRepetitionError(int pid, long interval) {
    }

    /**
     * A gap between a track's PTS values longer than TR 101 290's 700ms limit.
     *
     * <p>Unlike {@link #onPcrRepetitionError}, this one is quiet on ordinary
     * streams: video typically carries a PTS every 40ms and audio every few
     * hundred, so a breach is worth acting on rather than sampling.
     *
     * @param interval the gap in 27 MHz units; divide by 27,000 for milliseconds
     */
    default void onPtsRepetitionError(int pid, long interval) {
    }

    /**
     * A gap between occurrences of a PSI table longer than TR 101 290's 500ms.
     *
     * <p>Priority 1: without these tables a receiver cannot find the programs.
     * Quiet on ordinary streams, where a muxer emits them several times a second,
     * so this is worth acting on rather than sampling.
     *
     * @param pid      the PAT's PID, or the PID of the PMT that was late
     * @param interval the gap in 27 MHz units; divide by 27,000 for milliseconds
     */
    default void onTableRepetitionError(int pid, long interval) {
    }

    /**
     * A timestamp that arrived at or after the moment it was due.
     *
     * <p>Not a conformance failure — the stream is well-formed — but the clearest
     * sign it will not play smoothly, because a decoder handed a frame after its
     * deadline can only stall or drop it. Worth acting on rather than sampling:
     * on a healthy stream this never fires.
     *
     * @param skew how late, in 90 kHz units. Zero means it arrived exactly on its
     *             deadline; negative means it was already overdue
     */
    default void onLateTimestamp(int pid, long skew) {
    }

    default void onScramblingChanged(int pid, boolean scrambled) {
    }

    /**
     * The stream's table of contents changed: a PAT or PMT arrived and said
     * something different from what was known before.
     *
     * <p>Fires on the first PAT too, which is how a consumer learns what the
     * stream carries at all. After that it means a genuine change — a program
     * added or removed, a track's PID or codec changed — which on a live feed
     * usually means the source was reconfigured or switched.
     *
     * @param programs the new map, already assembled
     */
    default void onProgramsChanged(ProgramMap programs) {
    }

    /** A PID carried its first packet — how a consumer learns what is in the stream. */
    default void onPidDiscovered(int pid) {
    }

    /**
     * The sync byte was not where it should have been. Reported once per run of
     * bad bytes rather than per byte, so a badly damaged stream does not drown
     * the listener.
     *
     * @param bytesDiscarded how many bytes were skipped before sync was regained
     */
    default void onSyncLost(int bytesDiscarded) {
    }
}