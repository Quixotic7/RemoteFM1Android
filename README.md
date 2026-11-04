# RemoteFM1 for Android

An Android phone and tablet host for FM-1 firmware cores, the port of RemoteFM1 for iOS (`../RemoteFM1IOS`). See
[PLAN.md](PLAN.md) for the design and the milestones.

## Install (sideload)

RemoteFM1 for Android is not in the Play Store. You install the APK yourself:

1. On the phone or tablet, download `RemoteFM1-<version>.apk` from the
   [GitHub Releases](https://github.com/Quixotic7/RemoteFM1Android/releases) page.
2. Allow your browser or file manager to install apps: Android asks the first time you open the APK
   (Settings › Apps › Special app access › Install unknown apps, then the app you downloaded it with).
3. **Samsung:** turn off **Auto Blocker** while you install (Settings › Security and privacy › Auto Blocker). It blocks
   every app not from a store. You can turn it back on afterwards.
4. Open the APK and tap Install. Android may show a Play Protect note for an app it doesn't know: choose to install
   anyway.

Updates: download the newer APK and open it; it installs over the old one and keeps your firmware, states and
settings (every release is signed with the same key). If Android refuses an update because the package conflicts
with the one installed, that APK was not signed by me: don't uninstall to get round it, since uninstalling deletes
the app's data (back it up first: Settings › Your data › Back up everything).

What you need: Android 10 or newer on an arm64 phone or tablet (nearly every phone since 2017). For the panel, an
FM-1 running the RemoteFM1 firmware, over USB (USB-C or an OTG adapter) or Bluetooth; without one you play on the
screen. Known issues: on some Samsung/Qualcomm devices (seen on a Galaxy Tab A9+) sound through the FM-1's own USB
audio doesn't work and can make the FM-1 drop off USB; play through the phone's speaker, headphones or Bluetooth
(see [Known issues](#known-issues)).

Check the download (optional): its SHA-256 is in the release notes and in `RemoteFM1-<version>.apk.sha256`:

    shasum -a 256 RemoteFM1-0.2.0.apk                       # macOS / Linux; Windows: certutil -hashfile <file> SHA256

and the signing certificate, with the Android SDK's build-tools:

    apksigner verify --print-certs RemoteFM1-0.2.0.apk

must show `Signer #1 certificate SHA-256 digest: 6fd9956c1ce876619e710ebb33ef44a5d72cf6968ca29b278613938b6a1799cb`.
An APK with another certificate is not from me.

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
No faceplate yet (A3). After the first real FM-1 test (2026-11-01, see "Known issues"): the sound never goes to a USB
output unless picked, and the link reopens a link whose write failed or whose unit went quiet. After the second
(2026-11-01): the screen and buttons worked and the sound on the tablet worked, but the sound to the FM-1's USB audio
played nothing, the FM-1 dropped off USB, and a stream's close then froze the app (killed by Android); so every audio
stream call now runs on an audio-control thread that never makes the UI wait and goes on past a stuck call, and USB
outputs are offered only with Settings › Sound through USB devices (experimental). The third-round device test is
[docs/A1-DEVICE-TEST.md](docs/A1-DEVICE-TEST.md).

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
    rules (`AudioRoute`, pure); `AudioControl.kt`: the audio-control thread (pure Kotlin: the wants, the stream
    calls on workers with time limits, the state); `PlaybackService.kt`: the foreground service; `MainActivity.kt`: the main,
    Diagnostics and Bluetooth pages; `Levels.kt`: audio levels.
  - `src/debug/`: `FakeFm1Service`, a virtual MIDI device "RemoteFM1 Fake FM-1" with the fake unit behind it (debug
    builds only; disabled unless a test or the `virtual` launch extra enables it).
  - `src/test`: JVM tests (`Fm1CandidateTest`, `ReconnectPolicyTest`, `LinkModelsTest`, `LevelsTest`,
    `FlashFileTest`, `AudioRouteTest`, `AudioControlTest`); `src/androidTest`: `CoreTest` (A0), `CoresTest` (A2),
    `AudioControlDeviceTest` (the audio-control thread on Oboe) and `LinkTest` (A1).
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

The signed release APK (A8; the owner's key, kept outside the repo):

    scripts/build_release.sh                        # NOCLEAN=1 skips the clean

It builds the six cores and the app from clean, signs with the key named in
`~/.android-keys/remotefm1-release.properties` (storeFile, storePassword, keyAlias, keyPassword; `RFM_SIGNING_PROPS`
as a Gradle property or env var names another file; without one a release build stops, it is never signed with the
debug key), checks the certificate (SHA-256 `6fd9956c...a1799cb`), the v2/v3 signatures, zip alignment, the 16 KB
ELF alignment of every `.so`, the cores and engine present and no debug-only parts in the manifest, and writes
`build/release/RemoteFM1-<version>.apk` with its `.sha256`. `./gradlew releaseApk` alone builds and copies the APK
without the checks. R8 is off (JNI names, the JSON code and the web editors' `@JavascriptInterface` stay as written).

## Run

    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s <serial> shell am start -n com.quixotic7.remotefm1/.MainActivity [extras]

Launch extras (iOS's launch arguments): `--ez fake true` (libremotefm1's fake unit in the app: no MIDI device is
touched), `--ez fakeble true` (the fake as a Bluetooth unit), `--ez virtual true` (debug: the real Android MIDI path on
the virtual FM-1), `--es source fm1|tablet`, `--es mode pattern|core`, `--es core <id>`, `--ez sound true`,
`--es page diagnostics|bluetooth`. Source, mode and core are remembered. Release builds ignore the test-only extras
(`fake`, `fakeble`, `virtual`, and the faceplate's `installdemo`, `cartdemo`, `fm1prompt`, `demomap`); the rest
(source, mode, core, sound, output, page, and the faceplate's view/sheet/theme ones) still work there.

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

`LinkTest` (18 tests) runs on libremotefm1's fake unit: in the app's process (the session and M1's test pattern; the
panel's events and the LEDs they light; Bluetooth pacing and HOLD; stop and start; ChoralRoot on the fake panel, a key
making sound; TRS MIDI in and TOUCH, ported from iOS's TrsMidiTests and TouchTests on the echo core), and through
Android's MIDI service (the virtual FM-1 found, opened and driven over AMidi; ChoralRoot played from it; and what the
first FM-1 test raised: the app killed and opened again while the unit keeps its session, the device seen twice, a
failed write, the unit's side going quiet for a while). It saves
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
- **Links that break.** A session write that fails (AMidi error, or the MIDI service taking less than the whole
  message for 20 ms) drops the link and the next discovery opens its ports again (A1 left it dead while the device
  stayed: the screen frozen, the keys dead). A link that has heard nothing from the unit for 6 s (12, 24 .. 60 s
  after each try, back to 6 s once it talks) is closed and opened afresh, so a USB MIDI input that went quiet under
  Android gets a new ALSA open. Every port open and close is logged (`RFMMidi`: index and direction), the first bytes
  after the ports open and after each session start, and why a link went (`link gone: <name>: <why>`); every 5 s
  `RFMLink` logs rx/tx bytes, PANEL_R and events (with the change), sessions, units lost, link drops and reopens.
  Diagnostics › Link shows the same (rx bytes, the last event's age, PANEL_R, session restarts, port drops).
- **USB**: `res/xml/device_filter.xml` (1209:0001) opens the app when the FM-1 is plugged in. MIDI over USB needs no
  USB permission (Android's MIDI service owns the device).
- **Bluetooth**: the Bluetooth page scans for the BLE-MIDI service (03B80E5A-...), asks for BLUETOOTH_SCAN
  (neverForLocation) and BLUETOOTH_CONNECT on Android 12+ or fine location on 10–11 only there, opens the device with
  `MidiManager.openBluetoothDevice`, remembers it and reopens it at launch and after it drops (2, 5, 10, 20, 30 s).
- **Background**: `PlaybackService`, a foreground service (mediaPlayback, plus connectedDevice when Bluetooth may be
  used) with the notification "RemoteFM1 is playing <core>" and Stop, while the FM-1 is the source or a core plays.

## Audio output

The Oboe stream is always opened on a device id, never on Android's default: once the FM-1 is plugged in, Android
makes its USB audio the default output. The picker (main page, Audio output) offers Automatic (wired headphones,
then Bluetooth, then other outputs, then the speaker; never USB), the speaker, headphones, Bluetooth and, only with
**Settings › Sound through USB devices (experimental)** on (off by default; remembered), a listed USB output. The
pick is saved as a kind, except USB, which lasts until the app quits. A USB output is opened shared, on Android's
mixer (performance mode None) with MMAP off; if it plays no frames within 2 s it is stopped, the pick goes back to
Automatic and the app says "Sound through USB didn't start on this tablet". Any other stream that Android routes to
a USB output anyway is closed before it starts (`RFMAudio` logs where each stream really went: asked device, got
device, API, sharing, performance mode, MMAP). Plugging the FM-1 in or out never moves the sound to or from it; when
the picked output goes, the sound goes to Automatic's choice.

**The audio-control thread** (`AudioControl.kt`, `RFMOboe.h`). The main thread never calls Oboe: it says what it
wants (sound on or off, the outputs to try in order: the pick, then Automatic's) and returns; only the latest want
counts (sound on/off/on before the thread looks is one open, or none if the stream still plays). The thread
`RFMAudioCtl` opens, starts, watches (every 100 ms: frames moving, a disconnect) and closes the streams, each
blocking call on a worker thread of its own with a time limit: a close not back in 2 s is abandoned (the stream was
silenced first; its worker finishes it whenever Android lets it, then frees it) and the next stream opens anyway; an
open not back in 5 s shows "The tablet's audio system is busy", is waited for up to 20 s (or until the sound isn't
wanted on that output), then abandoned (what it opens late is closed); at most two such opens are pending before a
new one is tried. Failures retry after 0.5, 1, 2, 4 and 8 s, then the line under the picker says "Sound failed: …"
until the sound is turned on again or the outputs change. Natively, several streams may exist at once but only the
active one renders the engine (each callback checks its stream's generation and plays silence otherwise; two
callbacks never render at once), a close never touches the engine, and the status the main page reads at 15 Hz
never blocks (what open recorded, the callback's counters, the getters refreshed by the audio thread). The engine's
clock changes (the main thread's and the audio thread's claims: TIMER to AUDIO when a stream plays, back when it
goes) are serialised in `rfm_jni.cpp`. Diagnostics › Core shows the thread's state, opens, closes, stuck closes and
opens, stalls and how many streams exist.

## Known issues

- **Galaxy Tab A9+ (SM-X210, Snapdragon, Android 16): sound to the FM-1's USB audio can restart the tablet.** In the
  first FM-1 test (2026-11-01, build A1) Android routed the app's low-latency MMAP stream to the FM-1, the Qualcomm USB
  audio offload started it (`uaudio`, `enable_audio_stream ... iface:3`) and 100 ms later the kernel hit an SMMU fault
  in the audio DSP's memory (`msm-audio-ion`) and a fatal NoC error: the tablet rebooted. After the reboot, Android's
  normal (mixer) path to the FM-1 failed instead: its audio HAL retried opening the USB output about 450 times a second
  for 17 s (`AGM ... Connect failed error:-22`). So the app never sends sound to a USB output unless the user picks it
  (with a warning on the row), and then the careful way (shared, no MMAP); whether that path is safe on this tablet
  is not known. Other Samsung/Qualcomm devices may do the same. The FM-1's USB audio is not needed for anything else:
  pick the tablet's speaker, headphones or Bluetooth.
- **The careful path doesn't work there either, and it makes the FM-1 drop off USB.** In the second FM-1 test
  (2026-11-01, build 07809bd) the FM-1's USB output picked by hand opened shared, performance None, MMAP 0 (AAudio,
  device 117, burst 1922), but the Qualcomm HAL could not play it: about 400 `AGM session_connect_aif ... graph open
  failed` / `PAL SessionAlsaPcm: start: pcm open not ready` / `pal_stream_start failed -22` lines, no sound. On the
  second try the FM-1 dropped off USB 3 s after the start (its USB output removed and added again 3 s later), as it
  did in round one right after a USB audio start. Then switching to the speaker blocked the app's main thread in the
  stream's close until Android killed it (ANR), and after the relaunch the audio server was still busy (an open took
  8 s; more ANRs when the sound was toggled). Since then: no stream call on the main thread (the audio-control
  thread), and USB outputs are not offered unless **Settings › Sound through USB devices (experimental)** is on.

## MIDI controllers, MIDI learn and the OMX-27 (A6)

As on iOS (its README's section of that name): **MIDI & audio › MIDI input** (All, None, or one device, remembered by
its name: Android gives a device a new id each time it connects) lists Android's USB and Bluetooth MIDI devices
(MidiManager), never the FM-1 (Fm1Candidate's rule, the website's name test for a virtual one) and never the OMX-27
while it is in remote mode. A controller's channel messages go to the engine as host MIDI (`rfme_midi_in`): learn
first, then the learned map (when **Use learned knobs** is on), then **Keys from MIDI** (Any, a channel, Off; a page),
**Transpose** (octaves) and "...and reach the firmware's MIDI in", the rest to the firmware. Android opens a device's
ports for one app at a time on some devices: a controller another app holds shows "can't open" and says so.

**Knobs from MIDI › Learn** closes the sheet; the faceplate outlines its 22 learnable controls (7 encoders, MASTER, 14
buttons), a tap picks one, the next CC or note binds to it (a toast says what), Forget / Done on the bar. The mappings
(an encoder's mode on a page of its own, × removes), Knob speed, Use learned knobs and Clear all are on the MIDI &
audio page; a learned MASTER pot waits to pick MASTER up (a mark on MASTER and a toast). The map is iOS's
`midi-map.json` (the website's `prefs.midiMap`, the same ids) in the app's files, run on the engine's core thread
(`rfme_set_midi_map`). **Connect my OMX-27** (the OMX-27 firmware with REMOTE mode, over USB): its keys, encoder and
pots play the panel, the app lights its 27 keys and draws its 128 x 32 screen; it connects by itself at the next start
and when it is plugged in again; Stop (the notification) clears its LEDs and screen.

**Velocity & pressure from my FM-1** (on by default; RemoteFM1 0.3.1 or later): the link's TOUCH (`rfmc_set_key_touch`);
Diagnostics' touch row says what the unit confirmed. The settings are iOS's dictionary (`rfm1.midiSettings`).

**Firmware › Open its editor** (ChoralRoot and PurpleMonkey, Felucca, Melodee, JIANT, GHOULBOX: the managers
remotefm1.com hosts) opens the site's page full screen in a WebView, with iOS's Web MIDI injected at document start
(androidx.webkit, only for https://remotefm1.com; where the WebView can't, when the page starts): one port each way
named as the firmware's USB port, SysEx granted, the site's shim told to stand aside. The page's SysEx goes to the
firmware whole (`rfme_sysex_in`), its other messages to the firmware's MIDI in, the firmware's MIDI out back as whole
messages; the bridge (`window.fm1mgr`) answers only while the page is on remotefm1.com over https. Opening it keeps a
safety copy first ("before manager write"). Links to other sites open in the browser; an editor's `blob:` downloads
(backups) are not saved yet.

Tests: `./gradlew :app:testDebugUnitTest` has `MidiMapTest`, `MidiSettingsTest`, `OmxTest` (the wire, the views and
OmxLink against a fake OMX-27 on a fake clock) and `ManagerListTest`; on the emulator `MidiEngineTest` (the map,
learn, pickup and keys in ChoralRoot; a 3 KB SysEx through the echo core and back), `PanelMidiTest`, `OmxScreenTest`,
`MidiDevicesTest` (the debug build's virtual keyboard and OMX-27 through Android's MIDI service: the keyboard plays the
keys, a learned knob turns KN1, the OMX-27 plays the panel, comes back after it is unplugged, Disconnect clears it) and
`ManagerTest` (ChoralRoot's manager from remotefm1.com connects and reads the running firmware).

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
| `RFMWasmCore.cpp`, `include/RFMWasm.h`, `Sources/CWasm3` | `native/rfmcore/RFMWasmCore.cpp`, `include/RFMWasm.h`, `rfm_wasm_jni.cpp`, `native/wasm3` | A4: own SHA-256, CLOCK_MONOTONIC, Android log |
| `Firmware.swift`, `FirmwareFeeds.swift`, `FirmwareStore.swift`, `FirmwareThumbs.swift`, `CorePlayer.swift` (CoreInfo, CoreRef, pick, replacement, adoptBuiltInFlash) | `app/.../firmware/` (`FirmwareLibrary`, `FirmwareFeeds`, `FirmwareStore`, `FirmwareThumbs`, `Cores`, `FirmwareCenter`) | A4: files/Firmware is iOS's mirror (`<sha256>.rfm1` + index.json); a refused file is not kept |
| `WasmTests.swift`, `ImportTests.swift`, `FirmwareStoreTests.swift`, `FirmwareThumbsTests.swift` | `app/src/test/.../firmware/` (JVM), `app/src/androidTest/.../firmware/FirmwareTest.kt`, `scripts/emu_firmwaretest.sh` | A4 |
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
| `FlashHistory.swift` (`FlashHistory`), `StateLibrary.swift`, `StateFile.swift` (StateFile, import, StateMirror, Backup) | `app/.../data/` (FlashHistory, StateLibrary, StateFile, StateMirror, Backup, DataTree, DataFolder, Json) | A5; the mirror and backups in a folder the user picks (SAF); a backup is one .zip |
| `CorePlayer.swift` (safety copies, checkVersion, wipedCopy, saveState/loadState, currentFlashes) | `app/.../data/DataSafety.kt` (plain functions the player calls) | A5 |
| Apple's Compression (LZFSE) | `native/lzfse/` (lzfse-1.0, BSD-3) + `data/Lzfse.kt` | A5; byte for byte the same stream |
| `FlashHistoryTests`, `StateLibraryTests`, `StateFileTests` | `app/src/test/.../data/` (+ `IosCompatTest`: files the iOS code wrote) | A5 |
| (edf2039) `MIDI/MidiMap.swift`, `MidiLearn.swift`, `MidiMapStore.swift`, `ControllerInput.swift`; `AUv3/Shared.swift` (AUSettings) | `app/.../midi/` (MidiMap, MidiLearn, MidiMapStore, ControllerInput + MidiParser, MidiSettings) | A6; MidiManager byte streams instead of UMP; one process: listeners instead of Darwin notifications |
| (edf2039) `OMX27/*.swift` | `app/.../omx/` (OmxWire, OmxViews, OmxScreen, OmxTransport, OmxLink, PanelOmxTarget) | A6; OmxAndroidMidi instead of OmxCoreMIDI (no UMP) |
| (edf2039) `Faceplate/LearnMode.swift`, `LearnViews.swift`, `Toast.swift`, `MidiSheet.swift`, `PanelFeed.swift` (sources, pickup) | `ui/faceplate/LearnMode.kt`, `LearnViews.kt`, `PanelFeed.kt`, `ui/MidiSheet.kt` | A6 |
| (edf2039) `Managers/ManagerBridge.swift`, `ManagerPage.swift`; `CorePlayer.swift` (manager*); `App/AppMidi.swift` | `app/.../managers/`, `AppMidi.kt` | A6; WebView, document-start script, `@JavascriptInterface` |
| (edf2039) `MidiMapTests`, `MidiEngineTests`, `OmxTests`, `PanelMidiTests`, `ManagerTests`, `TouchTests` (settings) | `app/src/test/.../midi/`, `omx/`, `managers/`; `app/src/androidTest/.../midi/MidiEngineTest.kt`, `PanelMidiTest.kt`, `omx/OmxScreenTest.kt`, `MidiDevicesTest.kt`, `managers/ManagerTest.kt` | A6 |

## Licence

GPL-3.0-only (LICENSE), as RemoteFM1IOS, except these files, which are MPL-2.0 (LICENSES/MPL-2.0.txt; as on iOS, the
author's decision of 2026-10-30): the MIDI map and learn (`native/rfmcore/RFMMidiMap.{h,cpp}`,
`app/.../midi/MidiMap.kt`, `MidiLearn.kt`), the OMX-27 (`app/.../omx/OmxWire.kt`, `OmxViews.kt`, `OmxScreen.kt`,
`OmxTransport.kt`, `OmxLink.kt`) and their tests (`app/src/test/.../midi/MidiMapTest.kt`, `omx/OmxTest.kt`,
`app/src/androidTest/.../midi/MidiEngineTest.kt`, `omx/OmxScreenTest.kt`). Each file's SPDX line says which. Oboe is
Apache-2.0, androidx and Compose Apache-2.0; lzfse (`native/lzfse`, Apple's) BSD-3-Clause (its LICENSE); the cores carry their own licences (their descriptors' credits).
