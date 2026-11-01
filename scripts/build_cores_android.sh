#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# Copied from RemoteFM1IOS 0d1a98b scripts/build_cores.sh; changes: the NDK toolchain (arm64-v8a, API 29) instead of
# the Apple SDKs, one libfm1core_<id>.so per core instead of four slot frameworks, no store variant (saadet_store is
# configured by cores/saadet/core.cmake but not built), each core built on its own so one failure doesn't stop the
# others; the source sync is iOS's, unchanged.
#
# Builds the FM-1 cores (ChoralRoot, Felucca, FIZZ, Saadet, Boardwalk, LOOM-8) with cores/CMakeLists.txt into
# build/cores/android/jniLibs/arm64-v8a/libfm1core_<id>.so, which the app packages (app/build.gradle.kts: jniLibs).
#
#   scripts/build_cores_android.sh [id ...]     (default: all six; RFM_CORES="a;b" works too)
#
# Prints one line per core (ok or FAILED with the log's tail) and exits 1 when ChoralRoot failed (RFM_REQUIRED="a b"
# names the cores that must build; default choralroot). Incremental: the trees are synced (rsync, times kept), CMake
# rebuilds what changed, a library is copied again only when it is newer.
#
# vendor/ stays untouched: the generate steps write <tree>/build/gen, so the build runs on a copy of the parts of
# vendor/FM1VST it needs, laid out as there (build/cores/src: cmake/, core-api/, core-glue/, engine/, tests/ and
# cores/<tree>), with cores/CMakeLists.txt as its top. Each tree's copy gets a .git file pointing at its submodule's
# git dir, so fm1_add_core reads the right commit. FIZZ comes from vendor/FIZZ (licence files, core/ without
# core/tests/, third_party/, the font licence). Saadet from vendor/saadet (without tests/ and docs/) with
# vendor/fm1-base as its base/ (without bsp/). Boardwalk from vendor/boardwalk (without mockups/, tests/ and its base's
# bsp/), LOOM-8 from vendor/loom (without docs/, tests/, its base's bsp/ and bt/, and app/samples/909_hat.wav:
# GPL-3.0, never in this build). CR_PYTHON=... picks the python with Pillow and fontTools; ANDROID_HOME / ANDROID_NDK
# the SDK and NDK (default ~/Library/Android/sdk, its ndk/29.0.14206865).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FM1VST=$ROOT/vendor/FM1VST
FIZZ=$ROOT/vendor/FIZZ
SAADET=$ROOT/vendor/saadet
BOARDWALK=$ROOT/vendor/boardwalk
LOOM=$ROOT/vendor/loom
FM1BASE=$ROOT/vendor/fm1-base
if [ $# -gt 0 ]; then CORES=$(IFS=';'; echo "$*"); else CORES=${RFM_CORES:-choralroot;felucca;fizz;saadet;boardwalk;loom}; fi
REQUIRED=${RFM_REQUIRED:-choralroot}
B=$ROOT/build/cores
SRC=$B/src
DIR=$B/android
OUT=$DIR/jniLibs/arm64-v8a
PY=${CR_PYTHON:-/Library/Frameworks/Python.framework/Versions/3.11/bin/python3}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=${ANDROID_NDK:-$SDK/ndk/29.0.14206865}
CMAKE=${CMAKE:-$SDK/cmake/3.31.6/bin/cmake}
NINJA=$SDK/cmake/3.31.6/bin/ninja
API=29
JOBS=$(sysctl -n hw.ncpu)

say() { printf '== %s\n' "$*"; }

# the copy's .git: its submodule's git dir (fm1_add_core reads the commit there)
git_pointer() {   # submodule copy
    local want
    want="gitdir: $(git -C "$1" rev-parse --absolute-git-dir)"
    if [ "$(cat "$2/.git" 2>/dev/null || true)" != "$want" ]; then echo "$want" >"$2/.git"; fi
}

sync_sources() {
    mkdir -p "$SRC/cores"
    for d in cmake core-api core-glue engine tests; do rsync -a --delete "$FM1VST/$d/" "$SRC/$d/"; done
    for t in ChoralRootFM1 Felucca; do
        local from=$FM1VST/cores/$t
        [ -n "$(ls -A "$from" 2>/dev/null)" ] || { echo "$from is empty: git -C vendor/FM1VST submodule update --init --recursive cores/$t"; exit 1; }
        rsync -a --delete --exclude=/build/ --exclude=.git "$from/" "$SRC/cores/$t/"
        git_pointer "$from" "$SRC/cores/$t"
    done
    [ -f "$FIZZ/core/glue/core_quadrille.c" ] || { echo "$FIZZ is empty: git submodule update --init vendor/FIZZ"; exit 1; }
    rsync -a --delete --exclude=.git --exclude=/core/tests/ --include=/LICENSE --include=/LICENSING.md \
        --include='/core/***' --include='/third_party/***' --include=/assets/ --include=/assets/fonts/ \
        --include=/assets/fonts/OFL.txt --exclude='*' "$FIZZ/" "$SRC/cores/FIZZ/"
    git_pointer "$FIZZ" "$SRC/cores/FIZZ"
    [ -f "$SAADET/platform/instrument.c" ] || { echo "$SAADET is empty: git submodule update --init vendor/saadet"; exit 1; }
    [ -f "$FM1BASE/host/plat_host.c" ] || { echo "$FM1BASE is empty: git submodule update --init vendor/fm1-base"; exit 1; }
    rsync -a --delete --exclude=.git --exclude=/build/ --exclude=/platform/build/ --exclude=/base/ --exclude=/tests/ \
        --exclude=/docs/ "$SAADET/" "$SRC/cores/saadet/"
    rsync -a --delete --exclude=.git --exclude=/build/ --exclude=/bsp/ --exclude=/tests/ --exclude=/examples/ \
        "$FM1BASE/" "$SRC/cores/saadet/base/"
    git_pointer "$SAADET" "$SRC/cores/saadet"
    [ -f "$BOARDWALK/base/host/plat_host.c" ] || { echo "$BOARDWALK is empty: git submodule update --init vendor/boardwalk && git -C vendor/boardwalk submodule update --init base"; exit 1; }
    rsync -a --delete --exclude=.git --exclude=/app/build/ --exclude=/base/bsp/ --exclude=/base/build/ --exclude=/mockups/ \
        --exclude=/tests/ "$BOARDWALK/" "$SRC/cores/boardwalk/"
    git_pointer "$BOARDWALK" "$SRC/cores/boardwalk"
    [ -f "$LOOM/base/host/plat_host.c" ] || { echo "$LOOM is empty: git submodule update --init vendor/loom && git -C vendor/loom submodule update --init base"; exit 1; }
    rsync -a --delete --exclude=.git --exclude=/app/build/ --exclude=/base/bsp/ --exclude=/base/bt/ --exclude=/base/build/ \
        --exclude=/docs/ --exclude=/tests/ --exclude=/app/samples/909_hat.wav "$LOOM/" "$SRC/cores/loom/"
    git_pointer "$LOOM" "$SRC/cores/loom"
    cmp -s "$ROOT/cores/CMakeLists.txt" "$SRC/CMakeLists.txt" || cp "$ROOT/cores/CMakeLists.txt" "$SRC/CMakeLists.txt"
}

configure() {
    # configured again when the core list changed (RFM_CORES is a cache variable)
    if [ ! -f "$DIR/CMakeCache.txt" ] || ! grep -qxF "RFM_CORES:STRING=$CORES" "$DIR/CMakeCache.txt"; then
        say "cores: configure ($CORES) -> $DIR"
        "$CMAKE" -S "$SRC" -B "$DIR" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
            -DANDROID_PLATFORM=android-$API -DRFM_OWN="$ROOT/cores" -DCR_PYTHON="$PY" -DRFM_CORES="$CORES" \
            >"$DIR.configure.log" 2>&1 || { tail -40 "$DIR.configure.log"; exit 1; }
    fi
}

[ -x "$PY" ] || { echo "no python at $PY (CR_PYTHON=...)"; exit 1; }
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "no NDK at $NDK (ANDROID_NDK=...)"; exit 1; }
mkdir -p "$DIR" "$OUT"
sync_sources
configure

