#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# A4's firmware test on a running headless emulator: installs the app and its test APK, pushes the .rfm1 files in
# $RFM1_DIR (felucca, choralroot, melodee; default FM1VST's rfm1 worktree build, else the test downloads them from
# the feeds) into the app's files/test-rfm1, runs FirmwareTest (the loader's refusals, AMB-1, Felucca / ChoralRoot /
# Melodee playing and their render cost, two instances isolated, the library with the real loader, the catalog's
# Melodee and Felucca installed from the real feeds, a thumbnail), prints firmwaretest.json's numbers.
#
#   SERIAL=emulator-5556 scripts/emu_firmwaretest.sh     NOBUILD=1 skips the build; TEST=FirmwareTest#method; OUT=...
#
# Talks only to the emulator SERIAL names (default emulator-5556, the A4 agent's RFM_Tablet2); never boots one.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
SERIAL=${SERIAL:-emulator-5556}
OUT=${OUT:-$ROOT/build/emu-firmwaretest}
PKG=com.quixotic7.remotefm1
RFM1_DIR=${RFM1_DIR:-$ROOT/../FM1VST/.claude/worktrees/rfm1/build-rfm1/rfm1}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
mkdir -p "$OUT"
case $SERIAL in emulator-*) ;; *) echo "$SERIAL is not an emulator"; exit 1 ;; esac

if [ "${NOBUILD:-0}" != 1 ]; then
    echo "== building"
    (cd "$ROOT" && ./gradlew -q assembleDebug assembleDebugAndroidTest) >"$OUT/build.log" 2>&1 || { tail -40 "$OUT/build.log"; exit 1; }
fi
echo "== installing on $SERIAL"
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
"$ADB" -s "$SERIAL" shell run-as $PKG rm -f files/firmwaretest.json || true
"$ADB" -s "$SERIAL" shell run-as $PKG mkdir -p files/test-rfm1
for id in felucca choralroot melodee; do
    f=$(ls "$RFM1_DIR"/$id-*.rfm1 2>/dev/null | head -1 || true)
    [ -n "$f" ] || continue
    "$ADB" -s "$SERIAL" push "$f" /data/local/tmp/ >/dev/null
    n=$(basename "$f")
    "$ADB" -s "$SERIAL" shell "cat /data/local/tmp/$n | run-as $PKG sh -c 'cat > files/test-rfm1/$n'"
    "$ADB" -s "$SERIAL" shell rm -f "/data/local/tmp/$n"
    echo "pushed $n"
done
"$ADB" -s "$SERIAL" logcat -c || true
CLASS=$PKG.firmware.${TEST:-FirmwareTest}
echo "== $CLASS"
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS" $PKG.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrument.txt" 2>&1
set -e
"$ADB" -s "$SERIAL" shell run-as $PKG cat files/firmwaretest.json >"$OUT/firmwaretest.json" 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -d -s RFMFirmwareTest:I RFMWasm:I RFMFirmware:I AndroidRuntime:E TestRunner:* >"$OUT/logcat.txt" 2>&1 || true
python3 - "$OUT/firmwaretest.json" <<'EOF'
import json, sys
try:
    all = json.load(open(sys.argv[1]))
except Exception as e:
    print('no firmwaretest.json:', e); sys.exit(0)
p = all.get('rfm1_play', {})
if p:
    print("rfm1        load ms  rms      core thread us per 192-frame buffer (4 ms)  share  frame() max us  x real time")
    for i in ['felucca', 'choralroot', 'melodee']:
        o = p.get(i)
        if o:
            print(f"{i:<11} {o.get('load_ms', 0):7.0f}  {o.get('rms', 0):.4f}   {o.get('core_us_per_buffer', 0):6.0f}"
                  f"{'':37} {o.get('core_us_per_buffer', 0) / 40:4.1f} %  {o.get('frame_max_us'):>14}  {o.get('x_realtime', 0):.1f}")
for k, v in all.items():
    print(f"  {k:<18} {'PASS' if v.get('pass') else 'FAIL'}")
EOF
grep -E "^INSTRUMENTATION_(RESULT|CODE|FAILED)|^OK |^FAILURES|Tests run" "$OUT/instrument.txt" | tail -5
echo "saved: $OUT/firmwaretest.json, instrument.txt, logcat.txt"
grep -q "^OK (" "$OUT/instrument.txt"
