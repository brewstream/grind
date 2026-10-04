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

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.text.Normalizer;

/**
 * Decodes a DVB text string (ETSI EN 300 468 Annex A).
 *
 * <p><b>A DVB string is not a Java string with a charset applied to it.</b> It
 * chooses its own character table, with an optional control code at the front:
 * a first byte of {@code 0x20} or above means the default table, and a first
 * byte below that selects another one and is not itself part of the text.
 * Decoding the bytes as UTF-8, or as ISO-8859-1, gets the common case right and
 * turns every other case into mojibake on a dashboard. The failure is silent,
 * looks like a font problem, and gets blamed on one for a while.
 *
 * <p>Measured rather than assumed: ffmpeg emits no selector at all for a
 * pure-ASCII service name, and a {@code 0x15} (UTF-8) selector as soon as one
 * character is not ASCII. Both shapes are in {@code dvb.ts}, put there by the
 * muxer rather than by this class's author.
 *
 * <p><b>The default table is ISO/IEC 6937, not Latin-1.</b> They agree across
 * {@code 0x20}-{@code 0x7E} apart from one position, and diverge entirely above
 * it: 6937 spends {@code 0xC1}-{@code 0xCF} on combining diacritics that
 * <em>precede</em> the letter they modify, so {@code 0xC2 0x65} is a two-byte
 * "e-acute". Read as Latin-1 that is two characters, the first of them a
 * capital A with circumflex.
 *
 * <p>What is implemented: the default table, the ISO 8859 family by both routes
 * Annex A allows, UTF-8 and UTF-16BE. What is not: the East Asian tables
 * ({@code 0x12} KSX1001, {@code 0x13} GB-2312, {@code 0x14} Big5) and the
 * {@code 0x1F} encoding-type escape. Those need character sets the JDK does not
 * always carry, and no tool available here produces one to check against. They
 * decode to replacement characters rather than to plausible nonsense, so an
 * unreadable name reads as unreadable instead of as the wrong name.
 */
public final class DvbText {

    /** The first byte value that is text rather than a character-table selector. */
    private static final int FIRST_TEXT_BYTE = 0x20;

    /** What an undecodable byte becomes, rather than a character it is not. */
    private static final char REPLACEMENT = '\uFFFD';

    /** Marks a table position the standard leaves unassigned. */
    private static final char UNUSED = '\0';

    /**
     * ISO/IEC 6937's upper half, {@code 0xA0}-{@code 0xFF}, one character per
     * byte, indexed from {@code 0xA0}.
     *
     * <p>Written as escapes rather than as literal characters so that the bytes
     * this produces cannot depend on the encoding the compiler happened to read
     * the source with. Everywhere else in this project that would be pedantry;
     * here a source file misread as Latin-1 would silently change a character
     * table into a slightly different character table, which is the kind of bug
     * that reaches a screen and not a test.
     *
     * <p>{@link #UNUSED} marks an unassigned position, and covers
     * {@code 0xC1}-{@code 0xCF}, which never reach this table because the
     * combining range is handled a byte at a time. The sentinel is not a space,
     * because {@code 0xA0} genuinely is one and using a space to mean "nothing
     * here" would turn it into a replacement character.
     *
     * <p>{@code 0xA4} is the dollar sign and {@code 0x24} the generic currency
     * sign, which is the reverse of Latin-1 and the one divergence below
     * {@code 0x7F}.
     */
    private static final String UPPER_HALF =
            "\u0020\u00A1\u00A2\u00A3\u0024\u00A5\u0000\u00A7"  // A0
            + "\u00A4\u2018\u201C\u00AB\u2190\u2191\u2192\u2193"
            + "\u00B0\u00B1\u00B2\u00B3\u00D7\u00B5\u00B6\u00B7"  // B0
            + "\u00F7\u2019\u201D\u00BB\u00BC\u00BD\u00BE\u00BF"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"  // C0 unassigned; the combining range is handled before this table
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u2015\u00B9\u00AE\u00A9\u2122\u266A\u00AC\u00A6"  // D0
            + "\u0000\u0000\u0000\u0000\u215B\u215C\u215D\u215E"
            + "\u2126\u00C6\u0110\u00AA\u0126\u0000\u0132\u013F"  // E0
            + "\u0141\u00D8\u0152\u00BA\u00DE\u0166\u014A\u0149"
            + "\u0138\u00E6\u0111\u00F0\u0127\u0131\u0133\u0140"  // F0
            + "\u0142\u00F8\u0153\u00DF\u00FE\u0167\u014B\u00AD";