failed=()
while read -r id ver _commit; do
    case $id in *_store) continue ;; esac            # (no store build on Android)
    log=$DIR/build-$id.log
    if "$CMAKE" --build "$DIR" --target "FM1Core_$id" -j "$JOBS" >"$log" 2>&1; then
        lib=$DIR/lib/libfm1core_$id.so
        if [ ! -f "$OUT/libfm1core_$id.so" ] || [ "$lib" -nt "$OUT/libfm1core_$id.so" ]; then
            cp "$lib" "$OUT/libfm1core_$id.so.tmp" && mv "$OUT/libfm1core_$id.so.tmp" "$OUT/libfm1core_$id.so"
        fi
        printf '%-11s %-15s ok      %s  %s\n' "$id" "$ver" "$(du -h "$lib" | cut -f1)" "$OUT/libfm1core_$id.so"
    else
        printf '%-11s %-15s FAILED  (%s)\n' "$id" "$ver" "$log"
        grep -E "error|undefined" "$log" | head -8 | sed 's/^/    /'
        rm -f "$OUT/libfm1core_$id.so"
        failed+=("$id")
    fi
done <"$DIR/cores.txt"
# a core no longer built is removed, so nothing packages an old one
for f in "$OUT"/libfm1core_*.so; do
    [ -f "$f" ] || continue
    id=$(basename "$f" .so); id=${id#libfm1core_}
    awk '{print $1}' "$DIR/cores.txt" | grep -qxF "$id" || { rm -f "$f"; echo "removed $(basename "$f") (not built)"; }
done
for r in $REQUIRED; do
    for f in "${failed[@]:-}"; do [ "$f" = "$r" ] && { echo "required core $r failed"; exit 1; }; done
done
exit 0
