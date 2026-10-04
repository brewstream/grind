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

package org.brewstream.grind.fmp4;

import org.brewstream.grind.fmp4.cli.TsToMp4;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** External decoders must agree on every audio sample and on the A/V timestamps. */
@Tag("interop")
class AacInteropTest {
    @TempDir Path temporary;

    private static byte[] run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        byte[] result = p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", command)).isZero();
        return result;
    }

    private static boolean available(String command) {
        try {
            return new ProcessBuilder(command, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (Exception absent) {
            return false;
        }
    }

    private static List<Double> timestamps(Path file, String track) throws Exception {
        String csv = new String(run("ffprobe", "-v", "error", "-select_streams", track,
                "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.toString()),
                java.nio.charset.StandardCharsets.UTF_8);
        List<Double> times = new ArrayList<>();
        for (String line : csv.lines().toList()) {
            if (!line.isBlank()) {
                times.add(Double.parseDouble(line.split(",")[0]));
            }
        }
        return times;
    }

    @Test
    void allAudioDecodesIdenticallyAndKeepsItsOffsetFromVideo() throws Exception {
        assumeTrue(available("ffmpeg") && available("ffprobe"));
        for (String fixture : List.of("sample", "bframes", "multiprogram", "stereo48")) {
            Path input = temporary.resolve(fixture + ".ts");
            try (var in = getClass().getResourceAsStream("/" + fixture + ".ts")) {
                Files.copy(in, input);
            }
            Path output = temporary.resolve(fixture + ".mp4");
            TsToMp4.main(new String[] {input.toString(), output.toString()});
            byte[] sourcePcm = run("ffmpeg", "-v", "error", "-i", input.toString(),
                    "-map", "0:a:0", "-f", "s16le", "-");
            byte[] outputPcm = run("ffmpeg", "-v", "error", "-i", output.toString(),
                    "-map", "0:a:0", "-f", "s16le", "-");
            assertThat(sourcePcm.length).isPositive();
            assertThat(outputPcm).as("%s decoded PCM", fixture).isEqualTo(sourcePcm);
            List<Double> sourceAudio = timestamps(input, "a:0");
            List<Double> resultAudio = timestamps(output, "a:0");
            assertThat(resultAudio).hasSameSizeAs(sourceAudio);
            for (int i = 0; i < sourceAudio.size(); i++) {
                assertThat(resultAudio.get(i)).as("%s audio PTS %d", fixture, i)
                        .isCloseTo(sourceAudio.get(i), org.assertj.core.data.Offset.offset(0.000024));
            }
            assertThat(timestamps(output, "v:0")).isEqualTo(timestamps(input, "v:0"));
            String info = new String(run("ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=codec_name,sample_rate,channels", "-of", "csv=p=0",
                    output.toString()), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(info.trim()).isEqualTo(fixture.equals("stereo48") ? "aac,48000,2" : "aac,44100,1");
        }
    }
}
