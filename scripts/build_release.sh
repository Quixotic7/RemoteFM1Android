#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 RemoteFM1 contributors
# The signed release APK (A8): the cores built afresh, the app built clean and signed with the release key
# (~/.android-keys/remotefm1-release.properties, or RFM_SIGNING_PROPS=...), then checked: the signature and its
# certificate, v2/v3 schemes, zip alignment, 16 KB ELF alignment of every .so, the six cores and the engine present,
# no debug-only parts in the manifest. Writes build/release/RemoteFM1-<version>.apk and its .sha256.
#
#   scripts/build_release.sh            NOCLEAN=1 skips the clean (and keeps the cores already built)
#
# It never prints the key's passwords and never makes a key.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.0.0
NDK=$SDK/ndk/29.0.14206865
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
# the release certificate (SHA-256): a different one means a different key, and users could not update
CERT=6fd9956c1ce876619e710ebb33ef44a5d72cf6968ca29b278613938b6a1799cb
CORES="boardwalk choralroot felucca fizz loom saadet"
OUTDIR=$ROOT/build/release
LOG=$OUTDIR/build.log

say() { printf '== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

PROPS=${RFM_SIGNING_PROPS:-$HOME/.android-keys/remotefm1-release.properties}
[ -f "$PROPS" ] || fail "no release key: $PROPS not found (RFM_SIGNING_PROPS=... picks another)"
export RFM_SIGNING_PROPS=$PROPS

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -1)
[ -n "$VERSION" ] || fail "no versionName in app/build.gradle.kts"
APK=$OUTDIR/RemoteFM1-$VERSION.apk
mkdir -p "$OUTDIR"
: >"$LOG"

cd "$ROOT"
if [ "${NOCLEAN:-0}" != 1 ]; then
    say "clean (the app and the cores)"
    ./gradlew -q :app:clean >>"$LOG" 2>&1 || { tail -30 "$LOG"; fail "gradle clean"; }
    # the cores' build trees are moved aside into the temp folder (not deleted here), so they are made again
    if [ -d build/cores ]; then
        mv build/cores "$(mktemp -d "${TMPDIR:-/tmp}/rfm-old-cores.XXXXXX")/"
    fi
fi
rm -f "$APK" "$APK.sha256"

say "building the cores"
RFM_REQUIRED="$CORES" bash scripts/build_cores_android.sh >>"$LOG" 2>&1 || { tail -30 "$LOG"; fail "the cores"; }
say "building RemoteFM1 $VERSION (release)"
./gradlew -PrfmSkipCores=true releaseApk >>"$LOG" 2>&1 || { tail -30 "$LOG"; fail "the release build"; }
[ -f "$APK" ] || fail "$APK was not made"

say "checking the signature"
SIG=$("$BT/apksigner" verify --verbose --print-certs --min-sdk-version 24 "$APK" 2>/dev/null) || fail "apksigner verify"
# (here-strings, not pipes: grep -q quitting early would fail the pipe under pipefail)
grep -q "v2 scheme (APK Signature Scheme v2): true" <<<"$SIG" || fail "no v2 signature"
grep -q "v3 scheme (APK Signature Scheme v3): true" <<<"$SIG" || fail "no v3 signature"
GOT=$(echo "$SIG" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$GOT" = "$CERT" ] || fail "signed with another certificate ($GOT), expected $CERT"
echo "certificate SHA-256 $GOT"

say "checking alignment"
"$BT/zipalign" -c -P 16 4 "$APK" || fail "zipalign"
TMP=$(mktemp -d "${TMPDIR:-/tmp}/rfm-release.XXXXXX")
unzip -q -o "$APK" 'lib/*' -d "$TMP"
READELF=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)
for so in "$TMP"/lib/arm64-v8a/*.so; do
    for a in $("$READELF" -lW "$so" | awk '$1 == "LOAD" {print $NF}'); do
        [ $((a)) -ge 16384 ] || fail "$(basename "$so"): LOAD aligned to $a (16 KB pages need 0x4000)"
    done
done
for c in $CORES; do
    [ -f "$TMP/lib/arm64-v8a/libfm1core_$c.so" ] || fail "the $c core is missing"
done
for l in librfmcore librfmlzfse liboboe libc++_shared; do
    [ -f "$TMP/lib/arm64-v8a/$l.so" ] || fail "$l.so is missing"
done
echo "$(ls "$TMP"/lib/arm64-v8a/*.so | wc -l | tr -d ' ') libraries, all 16 KB aligned; six cores, the engine, lzfse, Oboe"

say "checking the manifest"
MAN=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK")
! grep -qi 'fake' <<<"$MAN" || fail "a debug-only component (Fake...) is in the manifest"
! grep -q 'debuggable' <<<"$MAN" || fail "the APK is debuggable"
"$BT/aapt2" dump badging "$APK" | sed -n 1p

( cd "$OUTDIR" && shasum -a 256 "$(basename "$APK")" >"$(basename "$APK").sha256" )
say "done"
echo "$APK  $(du -h "$APK" | cut -f1)"
cat "$APK.sha256"
