# RemoteFM1 for Android

An Android phone and tablet host for FM-1 firmware cores, the port of RemoteFM1 for iOS (`../RemoteFM1IOS`). See
[PLAN.md](PLAN.md) for the design and the milestones.

Status: A2 (cores, sound, flash files) built and verified on the headless emulator; the tablet and the real FM-1's
USB audio are still to be tried. A1 (the FM-1 link) verified on libremotefm1's fake unit; the real FM-1 test is
[docs/A1-DEVICE-TEST.md](docs/A1-DEVICE-TEST.md). The APK carries the six built-in cores (ChoralRoot 0.26.1, Felucca
1.6.1, FIZZ 0.12.0-beta.12, Saadet 0.1.1, Boardwalk 0.9, LOOM-8 0.9.3), the engine and the FM-1 link copied from the
iOS app, an Android MIDI back end (AMidi) for libremotefm1, USB and Bluetooth LE MIDI discovery, a Bluetooth page,
the main page (sound source, Pattern / Core mode, the core, the sound), Diagnostics (as iOS's) and a foreground
service. Each core's memory is kept in `files/cores/<id>.bin` as on iOS (safe writes, `<id>.bin.prev`, the sidecar
`<id>.json` with iOS's fields), saved when iOS saves (the clock's copy every 30 device s when the flash changed, the
app going to the background, Pattern mode, a core switch, Restart, a firmware's own reboot, the service stopping);
Restart firmware (a power cycle from the flash); the audio output picked from Android's outputs (Oboe device id), a
note when the sound goes to a USB sound card, the stream reopened after a disconnect or when the picked output goes.
No faceplate yet (A3).

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose; app id `com.quixotic7.remotefm1`, minSdk 29, target 36,
  arm64-v8a only).
  - `RfmEngine.kt`, `RfmLink.kt`, `FakeUnit.kt`: the JNI faces of the engine, the link (RFMCore's `rfmc_*`) and a
    standalone fake unit; `RfmMidi.kt`: MidiManager discovery (USB and Bluetooth LE, hot-plug), the candidates opened
    and handed to the native back end; `Fm1Candidate.kt`: the FM-1 candidate rule, the device description and naming,
    `ReconnectPolicy` (pure Kotlin); `LinkModels.kt`: LinkState, UnitHello, LinkStats, PanelEvent (Models.swift);
    `BleScanner.kt`: the BLE-MIDI scan; `AppModel.kt`: the process-wide model (link, engine, source, mode, core,
    sound, reconnect, the flash's save points, the audio output and its reopening); `FlashFile.kt`: safe flash
    writes, `.prev`, the sidecar (pure Kotlin); `CoreFlash.kt`: a core's flash file read at boot, the clock's copies
    written, save now / stopped, the power cycle; `AudioOutput.kt`: Android's outputs (`AudioOutputs`), the route
    rules (`AudioRoute`, pure); `PlaybackService.kt`: the foreground service; `MainActivity.kt`: the main,
    Diagnostics and Bluetooth pages; `Levels.kt`: audio levels.
  - `src/debug/`: `FakeFm1Service`, a virtual MIDI device "RemoteFM1 Fake FM-1" with the fake unit behind it (debug
    builds only; disabled unless a test or the `virtual` launch extra enables it).
  - `src/test`: JVM tests (`Fm1CandidateTest`, `ReconnectPolicyTest`, `LinkModelsTest`, `LevelsTest`,
    `FlashFileTest`, `AudioRouteTest`); `src/androidTest`: `CoreTest` (A0), `CoresTest` (A2) and `LinkTest` (A1).
- `native/`: the app's native library `librfmcore.so` (`native/CMakeLists.txt`, built by Gradle's
  `externalNativeBuild`): libremotefm1 compiled in place from `vendor/RemoteFM1/host/src` (portable C99), and
  `native/rfmcore/`: the engine copied from RemoteFM1IOS (each file's header says from which commit and what
  changed), the Oboe output (`RFMOboe`), the JNI functions (`rfm_jni.cpp`: the engine; `rfm_link_jni.cpp`: the link,
  the MIDI device list, the standalone fake) and `android/rfm_android.*`: libremotefm1's Android back end (AMidi), in
  place of RemoteFM1's `host/src/macos` (`rfm_mac_*`). fm1remote's test pattern (`host/tools/fm1remote/patterns.cpp`)
  is compiled in place.
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

## Run

    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s <serial> shell am start -n com.quixotic7.remotefm1/.MainActivity [extras]

Launch extras (iOS's launch arguments): `--ez fake true` (libremotefm1's fake unit in the app: no MIDI device is
touched), `--ez fakeble true` (the fake as a Bluetooth unit), `--ez virtual true` (debug: the real Android MIDI path on
the virtual FM-1), `--es source fm1|tablet`, `--es mode pattern|core`, `--es core <id>`, `--ez sound true`,
`--es page diagnostics|bluetooth`. Source, mode and core are remembered.

## Test

    ./gradlew test                                  # JVM tests

The core test on the headless emulator (it boots the AVD `RFM_Tablet` with no window and no audio if no emulator
runs it, and shuts down an emulator it booted; it only talks to `emulator-*` serials):

    scripts/emu_coretest.sh [AVD]                   # NOBUILD=1, OUT=... (default build/emu-coretest)

`CoreTest` scans the cores, loads and boots ChoralRoot, renders 1 s with no key and 3 s with a key held through the
render-ahead ring (offline: each render waits for the core thread), plays 2 s through the Oboe stream, then loads
ChoralRoot again (the old copy unloaded) and plays. `CoresTest` (A2, from iOS's CoreTests) runs every core on the
caller's clock (boots, draws, a held D4 sounds; FIZZ's demo after PLAY, Boardwalk's attract tune and PLAY/HOME,
LOOM-8's written kicks, Saadet's page), measures each core's render load through the ring (2 s, a key held), saves
ChoralRoot's tempo and Felucca's palette through `CoreFlash` into files and boots them back (and a damaged file from
`.prev`), powers ChoralRoot on again after its own reboot (exit 0) with the tempo kept, power cycles every core
(the image kept bit for bit), switches the sound off and on (the core's state kept) and reopens the Oboe stream after
a disconnect (a test hook: the emulator can't add or remove an audio device). It prints the summary and saves
`coretest.json` (one object per test), the instrumentation output and the log under `$OUT`; `TEST=CoresTest` or
`TEST=CoresTest#method` runs one. On a real device (quiet notes on its own speaker, never a USB sound card):

    DEVICE=1 SERIAL=<adb serial> OUT=build/tablet-coretest scripts/emu_coretest.sh

The link test (A1) on the headless emulator, or on a device with `DEVICE=1 SERIAL=...` (it needs no FM-1; nothing but
the app is touched):

    scripts/emu_linktest.sh [AVD]                   # NOBUILD=1, OUT=... (default build/emu-linktest), TEST=<method>

`LinkTest` (14 tests) runs on libremotefm1's fake unit: in the app's process (the session and M1's test pattern; the
panel's events and the LEDs they light; Bluetooth pacing and HOLD; stop and start; ChoralRoot on the fake panel, a key
making sound; TRS MIDI in and TOUCH, ported from iOS's TrsMidiTests and TouchTests on the echo core), and through
Android's MIDI service (the virtual FM-1 found, opened and driven over AMidi; ChoralRoot played from it). It saves
`linktest.json`, the instrumentation output and the log (tags RFMLinkTest, RFMMidi, RFMFakeFm1) under `$OUT`.

The real FM-1 over USB and Bluetooth: [docs/A1-DEVICE-TEST.md](docs/A1-DEVICE-TEST.md).

The AVD was made with

    avdmanager create avd -n RFM_Tablet -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_tablet

libremotefm1's own tests on the Mac, built outside the source tree:

    cmake -S vendor/RemoteFM1/host -B <build dir> -DRFM_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug -DRFM_FM1REMOTE=OFF
    cmake --build <build dir> -j 8 && (cd <build dir> && ASAN_OPTIONS=detect_leaks=0 ctest --output-on-failure)

(`RFM_FM1REMOTE=OFF`: the fm1remote tool, which is not a test, finds vendor/FM1VST and doesn't compile against
v0.6.2's engine; the seven tests pass either way.)

## The FM-1 link on Android

- **Back end.** `native/rfmcore/android/rfm_android.cpp` is what `host/src/macos/host.c` is on the Mac: links in
  slots added to the session, gone ones removed. Kotlin (`RfmMidi`) opens each candidate `MidiDevice` and hands it
  down (`AMidiDevice_fromJava`); each RemoteLink run opens an input and an output port on it and adds the pair as a
  SysEx link; the session finds the unit by HELLO. `AMidiOutputPort_receive` is non-blocking and has no file
  descriptor to wait on, so the session thread reads every port in `pump()` and sleeps 1 ms in `wait()` (no reader
  thread: it could only poll too). AMidi's port open and close are binder calls that run Java code when the device's
  server is in our own process (the virtual FM-1), so the session thread is attached to the JVM while a run lasts.
- **SysEx over AMidi (measured on the virtual FM-1, emulator and Tab A9+).** A frame (up to 1024 body bytes, ~1.2 KB
  packed) goes out in one `AMidiInputPort_send`; AMidi cuts it into packets of at most 1015 bytes and the device
  receives them as separate chunks (max 1015, ~540 on average); libremotefm1's stream parser joins them, 0 decode
  errors, 0 short writes. USB pacing (budget 14000 B, 2 frames in flight): ~19 fps, ~135 KB/s, FRAME_ACK in 1 ms
  (emulator) / 15 ms avg, 47 ms max (tablet). Real USB and Bluetooth MIDI still have to be measured
  (docs/A1-DEVICE-TEST.md).
- **Bluetooth pacing** is libremotefm1's, chosen from the unit's HELLO_R (sxq <= 256 with the Bluetooth bit), not from
  Android's transport type; Diagnostics shows both (Unit › link: `Bluetooth (HELLO_R), Android: midi "FM-1 ..." (Bluetooth)`).
- **The FM-1 candidate rule** (`Fm1Candidate.kt`, from FM1VST's RemoteMidi.c): USB with Android's UsbDevice property:
  exactly 1209:0001; USB without it (some makers' builds): by name; Bluetooth: by name only; virtual: never (except
  the tests' loopback by exact name); never an updater (a device or port named "update"), never a device without
  both directions. Names are matched tolerantly: "FM-1", "FM 1", "FM_1", "FM1" not followed by a digit. Every device
  Android reports is logged (`RFMMidi`: name, manufacturer, product, USB IDs, ports) so the first real FM-1 shows
  what Android calls it.
- **USB**: `res/xml/device_filter.xml` (1209:0001) opens the app when the FM-1 is plugged in. MIDI over USB needs no
  USB permission (Android's MIDI service owns the device).
- **Bluetooth**: the Bluetooth page scans for the BLE-MIDI service (03B80E5A-...), asks for BLUETOOTH_SCAN
  (neverForLocation) and BLUETOOTH_CONNECT on Android 12+ or fine location on 10–11 only there, opens the device with
  `MidiManager.openBluetoothDevice`, remembers it and reopens it at launch and after it drops (2, 5, 10, 20, 30 s).
- **Background**: `PlaybackService`, a foreground service (mediaPlayback, plus connectedDevice when Bluetooth may be
  used) with the notification "RemoteFM1 is playing <core>" and Stop, while the FM-1 is the source or a core plays.

## iOS → Android file map

| RemoteFM1IOS (0d1a98b) | RemoteFM1Android | |
| --- | --- | --- |
| `RFMKit/Sources/RFMCore/RFMEngine.cpp`, `include/RFMEngine.h` | `native/rfmcore/RFMEngine.cpp`, `include/RFMEngine.h` | host parameters and workgroup left out; ns clock |
| `RFMDevice.h/.cpp`, `RFMNativeCore.cpp` | same names | ns clock |
| `RFMSlots.h/.cpp` | same names | one `.so` per core, a fresh copy per instance |
| `RFMRealtime.h/.cpp` | same names | futex semaphore, SCHED_FIFO or nice -19 |
| `RFMCoreBackend.h`, `RFMEchoCore.cpp`, `RFMMidiMap.h`, `RFMInternal.h` | same names | unchanged (MidiMap: the FM-1 candidate rule defined in it) |
| `RFMEngineNode.mm` (AVAudioSourceNode) | `native/rfmcore/RFMOboe.h/.cpp` | new: Oboe |
| `RemoteLink.h/.cpp`, `RFMCore.cpp`, `include/RFMCore.h` | same names | no lease, unit lock, gate or shared fake; rfm_android instead of rfm_mac |
| `FM1Patterns.cpp` | `native/CMakeLists.txt` compiles `patterns.cpp` in place | |
| RemoteFM1 `host/src/macos/host.c`, `coremidi.c`; FM1VST `plugin/RemoteMidi.c` | `native/rfmcore/android/rfm_android.*`, `app/.../RfmMidi.kt`, `Fm1Candidate.kt` | AMidi + MidiManager |
| `RFMKit/Sources/RFMKit/RemoteController.swift`, `Models.swift` | `app/.../RfmLink.kt`, `LinkModels.kt`, `AppModel.kt` | |
| `RFMKit/Sources/RFMKit/UI/BluetoothSheet.swift` | `app/.../BleScanner.kt`, `MainActivity.kt` (Bluetooth page) | own scan page |
| `App/DiagnosticsScreen.swift` | `MainActivity.kt` (Diagnostics page) | no installer, AU test or About yet |
| `RFMKit/Tests/RFMKitTests/FakeLinkTests.swift`, `TrsMidiTests.swift`, `TouchTests.swift` | `app/src/androidTest/.../LinkTest.kt` | + the virtual-device cases |
| `App/RemoteFM1App.swift` (`-fake`, `-fakeble`) | `LaunchOptions` in `AppModel.kt` | adb `am start` extras |
| `RFMKit/Package.swift` (CRemoteFM1 target) | `native/CMakeLists.txt` | libremotefm1 without the macOS back ends |
| `RFMWasmCore.cpp`, `Sources/CWasm3` | not yet | A4 |
| `RFMKernel*`, `RFMLease`, `SharedFake`, `RFMProbe`, `RFMHostParams`, `RFMHostClock` | not ported | the AU's |
| `cores/CMakeLists.txt` | `cores/CMakeLists.txt` | Android: ELF `.so`, version script, coroutine, lock |
| `cores/fizz`, `cores/saadet`, `cores/boardwalk`, `cores/loom` | same | unchanged (no store build: `saadet_store` is configured, not built) |
| `cores/ios/coro_arm64.c`, `cores/ios/libproc.h` | `cores/android/coro_arm64.c`, `cores/android/os/lock.h` | libproc not needed (Apple-only include) |
| `scripts/build_cores.sh` | `scripts/build_cores_android.sh` | NDK toolchain, one library per core |
| `scripts/sim_coretest.sh` | `scripts/emu_coretest.sh` | |
| `RFMKit/Sources/RFMKit/CorePlayer.swift` (core, audio, mode) | `app/.../RfmEngine.kt`, `AppModel.kt` (A1's part of it) | |
| `CorePlayer.swift` (the flash: boot read, saveNow, saveStopped, takeFlash, powerCycle, refresh) | `app/.../CoreFlash.kt`, `AppModel.kt` (when it saves) | A2 |
| `RFMKit/Sources/RFMKit/FlashHistory.swift` (`FlashFile`) | `app/.../FlashFile.kt` | A2; `FlashHistory` (the safety copies) is A5 |
| `RFMKit/Sources/RFMKit/AudioRoute.swift`, `CorePlayer.swift` (route change, audioConfigurationChanged) | `app/.../AudioOutput.kt`, `native/rfmcore/RFMOboe.cpp` (disconnect) | own picker (Android has no route picker) |
| `RFMKit/Tests/RFMKitTests/CoreTests.swift` | `app/src/androidTest/.../CoresTest.kt` | A2 |
| `FlashHistoryTests.swift` (FlashFile cases) | `app/src/test/.../FlashFileTest.kt` | |

## Licence

GPL-3.0-only (LICENSE), as RemoteFM1IOS; LICENSES/ holds MPL-2.0 for the files under it (`RFMMidiMap.*`). Oboe is
Apache-2.0, androidx and Compose Apache-2.0; the cores carry their own licences (their descriptors' credits).
