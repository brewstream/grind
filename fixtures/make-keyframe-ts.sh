#!/usr/bin/env bash
# Keyframe extraction fixtures: the 4:2:0 profiles contribution streams use and
# browsers decode (H.264 High, HEVC Main), with an IDR every second.
set -euo pipefail
cd "$(dirname "$0")/.."
resources=grind-core/src/test/resources
ffmpeg -v error -y -f lavfi -i testsrc2=size=320x180:rate=25 -t 3 \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -g 25 -keyint_min 25 -sc_threshold 0 \
  -f mpegts "$resources/keyframe-h264.ts"
ffmpeg -v error -y -f lavfi -i testsrc2=size=320x180:rate=25 -t 3 \
  -c:v libx265 -profile:v main -pix_fmt yuv420p -x265-params keyint=25:min-keyint=25:scenecut=0:log-level=error \
  -f mpegts "$resources/keyframe-hevc.ts"
# Variants that exercise the rest of the codec string: Baseline sets constraint
# flags in avc1.PPCCLL; Main 10 sets a different compatibility bit in hev1.
ffmpeg -v error -y -f lavfi -i testsrc2=size=160x90:rate=25 -t 1.2 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 25 -sc_threshold 0 \
  -f mpegts "$resources/keyframe-h264-baseline.ts"
ffmpeg -v error -y -f lavfi -i testsrc2=size=160x90:rate=25 -t 1.2 \
  -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -x265-params keyint=25:min-keyint=25:scenecut=0:log-level=error \
  -f mpegts "$resources/keyframe-hevc-main10.ts"
