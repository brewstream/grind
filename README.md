# Grind

MPEG-TS inspection and demultiplexing for the JVM. Part of **BrewStream**.

Grind reads an MPEG transport stream and tells you what is in it and whether it
is healthy: programs, PIDs and codecs; continuity errors; clock drift; bitrate
per track. It is a parser and an inspector, deliberately **not** a decoder — you
can answer every question a stream-health dashboard asks without decoding a
single frame, and decoding H.264 or AAC in pure Java is a different project.

The core has **no dependencies** and knows nothing about how the bytes arrived.
A companion module ships Netty handlers, so an SRT stream from
[Roast](https://github.com/brewstream/roast) can be inspected by adding two
handlers to a pipeline.

**Status:** phase 1 complete. Packets, adaptation fields, PCR, PSI section
assembly, PAT, PMT and PES headers are implemented and tested against real
streams — enough to say what a stream contains, how it is timed, and whether it
is healthy. See the roadmap for what is next.

## Requirements

Java 21 or newer.

Two artifacts:

| Module | Purpose | Dependencies |
|---|---|---|
| `grind` | parser and analyzer | none |
| `grind-netty` | pipeline handlers | Netty |

**Not yet published.** Consume as a Gradle composite build until a release is cut:

```groovy
// settings.gradle
includeBuild '../grind'

// build.gradle
dependencies {
    implementation 'io.github.brewstream:grind'
    implementation 'io.github.brewstream:grind-netty'   // only if you want the handlers
}
```

## In a Netty pipeline

The reason Roast makes every connection a `Channel`: drop two handlers on it and
an SRT stream is inspected as it arrives.

```java
TsAnalyzer analyzer = new TsAnalyzer();
analyzer.addListener(new TsStreamListener() {
    @Override
    public void onContinuityError(int pid, int expected, int actual, int lost) {
        // Transport loss just became visible media damage.
        log.warn("lost {} packets on PID 0x{}", lost, Integer.toHexString(pid));
    }
});

srtConnection.pipeline().addLast(
        new MpegTsDecoder(analyzer),      // ByteBuf -> TsPacket, with resync
        new TsHealthHandler(analyzer),    // counts, then forwards unchanged
        yourHandler);
```

Then poll for the dashboard, alongside Roast's own `ConnectionStats`:

```java
TsStreamStats media = analyzer.stats();

media.isHealthy();        // nothing lost, corrupt, unaligned, or failing its CRC
media.lossRate();         // the number to lead with
media.pid(0x100).lossRate();
```

## Knowing what the stream carries

The tables turn PID numbers into something a person can read, which is the
difference between a dashboard and a hex dump:

```java
ProgramMap programs = analyzer.programs();

programs.describe(0x100);        // "program 1 H.264 / AVC"
programs.programs().get(1).pcrPid();
programs.allStreams();           // every track across every program
```

Per-track timing comes from the PES headers:

```java
PidStats video = media.pid(0x100);

video.lastPtsSeconds();   // presentation time of the most recent frame
video.pesPackets();       // PES packets, which is frames for video
video.streamId();         // 0xE0 video, 0xC0 audio
```

Note `pesPackets` counts PES packets, not access units. Video here carries one
frame per packet so the two coincide, but audio commonly packs many frames into
one — this file puts 88 AAC frames in 6 PES packets. Calling it a frame count
would be right for video and wrong by a factor of fifteen for audio.

`onProgramsChanged` fires when the PAT or PMT says something new — on the first
tables, and afterwards only on a real change, not on the repeats a multiplexer
sends constantly.

Sections are reassembled across packets and CRC-checked before being believed. A
table stitched across a continuity break parses perfectly well and describes a
stream that does not exist, so failures are counted in
`TsStreamStats.tableCrcFailures()` and make `isHealthy()` false — loss on a table
PID is worse than loss on a video PID, because it can leave the structure
unknown.

`MpegTsDecoder` handles the part that is easy to get wrong: packets are 188
bytes and nothing makes a network read land on a boundary. It also regains
alignment when a stream does not start on a sync byte, requiring `0x47` to recur
at the packet stride before trusting it — a lone `0x47` inside compressed video
is an ordinary byte, and a naive scan locks onto noise.

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
| **1 — Packets and tables** | TS packet layer, adaptation fields, PCR; PSI section assembly with CRC32; PAT and PMT; PES headers (PTS/DTS); continuity tracking; per-PID stats | **done** |
| **2 — Elementary streams** | PES payload reassembly into access units; frame boundaries and random-access points; PTS-to-PCR skew | next |
| **3 — Extended metadata** | DVB tables (SDT, EIT, NIT); descriptor parsing; SCTE-35 splice information | planned |
| **4 — Output** | TS muxing: writing a conforming stream, PCR insertion, stuffing — for repackaging without transcoding | planned |
| **5 — Long tail** | Scrambled-stream structure (parse without decrypting), teletext and subtitle PIDs, multi-program selection and filtering | planned |

Phases 4 and 5 are genuinely optional and exist so the boundary is written down.
The honest v1 line is: **everything needed to inspect, nothing needed to
decode.**

## How this is verified

Parsing is checked against real transport streams, not hand-built bytes. Three
fixtures, each there because the others cannot show something:

| Fixture | Shows |
|---|---|
| `sample.ts` | one program, H.264 + AAC, no B-frames |
| `bframes.ts` | reordered frames, so a real DTS that differs from the PTS |
| `multiprogram.ts` | two programs, two PMTs on separate PIDs, four tracks |

Expectations are cross-checked against what `ffprobe` and TSDuck independently
report about the same files.

The second fixture exists for a reason worth stating: **a hand-built input written
by whoever wrote the parser encodes the same belief as the parser.** The DTS test
built a timestamp with the same understanding of the 33-bit marker-bit layout
that the parser reads it with, so a shared misreading would have passed. PTS was
covered, because ffprobe decodes the same bytes independently — DTS was not,
because `sample.ts` contains no DTS at all.

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
