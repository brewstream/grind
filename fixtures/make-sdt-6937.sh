#!/usr/bin/env bash
#
# Regenerates sdt-6937.bin: one SDT section whose strings are not ASCII.
#
# Why this is a bare section and not a transport stream. The point of the
# fixture is DvbText, and what it needs is bytes a real encoder chose - not a
# multiplex. ffmpeg only ever writes UTF-8 with a 0x15 selector, so dvb.ts
# covers that path and no other; TSDuck encodes the same names the way a DVB
# broadcaster's equipment does, which turns out to be two different ways in one
# descriptor:
#
#   - "Rundfunk Öst" and "Télé Un" get a 0x0B selector, ISO 8859-15.
#   - "Ø" and "Œuvre ½ æon" get no selector at all and are ISO/IEC 6937, the
#     default table - where 0xE9 is Ø, 0xEA is Œ, 0xBD is ½ and 0xF1 is æ. Read
#     as Latin-1, which is the mistake this exists to catch, those same bytes
#     are é, ê, ½ and ñ: still letters, still plausible, and wrong.
#
# Putting this in a .ts would mean either a second SDT fighting the muxer's own
# on PID 0x11, or a quarter-megabyte fixture to carry sixty-eight bytes.
#
# Requires TSDuck (tstabcomp) on the PATH.
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/../grind-core/src/test/resources/sdt-6937.bin}"

tstabcomp "$here/sdt-6937.xml" -o "$out"

echo "Wrote $out"
echo
echo "What TSDuck reads back from it:"
tstabcomp -d "$out" -o - | sed 's/^/  /'
