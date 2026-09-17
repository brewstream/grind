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
`TsStreamStats.crcErrors()` and make `isHealthy()` false — loss on a table
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

## What it measures, in TR 101 290 terms

[ETSI TR 101 290][tr101290] is the measurement vocabulary broadcast engineers
already use, so Grind reports in it rather than inventing names. The standard
groups its checks into three priorities: Priority 1 is "the stream is not
decodable", Priority 2 is "decodable but wrong", Priority 3 is optional.

| TR 101 290 check | Priority | Grind |
|---|:---:|:---:|
| `TS_sync_loss` | 1 | `syncLosses()` |
| `Sync_byte_error` | 1 | `TsPacket.parse` returns `null` |
| `Continuity_count_error` | 1 | `continuityErrors()`, per PID and per stream |
| `PAT_error` / `PMT_error` — table present and parseable | 1 | partial: presence and CRC, **not the 0.5s repetition limit** |
| `Transport_error` | 2 | `transportErrors()` |
| `CRC_error` | 2 | `crcErrors()` |
| `PCR_discontinuity_indicator_error` | 2 | `pcrDiscontinuities()` |
| `PID_error` — a referenced PID never appears | 3 | derivable: the PMT lists it, `stats.pid()` returns `null`. No timer |
| `PCR_repetition_error` (40ms) | 2 | `pcrRepetitionErrors()`, `maxPcrIntervalMillis()` |
| `PCR_accuracy_error` (±500ns) | 2 | **not implemented** |
| `PTS_error` — PTS at least every 700ms | 2 | `ptsErrors()`, `maxPtsIntervalMillis()` |
| `CAT_error`, scrambling checks | 2 | **not implemented** |

The unimplemented ones are all *timing* checks, and they share a reason: they
need a rate model rather than a parser. Phase 2's PTS-to-PCR skew work is where
that starts.

### Conformance is not damage

`PCR_repetition_error` is counted but does **not** mark an errored second and
does **not** clear `isHealthy()`. That is not an oversight, and the reason is
worth knowing before comparing Grind's output against another probe's.

Every fixture in this project breaches the 40ms limit on every single interval:
ffmpeg's muxer spaces PCRs 80ms apart by default, exactly twice the limit, and
those streams are perfectly watchable. A stream whose PCRs are late has lost
nothing — the receiver's clock recovery simply has less to work with, and its
tolerance for jitter narrows. Folding that into errored seconds would report an
ordinary, working stream as broken for every second of its duration, and the
figure that was supposed to mean "for how long was this broken" would come to
mean "for how long was this stream muxed by ffmpeg".

`PTS_error` is treated the same way, and for the same reason: a track whose
timestamps are sparse has lost nothing, it has only made presentation harder to
schedule. The contrast between the two is worth noting, though — no fixture here
breaches the PTS limit, because video carries one about every 39ms and audio
every 320ms. A PCR breach mostly means ffmpeg; a PTS breach means something.

So the health figures answer *was anything lost or corrupted*, and conformance
checks are reported separately. Probes differ on this, which is exactly why it is
written down rather than left to be inferred.

**Errored seconds** is reported alongside, per PID and per stream: seconds of
stream time containing at least one of the above, counted once however many the
second holds. It answers "for how long was this broken" rather than "how many
packets went wrong". The time base is the stream's own PCR, so a file analysed
faster than real time still reports the seconds it actually contains, and a live
stream is measured in its own clock rather than the analyser's.

**There is a clock per program, not per stream.** A multiplex carrying unrelated
services has independent PCRs, potentially hours apart, and a second counted
against one program has to be a second of *that* program's timeline. A PID's
errors are counted against its own program's clock, resolved from the PMT; PSI
PIDs and null packets belong to no program and fall to the reference clock, which
is the first the stream revealed. The stream-level figure is measured on that
reference, because a count spanning programs needs one timeline to be counted
on — when programs really are independent, the per-PID figures are the ones to
read.

### GOP damage, which TR 101 290 has no name for

The standard counts errors and seconds. Neither says where damage landed in the
*media*, and that is what decides whether a viewer saw anything. So Grind reports
`damagedGops()` alongside: groups of pictures containing at least one error,
counted once per GOP however many it took.

The two measures disagree in a way worth having both for. A burst of loss inside
one second is one errored second and one damaged GOP. The same number of packets
spread thinly is still about one errored second, but damages every GOP it
touches — far more visible, and indistinguishable on the standard figures alone.

