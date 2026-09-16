# Grind

MPEG-TS inspection and demultiplexing for the JVM. Part of **BrewStream**.

Grind reads an MPEG transport stream and tells you what is in it and whether it
is healthy: programs, PIDs and codecs; continuity errors; clock drift; bitrate
per track. It is a parser and an inspector, deliberately **not** a decoder — you
can answer every question a stream-health dashboard asks without decoding a
single frame, and decoding H.264 or AAC in pure Java is a different project.

It has **no dependencies** and knows nothing about how the bytes arrived. Pair it
with [Roast](https://github.com/brewstream/roast) for SRT ingest, or feed it from
a file, UDP socket, or anything else.

**Status:** early. The transport packet layer is implemented and tested against
real streams; see the roadmap below for what is coming and in what order.

## Requirements

Java 21 or newer.

**Not yet published.** Consume it as a Gradle composite build until a release is
cut:

```groovy
// settings.gradle
includeBuild '../grind'

// build.gradle
dependencies {
    implementation 'io.github.brewstream:grind'
}
```

## Reading a stream

```java
byte[] data = Files.readAllBytes(Path.of("stream.ts"));

for (int offset = 0; offset + TsPacket.LENGTH <= data.length; offset += TsPacket.LENGTH) {
    TsPacket packet = TsPacket.parse(data, offset);
    if (packet == null) {
        // Sync byte was not where it should be: realign before continuing.
        continue;
    }
    if (packet.pcr() >= 0) {
        System.out.printf("PID 0x%X carries PCR %.3fs%n",
                packet.pid(), packet.adaptationField().pcrSeconds());
    }
}
```

`TsPacket` is a view, not a copy: `payloadOffset` and `payloadLength` index into
your own array. A transport stream at broadcast rates is tens of thousands of
packets a second, and copying each payload would cost more than everything else
the parser does.

`parse` returns `null` when the sync byte is missing, which means the caller has
lost alignment rather than that one packet is bad — the recovery is to
resynchronise, not to skip 188 bytes.

## Roadmap

v1 is the inspection layer: enough to drive a dashboard that says whether a
stream is healthy and why. Later phases widen toward full MPEG-TS.

| Phase | Scope | State |
|---|---|:---:|
| **1 — Packets and tables** | TS packet layer, adaptation fields, PCR; PSI section assembly with CRC32; PAT and PMT; continuity tracking; PES headers (PTS/DTS); per-PID bitrate and stats | packet layer done |
| **2 — Elementary streams** | PES payload reassembly into access units; track model; codec identification from `stream_type` and descriptors; frame boundaries and random-access points | planned |
| **3 — Extended metadata** | DVB tables (SDT, EIT, NIT); descriptor parsing; SCTE-35 splice information | planned |
| **4 — Output** | TS muxing: writing a conforming stream, PCR insertion, stuffing — for repackaging without transcoding | planned |
| **5 — Long tail** | Scrambled-stream structure (parse without decrypting), teletext and subtitle PIDs, multi-program selection and filtering | planned |

Phases 4 and 5 are genuinely optional and exist so the boundary is written down.
The honest v1 line is: **everything needed to inspect, nothing needed to
decode.**

## How this is verified

Parsing is checked against real transport streams, not hand-built bytes. The test
fixture is two seconds of H.264 and AAC muxed by FFmpeg, and expectations are
cross-checked against what `ffprobe` independently reports about the same file —
video on PID 0x100, audio on 0x101.

Hand-made packets test only that the parser agrees with whoever wrote the test. A
real multiplexer's output tests that it agrees with the world, including the
stuffing, adaptation fields and PCR placement a hand-written fixture would never
think to include. Edge cases a clean stream cannot produce — a missing sync byte,
an adaptation field longer than its packet, a PCR flag with no room for a PCR —
are built by hand, because they have to be.

Where a test could pass vacuously by skipping everything, it asserts how much it
actually examined.

```sh
./gradlew check          # tests, javadoc, licence headers
./gradlew interopTest    # cross-checks against ffprobe/TSDuck; skips if absent
```

Tests are JUnit 5 with AssertJ assertions.

## Reference

ISO/IEC 13818-1 (MPEG-2 Systems) is the normative source, and the javadoc cites
it by section where a decision follows from the spec. `ffprobe` and
[TSDuck](https://tsduck.io) serve as independent cross-checks — TSDuck in
particular is the tool to reach for when Grind and reality disagree.

## Licence

[Apache License 2.0](LICENSE). Every source file carries the header;
`scripts/license-header.sh` adds it to anything missing one, and `--check` fails
if something is. Grind incorporates no third-party code — see [NOTICE](NOTICE).
