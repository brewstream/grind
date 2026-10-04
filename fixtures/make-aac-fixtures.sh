#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
resources=grind-fmp4/src/test/resources
mkdir -p "$resources"
# Independent ADTS extraction of the existing 44.1 kHz mono source.
ffmpeg -v error -y -i grind-core/src/test/resources/sample.ts \
  -map 0:a:0 -c copy -f adts "$resources/sample.aac"
# A second audio clock and channel layout, plus browser-compatible H.264 video.
ffmpeg -v error -y -f lavfi -i testsrc2=size=160x90:rate=25 \
  -f lavfi -i sine=frequency=997:sample_rate=48000 -t 1 \
  -c:v libx264 -pix_fmt yuv420p -g 10 -c:a aac -ac 2 -b:a 96k \
  -f mpegts "$resources/stereo48.ts"
