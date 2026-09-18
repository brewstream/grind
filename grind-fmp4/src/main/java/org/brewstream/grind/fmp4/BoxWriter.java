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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Writes ISO base media file format boxes, as ISO/IEC 14496-12 defines them.
 *
 * <p>A box is a length, a four-character type, and a body — and bodies contain
 * boxes, so the length of an outer box is not known until every inner one has
 * been written. {@link #box} handles that by writing the children first and
 * back-filling the size, which is the only part of this that is not clerical.
 *
 * <p>Everything is big-endian. The format predates any argument about that.
 */
final class BoxWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /**
     * Writes a box whose body is produced by {@code body}.
     *
     * <p>The body writes into the writer it is handed, so its length is known
     * before the header goes out. Passing it explicitly rather than holding the
     * active writer in a field keeps this free of hidden state — a box tree is
     * built by recursion, and a shared cursor would have to be unwound correctly
     * on every path including the ones that throw.
     */
    BoxWriter box(String type, Consumer<BoxWriter> body) {
        BoxWriter inner = new BoxWriter();
        body.accept(inner);
        byte[] payload = inner.toByteArray();
        u32(8 + payload.length);
        type(type);
        return bytes(payload);
    }

    /**
     * Writes a full box: a box whose body opens with a version and flags.
     *
     * <p>The distinction matters because the version decides field widths — a
     * {@code tfdt} with version 1 carries a 64-bit decode time and with version 0
     * a 32-bit one, and a reader trusts the version rather than the length.
     */
    BoxWriter fullBox(String type, int version, int flags, Consumer<BoxWriter> body) {
        return box(type, inner -> {
            inner.u8(version);
            inner.u24(flags);
            body.accept(inner);
        });
    }

    BoxWriter u8(int value) {
        out.write(value & 0xFF);
        return this;
    }

    BoxWriter u16(int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
        return this;
    }

    BoxWriter u24(int value) {
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
        return this;
    }

    BoxWriter u32(long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
        return this;
    }

    BoxWriter u64(long value) {
        u32(value >>> 32);
        u32(value & 0xFFFFFFFFL);
        return this;
    }

    /** A signed 32-bit value, for composition offsets that may run negative. */
    BoxWriter s32(int value) {
        return u32(Integer.toUnsignedLong(value));
    }

    /** A four-character code, which must be exactly four ASCII characters. */
    BoxWriter type(String code) {
        byte[] ascii = code.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length != 4) {
            throw new IllegalArgumentException("not a four-character code: " + code);
        }
        return bytes(ascii);
    }

    BoxWriter bytes(byte[] value) {
        out.writeBytes(value);
        return this;
    }

    /** {@code count} zero bytes, for the reserved fields these boxes are full of. */
    BoxWriter zeros(int count) {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
        return this;
    }

    /** A 16.16 fixed-point value, the format's notion of a fraction. */
    BoxWriter fixed16_16(double value) {
        return u32(Math.round(value * 65536.0));
    }

    int length() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
