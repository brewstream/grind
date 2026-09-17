#!/usr/bin/env bash
#
# Regenerates splice.ts, the SCTE-35 fixture.
#
# The other fixtures are one ffmpeg command each. This one is not, because
# ffmpeg cannot produce SCTE-35 at all: it knows the stream type well enough to
# pass one through, and has no encoder. TSDuck does the injection.
#
# Requires ffmpeg and TSDuck (tsp) on the PATH.
#
# Three things here are not obvious, and each cost an attempt to find out:
#
#   1. spliceinject injects into an SCTE-35 component the PMT ALREADY declares.
#      It does not create the PID or rewrite the PMT, so the `pmt` plugin has to
#      add the component first.
#
#   2. It REPLACES null packets rather than inserting. A variable-bitrate source
#      has no stuffing to replace, so injection silently does nothing - hence
#      -muxrate, which pads the stream to a constant rate.
#
#   3. The input has to be regulated to real time. Read at full speed a seven
#      second file is through in milliseconds, and the scheduling that places a
#      section ahead of its splice point never gets a chance to run. Without
#      -P regulate only the first command lands.
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/../grind-core/src/test/resources/splice.ts}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Seven seconds, constant bitrate so there is stuffing to replace. Small frame
# size keeps the fixture reasonable; nothing here depends on the picture.
ffmpeg -y -v error \
    -f lavfi -i testsrc=size=320x180:rate=25 \
    -f lavfi -i sine \
    -c:v libx264 -preset ultrafast -b:v 120k -bf 2 -g 25 \
    -c:a aac -b:a 32k \
    -t 7 -muxrate 300k -f mpegts "$work/source.ts"

# The cue times in cues/*.xml are absolute PTS values, so they depend on where
# ffmpeg starts the clock. It has been 133,200 (1.48s) every time; this fails
# loudly rather than producing a fixture whose markers point outside the stream.
# Read whole, then take the first line in the shell. Piping into `head` would
# close the pipe early, and under `set -o pipefail` ffprobe's SIGPIPE becomes a
# failed pipeline that `set -e` turns into a silent exit.
all_pts="$(ffprobe -v error -select_streams v -show_entries packet=pts -of csv=p=0 "$work/source.ts")"
first_pts="${all_pts%%$'\n'*}"
first_pts="${first_pts//,/}"
if [ "$first_pts" != "133200" ]; then
    echo "First video PTS is $first_pts, expected 133200." >&2
    echo "The pts_time values in cues/*.xml are absolute and would now be wrong." >&2
    exit 1
fi

tsp -I file "$work/source.ts" \
    -P regulate \
    -P pmt --service 1 --add-pid 500/0x86 --add-programinfo-id 0x43554549 \
    -P spliceinject --service 1 --files "$here/cues/*.xml" --wait-first-batch \
    -O file "$out"

echo "Wrote $out"
echo
echo "What TSDuck reads back from it:"
tsp -I file "$out" -P tables --pid 500 -O drop 2>&1 \
    | grep -E "Command type|Segmentation type id|Time PTS|Time: " | sed 's/^ */  /'