    /**
     * The combining marks {@code 0xC1}-{@code 0xCF} select, as Unicode combining
     * characters, indexed from {@code 0xC1}.
     *
     * <p>Composed onto the byte that <em>follows</em> and then normalised, so
     * that an accented letter arrives as one character rather than as a letter
     * plus a floating mark — which matters the moment anything compares two
     * service names for equality.
     */
    private static final char[] DIACRITICS = {
            '\u0300', // C1 grave
            '\u0301', // C2 acute
            '\u0302', // C3 circumflex
            '\u0303', // C4 tilde
            '\u0304', // C5 macron
            '\u0306', // C6 breve
            '\u0307', // C7 dot above
            '\u0308', // C8 diaeresis
            '\u0000', // C9 unassigned
            '\u030A', // CA ring above
            '\u0327', // CB cedilla
            '\u0332', // CC low line
            '\u030B', // CD double acute
            '\u0328', // CE ogonek
            '\u030C', // CF caron
    };

    private DvbText() {
    }

    /**
     * Decodes a span of bytes as a DVB string.
     *
     * @param data   the bytes the string sits in
     * @param offset where it starts, at its selector if it has one
     * @param length how many bytes long it is, selector included
     * @return the text, or an empty string when the span is empty or lies
     *         outside {@code data}
     */
    public static String decode(byte[] data, int offset, int length) {
        if (length <= 0 || offset < 0 || offset + length > data.length) {
            return "";
        }

        int first = data[offset] & 0xFF;
        if (first >= FIRST_TEXT_BYTE) {
            return decodeIso6937(data, offset, length);
        }

        // A selector, which is consumed rather than displayed.
        int bodyStart = offset + 1;
        int bodyLength = length - 1;
        switch (first) {
            case 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B:
                // 0x01 is ISO 8859-5, and they run consecutively from there.
                return decodeCharset("ISO-8859-" + (first + 4), data, bodyStart, bodyLength);
            case 0x10: {
                // Three bytes: 0x10 0x00 nn selects ISO 8859-nn. The long form
                // exists because the short one above has no room for 8859-1.
                if (bodyLength < 2) {
                    return "";
                }
                int part = data[offset + 2] & 0xFF;
                return decodeCharset("ISO-8859-" + part, data, offset + 3, length - 3);
            }
            case 0x11:
                return decodeCharset("UTF-16BE", data, bodyStart, bodyLength);
            case 0x15:
                return new String(data, bodyStart, bodyLength, StandardCharsets.UTF_8);
            default:
                // 0x12-0x14 East Asian, 0x1F an encoding-type escape, and the
                // reserved values. Saying the bytes are unreadable is more
                // honest than decoding them as something they are not.
                return String.valueOf(REPLACEMENT).repeat(Math.max(1, bodyLength));
        }
    }

    /**
     * Decodes through a named {@link Charset}.
     *
     * <p>Falls back to replacement characters when the JDK does not carry the
     * table. Several of the ISO 8859 parts are optional, and {@code 8859-16} is
     * routinely absent, so this is a real path rather than a formality.
     */
    private static String decodeCharset(String name, byte[] data, int offset, int length) {
        if (length <= 0 || offset < 0 || offset + length > data.length) {
            return "";
        }
        try {
            return new String(data, offset, length, Charset.forName(name));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return String.valueOf(REPLACEMENT).repeat(length);
        }
    }

    /**
     * Decodes the default table.
     *
     * <p>Control codes {@code 0x80}-{@code 0x9F} are dropped rather than shown,
     * except {@code 0x8A}, which EN 300 468 defines as a line break. They are
     * instructions to a set-top box's renderer, and emitting them as characters
     * would put unprintable bytes into a name something is about to display.
     */
    private static String decodeIso6937(byte[] data, int offset, int length) {
        StringBuilder text = new StringBuilder(length);
        int end = offset + length;
        for (int cursor = offset; cursor < end; cursor++) {
            int b = data[cursor] & 0xFF;

            if (b < 0x20 || (b >= 0x80 && b <= 0x9F)) {
                if (b == 0x8A) {
                    text.append('\n');
                }
                continue; // a rendering control code, not text
            }

            if (b < 0x80) {
                text.append(b == 0x24 ? '\u00A4' : (char) b);
                continue;
            }

            if (b >= 0xC1 && b <= 0xCF) {
                char mark = DIACRITICS[b - 0xC1];
                // A mark at the very end modifies nothing, and an unassigned one
                // names no mark at all. Both are malformed rather than empty.
                if (mark == UNUSED || cursor + 1 >= end) {
                    text.append(REPLACEMENT);
                    continue;
                }
                char base = (char) (data[cursor + 1] & 0xFF);
                text.append(Normalizer.normalize(
                        String.valueOf(base) + mark, Normalizer.Form.NFC));
                cursor++;
                continue;
            }

            char mapped = b >= 0xA0 ? UPPER_HALF.charAt(b - 0xA0) : UNUSED;
            text.append(mapped == UNUSED ? REPLACEMENT : mapped);
        }
        return text.toString();
    }
}
