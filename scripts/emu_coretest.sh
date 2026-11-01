#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The core test on the headless Android emulator (iOS: scripts/sim_coretest.sh): builds the app and its test APK,
# boots the AVD headless if no emulator runs it (no window, no audio, no snapshot saved), installs, runs CoreTest (A0:
# ChoralRoot boots through the engine, a key held, 3 s rendered offline through the render-ahead ring, the Oboe
# stream tried) and CoresTest (A2: all six cores boot and sound, their render load, the flash files, power cycles,
# sound off/on, the stream reopened after a disconnect) and AudioControlDeviceTest (the audio-control thread: sound
# on/off spam, output switches, a stuck close and a slow open with the main thread probed), prints the summary and saves coretest.json and the log
# under $OUT; shuts down an emulator it booted.
#
#   scripts/emu_coretest.sh [AVD]          default RFM_Tablet; NOBUILD=1 skips the build; OUT=... (default
#                                          build/emu-coretest); SERIAL=emulator-NNNN picks a running emulator;
#                                          TEST=Class[#method] runs one (default CoreTest and CoresTest)
#   DEVICE=1 SERIAL=<serial> scripts/emu_coretest.sh    the same on a real device (quiet notes on its own speaker,
#                                                        never a USB sound card)
#
# Talks only to the emulator it finds or boots (adb -s emulator-*), or to the one device SERIAL names with DEVICE=1.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AVD=${1:-RFM_Tablet}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator
OUT=${OUT:-$ROOT/build/emu-coretest}
PKG=com.quixotic7.remotefm1
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
mkdir -p "$OUT"

say() { printf '== %s\n' "$*"; }

