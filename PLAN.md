# RemoteFM1 for Android (plan)

Status (2026-11-01): A0 done (skeleton, engine, all six cores; ChoralRoot plays on the emulator and the Tab A9+ through Oboe, 9.3 ms, 0 underruns).
A1 built and verified on the fake unit (14 link tests pass on the emulator and the Tab A9+, in-process and through
Android's MIDI service with a virtual FM-1 over AMidi); its "done when" waits for the real FM-1 test
(docs/A1-DEVICE-TEST.md). A1 also brought forward from A2/A6: Core mode on the link with the core picker and sound
(no flash files yet), the foreground service, TOUCH and TRS MIDI in (the engine had them).
A2 built and verified on the headless emulator (2026-11-01): the flash files (iOS's FlashFile: safe writes, `.prev`,
the sidecar; saved when iOS's CorePlayer saves), Restart, the audio output picker and the stream reopened on a
disconnect, all six cores tested (`CoresTest`, 11 tests; JVM: `FlashFileTest`, `AudioRouteTest`). Its "done when"
waits for the tablet and the real FM-1 (ChoralRoot heard through the FM-1's USB audio, underruns 0 on the tablet).
First real FM-1 test (2026-11-01, build A1): found and played, but Android routed the sound to the FM-1's USB audio and
the MMAP stream through the Qualcomm USB offload restarted the Tab A9+; after it the keys were dead. Fixed (not yet
tried on the FM-1): the sound goes to a USB output only when the user picks it (then shared, no MMAP; a warning on
the row; never saved), every stream opened on an explicit device; a link whose write failed is dropped and reopened
(A1 left it dead), a link silent for 6 s+ is reopened; logs and Diagnostics for every port, drop and session; the
Sound source chips say what they do. JVM 36 tests, LinkTest 18 (emulator and tablet). Second round:
docs/A1-DEVICE-TEST.md. A2's "heard through the FM-1's USB audio" is now optional there (README, "Known issues").
Second real FM-1 test (2026-11-01, build 07809bd): the FM-1's screen and buttons worked over USB and ChoralRoot played
on the tablet's speaker; the FM-1's USB audio (picked by hand, shared, no MMAP) played nothing (Qualcomm HAL:
`graph open failed`, `pal_stream_start failed -22`), the FM-1 dropped off USB right after, and switching back to the
speaker blocked the main thread in the stream's close: ANR, and after the relaunch the audio server was still busy
(more ANRs). No Bluetooth was tried. Fixed: the audio-control thread (`AudioControl.kt`: nothing on the main thread
waits for Oboe, wants coalesce, a stuck close is abandoned after 2 s, a slow open shows "busy" and is retried, a
silent USB stream falls back to Automatic; natively several streams may exist, only the active one renders) and USB
outputs only with Settings › Sound through USB devices (experimental). A2's "heard through the FM-1's USB audio" is
dropped for this tablet. Third round: docs/A1-DEVICE-TEST.md (Bluetooth, sound spam on the speaker, 5 minutes over
USB MIDI; no USB audio).

A3 built on the headless emulator (2026-11-01): the faceplate in Compose (ui/: Full for phone portrait, phone landscape, tablet landscape and portrait; Controls; Screen; the themes, the editor, the four fonts; display alone; the screen protector; the firmware's button names; the pull-down sheet with iOS's pages and wording), the engine polled every frame with no allocations (RfmEngine.panel / previewDirect); A4's firmware/, A5's data/ and A7's install/ + cart/ wired to the sheet's pages (AppModel: catalog keys, .rfm1 loads, the data steps, the FM-1 lent to an install or a send). The app now starts in Core mode with the sound on (the test pattern is in Diagnostics, as on iOS). scripts/emu_shots.sh takes the iOS sim_shots set on the emulator (phone sizes by `wm size`).

Owner's answers (2026-11-01): install the Android tooling (yes); test device: the owner's Samsung Galaxy Tab A9+
(SM-X210, Android 16 / API 36, arm64, 1200x1920; adb serial R95Y906QALT; Samsung's Auto Blocker must be off for adb and
sideloading); copy RFMCore from RemoteFM1IOS (yes); app id `com.quixotic7.remotefm1`, name "RemoteFM1"; a local
release key made now (yes).

The owner's words: "This should work exactly like the iOS version, but run on Android. Not planning to try and get
this on the Play Store yet, so just release as an apk that users can sideload."

The model is RemoteFM1IOS (`../RemoteFM1IOS`, its README.md and PLAN.md, build 15 / 0.1, 2026-10-31): a phone/tablet
host for FM-1 firmware cores with a real FM-1 running RemoteFM1 firmware as the panel, plus an on-screen FM-1 for use
without one. Everything the iOS **app** does, the Android app does, looks the same and keeps the same files. The
iOS **AUv3** has no Android counterpart (section 4).

## 1. The stack

| Layer | Choice | Why |
| --- | --- | --- |
| UI | Kotlin + Jetpack Compose (Material 3 only for system bits; the faceplate is drawn by us) | the SwiftUI faceplate maps closely to Compose (Canvas, gestures, `BoxWithConstraints` for layouts) |
| Audio | Oboe (Apache-2.0, AAudio underneath; OpenSL ES on old devices), low-latency exclusive mode | the standard for low-latency audio on Android; one callback, like the iOS output node |
| Engine | the iOS app's C++ (`RFMCore`: RFMEngine, Device, RemoteLink, MIDI map, host clock, Wasm loader) built with the NDK (CMake), behind a JNI layer | portable C++ already; only its Apple calls change (section 3) |
| Firmware cores | FM1VST's cores and glue, cross-compiled per ABI as `.so` files in the APK | the same build as iOS's `cores/CMakeLists.txt`, Android toolchain instead |
| Imported firmware | `.rfm1` run by wasm3 v0.5.0 (MIT), as on iOS | same loader, same checks, same files |
| FM-1 link | libremotefm1 (RemoteFM1 0.3.1, MPL-2.0) + a new Android MIDI back end on the NDK's AMidi | replaces CoreMIDI (`RemoteMidi.c`, `macos/host.c`) |
| MIDI | `android.media.midi` (`MidiManager`): USB MIDI and Bluetooth LE MIDI; AMidi for the native byte path | built into Android 6+; AMidi needs Android 10 |
| Bluetooth pairing | our own BLE scan page (MIDI service UUID) → `MidiManager.openBluetoothDevice` | Android has no system MIDI pairing sheet like `CABTMIDICentralViewController` |
| Web managers | Android `WebView` + androidx.webkit `addDocumentStartJavaScript` + a JS bridge | same injected shim as iOS's WKWebView |
| State files | Apple's open-source LZFSE (BSD-3) in the NDK | `.rfm1state` and state files stay byte-compatible with iOS |
| Installer | a Kotlin port of FM1Kit 0.4.x (MIT, Drew Robinson) over USB MIDI | FM1Kit is Swift; its protocol and checks are ported, credited |
| Build | Gradle (Android Gradle Plugin), Kotlin, NDK, CMake | standard; `./gradlew assembleRelease` makes the APK |

Minimum Android 10 (API 29): AMidi, Oboe low-latency and scoped storage all settled; about 9 in 10 active devices.
Compile/target API 36. ABI: arm64-v8a only (phones, tablets, and the emulator on this Apple-silicon Mac).
armeabi-v7a or x86_64 only if users ask. Tooling (A0): SDK in `~/Library/Android/sdk`, NDK 29.0.14206865, CMake
3.31.6, build-tools 36.0.0, JDK 21 (`/opt/homebrew/opt/openjdk@21`), emulator image android-36 google_apis arm64.

## 2. What maps 1:1 and what needs Android work

| iOS piece | Android | Work |
| --- | --- | --- |
| libremotefm1 (codec, session, panel, screen, fake unit) | as is | none: portable C99 |
| `RFMCore` C++: engine, render-ahead core thread, Device, RemoteLink, MIDI map, host params (parts), echo core, Wasm core | copied in (`native/rfmcore/`, header says from which iOS commit) | small: section 3 |
| Cores (ChoralRoot, Felucca, FIZZ, Saadet, Boardwalk, LOOM-8) | `.so` per core, one per ABI | small: the toolchain file; ChoralRoot's `<libproc.h>` stand-in again |
| Slots (4 signed frameworks per core) | not needed: one app, one instance at a time | dropped |
| CoreMIDI back end + candidate rule (`RemoteMidi.c`, `macos/host.c`) | AMidi back end, same candidate rule (USB ports named FM-1, Bluetooth device names) | new: about 600 lines C + Kotlin device discovery |
| `CABTMIDICentralViewController` | own BLE scan page; Android 12+ Bluetooth permissions | new |
| AVAudioEngine + output picker + USB audio route | Oboe stream; `AudioManager` device list; pick the output (`setDeviceId`); USB audio of the FM-1 shows up as an output | new, small |
| Background audio (`UIBackgroundModes`) | a foreground service (media playback + connected device) with a notification | new |
| Swift app logic (RFMKit, ~17,000 lines): CorePlayer, firmware library, feeds and store, thumbnails, flash history, states, state files, mirror, backup, cart, OMX-27, learn, install prompt | Kotlin, same rules and file formats; pure-Kotlin modules tested on the JVM | the bulk of the port: rewritten, file by file, with iOS's tests as the spec |
| SwiftUI faceplate (Full / Controls / Screen, themes, keybed, sheet, theme editor, screen protector, display alone, learn mode, toasts) | Compose, same tokens (`design/tokens.json`), same four OFL fonts | big: rewritten, checked against iOS screenshots |
| Files app folder (Documents/Firmware, States, Backups) | app storage + a folder the user picks once (Storage Access Framework) for the States mirror and backups; "Open with RemoteFM1" for `.rfm1`, `.rfm1state`, `.syx` | Android-specific |
| AirDrop / share sheet | Android share sheet (`ACTION_SEND`, a FileProvider) | small |
| App Group (app ↔ AU sharing), Darwin notifications, lease file | not needed (no AU); in-process listeners instead | dropped |
| The lease / "Use this FM-1" | kept only as "the FM-1 has a session from elsewhere" (HELLO_R's flag) | small |
| FM1Kit installer (Swift package) | Kotlin port of its USB-MIDI update protocol, `.fwsc` checks, resume; its `SimulatedFM1` too, for tests | medium |
| WKWebView managers + injected fm1-shim side | WebView, document-start script, `@JavascriptInterface` bridge | small |
| Mach real-time thread, semaphores, audio workgroup | `pthread` at audio priority (`setpriority`/SCHED_FIFO where allowed), futex or `sem_t`; Oboe's callback thread | small |
| LZFSE (Apple's Compression framework) | lzfse C library (same format) | small |
| UserDefaults | DataStore / SharedPreferences | small |
| TestFlight / App Store build, store cores list | not needed: one sideload build with every core the iOS full build has | dropped |
| Simulator scripts (`sim_*.sh`) | headless Android emulator (`emulator -no-window`), `adb` launch extras instead of launch arguments, `adb exec-out screencap` | new scripts |

Same on both, deliberately: the catalog feeds (remotefm1.com `catalog.json`, RemoteFM1FW releases), the `.rfm1` and
`.rfm1state` formats, the flash files and sidecars, the history and state JSON, the MIDI map's ids
(`midi-map.json`), the theme tokens, the button-name rules, the wording on every page. A state saved on an iPhone
opens on Android and back.

## 3. The C++ engine on Android

What changes in the copied `RFMCore` (everything else compiles as it is):

- `RFMRealtime.*`, `RFMEngine.cpp`: Mach semaphores and the Mach real-time policy → `sem_t` / futex and an audio-priority
  thread; no audio workgroup (Oboe's callback is the "host"). The render-ahead ring stays: on Android it matters
  more, since phones differ widely and some have 10-20 ms buffers.
- `RFMEngineNode.mm` (AVAudioSourceNode) → `RFMOboe.cpp`: an Oboe data callback that only copies from the ring and
  wakes the core thread, as the iOS callback does. Rate from the stream; the Device resamples 44.1 kHz as before.
- `RFMNativeCore.cpp`, `RFMSlots.cpp` → a loader that `dlopen`s `libfm1core_<id>.so` from the APK's native library
  folder (no slots, no copies; Android can't load code from writable storage anyway, which we don't need).
- `RemoteLink.*`: its CoreMIDI calls go through the new `rfm_android` back end (AMidi input/output ports opened from
  `MidiDevice`s that Kotlin hands down). Bluetooth pacing, lossy tiles and HOLD are libremotefm1's already.
- `RFMLease.cpp`, `SharedFake.cpp`, `RFMProbe.cpp`, `RFMKernel*` (the AU's render block): not built. `RFMHostParams`
  and `RFMHostClock` are AU-only too: not built unless an Android plugin format is wanted later (section 4).

Later options Android allows that iOS doesn't (not in this plan unless the owner wants them):

- **USB vendor bulk.** libremotefm1 has a libusb path (built off on iOS: `RFM_NO_LIBUSB`). Android's USB host API can
  do bulk transfers, so the panel could run faster than over USB MIDI.
- **A JIT for imported firmware.** Android apps may JIT, so WAMR's fast JIT or AOT could run `.rfm1` several times
  faster than wasm3, and fm1-emu with its JIT could run original `.fwsc` firmware (iOS can't: no JIT). wasm3 first,
  for identical behaviour with iOS.

## 4. Not ported

- **The AUv3 instrument** and everything only it needs: the lease between instances, slots, `fullState`, the
  parameter tree (129 parameters), the host's clock and transport, AU MIDI out. Android has no plug-in format in
  common use (AAP, the Android Audio Plugin project, exists but almost no hosts load it). The app's own MIDI out and
  clock options are kept where the iOS app has them.
- **TestFlight / App Store** builds and the store core list. One sideload build, GPL, every core of the iOS full
  build: ChoralRoot, Felucca, FIZZ, Saadet, Boardwalk, LOOM-8 (Melodee as the catalog's `.rfm1`, as on iOS since
  2026-10-31). The gated cores stay out, as everywhere.
- iOS-only fixes (the iOS 27 Bluetooth sheet crash, `UIScreen` NaN, iPad rotation workarounds).

## 5. Milestones

Each ends in something tested: JVM unit tests for the Kotlin logic (`./gradlew test`), the C/C++ tests on the Mac
(libremotefm1's ctest, the engine and core tests against macOS builds), instrumented tests and screenshots on a
headless emulator with the fake unit, and on a real Android device with the FM-1 where the link is involved.

| | What | Done when |
| --- | --- | --- |
| A0 | Toolchain and skeleton: the SDK, NDK and emulator installed; Gradle project (`app`, `native` with CMake); RemoteFM1, FM1VST and the firmware repos as submodules at the iOS app's pins; libremotefm1 + the engine + one core built for arm64; a JNI smoke test | the APK builds from a clean checkout; on the headless emulator ChoralRoot boots on the fake unit and its first audio frames aren't silent |
| A1 | The FM-1 link: AMidi back end, USB MIDI discovery (no USB permission is needed for MIDI; a USB_DEVICE_ATTACHED filter opens the app), the BLE scan page, HELLO, session, M1's test pattern, Core mode on the link, the Diagnostics page (HELLO_R fields, link stats), the foreground service | on a real phone: the pattern moves on the FM-1 over USB and Bluetooth, its keys light their LEDs. Fake unit: done (`scripts/emu_linktest.sh`); real FM-1: docs/A1-DEVICE-TEST.md |
| A2 | Cores and sound: every core as `.so`, the engine with render-ahead on Oboe, the flash files (safe writes, `.prev`), the foreground service, the audio output picker, the audio-control thread | ChoralRoot plays from the FM-1, heard on the phone (the FM-1's USB audio: experimental, off by default; it fails on the Tab A9+); a power cycle keeps its settings; underruns counted and 0 on the test phone; no ANR from sound on/off or output switches |
| A3 | The faceplate in Compose: Full (phone portrait with the scrolling keybed, landscape, tablet), Controls, Screen; the themes and editor; display alone; screen protector; the pull-down sheet with its pages; button names; touch keys without an FM-1 ("This phone") | emulator screenshots match iOS's `sim_shots.sh` set page for page; playable with no FM-1 |
| A4 | Firmware: the library, `.rfm1` (wasm3), Add firmware / Open with, Get firmware (both feeds, updates, badge, offline cache), delete, thumbnails, the Firmware card (description, View source) | the Melodee and Felucca `.rfm1` from the catalog install and play next to the built-in cores |
| A5 | Data safety (iOS section 18): safety copies and history, Reset, named states, `.rfm1state` share/import, the States mirror and Back up everything / Restore (in the folder the user picks), version questions, the wiped-memory card | a state saved on the iPhone app loads on Android and back; the iOS tests' cases pass in Kotlin |
| A6 | MIDI: controller input, Keys from MIDI, MIDI learn, the OMX-27, the FM-1's TRS MIDI in, velocity and pressure (TOUCH), the firmware's web managers in a WebView | a USB keyboard plays the keys, a learned knob turns KN1, ChoralRoot's manager reads the running firmware |
| A7 | The FM-1 itself: install RemoteFM1 (FM1Kit port, releases.json, checks, resume, the install prompt), send a DX7 cartridge and manage user banks | on the simulated FM-1 (ported `SimulatedFM1`) a full install and a cut-off resume pass; on a real FM-1 over USB an update installs. **The firmware session does the real flash, never this one** |
| A8 | Release: a local release key (kept outside the repo, backed up by the owner), a signed release APK, README with sideload steps, a GitHub release | the owner OKs the APK, then the release is published |

Code is written by Opus subagents, one milestone at a time; Claude designs, briefs, reviews and tests (the owner's
rule). Nothing is pushed or released without the owner's OK.

## 6. Android-specific details

- **USB.** The FM-1 on USB is a MIDI device and a USB sound card. Android asks the user once per device for USB
  access; a `USB_DEVICE_ATTACHED` filter for the FM-1's IDs opens the app on plugging in and can remember the choice.
  OTG adapters: USB-C phones are fine; the app says when no USB MIDI device appears.
- **Bluetooth LE MIDI.** Android 12+: `BLUETOOTH_SCAN` (never for location) and `BLUETOOTH_CONNECT`; Android 10–11:
  location permission for scanning, asked only on the Bluetooth page. A unit paired once is reopened at launch.
  `fm1-base`'s fast BLE settings (DLE, 2M PHY, MTU 247) help Android as much as iOS; whether a phone's stack takes
  them varies by maker, measured in A1.
- **Audio latency.** Oboe's low-latency mode and the render-ahead target (buffer + 6 ms as on iOS). Some phones have
  long output paths; Diagnostics shows the measured latency and underruns.
- **Storage.** The app's own folder holds firmware, flash, history and states (`files/`). Android has no Files-app
  folder per app as iOS does, so the user picks a folder once (Settings › Your data) for the States mirror and
  backups; without one the mirror is off and backups go through the share sheet. Backups are written as one `.zip`
  (Android can make it directly). Opening `.rfm1`, `.rfm1state`, `.syx` from a file manager, a browser download or
  a share imports them, as on iOS.
- **Lifecycle.** Playing keeps a foreground service and a notification ("RemoteFM1 is playing ChoralRoot"), so the
  panel and sound go on with the screen off. The flash is saved when the app goes to the background, as on iOS.
- **Sideloading.** Users enable "Install unknown apps" for their browser or file manager, download the APK from the
  GitHub release, open it. Updates: a newer APK signed with the same key installs over the old one and keeps the
  data. So the release key must never change or be lost: it is created once, kept out of the repo, and the owner
  keeps a backup. The app can check the GitHub releases for a newer APK (once a day, like Get firmware) and offer the
  download; it never installs by itself.

## 7. Licences

GPL-3.0-only, as RemoteFM1IOS, with the same files MPL-2.0 (the MIDI map and learn, the OMX-27 code, and their Kotlin
ports). Other people's code: wasm3 (MIT), Oboe (Apache-2.0), lzfse (BSD-3), androidx and Compose (Apache-2.0), the four
UI fonts (OFL), FM1Kit's protocol code ported (MIT; its notice kept), and the cores with their own licences (iOS
PLAN.md section 7). The About page lists them, as on iOS. Sideloaded GPL APKs have none of the App Store conflict:
every core of the full build can ship. The `.rfm1`-only firmwares are downloaded by the user from their authors'
releases, as on iOS.

## 8. Questions for the owner

1. **Install the Android tooling?** This Mac has `adb` and a JDK but no Android SDK. Needed: the command-line tools,
   a platform, build tools, the NDK, CMake and the emulator with one arm64 system image: about 8–10 GB in
   `~/Library/Android/sdk`. No Android Studio needed (the emulator runs headless, nothing in the foreground).
2. **A real Android device for testing?** The link, USB, Bluetooth, latency and the installer can't be tried in the
   emulator (it has no MIDI devices). Which phone or tablet, and which Android version?
3. **The C++ engine: copy or share?** Plan: copy iOS's `RFMCore` into this repo with its source commit noted (as iOS
   did with FM1VST's RemoteLink), and port fixes by hand. The alternative is a shared library repo both apps use;
   cleaner later, but it means changes in RemoteFM1IOS now.
4. **App id and name:** `com.quixotic7.remotefm1` and "RemoteFM1", as on iOS?
5. **Signing:** a local release key made now (recommended, since sideloaded updates need the same key forever), or
   debug-signed test APKs until the first public release?

## 9. Risks

- The faceplate is the largest piece (about 4,700 lines of SwiftUI, plus the app's pages) and the part users notice most; Compose differs in
  gestures and text layout, so pixel matching takes iterations.
- Android devices vary: audio latency, Bluetooth MIDI quality and USB behaviour depend on the maker. Only the test
  device(s) are verified; the release notes say which.
- wasm3 on slower Android phones: ChoralRoot `.rfm1` used about 36 % of the core thread on an iPhone 13 Pro Max;
  mid-range Android phones are slower. The app shows a note for a firmware too heavy for the device, as on iOS.
- Keeping two apps in step: every iOS change after build 15 needs a matching Android change. A file-by-file map of
  iOS → Android sources (in README) makes that easier.
