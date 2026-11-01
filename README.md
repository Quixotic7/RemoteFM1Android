# RemoteFM1 for Android

An Android phone and tablet host for FM-1 firmware cores, the port of RemoteFM1 for iOS (`../RemoteFM1IOS`). See
[PLAN.md](PLAN.md) for the design and the milestones.

Status: A0 (toolchain and skeleton). The APK carries the six built-in cores (ChoralRoot 0.26.1, Felucca 1.6.1,
FIZZ 0.12.0-beta.12, Saadet 0.1.1, Boardwalk 0.9, LOOM-8 0.9.3) and the engine copied from the iOS app. The app's
one screen loads ChoralRoot, shows its LCD and status, plays it through Oboe, and has one key to hold. There is no
FM-1 link yet (A1) and no faceplate (A3).

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose; app id `com.quixotic7.remotefm1`, minSdk 29, target 36,
  arm64-v8a only). `RfmEngine.kt`: the JNI face of the engine; `MainActivity.kt`: A0's screen; `Levels.kt`: audio
  levels (RMS, peak). `src/test`: JVM tests; `src/androidTest`: `CoreTest`, the core test on a device or emulator.
- `native/`: the app's native library `librfmcore.so` (`native/CMakeLists.txt`, built by Gradle's
  `externalNativeBuild`): libremotefm1 compiled in place from `vendor/RemoteFM1/host/src` (portable C99), and
  `native/rfmcore/`: the engine copied from RemoteFM1IOS (each file's header says from which commit and what
  changed), the Oboe output (`RFMOboe`) and the JNI functions (`rfm_jni.cpp`).
- `cores/`: the CMake project for the cores (FM1VST's `fm1_add_core` and the cores' glue, without JUCE), copied from
  RemoteFM1IOS with its `fizz/`, `saadet/`, `boardwalk/` and `loom/` folders unchanged, and `cores/android/`:
  `coro_arm64.c` (iOS's arm64 coroutine: bionic has no `makecontext` / `swapcontext`) and `os/lock.h` (ChoralRoot's
  host build takes Apple's `os_unfair_lock`; a pthread mutex here).
- `scripts/build_cores_android.sh`: builds the cores with the NDK into
  `build/cores/android/jniLibs/arm64-v8a/libfm1core_<id>.so`; `scripts/emu_coretest.sh`: the core test on the
  headless emulator.
- `vendor/`: the iOS app's submodules at its pins, read-only: RemoteFM1 0.3.1 (e6fa19d), FM1VST v0.6.2 (177e27a, with
  `cores/ChoralRootFM1` and `cores/Felucca` and its `firmware/engines`), FIZZ 0.12.0-beta.12 (1b7f8ef), Saadet
  v0.1.1 (2eff7f1), fm1-base (6c0b47e, Saadet's), Boardwalk v0.9 (cff737a, with its `base`) and LOOM-8 v0.9.3
  (92b4bf2, with its `base`). FM1VST's `cores/melodee` is not needed (Melodee comes as the catalog's `.rfm1`).

### How the cores get into the APK

The cores are not part of the Gradle CMake build: their generators run Python on the Mac and write into their own
trees, so `scripts/build_cores_android.sh` builds them on a copy of the sources (`build/cores/src`, as on iOS) with
the NDK's CMake toolchain. Gradle runs the script before every build (`preBuild` depends on the `buildCores` task;
the script is incremental, a few seconds when nothing changed; `-PrfmSkipCores=true` skips it) and packages
`build/cores/android/jniLibs` as a jniLibs folder. The libraries are extracted at install (`useLegacyPackaging`), so
the engine's loader finds the cores by listing `applicationInfo.nativeLibraryDir`, and loads each instance as its own
copy (`android_dlopen_ext` with `ANDROID_DLEXT_FORCE_LOAD`: fresh globals, which iOS gets from its four slots).

## Build

Tooling: the Android SDK in `~/Library/Android/sdk` with platform 36, build-tools 36.0.0, NDK 29.0.14206865, CMake
3.31.6, the emulator and the android-36 google_apis arm64-v8a image; JDK 21; python.org's Python 3.11 with Pillow
(with libraqm), fontTools and numpy for the cores' generators (`CR_PYTHON=...` picks another).

    git submodule update --init vendor/RemoteFM1 vendor/FM1VST vendor/FIZZ vendor/saadet vendor/fm1-base vendor/boardwalk vendor/loom
    git -C vendor/boardwalk submodule update --init base
    git -C vendor/loom submodule update --init base
    git -C vendor/FM1VST submodule update --init cores/ChoralRootFM1
    git -C vendor/FM1VST submodule update --init --recursive cores/Felucca
    echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    ./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk

