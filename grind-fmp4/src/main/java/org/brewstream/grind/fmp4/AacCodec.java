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

/**
 * AAC-LC carried in ADTS, repackaged without decoding.
 *
 * <p>Supports indexed sample rates up to 48 kHz and channel configurations 1–7
 * (7 describes eight channels). PCE layouts, other profiles and multiple raw
 * data blocks per ADTS frame are rejected explicitly. CRC-protected headers are
 * stripped, but the CRC is not validated. Configuration changes require a new
 * codec and init segment.
 */
public final class AacCodec {
    private static final int[] RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350
    };
    private Header configuration;

    record Header(int length, int headerLength, int frequencyIndex, int channelConfiguration) {
    }

    static Header header(byte[] data, int at) {
        if ((data[at] & 0xff) != 0xff || (data[at + 1] & 0xf6) != 0xf0) {
            throw new IllegalArgumentException("invalid ADTS sync word or layer");
        }
        int objectType = ((data[at + 2] & 0xff) >>> 6) + 1;
        int frequency = (data[at + 2] >>> 2) & 15;
        int channels = ((data[at + 2] & 1) << 2) | ((data[at + 3] >>> 6) & 3);
        if (objectType != 2) {
            throw new IllegalArgumentException("only AAC-LC is supported");
        }
        if (frequency >= RATES.length || RATES[frequency] > 65535) {
            throw new IllegalArgumentException("unsupported ADTS sample rate");
        }
        if (channels == 0) {
            throw new IllegalArgumentException("AAC program-config-element layouts are not supported");
        }
        if ((data[at + 6] & 3) != 0) {
            throw new IllegalArgumentException("multiple raw data blocks per ADTS frame are not supported");
        }
        int size = ((data[at + 3] & 3) << 11) | ((data[at + 4] & 0xff) << 3)
                | ((data[at + 5] & 0xff) >>> 5);
        int headerSize = (data[at + 1] & 1) != 0 ? 7 : 9;
        if (size <= headerSize) {
            throw new IllegalArgumentException("ADTS frame length does not include an audio payload");
        }
        return new Header(size, headerSize, frequency, channels);
    }

    void configure(Header header) {
        if (configuration != null && (configuration.frequencyIndex() != header.frequencyIndex()
                || configuration.channelConfiguration() != header.channelConfiguration())) {
            throw new IllegalArgumentException("AAC configuration changed; start a new track and init segment");
        }
        configuration = header;
    }

    /** Whether a complete ADTS frame has provided the track configuration. */
    public boolean isConfigured() {
        return configuration != null;
    }

    /** Audio track timescale: one tick per decoded audio sample. */
    public int sampleRate() {
        requireConfiguration();
        return RATES[configuration.frequencyIndex()];
    }

    /** Number of output channels. */
    public int channels() {
        requireConfiguration();
        int value = configuration.channelConfiguration();
        return value == 7 ? 8 : value;
    }

    /** MPEG-4 AudioSpecificConfig for AAC-LC, with 1024 samples per frame. */
    public byte[] audioSpecificConfig() {
        requireConfiguration();
        int bits = (2 << 11) | (configuration.frequencyIndex() << 7)
                | (configuration.channelConfiguration() << 3);
        return new byte[] {(byte) (bits >>> 8), (byte) bits};
    }

    void writeConfiguration(BoxWriter entry) {
        // ISO/IEC 14496-1 descriptors. All bodies fit in a one-byte length.
        entry.fullBox("esds", 0, 0, esds -> esds
                .u8(3).u8(25).u16(0).u8(0) // ES_Descriptor, no optional fields
                .u8(4).u8(17)              // DecoderConfigDescriptor
                .u8(0x40).u8(0x15)         // MPEG-4 Audio, audio stream, reserved bit
                .u24(0).u32(0).u32(0)      // unspecified buffer size and bit rates
                .u8(5).u8(2).bytes(audioSpecificConfig())
                .u8(6).u8(1).u8(2));       // SLConfigDescriptor: MP4 predefined
    }

    private void requireConfiguration() {
        if (!isConfigured()) {
            throw new IllegalStateException("AAC has not received a complete ADTS frame");
        }
    }
}
