#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# PerfTest on a running headless emulator: heavy .rfm1 firmwares (JIANT, Rainbow Mode, X0X, Ravel; Felucca to
# compare) played in real time through Oboe, read as the app reads them. Pushes the .rfm1 files in $RFM1_DIR (from
# RemoteFM1FW's releases) into the app's files/test-rfm1, runs PerfTest with GUARD (1: the app's PerfGuard, 0: the
# engine's defaults), prints perftest.json as a table.
#
#   RFM1_DIR=dir SERIAL=emulator-5556 GUARD=1 scripts/emu_perftest.sh     NOBUILD=1 skips the build; OUT=...
#   BUFFER=192: a paced 192-frame callback instead of Oboe (the emulator's output has 20 ms bursts; a phone's are
#   2-5 ms); SLOW=3: the core thread three times slower (a phone's CPU against the emulator on an Apple M chip)
#
# Talks only to the emulator SERIAL names; never boots one.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
SERIAL=${SERIAL:-emulator-5556}
GUARD=${GUARD:-1}
SECONDS_RUN=${SECONDS_RUN:-12}
OUT=${OUT:-$ROOT/build/emu-perftest-guard$GUARD}
PKG=com.quixotic7.remotefm1
RFM1_DIR=${RFM1_DIR:?the folder with the .rfm1 files}
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
"$ADB" -s "$SERIAL" shell run-as $PKG rm -f files/perftest.json || true
"$ADB" -s "$SERIAL" shell run-as $PKG mkdir -p files/test-rfm1
for f in "$RFM1_DIR"/*.rfm1; do
    n=$(basename "$f")
    "$ADB" -s "$SERIAL" push "$f" /data/local/tmp/ >/dev/null
    "$ADB" -s "$SERIAL" shell "cat /data/local/tmp/$n | run-as $PKG sh -c 'cat > files/test-rfm1/$n'"
    "$ADB" -s "$SERIAL" shell rm -f "/data/local/tmp/$n"
done
"$ADB" -s "$SERIAL" logcat -c || true
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e class $PKG.firmware.PerfTest -e guard "$GUARD" -e seconds "$SECONDS_RUN" -e buffer "${BUFFER:-0}" -e slow "${SLOW:-1}" \
    ${IDS:+-e ids "$IDS"} $PKG.test/androidx.test.runner.AndroidJUnitRunner >"$OUT/instrument.txt" 2>&1
set -e
grep -E "FAILURES|OK \(|Error|Exception" "$OUT/instrument.txt" | head -5 || true
"$ADB" -s "$SERIAL" shell run-as $PKG cat files/perftest.json >"$OUT/perftest.json" 2>/dev/null || true
"$ADB" -s "$SERIAL" logcat -d -s RFMPerfTest:I RFMPerf:I RFMWasm:I AndroidRuntime:E >"$OUT/logcat.txt" 2>&1 || true
python3 -I - "$OUT/perftest.json" <<'EOF'
import json, sys
try:
    a = json.load(open(sys.argv[1]))
except Exception as e:
    print('no perftest.json:', e); sys.exit(0)
print("guard", a.get("guard"), "seconds", a.get("seconds"), "buffer", a.get("buffer"), "slow", a.get("slow"))
cols = ["underruns", "silent_pct", "core_load_pct", "core_max_us", "frame_max_us", "frame_avg_us", "redraws_per_s",
        "frames_deferred", "latency_ms", "realtime", "x_realtime_offline", "offline_frame_avg_us", "guard_headroom_ms", "guard_frame_ms"]
print("%-24s" % "file" + "".join("%15s" % c[:14] for c in cols))
for k, v in a.items():
    if not isinstance(v, dict): continue
    print("%-24s" % v.get("file", k) + "".join("%15s" % (("%.2f" % v[c]) if isinstance(v.get(c), float) else v.get(c, "-")) for c in cols))
    if v.get("windows"): print("    per 2 s:", [w["underruns"] for w in v["windows"]])
EOF