# a running emulator whose AVD is $AVD
find_serial() {
    local s
    for s in $("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}'); do
        if [ "$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ]; then
            echo "$s"
            return
        fi
    done
}

BOOTED=0
SERIAL=${SERIAL:-$(find_serial)}
EMU_PID=
if [ -z "$SERIAL" ]; then
    PORT=5584
    SERIAL=emulator-$PORT
    say "booting $AVD headless ($SERIAL)"
    "$EMU" -avd "$AVD" -port $PORT -no-window -no-boot-anim -no-audio -no-snapshot-save >"$OUT/emulator.log" 2>&1 &
    EMU_PID=$!
    BOOTED=1
    t0=$(date +%s)
    until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        kill -0 "$EMU_PID" 2>/dev/null || { echo "the emulator quit (see $OUT/emulator.log)"; exit 1; }
        [ $(($(date +%s) - t0)) -lt 300 ] || { echo "the emulator did not boot in 300 s"; exit 1; }
        "$ADB" -s "$SERIAL" wait-for-device >/dev/null 2>&1 || true
        sleep 2
    done
    say "booted in $(($(date +%s) - t0)) s"
fi
case $SERIAL in emulator-*) ;; *) [ "${DEVICE:-0}" = 1 ] || { echo "$SERIAL is not an emulator (DEVICE=1 for a real device)"; exit 1; } ;; esac
cleanup() {
    if [ "$BOOTED" = 1 ]; then
        say "shutting down $SERIAL"
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        [ -n "$EMU_PID" ] && wait "$EMU_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [ "${NOBUILD:-0}" != 1 ]; then
    say "building"
    (cd "$ROOT" && ./gradlew -q assembleDebug assembleDebugAndroidTest) >"$OUT/build.log" 2>&1 \
        || { tail -40 "$OUT/build.log"; exit 1; }
fi
say "installing on $SERIAL"
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
"$ADB" -s "$SERIAL" logcat -c || true
"$ADB" -s "$SERIAL" shell run-as $PKG rm -f files/coretest.json || true

CLASSES=$PKG.CoreTest,$PKG.CoresTest,$PKG.AudioControlDeviceTest
[ -n "${TEST:-}" ] && CLASSES=$PKG.$TEST
say "$CLASSES"
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$CLASSES" $PKG.test/androidx.test.runner.AndroidJUnitRunner \
    >"$OUT/instrument.txt" 2>&1
set -e
"$ADB" -s "$SERIAL" shell run-as $PKG cat files/coretest.json >"$OUT/coretest.json" 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -d -s RFMCoreTest:I RFMFlash:I RFMAudio:I AndroidRuntime:E TestRunner:* DEBUG:* >"$OUT/logcat.txt" 2>&1 || true

python3 - "$OUT/coretest.json" <<'EOF'
import json, sys
try:
    all = json.load(open(sys.argv[1]))
except Exception as e:
    print('no coretest.json:', e)
    sys.exit(0)
def stream(s):
    return (f"{s.get('api')} {s.get('rate')} Hz x{s.get('channels')} {s.get('sharing')} (perf {s.get('performance')}), device {s.get('device_id')},"
            f" burst {s.get('frames_per_burst')}, buffer {s.get('buffer_size')}/{s.get('buffer_capacity')},"
            f" latency {s.get('latency_ms') or -1:.1f} ms, callbacks {s.get('callbacks')}, rms {s.get('rms', 0):.5f}, xruns {s.get('xruns')}")
r = all.get('a0')
if r:
    o = r.get('oboe', {})
    s = o.get('stream', {})
    print(f"a0          cores {r.get('cores_found')}: {', '.join(r.get('cores', []))}  {r.get('scan_errors') or ''}")
    print(f"            load {r.get('load_ms', 0):.1f} ms, boot {r.get('boot_ms', 0):.1f} ms; key held rms {r.get('held_rms', 0):.5f},"
          f" first sound {r.get('first_sound_ms', -1):.1f} ms, x{r.get('realtime_factor', 0):.0f} real time;"
          f" core thread avg {r.get('core_avg_us')} us, load {r.get('core_load_pct')} %, priority {r.get('realtime')}")
    if o.get('start_error'):
        print(f"            oboe failed: {o.get('start_error')}  ({s.get('error')})")
    else:
        print(f"            oboe {stream(s)}; engine underruns {o.get('engine_underruns')}, latency {o.get('latency_frames')} frames")
    print(f"            reload {r.get('reload_error') or 'ok'}, rms {r.get('reload_rms', 0):.5f}; retired {r.get('copies_retired')}")
e, l = all.get('each_core', {}), all.get('render_load', {})
ids = ['choralroot', 'felucca', 'fizz', 'saadet', 'boardwalk', 'loom']
if e or l:
    print("core        boot ms  key rms   peak   colours | ring rms  core avg/max us  load %  frame() max us  x real time  underruns")
    for i in ids:
        a, b = e.get(i, {}), l.get(i, {})
        print(f"{i:<11} {a.get('boot_ms', 0):7.1f}  {a.get('key_rms', 0):.5f} {a.get('key_peak', 0):6} {a.get('colours', 0):7} |"
              f" {b.get('rms', 0):.5f}  {b.get('core_avg_us', 0):6}/{b.get('core_max_us', 0):<6}  {b.get('core_load_pct', 0):6}"
              f"  {b.get('frame_max_us', 0):14}  {b.get('x_realtime', 0):10.1f}  {b.get('underruns', 0)}")
for k, what in [('fizz', 'demo_peak'), ('boardwalk', 'tune_peak'), ('loom', 'kicks_peak'), ('saadet', 'colours')]:
    if k in all:
        print(f"{k:<11} {what} {all[k].get(what)}")
if 'choralroot_flash' in all:
    c = all['choralroot_flash']
    print(f"flash       choralroot.bin {c.get('file_bytes')} B, tempo 121 kept; damaged file: {c.get('damaged_note')}")
if 'power_cycle' in all:
    p = all['power_cycle']
    print(f"power cycle {p.get('message')}; " + ", ".join(f"{i} {p.get(i, {}).get('bytes_changed_by_the_reboot')} B changed" for i in ids))
if 'sound_switch' in all:
    w = all['sound_switch']
    print(f"sound       timer -> audio -> timer: device ms {w.get('dev_ms_timer')} -> {w.get('dev_ms_end')}, tempo kept"
          + (f"; {stream(w['stream'])}" if 'stream' in w else f" (no stream: {w.get('start_error')})"))
if 'oboe_reopen' in all:
    o = all['oboe_reopen']
    if 'first' in o:
        print(f"stream      {stream(o['first'])}")
    if 'again' in o:
        print(f"reopen      after a disconnect: {stream(o['again'])}; engine underruns {o.get('engine_underruns')}, ring {o.get('ring_target')}")
    print(f"outputs     {o.get('outputs')}")
for k in ['audio_spam', 'audio_switch', 'audio_stuck_close', 'audio_slow_open']:
    if k in all:
        print(f"{k:<18} " + ", ".join(f"{a}={b}" for a, b in all[k].items() if a not in ('stream', 'states', 'outputs', 'pass')))
for k, v in all.items():
    print(f"  {k:<22} {'PASS' if v.get('pass') else 'FAIL'}")
EOF
grep -E "^INSTRUMENTATION_(RESULT|CODE|FAILED)|^OK |^FAILURES|Tests run" "$OUT/instrument.txt" | tail -5
echo "saved: $OUT/coretest.json, instrument.txt, logcat.txt"
grep -q "^OK (" "$OUT/instrument.txt"
