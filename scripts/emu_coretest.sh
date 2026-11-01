#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The core test on the headless Android emulator (iOS: scripts/sim_coretest.sh): builds the app and its test APK,
# boots the AVD headless if no emulator runs it (no window, no audio, no snapshot saved), installs, runs CoreTest
# (ChoralRoot boots through the engine, a key held, 3 s rendered offline through the render-ahead ring, the Oboe
# stream tried), prints the summary and saves coretest.json and the log under $OUT; shuts down an emulator it booted.
#
#   scripts/emu_coretest.sh [AVD]          default RFM_Tablet; NOBUILD=1 skips the build; OUT=... (default
#                                          build/emu-coretest); SERIAL=emulator-NNNN picks a running emulator
#   DEVICE=1 SERIAL=<serial> scripts/emu_coretest.sh    the same on a real device (plays 2 s of a quiet note)
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

say "CoreTest"
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class $PKG.CoreTest $PKG.test/androidx.test.runner.AndroidJUnitRunner \
    >"$OUT/instrument.txt" 2>&1
set -e
"$ADB" -s "$SERIAL" shell run-as $PKG cat files/coretest.json >"$OUT/coretest.json" 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -d -s RFMCoreTest:I AndroidRuntime:E TestRunner:* DEBUG:* >"$OUT/logcat.txt" 2>&1 || true

python3 - "$OUT/coretest.json" <<'EOF'
import json, sys
try:
    r = json.load(open(sys.argv[1]))
except Exception as e:
    print('no coretest.json:', e)
    sys.exit(0)
o = r.get('oboe', {})
s = o.get('stream', {})
print(f"cores     {r.get('cores_found')}: {', '.join(r.get('cores', []))}  {r.get('scan_errors') or ''}")
print(f"boot      load {r.get('load_ms', 0):.1f} ms, boot {r.get('boot_ms', 0):.1f} ms {r.get('load_error') or ''}")
print(f"before    rms {r.get('before_key_rms', 0):.5f} (1 s, no key; {r.get('before_key_wall_ms', 0):.0f} ms wall)")
print(f"key held  rms {r.get('held_rms', 0):.5f}  peak {r.get('held_peak', 0):.4f}  first sound {r.get('first_sound_ms', -1):.1f} ms"
      f"  (3 s in {r.get('held_wall_ms', 0):.0f} ms: x{r.get('realtime_factor', 0):.1f} real time)")
print(f"render    {r.get('renders')} renders of 192, avg {r.get('render_avg_us')} us, max {r.get('render_max_us')} us;"
      f" core thread avg {r.get('core_avg_us')} us, max {r.get('core_max_us')} us, load {r.get('core_load_pct')} %,"
      f" frame() max {r.get('frame_max_us')} us, priority {r.get('realtime')}")
print(f"ring      target {r.get('ring_target')}, latency {r.get('latency_frames')} frames")
if o.get('start_error'):
    print(f"oboe      failed: {o.get('start_error')}  ({s.get('error')})")
else:
    print(f"oboe      {s.get('api')} {s.get('rate')} Hz x{s.get('channels')} {s.get('sharing')}, burst {s.get('frames_per_burst')},"
          f" buffer {s.get('buffer_size')}/{s.get('buffer_capacity')}, callbacks {s.get('callbacks')} (max {s.get('max_frames')} frames),"
          f" rms {s.get('rms', 0):.5f}, xruns {s.get('xruns')}; engine underruns {o.get('engine_underruns')},"
          f" render avg {o.get('render_avg_us')} us, latency {o.get('latency_frames')} frames")
print(f"reload    {r.get('reload_error') or 'ok'}, rms {r.get('reload_rms', 0):.5f}; copies in use {r.get('copies_in_use')},"
      f" retired (image stayed loaded) {r.get('copies_retired')}")
print('PASS' if r.get('pass') else 'FAIL')
EOF
grep -E "^INSTRUMENTATION_(RESULT|CODE|FAILED)|^OK |^FAILURES|Tests run" "$OUT/instrument.txt" | tail -5
echo "saved: $OUT/coretest.json, instrument.txt, logcat.txt"
grep -q "^OK (" "$OUT/instrument.txt"
