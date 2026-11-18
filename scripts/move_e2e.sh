#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The Ableton Move end to end on an emulator (MoveE2eTest): Move Remote's simulator on this Mac (abletonmoveremote's
# build/sim/moveremote-sim, beside this repo, or $MOVEREMOTE_SIM) on a spare port with its test FIFO, the instrumented
# test connecting to it at 10.0.2.2, and the presses: a pad, Track 4, steps 1 and 5, Play. Then the simulator's own
# screen shot is checked (the app drew it). The app and its tests must be installed (./gradlew installDebug
# installDebugAndroidTest). Never a real Move.
#   SERIAL=emulator-5558 scripts/move_e2e.sh
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SIM="${MOVEREMOTE_SIM:-$HERE/../abletonmoveremote/build/sim/moveremote-sim}"
SERIAL="${SERIAL:?SERIAL=emulator-NNNN}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
case "$SERIAL" in emulator-*) ;; *) echo "move_e2e: emulators only"; exit 2 ;; esac
[ -x "$SIM" ] || { echo "move_e2e: no simulator at $SIM (make sim in abletonmoveremote)"; exit 77; }
PORT="${PORT:-$((17800 + $$ % 1000))}"
DIR="$(mktemp -d "${TMPDIR:-/tmp}/rfm-move-e2e.XXXXXX")"
"$SIM" --port "$PORT" --dir "$DIR" 2>"$DIR/sim.log" &
PID=$!
cleanup() {
    [ -p "$DIR/mr-cmd" ] && echo quit > "$DIR/mr-cmd"
    kill "$PID" 2>/dev/null
    wait "$PID" 2>/dev/null
}
trap cleanup EXIT
i=0
while [ ! -p "$DIR/mr-cmd" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
[ -p "$DIR/mr-cmd" ] || { echo "move_e2e: the simulator did not start"; cat "$DIR/sim.log"; exit 1; }
echo "move_e2e: simulator on port $PORT ($DIR)"
"$ADB" -s "$SERIAL" shell am instrument -w -e class com.quixotic7.remotefm1.move.MoveE2eTest -e moveSim "10.0.2.2:$PORT" \
    com.quixotic7.remotefm1.test/androidx.test.runner.AndroidJUnitRunner > "$DIR/instrument.txt" 2>&1 &
IPID=$!
i=0
until grep -q "host=RemoteFM1" "$DIR/mr-state" 2>/dev/null || [ $i -ge 300 ]; do sleep 0.1; i=$((i + 1)); done
grep -q "host=RemoteFM1" "$DIR/mr-state" 2>/dev/null || { echo "move_e2e: the app never connected"; cat "$DIR/sim.log"; }
sleep 1
cmd() { echo "$1" > "$DIR/mr-cmd"; sleep "${2:-0.15}"; }
cmd "note 84 100" 0.3           # a pad: FM-1 key 0 (F3)
cmd "note 84 0" 0.5
cmd "cc 40 127" 0.05            # Track 4 tapped: the sequencer
cmd "cc 40 0" 0.6
cmd "note 16 127" 0.05          # step 1 tapped: the root
cmd "note 16 0" 0.3
cmd "note 20 127" 0.05          # step 5
cmd "note 20 0" 0.3
cmd "cc 85 127" 0.05            # Play
cmd "cc 85 0" 2.5
cmd "shot" 0.5                  # (the test stays connected 3 s after its checks: the app's screen, the state)
STATE="$(cat "$DIR/mr-state" 2>/dev/null)"
wait "$IPID"
cat "$DIR/instrument.txt"
echo "move_e2e: simulator state: $STATE"
ONES=$(tr -cd '1' < "$DIR/mr-screen.pbm" 2>/dev/null | wc -c | tr -d ' ')
echo "move_e2e: the simulator's screen shot has $ONES lit pixels"
cp "$DIR/mr-screen.pbm" "${OUT:-$DIR}/move-e2e-screen.pbm" 2>/dev/null
ok=1
grep -q "OK (1 test)" "$DIR/instrument.txt" || ok=0
echo "$STATE" | grep -q "drew=1" || ok=0
[ "${ONES:-0}" -gt 64 ] || ok=0
[ $ok = 1 ] && echo "move_e2e: PASS" || { echo "move_e2e: FAIL"; tail -20 "$DIR/sim.log"; exit 1; }