The cores alone (all six, or the ones named):

    scripts/build_cores_android.sh [choralroot felucca fizz saadet boardwalk loom]

It prints a line per core and fails only when ChoralRoot does (`RFM_REQUIRED="a b"` for others).

## Test

    ./gradlew test                                  # JVM tests

The core test on the headless emulator (it boots the AVD `RFM_Tablet` with no window and no audio if no emulator
runs it, and shuts down an emulator it booted; it only talks to `emulator-*` serials):

    scripts/emu_coretest.sh [AVD]                   # NOBUILD=1, OUT=... (default build/emu-coretest)

`CoreTest` scans the cores, loads and boots ChoralRoot, renders 1 s with no key and 3 s with a key held through the
render-ahead ring (offline: each render waits for the core thread), plays 2 s through the Oboe stream, then loads
ChoralRoot again (the old copy unloaded) and plays. It prints the summary and saves `coretest.json`, the
instrumentation output and the log under `$OUT`. On a real device (it plays 2 s of a quiet note):

    DEVICE=1 SERIAL=<adb serial> OUT=build/tablet-coretest scripts/emu_coretest.sh

The AVD was made with

    avdmanager create avd -n RFM_Tablet -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_tablet

libremotefm1's own tests on the Mac, built outside the source tree:

    cmake -S vendor/RemoteFM1/host -B <build dir> -DRFM_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug -DRFM_FM1REMOTE=OFF
    cmake --build <build dir> -j 8 && (cd <build dir> && ASAN_OPTIONS=detect_leaks=0 ctest --output-on-failure)

(`RFM_FM1REMOTE=OFF`: the fm1remote tool, which is not a test, finds vendor/FM1VST and doesn't compile against
v0.6.2's engine; the seven tests pass either way.)

## iOS → Android file map

| RemoteFM1IOS (0d1a98b) | RemoteFM1Android | |
| --- | --- | --- |
| `RFMKit/Sources/RFMCore/RFMEngine.cpp`, `include/RFMEngine.h` | `native/rfmcore/RFMEngine.cpp`, `include/RFMEngine.h` | host parameters and workgroup left out; ns clock |
| `RFMDevice.h/.cpp`, `RFMNativeCore.cpp` | same names | ns clock |
| `RFMSlots.h/.cpp` | same names | one `.so` per core, a fresh copy per instance |
| `RFMRealtime.h/.cpp` | same names | futex semaphore, SCHED_FIFO or nice -19 |
| `RFMCoreBackend.h`, `RFMEchoCore.cpp`, `RFMMidiMap.h`, `RFMInternal.h` | same names | unchanged (MidiMap: the FM-1 candidate rule defined in it; Internal: the stub link) |
| `RFMEngineNode.mm` (AVAudioSourceNode) | `native/rfmcore/RFMOboe.h/.cpp` | new: Oboe |
| `RemoteLink.h/.cpp`, `RFMCore.cpp`, `include/RFMCore.h` | `native/rfmcore/RFMLinkStub.h/.cpp` (A0's stand-in) | A1 |
| `RFMKit/Package.swift` (CRemoteFM1 target) | `native/CMakeLists.txt` | libremotefm1 without the macOS back ends |
| `RFMWasmCore.cpp`, `Sources/CWasm3` | not yet | A4 |
| `RFMKernel*`, `RFMLease`, `SharedFake`, `RFMProbe`, `RFMHostParams`, `RFMHostClock` | not ported | the AU's |
| `cores/CMakeLists.txt` | `cores/CMakeLists.txt` | Android: ELF `.so`, version script, coroutine, lock |
| `cores/fizz`, `cores/saadet`, `cores/boardwalk`, `cores/loom` | same | unchanged (no store build: `saadet_store` is configured, not built) |
| `cores/ios/coro_arm64.c`, `cores/ios/libproc.h` | `cores/android/coro_arm64.c`, `cores/android/os/lock.h` | libproc not needed (Apple-only include) |
| `scripts/build_cores.sh` | `scripts/build_cores_android.sh` | NDK toolchain, one library per core |
| `scripts/sim_coretest.sh` | `scripts/emu_coretest.sh` | |
| `RFMKit/Sources/RFMKit/CorePlayer.swift` (core, audio) | `app/.../RfmEngine.kt`, `MainActivity.kt` (A0's part of it) | |

## Licence

GPL-3.0-only (LICENSE), as RemoteFM1IOS; LICENSES/ holds MPL-2.0 for the files under it (`RFMMidiMap.*`). Oboe is
Apache-2.0, androidx and Compose Apache-2.0; the cores carry their own licences (their descriptors' credits).
