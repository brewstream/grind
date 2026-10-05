#!/usr/bin/env bash
# Builds splice-audio.ts: audio only, so the program has no video PTS, carrying
# SCTE-35 on PID 500 from the same cue files as splice.ts. TSDuck injects the
# cues (timed against the audio PTS) and reads them back. The stream is short,
# so only the first cue (event 1001 out) fits, sent twice.
#
# Requires ffmpeg and TSDuck.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/../grind-core/src/test/resources/splice-audio.ts}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ffmpeg -y -v error -f lavfi -i sine -c:a aac -b:a 32k -t 7 -muxrate 100k -f mpegts "$work/source.ts"

# spliceinject only replaces null packets, and an audio-only mux has none.
tsp --add-input-stuffing 1/4 -I file "$work/source.ts" \
    -P pmt --service 1 --add-pid 500/0x86 --add-programinfo-id 0x43554549 \
    -P spliceinject --service 1 --pts-pid 256 --files "$here/cues/*.xml" --wait-first-batch \
    -O file "$out"

echo "Wrote $out"
tsp -I file "$out" -P splicemonitor -O drop 2>&1 | grep "time to event"
