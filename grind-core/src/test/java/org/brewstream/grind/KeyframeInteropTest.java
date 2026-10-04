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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The promise of a keyframe is that it decodes with nothing else. Each one is
 * written out alone and handed to ffmpeg, which must decode exactly one frame
 * and report no errors.
 */
@Tag("interop")
class KeyframeInteropTest {

    @ParameterizedTest
    @CsvSource({
            "/keyframe-h264.ts, h264",
            "/keyframe-h264-baseline.ts, h264",
            "/keyframe-hevc.ts, hevc",
            "/keyframe-hevc-main10.ts, hevc"})
    void aKeyframeDecodesOnItsOwn(String fixture, String format) throws Exception {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "ffmpeg and ffprobe are needed");
        Keyframe keyframe = KeyframeExtractorTest.run(fixture).latest().orElseThrow();
        Path still = Files.createTempFile("keyframe-", "." + format);
        still.toFile().deleteOnExit();
        Files.write(still, keyframe.data());

        String errors = output("ffmpeg", "-v", "error", "-f", format, "-i", still.toString(), "-f", "null", "-");
        String frames = output("ffprobe", "-v", "error", "-f", format, "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", still.toString());

        assertThat(errors).as("ffmpeg decoding the keyframe alone").isEmpty();
        assertThat(frames.replaceAll("[^0-9]", "")).as("frames decoded").isEqualTo("1");
    }

    private static String output(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
        return out;
    }

    private static boolean available(String command) {
        try {
            return new ProcessBuilder(command, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (Exception absent) {
            return false;
        }
    }
}