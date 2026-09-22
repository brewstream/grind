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

**Status:** phases 1, 1b, 2 and 3 complete. Packets, adaptation fields, PCR, PSI
section assembly, PAT, PMT, PES headers, access-unit reassembly, per-program
clocks and the TR 101 290 timing checks are implemented and tested against real
streams — enough to say what a stream contains, how it is timed, whether it
conforms, and whether it will play. The DVB tables are read too, so a stream says
what its services are *called* rather than only what number they are. SCTE-35
splice information is read in `grind-scte`, and `grind-fmp4` repackages H.264 and
AAC-LC into fragmented MP4. See the roadmap for what is next.

## Requirements

Java 21 or newer.

Two artifacts:

| Module | Purpose | Dependencies |
|---|---|---|
| `grind-core` | parser and analyzer | none |
| `grind-netty` | pipeline handlers | Netty |
| `grind-scte` | SCTE-35 splice information | `grind-core` |
| `grind-fmp4` | repackaging as fragmented MP4 | `grind-core` |

**Not yet published.** Consume as a Gradle composite build until a release is cut:

```groovy
// settings.gradle
includeBuild '../grind'

// build.gradle
dependencies {
    implementation 'io.github.brewstream:grind-core'
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

programs.describe(0x100);        // "Brewstream One H.264 / AVC"
programs.programs().get(1).pcrPid();
programs.allStreams();           // every track across every program
```

The name comes from the SDT, and the join that produces it is the one identity
DVB guarantees: **an SDT `service_id` is the PAT's `program_number`**
(EN 300 468 §5.2.3). Nothing else in a transport stream connects a name to a
PID. A stream carrying no SDT — which most contribution feeds do not — falls
back to `"program 1 H.264 / AVC"`, and `serviceName` returns null rather than a
fabricated label, so a caller can tell "it is called this" from "nothing said".

```java
programs.serviceName(1);         // "Brewstream One", or null
programs.services().get(1).runningStatus();   // RUNNING
programs.network().networkName();             // from the NIT

analyzer.events(1).present().name();          // "The Evening News", from the EIT
```

Descriptors are read on both PMT loops, which is what the rest of the stack was
waiting for:

```java
ProgramMapTable program = programs.programs().get(1);

program.registrationIdentifier();        // "CUEI" — this program carries SCTE-35
program.streamWithLanguage("fra");       // the French audio, not the first audio
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
| `PAT_error` / `PMT_error` | 1 | presence, CRC, and the 0.5s repetition limit |
| `Transport_error` | 2 | `transportErrors()` |
| `CRC_error` | 2 | `crcErrors()` |
| `PCR_discontinuity_indicator_error` | 2 | `pcrDiscontinuities()` |
| `PID_error` — a referenced PID never appears | 3 | derivable: the PMT lists it, `stats.pid()` returns `null`. No timer — and see the SCTE-35 note below before adding one |
| `PCR_repetition_error` (40ms) | 2 | `pcrRepetitionErrors()`, `maxPcrIntervalMillis()` |
| `PCR_accuracy_error` (±500ns) | 2 | **parked**, see below |
| `PTS_error` — PTS at least every 700ms | 2 | `ptsErrors()`, `maxPtsIntervalMillis()` |
| *(not a TR 101 290 check)* PTS-to-PCR skew | — | `ptsSkewMillis()`, `minPtsSkewMillis()`, `lateTimestamps()` |
| `CAT_error`, scrambling checks | 2 | **not implemented** |
| *(not a TR 101 290 check)* DVB table CRC failures | — | `dvbCrcErrors()`, counted apart and outside `isHealthy()` |

The unimplemented ones are all *timing* checks, and they share a reason: they
need a rate model rather than a parser. Phase 2's PTS-to-PCR skew work is where
that starts.

### fMP4 output, and the limits of checking it

`grind-fmp4` repackages access units as fragmented MP4, which is what a browser
can play. It is **not transcoding**: the coded pictures pass through untouched
and only the container changes, so the cost is negligible and nothing is
re-encoded.

**Video is complete.** A transport stream in, a file two independent tools accept
out. GPAC reads five fragments and fifty samples over two seconds; ffprobe
decodes all fifty frames at 320x240. The decisive check is that **every
presentation time, decode time and keyframe flag ffprobe reads back is identical
to what it reads from the source transport stream** — for both fixtures. Frames
decoded means the pictures survived, and timestamps identical means the timing
did.

Fragments begin at keyframes, which is where a player may join and — not
coincidentally — exactly the unit MoQ wants for a group. Both are answering the
same question: where can somebody start?

There is a tool for trying it, because the last word belongs to a browser rather
than to anything that reports what it sees:

```sh
java -cp grind-fmp4/build/classes/java/main:grind-core/build/classes/java/main \
     org.brewstream.grind.fmp4.cli.TsToMp4 input.ts output.mp4
