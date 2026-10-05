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

/** Which clock a splice event's pre-roll was measured against. */
public enum PreRollBasis {
    /**
     * The latest video PTS in the splice PID's program when the section arrived:
     * the warning equipment that acts on frames as they arrive actually gets.
     */
    VIDEO_PTS,
    /**
     * The program clock when the section arrived, because the program had no video
     * PTS yet, or no video. Longer than {@link #VIDEO_PTS} by the mux delay.
     */
    PCR,
    /** No pre-roll: the section names no time, or no clock had been seen. */
    NONE
}
