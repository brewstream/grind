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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DVB string decoding, checked against a section TSDuck encoded.
 *
 * <p>The bytes come from {@code sdt-6937.bin}, compiled by {@code tstabcomp}
 * from names given as UTF-8 XML. What character table each string ended up in
 * was TSDuck's choice, not this project's, and {@code tstabcomp -d} reads the
 * same file back as the names it started from — so the expectations below are an
 * independent tool's, twice over.
 *
 * <p>That matters more here than almost anywhere else in the parser. A decoder
 * tested against strings its own author encoded agrees with itself perfectly
 * while being wrong, and the output is still letters, so nothing looks broken.
 */
class DvbTextTest {

    /**
     * The SDT section as TSDuck wrote it, header and CRC stripped, which is what
     * {@link SectionAssembler} hands a parser.
     *
     * <p>Sliced here rather than fed through the assembler because the fixture is
     * a bare section rather than a transport stream, and wrapping sixty-eight
     * bytes in a quarter-megabyte of TS to exercise code that
     * {@link DvbTablesTest} already covers would buy nothing.
     */
    private static TableSection section() throws IOException {
        byte[] raw;
        try (InputStream in = DvbTextTest.class.getResourceAsStream("/sdt-6937.bin")) {
            raw = in.readAllBytes();
        }
        int length = 3 + (((raw[1] & 0x0F) << 8) | (raw[2] & 0xFF));
        assertThat(Crc32Mpeg.isValid(raw, 0, length))
                .as("the fixture is a real section and must pass its own checksum")
                .isTrue();

        byte[] body = Arrays.copyOfRange(raw, TableSection.LONG_HEADER_LENGTH,
                length - TableSection.CRC_LENGTH);
        int extension = ((raw[3] & 0xFF) << 8) | (raw[4] & 0xFF);
        return new TableSection(raw[0] & 0xFF, extension, (raw[5] & 0x3E) >> 1,
                (raw[5] & 0x01) != 0, raw[6] & 0xFF, raw[7] & 0xFF, body);
    }

    /**
     * A string that selects ISO 8859-15, which is how TSDuck encoded these two.
     *
     * <p>The selector byte is {@code 0x0B} and is consumed rather than shown. A
     * decoder that treated it as text would emit an unprintable character at the
     * front of every name.
     */
    @Test
    void aStringSelectingAnIso8859TableIsDecodedThroughIt() throws IOException {
        Service service = ServiceDescriptionTable.parse(section()).service(1);

        assertThat(service.name()).isEqualTo("Télé Un");
        assertThat(service.providerName()).isEqualTo("Rundfunk Öst");
    }

    /**
     * A string in the default table, where ISO 6937 and Latin-1 disagree about
     * every character.
     *
     * <p>These same bytes read as Latin-1 are {@code "êuvre ½ ñon"} and
     * {@code "é"} — still letters, still plausible, and wrong. That is the
     * failure this test exists for, so it is asserted both ways round.
     */
    @Test
    void aStringInTheDefaultTableIsIso6937RatherThanLatin1() throws IOException {
        Service service = ServiceDescriptionTable.parse(section()).service(2);

        assertThat(service.name()).isEqualTo("Œuvre ½ æon");
        assertThat(service.providerName()).isEqualTo("Ø");

        byte[] asLatin1 = {(byte) 0xEA, 'u', 'v', 'r', 'e', ' ',
                (byte) 0xBD, ' ', (byte) 0xF1, 'o', 'n'};
        assertThat(new String(asLatin1, StandardCharsets.ISO_8859_1))
                .as("what the wrong reading produces, kept here so the difference is visible")
                .isEqualTo("êuvre ½ ñon");
    }

    /** ASCII is ASCII under the default table, which is the overwhelmingly common case. */
    @Test
    void asciiNeedsNoSelectorAndIsUnchanged() {
        byte[] plain = "Brewstream One".getBytes(StandardCharsets.US_ASCII);

        assertThat(DvbText.decode(plain, 0, plain.length)).isEqualTo("Brewstream One");
    }