```

It finds the H.264 track from the PMT, so it can be pointed at a real capture.
Open the result in a browser: everything between the transport stream and that
file is this library, and ffmpeg is only ever used to make the input.

AAC-LC audio is supported alongside H.264. The CLI selects the first ADTS AAC
track **in the same program** as the video and writes both tracks into one file.
The library also exposes `AacFragmenter` and `InitSegment.forAudio` for a separate
audio track; neither knows about MoQ.

ADTS headers are stripped and each raw AAC frame becomes an MP4 sample. A PES
can carry several frames, or only part of one. The audio track uses its sample
rate as its timescale: every AAC-LC frame lasts exactly 1024 ticks, including a
single-frame fragment. PES timestamps anchor that clock to the video timeline;
missing timestamps continue it, normal 33-bit PTS wrap is unwrapped, and gaps
between PES payloads retain their timing. Call `discontinuity()` after upstream
loss to discard a partial frame and require a fresh PTS at an ADTS frame boundary.
A timestamp reset requires a new timeline. The CLI emits audio fragments as PES
payloads complete; audio does not wait for a video keyframe.

Verified against ffmpeg on 44.1 kHz mono and 48 kHz stereo fixtures: decoded PCM
is byte-identical to the source, every video PTS is unchanged, and every audio PTS
is within one audio tick of the source. Run `:grind-fmp4:interopTest` to repeat
these checks. A five-second synthetic H.264 + stereo AAC clip also reached the
end in the browser with 125 decoded video frames, decoded audio bytes and no
media error (playback was muted). Real EMX input remains to be validated.

Current audio scope: AAC-LC in ADTS, indexed rates from 7350 to 48000 Hz,
channel configurations 1–7 (the last means eight channels). LATM, PCE channel
layouts, multiple raw data blocks per ADTS frame, and other AAC profiles are not
supported. Implicit SBR/PS signalling is not detected. Protected ADTS headers are
stripped without checking their CRC. Malformed headers, incomplete final frames,
and configuration changes fail explicitly rather than produce a misleading file.
The CLI reports and omits unsupported audio stream types; unsupported ADTS
configurations stop conversion. It remains a file diagnostic, buffering the
input and output, not a live relay.

Be aware that this is packager territory — the container transform at the centre
of what Shaka Packager and Bento4 do. The parts that make a packager large are
absent, because MoQ has no manifests, no segment addressing, no DRM and no ABR
variants. What remains is the transform itself.

**The verification is weaker here than anywhere else in this project, and that is
worth knowing.** For parsing there was a total oracle: ffmpeg extracts the same
elementary stream and the bytes match exactly. No such thing exists for MP4
output, because valid muxers legitimately differ in box order and in whether they
repeat parameter sets in-band — a round trip through ffmpeg's own fragmenter and
back is not byte-identical either, which was measured rather than assumed.

So the checks are layered instead:

| check | what it establishes |
|---|---|
| `mp4dump` (Bento4) on a reference ffmpeg muxed from the same fixture | the exact bytes a correct `avcC` carries |
| `ffprobe` frame table on our output | frame count, every PTS and DTS, keyframe positions |
| `mp4fragment`, `MP4Box -info` | structure, against an independent fragmenter |
| a browser | the last word, and the least informative |

The whole `avcC` box is already checked against the first of those: it is
byte-identical to the one `mp4dump` reports in the reference muxer's output,
parameter sets and all.

**Codecs plug in.** `VideoCodec` is the entire per-codec surface — a sample entry
name, a configuration box, a framing conversion, and how to recognise a keyframe.
Everything above it is shared, so H.265 is a second implementation rather than
edits scattered through the muxing code. H.264 and H.265 differ less than they
look: both are NAL-based with parameter sets hoisted out of the samples, and they
disagree only on NAL header size, how a type is read from it, which types carry
configuration, and the shape of the configuration box. AV1 would be genuinely
different — OBUs rather than NALs — and slots in at the same seam.

One line had to be crossed, twice over. For the High profiles — and broadcast
H.264 is almost always High — `avcC` carries four bytes of chroma format and bit
depth that exist nowhere but inside the sequence parameter set's exp-golomb
bitstream. And the picture dimensions the track header needs are further along
the same bitstream, behind a scaling matrix that has to be walked to get past.
So this module parses a sequence parameter set, stopping at the last field it
needs. The parameter set itself is still copied whole and no picture is decoded.

**Cropping is why dimensions cannot be guessed.** A coded picture is a whole
number of macroblocks, so 1080p is coded as 1088 rows and cropped to 1080.
Ignoring the crop reports every 1080p stream eight rows too tall, and a player
that believes it stretches the picture. `cropped.ts` exists to hold that case:
320x180 is eleven and a quarter macroblocks high, and the reference muxer agrees
the answer is 180.

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

The two table checks are treated the same way, and they are the closest call of
the four because they are Priority 1 — without a PAT a receiver cannot find
anything. What settles it: when a gap between tables *is* caused by loss, that
loss already appears as continuity errors, counted where it happened. Including
the gap as well would report one fault twice, and would flag a merely slow muxer
as a damaged stream.

PTS-to-PCR skew sits outside this split. It is not a conformance check at all —
no standard defines a required slack — and it is not damage either, since nothing
is lost. It is the one figure here that speaks to whether a stream will *play*
rather than whether it is *correct*, which is why it is reported as a measurement
with a minimum rather than as a pass or fail.

`PTS_error` is treated the same way, and for the same reason: a track whose
timestamps are sparse has lost nothing, it has only made presentation harder to
schedule. The contrast between the two is worth noting, though — no fixture here
breaches the PTS limit, because video carries one about every 39ms and audio
every 320ms. A PCR breach mostly means ffmpeg; a PTS breach means something.

**A DVB table failing its CRC is counted apart from a PSI one**, in
`dvbCrcErrors`, and stays out of `isHealthy()` and out of errored seconds. This
one is excluded on a different principle from the others: a corrupt SDT or EIT
section really was lost, so it is not a conformance measure — but what it cost
was a name or a programme description, not the stream. Every PID stays findable
and every frame stays decodable, which is not true when a PAT or PMT is lost, and
that is why `crcErrors` keeps its place in `isHealthy()` and this does not.

The practical reason matters as much as the principled one: on a real broadcast
multiplex the EIT is the largest table by a wide margin, mostly schedule, and it
takes loss as a matter of course. Folding it in would report working streams as
broken for most of their duration — the same failure shape as folding in PCR
repetition, arrived at from the opposite direction.

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
| **1b — Clocks and timing** | Per-program clocks; PCR and PTS repetition; PAT/PMT repetition; PTS-to-PCR skew | **done** (PCR accuracy parked) |
| **2 — Elementary streams** | PES payload reassembly into access units | **done** |
| **3 — Extended metadata** | DVB tables (SDT, NIT, EIT present/following); descriptor parsing | **done** (EIT schedule parked) |
| **SCTE-35** | splice information, read only, in `grind-scte` | **in progress**, see below |
| **4 — Output** | TS muxing: writing a conforming stream, PCR insertion, stuffing — for repackaging without transcoding | planned |
| **fMP4** | repackaging access units as fragmented MP4, in `grind-fmp4` | **H.264 + AAC-LC implemented**, live integration remains |
| **5 — Long tail** | Scrambled-stream structure (parse without decrypting), teletext and subtitle PIDs, multi-program selection and filtering | planned |

Phases 4 and 5 are genuinely optional and exist so the boundary is written down.
The honest v1 line is: **everything needed to inspect, nothing needed to
decode.**

### What is being worked on, and what is parked

Phases 1, 1b, 2 and 3 are done, and `grind-fmp4` implements H.264 + AAC-LC. The
open threads are `grind-fmp4` **live integration** — it is implemented but has
never been wired to a live pipeline — and phases 4 and 5, both of which are
genuinely optional. Phase 3's own parked decisions are under
"Phase 3: what was read, and what was deliberately not" above.

What follows is the reasoning behind the earlier splits, kept here so the
decisions do not have to be rediscovered. Phase 2 as originally scoped bundled
two things with very different value, so it was split.

**The timing checks (1b), done.** Every TR 101 290 check Grind does
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
- **`PCR_accuracy_error`** (P2) — **parked.** See below.
- **`PTS_error`** (P2) — **done.** A PTS at least every 700ms, per track, with the
  widest gap kept alongside. Measured against the program's clock rather than by
  subtracting timestamps: the standard asks how often a PTS *appears*, and PTS
  values run backwards with B-frames, so their difference answers a different
  question. Resolution is therefore the PCR interval — 80ms on a typical stream —
  which against a 700ms limit can never produce a false breach.
- **PAT/PMT repetition** (P1) — **done.** PAT and each PMT timed separately, counted
  only on complete CRC-valid sections: a receiver cannot use a table it had to
  discard, so a corrupt one does not count as having arrived.
- **PTS-to-PCR skew** — **done.** How far ahead of the clock a track's timestamps
  run, which is how much slack the stream leaves a decoder. Reported per track as
  the current and the minimum gap, with a count of timestamps that arrived at or
  past their own deadline.

  One measurement shaped it, and contradicts what this line originally said.
  Video runs at about 720ms of skew and audio at about 410ms **in every healthy
  fixture**, because the two are buffered and interleaved differently. So a
  difference between tracks is not lip-sync drift, and presenting it as one would
  condemn every working stream. A track is compared against itself over time, and
  the figure that matters is the minimum: slack falling toward zero means the
  decoder's buffer is draining, and there is still time to act.

### Phase 3: what was read, and what was deliberately not

**SDT, NIT and EIT present/following are read; descriptors are read on both PMT
loops.** The two things that pay for the phase are the service-name join
described under "Knowing what the stream carries", and descriptors —
`registrationIdentifier()` is how a reader knows a private stream type is SCTE-35
rather than something else private, and `streamWithLanguage` is how a repackager
picks the English audio instead of whichever audio track the muxer listed first.

**EIT schedule (table ids 0x50–0x6F) is parked, and not for lack of time.** It
is the full multi-day EPG, segmented across hundreds of sections, and on a
broadcast multiplex it is the largest thing in the stream. Grind answers whether
a stream is healthy and what it carries; a week of programme synopses answers
neither, and holding it would cost memory proportional to the broadcaster's
ambition rather than to the stream. Present/following is two events and answers
"what is on now".

**The "other" variants — NIT other, SDT other, EIT p/f other — are recognised
and dropped.** They describe multiplexes this stream is not carrying, so applying
them would have a dashboard listing services that are not here. No fixture
carries one either, so reading them would be untested as well as wrong.

**Strings are decoded per EN 300 468 Annex A, not as UTF-8.** A DVB string picks
its own character table with an optional leading control code, and the default
table is ISO/IEC 6937 rather than Latin-1 — they disagree above 0x7F and about
the currency sign below it, and 6937 spends 0xC1–0xCF on combining diacritics
that *precede* the letter they modify. Reading them as Latin-1 still produces
letters, which is what makes it hard to notice.

Measured rather than assumed: ffmpeg writes no selector for a pure-ASCII name and
a 0x15 (UTF-8) selector as soon as one character is not, while TSDuck picks a
0x0B (ISO 8859-15) selector for some names and the default table for others.
Both tools' choices are in the fixtures. The East Asian tables (0x12–0x14) and
the 0x1F encoding escape are **not** implemented and decode to replacement
characters rather than to plausible nonsense — an unreadable name should read as
unreadable rather than as the wrong name. Only the table positions the fixtures
exercise are verified; the rest of the 6937 upper half is transcribed from the
standard and untested.

**No repetition checks were added for these tables.** The 500ms figure is a PAT
and PMT limit; DVB's own limits for the SDT, NIT and EIT come from a different
standard (TS 101 211) and are different numbers. Asserting one against the other
would manufacture breaches.

#### A section-assembly bug this phase uncovered

`dvb.ts` is the first fixture whose tables are large enough to span packets, so
it is the first to put a **non-zero pointer field** in front of a section. Two
bugs in `SectionAssembler` had been invisible until then, because every earlier
fixture's tables fit in one packet each and the pointer was always zero:

- **Joining a PID mid-section fabricated a table.** The bytes before the pointer
  belong to a section whose start was never seen. They were handed to the same
  path that begins a section, which read the first as a table id and the next two
  as a length and assembled a table out of the middle of another one. A long
  section's CRC rejected it — but the rejection was *counted*, so merely joining
  a live stream could report a CRC error and clear `isHealthy()`. A short section
  carries no CRC at all, and SCTE-35 splice sections are short, so there the
  invented section was emitted as real.
- **A section whose length field straddled a packet boundary got the wrong
  length.** The first one or two bytes were buffered correctly and then the
  length was read from the *next* packet's bytes, which are that section's fourth
  and fifth. The number that came out was whatever sat in the middle of the
  table.

Both are fixed, both are covered by tests in `SectionAssemblerTest`, and the
fixes are mutation-checked. Neither is specific to DVB — the first affects any
PSI PID joined mid-section, which is what a live stream always is.

### SCTE-35: scope, and why it is its own module

**Status:** sections, `splice_insert`, `time_signal` and `segmentation_descriptor`
are read, with an event view carrying both the arrival and the splice time.
UPIDs are returned as bytes, rendered as text for the ASCII forms. Remaining:
`splice_schedule`, per-component splice times, and the rest of the UPID formats.

**Read only.** Grind reports what splice information a stream carries. It does
not create, modify or remove it. Injection is discussed at the end of this
section and is deliberately not planned.

Splice information is independent of phase 2, despite the roadmap ordering.
SCTE-35 rides in PSI-style sections on its own PID, declared in the PMT as
stream type `0x86`; it never touches PES payload, so access-unit reassembly is
irrelevant to it. `StreamType.SCTE35` already exists, so Grind labels these
tracks today without parsing them, and `SectionAssembler` already does the
assembly and CRC-32 they need.

#### Its own module

`grind-scte`, depending only on `grind-core`'s published API.

This is not the usual reason for a module. `grind-netty` exists to isolate a
dependency, and SCTE-35 adds none — so on that test alone it would belong in
`grind-core`. Two things outweigh it:

- **It is a different standard**, with its own scope and revision cadence, and a
  different audience. Someone monitoring transport health should not have to
  take splice parsing with it.
- **It is not small.** `splice_insert`, `time_signal`, `splice_schedule`, plus
  `segmentation_descriptor` with some thirty segmentation types and fifteen UPID
  formats. Done fully it rivals the rest of `grind-core`.

It is cheap because the machinery it needs is already public: `SectionAssembler`,
`TableSection` and `Crc32Mpeg` are all exported, so this costs no new API surface
in `grind-core`.

#### What read-only support means

Roughly in order, each piece useful on its own:

1. **Find the PIDs.** Streams the PMT declares as type `0x86` — **done**.
   `StreamType.SCTE35` already existed, so Grind labelled these tracks before
   this module was written. A conforming PMT also
   carries a `CUEI` registration descriptor, which phase 3 now reads —
   `ProgramMapTable.registrationIdentifier()`. It stays a *confirmation* rather
   than a precondition, deliberately: a stream that declares type `0x86` without
   the descriptor is non-conforming and still carries splice information, and
   requiring the descriptor would mean ignoring cues that are plainly there.
2. **Assemble the sections** — **done**, and it needed a change in
   `grind-core`. Splice sections are *short form*, and the assembler discarded
   short sections outright: "None of the tables this library reads use them."
   They now come through, and — unlike a long section, whose header is stripped —
   a short section's body is the **whole section including its header**. SCTE 35
   computes its CRC over itself, and reconstructing those header bytes to check it
   would mean guessing the flag bits they carry.
3. **Parse `splice_info_section`** — table id `0xFC`: `pts_adjustment`, tier, the
   encryption flag, and the command type. **An encrypted section must be reported
   as unreadable rather than parsed**, or the fields come out as plausible
   nonsense.
4. **Commands** — **done** for the two that carry real-world traffic,
   `splice_insert` and `time_signal`. `splice_null`, `splice_schedule`, `bandwidth_reservation` and
   private commands should be recognised and reported by name without being
   parsed, so an unfamiliar stream is described rather than ignored.
5. **`segmentation_descriptor`** (tag `0x02`) — **done**. Event id, segmentation
   type, UPID and duration, plus the delivery restrictions. Descriptors are
   matched on their `CUEI` registration as well as the tag, since the tag is
   only unique within SCTE 35's own registry and another authority's descriptor
   would otherwise be read as if its fields lined up.
6. **The event view** — **done**. `SpliceEvent` carries both times and derives
   the pre-roll between them, which reads negative when a warning arrived too
   late to act on.

#### Three things that are easy to get wrong

**A splice PID is silent most of the time.** It is declared in the PMT and
carries nothing for minutes between ad breaks — TSDuck's `spliceinject` leaves
the PID inactive when there is nothing to signal, and offers `--min-bitrate` to
pad it with null commands precisely because that upsets some monitoring tools.
So **if `PID_error` ever grows a timer, SCTE-35 PIDs must be exempt**, or every
stream carrying ad markers reports a fault between breaks.

**A marker arrives before the event it describes.** The section says "a splice
happens at PTS X" and is inserted early — `spliceinject` defaults to 2,000ms of
pre-roll. Arrival time and fire time are different figures and both are worth
reporting; a view showing only arrival claims the break is happening while the
programme is still running.

**Splice times do not survive re-timestamping.** The splice point is
`pts_time + pts_adjustment` in the programme's 90 kHz timebase. Sections are
opaque, CRC-protected payload, so anything that rewrites PTS — looping a file
into a live feed, transcoding — moves the media and leaves the markers pointing
at timestamps that no longer occur. `pts_adjustment` is the standard's own
remedy, and TSDuck's `splicerestamp` applies it from a pair of old and new PCR
PIDs. This does not affect fixtures replayed through the analyser, where nothing
rewrites anything; it affects any live demonstration built by looping one.

Fixtures should be generated by TSDuck from XML and verified with its
`splicemonitor`, for the reason the whole verification section gives: the spec
has enough surface — `pts_adjustment`, some thirty segmentation types, some
fifteen UPID formats — that hand-crafting the binary would encode the same
misreading into the fixture and the parser at once.

#### Injection, which is not planned

Grind understands transport stream structure well enough that inserting splice
information into a live stream is a plausible next step, and it is the natural
product these libraries point at: SRT in, markers added in flight, SRT out, no
transcoding. It is recorded here so the option is not rediscovered, and it is
**not on the roadmap**.

What it would take, if it ever is: phase 4 promoted from optional to essential,
since writing a conforming stream is the prerequisite; a PMT rewrite with
correct version handling so receivers notice the new PID; and null-packet
*replacement* rather than insertion, so that nothing after the injection point
shifts position and the stream's PCR timing is preserved exactly. That last
constraint gives a property worth testing directly — every packet the injector
does not need to change comes out byte-identical.

The reason it is not a small step: everything here is safe by construction today,
because a bug in an analyser produces a wrong number. A bug in an injector
damages a working stream. That deserves a different standard of care, not just
more of the same work.

**Parked — `PCR_accuracy_error`.** The ±500ns check measures muxing jitter, and
measuring it means relating byte position in the stream to time: derive the
transport rate between two PCRs, then check whether each PCR sits where that rate
says it should. That works on a constant-bitrate file. It does not work on what
Grind actually consumes.

- Over SRT the network has already added its own jitter, so timing at the
  receiving socket describes the path rather than the muxer. The measurement
  would be precise and about the wrong thing. Real probes take it at the source,
  or on a CBR feed with hardware timestamping.
- The fixtures here are variable-bitrate, so there is no honest test to write.
- A figure that is always noisy teaches people to ignore the panel it sits on.
  Three checks' worth of work has gone into making conformance and damage legible
  apart from each other; one meaningless number would undo some of it.

The trigger for un-parking is Grind growing a file-analysis mode, where the input
is a complete CBR file and byte offsets mean something. If something is wanted in
this space sooner, the honest version is PCR jitter measured against the stream's
own average rate, named so it does not claim TR 101 290 conformance.

**Un-parked — access-unit reassembly.** `AccessUnitAssembler` collects PES
payload across transport packets into whole coded pictures. It was parked until
something needed it, on the grounds that its design questions could not be
answered in the abstract. Browser distribution over MoQ became that consumer, and
answered all three:

| question | the answer a fragmenting consumer forces |
|---|---|
| how much to buffer | one unit, capped at 8 MiB |
| how long to hold it | until it is returned, and no longer |
| what a gap produces | nothing — a damaged unit is counted and dropped |

Half a picture is worse than none: a decoder handed one produces artefacts rather
than an error, so the damage would surface as something a viewer sees instead of
something a log records.

Reassembly is still not decoding. No bitstream is parsed and nothing is
interpreted — the elementary stream bytes come out as the encoder produced them,
which is what keeps the v1 line intact.

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
| `splice.ts` | SCTE-35 ad markers, both signalling styles |
| `cropped.ts` | a height that is not a whole number of macroblocks, so the picture is cropped |
| `dvb.ts` | the DVB tables: SDT with two named services, NIT, EIT p/f split across its two sections, language descriptors on two audio tracks |
| `sdt-6937.bin` | one SDT section whose strings TSDuck encoded in ISO 6937 and ISO 8859-15 |
| `sample.h264`, `bframes.h264` | the same elementary streams as ffmpeg extracts them |

Expectations are cross-checked against what `ffprobe` and TSDuck independently
report about the same files.

Access-unit reassembly gets the strongest check in the project, because a total
one is available: `ffmpeg -c:v copy -f h264` extracts the same elementary stream,
and what the assembler produces is compared against it **byte for byte**. Both
fixtures match exactly. That is not a sample of the behaviour, it is all of it —
and ffmpeg has no reason to share this implementation's mistakes.

`dvb.ts` is built by [`fixtures/make-dvb-ts.sh`](fixtures/make-dvb-ts.sh), and is
the other fixture ffmpeg cannot produce alone: it writes the SDT, the NIT and the
language descriptors, but has no EIT at all, so TSDuck compiles
`fixtures/eit-pf.xml` and injects it. Three things about that are not obvious and
are written into the script. Injection *replaces* null packets rather than
inserting, so a source with no stuffing silently gets nothing — which is why the
script checks the output actually contains an EIT rather than trusting `tsp`'s
exit code, after an earlier version cheerfully produced a fixture with no EIT in
it. `--inter-packet` rather than `--replace`, since nothing carries PID 18 until
this step creates it. And `--eit-normalization`, which splits present and
following into the two sections TS 101 211 requires: without it TSDuck writes
both events into one section, and a parser that kept only the last section to
arrive would pass every test here and then report next week's film as what is on
now against a real broadcaster.

`sdt-6937.bin` is a bare section rather than a stream, built by
[`fixtures/make-sdt-6937.sh`](fixtures/make-sdt-6937.sh). What it is for is the
string decoder, and what that needs is bytes a real encoder chose — not a
multiplex. ffmpeg only ever writes UTF-8 with a 0x15 selector, so `dvb.ts` covers
that path and no other; TSDuck encodes the same names the way broadcast equipment
does, which turns out to be two different ways inside one descriptor. Wrapping it
in a transport stream would have meant either a second SDT fighting the muxer's
own on PID 0x11, or a quarter-megabyte fixture to carry sixty-eight bytes.

The first three are one `ffmpeg` command each. `splice.ts` is not, because
ffmpeg cannot produce SCTE-35 at all — it knows the stream type well enough to
pass one through and has no encoder — so TSDuck injects the markers. It is
regenerated by [`fixtures/make-splice-ts.sh`](fixtures/make-splice-ts.sh) from
the cue definitions in `fixtures/cues/`, which are XML rather than binary and so
can be read and changed. The script explains the three things about
`spliceinject` that are not obvious, each of which cost an attempt: it injects
into a component the PMT already declares rather than creating one, it replaces
null packets rather than inserting so a variable-bitrate source silently gets
nothing, and the input has to be regulated to real time or only the first
command lands.

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
