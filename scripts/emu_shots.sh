#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# Screenshots of the on-screen FM-1 for comparing with RemoteFM1IOS's scripts/sim_shots.sh, on a headless emulator
# (nothing in the foreground): installs the debug APK, then launches the app once per state with its launch extras
# (view, deck, knobs, source, sheet, sheetpage, theme, themeeditor, protector, peel, displayalone, steps, core; A6:
# learn, learnpick, demomap, sheetscroll, openmanager, modesource) and
# saves a PNG per state ("name@s": that shot waits s seconds). Phone sizes are emulated on the tablet emulator with
# `wm size` / `wm density` (a 393 x 852 dp portrait phone, and the same turned), reset afterwards.
#
#   scripts/emu_shots.sh [OUT]          (default $TMPDIR/remotefm1-android-shots)
#   SERIAL=emulator-5554                the emulator (never a real device)
#   NOINSTALL=1                         use the installed build; WAIT=s seconds before each shot (default 5)
#   ONLY="phone"|"landscape"|"tablet"   one set; MATCH=regex: only the shots whose name matches it
#                                       (e.g. MATCH='midi|learn|omx|manager|keychannel' for A6's)
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
SERIAL=${SERIAL:-emulator-5554}
OUT=${1:-${TMPDIR:-/tmp}/remotefm1-android-shots}
WAIT=${WAIT:-5}
APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"

case "$SERIAL" in emulator-*) ;; *) echo "only an emulator, not $SERIAL"; exit 1 ;; esac
a() { "$ADB" -s "$SERIAL" "$@"; }

[ -z "${NOINSTALL:-}" ] && { a install -r "$APK" | tail -1; }
a shell settings put secure immersive_mode_confirmations confirmed

shot() {   # name|extras
    local spec=$1 name=${1%%|*} extras=${1#*|} wait=$WAIT
    case "$name" in *@*) wait=${name##*@}; name=${name%@*} ;; esac
    [ -n "${MATCH:-}" ] && [[ ! "$name" =~ $MATCH ]] && return
    # shellcheck disable=SC2086
    a shell am start -S -W -n com.quixotic7.remotefm1/.MainActivity $extras >/dev/null
    sleep "$wait"
    a exec-out screencap -p > "$OUT/$name.png" && echo "ok $name"
}

E="--es source tablet --es core choralroot --ez sound false"
PHONE=(
    "phone-full|$E --es view full --es protector off"
    "phone-controls-buttons|$E --es view controls --es deck buttons --es knobs main"
    "phone-controls-keys|$E --es view controls --es deck keys --es knobs kn --ez steps true"
    "phone-screen|$E --es view screen"
    "phone-sheet|$E --es view full --ez sheet true"
    "phone-sheet-firmware|$E --es view full --es sheetpage firmware"
    "phone-sheet-settings|$E --es view full --es sheetpage settings"
    "phone-sheet-midi|$E --es view full --es sheetpage midi"
    "phone-states|$E --es view full --es sheetpage states"
    "phone-safety|$E --es view full --es sheetpage safetycopies"
    "phone-theme-editor|$E --es view full --ez themeeditor true"
    "phone-full-midnight|$E --es view full --es theme Midnight"
    "phone-names-fizz|--es source tablet --es core fizz --ez sound false --es view controls --es deck buttons"
    "phone-display-alone|$E --es view full --ez displayalone true"
    # (A6) MIDI: the page, its knobs and OMX-27 parts, the choice pages, learn on the faceplate, a firmware's editor
    "phone-midi-top|$E --es view full --es sheetpage midi --ez demomap true"
    "phone-midi-knobs|$E --es view full --es sheetpage midi --es sheetscroll knobs --ez demomap true"
    "phone-midiinput|$E --es view full --es sheetpage midiinput"
    "phone-keychannel|$E --es view full --es sheetpage keychannel"
    "phone-midimode|$E --es view full --es sheetpage midimode --ez demomap true --es modesource cc:1:22"
    "phone-learn|$E --es view full --ez demomap true --ez learn true --es learnpick KN1"
    "phone-omx|$E --es view full --es sheetpage midi --es sheetscroll omx"
    "phone-manager@25|$E --ez openmanager true"
)
LANDSCAPE=(
    "phone-full-landscape|$E --es view full --es protector on"
    "phone-controls-landscape|$E --es view controls --es deck buttons"
    "phone-full-cream-landscape|$E --es view full --es theme Cream"
    "phone-protector-peel-landscape|$E --es view full --es protector on --es peel 0.3"
    "phone-display-alone-landscape|$E --es view full --ez displayalone true"
)
TABLET=(
    "pad-full-landscape|$E --es view full --es protector on --es theme Tangerine"
    "pad-controls|$E --es view controls --es deck keys"
    "pad-screen-landscape|$E --es view screen"
    "pad-sheet-landscape|$E --es view full --ez sheet true"
    "pad-sheet-firmware|$E --es view full --es sheetpage firmware"
    "pad-theme-editor|$E --es view full --ez themeeditor true"
    "pad-learn-landscape|$E --es view full --ez demomap true --ez learn true --es learnpick PLAY"
    "pad-midi-knobs|$E --es view full --es sheetpage midi --es sheetscroll knobs --ez demomap true"
    "pad-manager@25|$E --ez openmanager true"
)

reset() { a shell wm size reset; a shell wm density reset; }
trap reset EXIT

if [ -z "${ONLY:-}" ] || [ "$ONLY" = phone ]; then
    a shell wm size 1206x2622; a shell wm density 460
    for s in "${PHONE[@]}"; do shot "$s"; done
fi
if [ -z "${ONLY:-}" ] || [ "$ONLY" = landscape ]; then
    a shell wm size 2622x1206; a shell wm density 460
    for s in "${LANDSCAPE[@]}"; do shot "$s"; done
fi
if [ -z "${ONLY:-}" ] || [ "$ONLY" = tablet ]; then
    reset
    for s in "${TABLET[@]}"; do shot "$s"; done
fi
echo "shots in $OUT"