    /**
     * The one place ISO 6937 and ASCII disagree below 0x7F.
     *
     * <p>{@code 0x24} is the generic currency sign here and the dollar sign in
     * ASCII; {@code 0xA4} is the reverse. Worth a test of its own because it is
     * the only single-byte divergence, and therefore the one a spot check of
     * "does ASCII work" would never find.
     */
    @Test
    void theCurrencyAndDollarSignsAreSwappedAgainstAscii() {
        assertThat(DvbText.decode(new byte[] {0x24}, 0, 1)).isEqualTo("¤");
        assertThat(DvbText.decode(new byte[] {(byte) 0xA4}, 0, 1)).isEqualTo("$");
    }

    /**
     * A combining diacritic precedes the letter it modifies, and the result is
     * composed.
     *
     * <p>{@code 0xC2} is acute and {@code 0x65} is "e", so the two bytes are one
     * character. Left uncomposed it would be "e" followed by a floating accent:
     * it renders identically and compares unequal to the same name written any
     * other way, which is the kind of bug that surfaces as a duplicate row on a
     * dashboard.
     */
    @Test
    void aCombiningDiacriticIsComposedOntoTheFollowingLetter() {
        byte[] eAcute = {(byte) 0xC2, 'e'};

        assertThat(DvbText.decode(eAcute, 0, eAcute.length))
                .isEqualTo("é")
                .hasSize(1);
    }

    /** A diacritic with nothing after it modifies nothing, and is not silently dropped. */
    @Test
    void aTrailingDiacriticIsMarkedUnreadable() {
        byte[] dangling = {'a', (byte) 0xC2};

        assertThat(DvbText.decode(dangling, 0, dangling.length)).isEqualTo("a�");
    }

    /**
     * Control codes are formatting for a set-top box, not characters.
     *
     * <p>{@code 0x8A} is the line break EN 300 468 defines; the rest of
     * {@code 0x80}-{@code 0x9F} is dropped. Emitting them would put unprintable
     * bytes into a name something is about to display.
     */
    @Test
    void renderingControlCodesAreDroppedExceptTheLineBreak() {
        byte[] withControls = {'a', (byte) 0x8A, 'b', (byte) 0x86, 'c', (byte) 0x87};

        assertThat(DvbText.decode(withControls, 0, withControls.length)).isEqualTo("a\nbc");
    }

    /** A UTF-8 selector, which is what ffmpeg writes and what dvb.ts carries. */
    @Test
    void aUtf8SelectorIsDecodedAsUtf8() {
        byte[] utf8 = "Brewstream Café".getBytes(StandardCharsets.UTF_8);
        byte[] selected = new byte[utf8.length + 1];
        selected[0] = 0x15;
        System.arraycopy(utf8, 0, selected, 1, utf8.length);

        assertThat(DvbText.decode(selected, 0, selected.length)).isEqualTo("Brewstream Café");
    }

    /**
     * A table this decoder does not implement reads as unreadable rather than as
     * the wrong name.
     *
     * <p>{@code 0x13} is GB-2312. Decoding it as something else would produce
     * characters, and characters are indistinguishable from a correct answer to
     * anything downstream.
     */
    @Test
    void anUnsupportedCharacterTableIsNotGuessedAt() {
        byte[] gb2312 = {0x13, (byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4};

        assertThat(DvbText.decode(gb2312, 0, gb2312.length)).isEqualTo("����");
    }

    @Test
    void anEmptyOrOutOfRangeSpanIsEmpty() {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);

        assertThat(DvbText.decode(data, 0, 0)).isEmpty();
        assertThat(DvbText.decode(data, -1, 2)).isEmpty();
        assertThat(DvbText.decode(data, 1, 5)).isEmpty();
    }
}
