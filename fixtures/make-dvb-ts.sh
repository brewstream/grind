#!/usr/bin/env bash
#
# Regenerates dvb.ts, the phase 3 fixture: the DVB tables and the descriptors
# that carry service identity.
#
# The other fixtures already carry an SDT - ffmpeg emits one whether or not it
# is asked to - so a service name alone would not have needed a new fixture.
# Four things do:
#
#   1. A NIT. ffmpeg writes one only behind -mpegts_flags +nit, and it brings a
#      network_name_descriptor and a service_list_descriptor with it.
#   2. Language descriptors on more than one audio track. The point of reading
#      them is choosing a track by language rather than taking the first one,
#      and a stream with one audio track cannot tell those two apart.
#   3. An EIT. ffmpeg cannot produce one at all, so TSDuck compiles eit-pf.xml
#      and injects it - the same division of labour as make-splice-ts.sh.
#   4. A service name that is not ASCII. EN 300 468 Annex A lets a string pick
#      its character table with a leading control code, and ffmpeg does exactly
#      that: measured, it emits no selector for a pure-ASCII name and a 0x15
#      (UTF-8) selector as soon as one character is not. Both paths therefore
#      come from a real muxer rather than from a decoder's author guessing, and
#      "Brewstream Café" is in here to keep the second one honest.
#
# Requires ffmpeg and TSDuck (tstabcomp, tsp) on the PATH.
#
# Two things here are not obvious:
#
#   - Injection REPLACES null packets rather than inserting them. A
#     variable-bitrate source has no stuffing and injection then silently does
#     nothing, which is why -muxrate is not decoration.
#   - --inter-packet, not --replace. --replace overwrites packets already on the
#     target PID, and there are none: nothing in the stream carries PID 18 until
#     this step puts it there. The first attempt used --replace and produced a
#     fixture with no EIT in it, reported as success.
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/../grind-core/src/test/resources/dvb.ts}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Three seconds. Constant bitrate so there is stuffing for the EIT to replace;
# a small frame keeps the fixture to the size of the others.
#
# Two programs, because one cannot show the thing worth showing. EN 300 468
# §5.2.3 makes an SDT service_id the same number as the PAT's program_number,
# and that identity is the whole payoff of reading an SDT - it is what turns
# "program 2" into a name. With a single service the join is indistinguishable
# from hardcoding 1.
#
# Program 1 carries two audio tracks, English and French, differing in frequency
# only so that what separates them is the metadata rather than anything a
# decoder would notice. -program with a title is what makes ffmpeg write a
# per-service name; -metadata service_name only applies to single-program
# output.
ffmpeg -y -v error \
    -f lavfi -i testsrc=size=320x180:rate=25 \
    -f lavfi -i sine=frequency=440 \
    -f lavfi -i sine=frequency=660 \
    -f lavfi -i testsrc2=size=320x180:rate=25 \
    -map 0:v -map 1:a -map 2:a -map 3:v \
    -c:v libx264 -preset ultrafast -b:v 120k -g 25 \
    -c:a aac -b:a 32k \
    -metadata:s:a:0 language=eng \
    -metadata:s:a:1 language=fra \
    -program 'title=Brewstream One:program_num=1:st=0:st=1:st=2' \
    -program 'title=Brewstream Café:program_num=2:st=3' \
    -t 3 -muxrate 700k -mpegts_flags +nit -f mpegts "$work/source.ts"

tstabcomp "$here/eit-pf.xml" -o "$work/eit.bin"

# --eit-normalization splits the subtable the way ETSI TS 101 211 requires:
# section 0 carries the present event and section 1 the following one. Without
# it TSDuck writes both events into a single section, which is legal enough that
# a parser taking the last section to arrive passes every test - and then
# reports next week's film as what is on now against a real broadcaster, who
# does split them. The fixture carries the conformant shape for that reason.
tsp -I file "$work/source.ts" \
    -P inject "$work/eit.bin" --pid 18 --inter-packet 200 \
        --eit-normalization --eit-actual-pf \
    -O file "$out"

# The muxrate has to exceed what the programs actually need, or there is no
# stuffing, the injection replaces nothing, and tsp reports success. That
# happened: at 500k the second program's video filled the multiplex, the
# fixture came out with no PID 18 in it, and this script said "Wrote ...".
# The failure is silent by construction, so it is checked rather than trusted.
if ! tsp -I file "$out" -P tables --pid 18 --max-tables 1 -O drop 2>&1 | grep -q "EIT"; then
    echo "No EIT in $out: the injection replaced nothing." >&2
    echo "The source had no stuffing to inject into - raise -muxrate." >&2
    exit 1
fi

echo "Wrote $out"
echo
echo "What TSDuck reads back from it:"
for pid in 0x10 0x11 0x12; do
    tsp -I file "$out" -P tables --pid "$pid" --max-tables 1 -O drop 2>&1 | sed 's/^/  /'
done
tsp -I file "$out" -P tables --tid 2 --max-tables 1 -O drop 2>&1 | sed 's/^/  /'
