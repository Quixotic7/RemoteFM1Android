#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# A1's link test on the headless Android emulator: builds the app and its test APK, boots the AVD headless if no
# emulator runs it (no window, no audio, no snapshot saved), installs, runs LinkTest (libremotefm1's fake unit: in
# the app's process, and as the debug build's virtual MIDI device through Android's MIDI service and AMidi), prints
# the summary and saves linktest.json and the log under $OUT; shuts down an emulator it booted.
#
#   scripts/emu_linktest.sh [AVD]          default RFM_Tablet; NOBUILD=1 skips the build; OUT=... (default
#                                          build/emu-linktest); SERIAL=emulator-NNNN picks a running emulator;
#                                          TEST=<method> runs one test
#   DEVICE=1 SERIAL=<serial> scripts/emu_linktest.sh    the same on a real device (the fake unit only: no FM-1 needed,
#                                          nothing but our app is touched; the virtual device is our app's own)
#
# Talks only to the emulator it finds or boots (adb -s emulator-*), or to the one device SERIAL names with DEVICE=1.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AVD=${1:-RFM_Tablet}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator
OUT=${OUT:-$ROOT/build/emu-linktest}
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
"$ADB" -s "$SERIAL" shell run-as $PKG rm -f files/linktest.json || true

CLASS=$PKG.LinkTest
[ -n "${TEST:-}" ] && CLASS="$CLASS#$TEST"
say "LinkTest${TEST:+ ($TEST)}"
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS" $PKG.test/androidx.test.runner.AndroidJUnitRunner \
    >"$OUT/instrument.txt" 2>&1
set -e
"$ADB" -s "$SERIAL" shell run-as $PKG cat files/linktest.json >"$OUT/linktest.json" 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -d -s RFMLinkTest:I RFMMidi:I RFMFakeFm1:I AndroidRuntime:E TestRunner:* DEBUG:* >"$OUT/logcat.txt" 2>&1 || true

python3 - "$OUT/linktest.json" <<'PY'
import json, sys
try:
    r = json.load(open(sys.argv[1]))
except Exception as e:
    print('no linktest.json:', e)
    sys.exit(0)
def g(name, *keys):
    t = r.get(name, {})
    return ', '.join(f"{k} {t.get(k)}" for k in keys if k in t)
for name in r:
    t = r[name]
    print(f"{'PASS' if t.get('pass') else 'FAIL'}  {name:16s} " + ', '.join(f"{k} {v:.4g}" if isinstance(v, float) else f"{k} {v}"
          for k, v in t.items() if k not in ('pass', 'device', 'text')))
    if 'device' in t:
        print(f"      device: {t['device']}")
PY
grep -E "^INSTRUMENTATION_(RESULT|CODE|FAILED)|^OK |^FAILURES|Tests run" "$OUT/instrument.txt" | tail -5
echo "saved: $OUT/linktest.json, instrument.txt, logcat.txt"
grep -q "^OK (" "$OUT/instrument.txt"