Read it on video. Nearly every audio frame is its own random-access point, so an
audio GOP is one frame long, damage does not propagate, and the figure
degenerates into an error count.

[tr101290]: https://www.etsi.org/deliver/etsi_tr/101200_101299/101290/

## Roadmap

v1 is the inspection layer: enough to drive a dashboard that says whether a
stream is healthy and why. Later phases widen toward full MPEG-TS.

| Phase | Scope | State |
|---|---|:---:|
| **1 — Packets and tables** | TS packet layer, adaptation fields, PCR; PSI section assembly with CRC32; PAT and PMT; PES headers (PTS/DTS); continuity tracking; per-PID stats | **done** |
| **1b — Clocks and timing** | Per-program clocks (**done**); PCR repetition and accuracy; PTS intervals and PTS-to-PCR skew; PAT/PMT repetition | **in progress** |
| **2 — Elementary streams** | PES payload reassembly into access units; frame boundaries from PES starts | **parked**, see below |
| **3 — Extended metadata** | DVB tables (SDT, EIT, NIT); descriptor parsing; SCTE-35 splice information | planned |
| **4 — Output** | TS muxing: writing a conforming stream, PCR insertion, stuffing — for repackaging without transcoding | planned |
| **5 — Long tail** | Scrambled-stream structure (parse without decrypting), teletext and subtitle PIDs, multi-program selection and filtering | planned |

Phases 4 and 5 are genuinely optional and exist so the boundary is written down.
The honest v1 line is: **everything needed to inspect, nothing needed to
decode.**

### What is being worked on, and what is parked

Phase 2 as originally scoped bundled two things with very different value, so it
has been split. What follows is the reasoning, kept here so the decision does not
have to be rediscovered.

**Being done now — the timing checks (1b).** Every TR 101 290 check Grind does
not implement is a *timing* check, and they are the largest remaining gap. They
are also close to free: PTS, DTS and PCR are already tracked per PID, so most of
the work is comparison rather than new parsing. In order:

- **Per-program clocks — done.** A transport stream is a multiplex and its
  programs need not share a time base. Errored seconds are now counted against
  the erring PID's *own* program clock. See the note below on why no fixture
  caught this.
- **`PCR_repetition_error`** (P2) — **done.** A PCR at least every 40ms, counted per
  PID with the widest interval kept alongside. Deliberately excluded from errored
  seconds and from `isHealthy()`; see below.
- **`PCR_accuracy_error`** (P2) — ±500ns. Needs a rate model, so it is the
  hardest of these and may land last.
- **`PTS_error`** (P2) — **done.** A PTS at least every 700ms, per track, with the
  widest gap kept alongside. Measured against the program's clock rather than by
  subtracting timestamps: the standard asks how often a PTS *appears*, and PTS
  values run backwards with B-frames, so their difference answers a different
  question. Resolution is therefore the PCR interval — 80ms on a typical stream —
  which against a 700ms limit can never produce a false breach.
- **PAT/PMT repetition** (P1) — tables at least every 0.5s.
- **PTS-to-PCR skew** — not a TR 101 290 check, but the diagnostic those parts
  enable: audio drifting against video, and timestamps running far enough ahead
  of or behind the clock that a player starves or overflows.

**Parked — access-unit reassembly.** Collecting PES payload across TS packets
into whole frames. Parked rather than dropped, and the reasons are worth stating
because they are the argument for un-parking it later:

- On its own it produces complete byte arrays that nothing reads. Its payoff is
  codec-level inspection — resolution, profile, real frame types from slice
  headers — and that is phase 3 work.
- It needs decisions that cannot be made well in the abstract: a memory cap for a
  corrupt stream whose PES never terminates, the lifetime of assembled buffers,
  and what a GOP straddling a discontinuity should produce.
- It is the first step across the v1 line above. Inspecting a stream does not
  require reassembling it; decoding does.

The trigger for un-parking it is a consumer that actually needs access units,
because that consumer can answer the questions above. Guessing at them now would
mean building the wrong thing carefully.

**Parked — a fixture with genuinely independent program clocks.** Both programs
in `multiprogram.ts` carry the same clock, because ffmpeg built them from one
source; their PCRs agree to the millisecond. So the per-program clock work is
covered by tests that take the program structure from that fixture and drive the
timeline by hand, which is precise but not the real thing. A fixture muxed from
two unrelated sources would be better evidence. It needs tooling this project
does not have yet, and the hand-driven tests do catch the bug — reverting to a
single clock fails three of them.

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
