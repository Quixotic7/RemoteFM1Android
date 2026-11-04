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

A6 built on the headless emulator (2026-11-01, iOS edf2039): MIDI input from controllers (MidiManager; never the
FM-1, never the OMX-27 in remote mode), Keys from MIDI, Transpose, "...and reach the firmware's MIDI in", MIDI learn on
the faceplate (iOS's `midi-map.json` in the app's files, run by the engine), the OMX-27 in REMOTE mode, Velocity &
pressure from my FM-1, the firmwares' web editors (Firmware › Open its editor: remotefm1.com's managers in a WebView with
iOS's Web MIDI injected). A3's leftovers: the wiped-memory card, the States mirror synced (launch, changes, the
foreground), the Bluetooth page from the FM-1 card. Tested with virtual devices (the debug build's keyboard and OMX-27)
and ChoralRoot's manager from the live site; its "done when" on hardware waits for a real keyboard, OMX-27 and FM-1.

A8 prepared locally (2026-11-01): 0.1.0 (versionCode 1), signed with the release key, `scripts/build_release.sh`
(clean build, signature and certificate, v2/v3, zip and 16 KB ELF alignment, the cores, no debug parts) writes
`build/release/RemoteFM1-0.1.0.apk` and its `.sha256`; the release APK installed, started and played ChoralRoot on
the headless emulator; README "Install (sideload)", `docs/RELEASE-NOTES-0.1.0.md`. Not tagged, pushed or published:
waits for the owner's OK.

**The release key (owner: back it up).** `~/.android-keys/remotefm1-release.jks` (the keystore) and
`~/.android-keys/remotefm1-release.properties` (its passwords and alias) on this Mac, outside the repo. Back up both
files together somewhere safe and offline (a password manager's file attachments, an encrypted disk); neither is ever
committed. Certificate SHA-256 `6F:D9:95:6C:1C:E8:76:61:9E:71:0E:BB:33:EF:44:A5:D7:2C:F6:96:8C:A2:9B:27:86:13:93:8B:6A:17:99:CB`.
If the key is lost, no later APK can install over 0.1.0: every user would have to uninstall (losing the app's data
unless they backed it up) and install the new one.

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
| A8 | Release: a local release key (kept outside the repo, backed up by the owner), a signed release APK, README with sideload steps, a GitHub release | the owner OKs the APK, then the release is published. Prepared 2026-11-01: `scripts/build_release.sh`, `build/release/RemoteFM1-0.1.0.apk` (+ `.sha256`), README "Install (sideload)", docs/RELEASE-NOTES-0.1.0.md; release build ignores the test-only launch extras; R8 off. Waits for the OK, the tag and the GitHub release |

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

## 10. Firmware library and catalog, caught up with iOS (2026-11-04)

From RemoteFM1IOS d629aa0 (fe93170, e91d519, 9494ebe, 836a983, 3828820, d88353e, ac7254d, a67b1cf, 3ea5b25):

- **RemoteFM1FW's catalog** is a third feed (catalog/catalog.json): its tag, description, editor and port always win;
  its screenshots are kept in files/Thumbs/catalog (offline), fetched again for a new release, shown before the
  shipped and captured thumbnails.
- **Lists:** only each firmware's newest version (FirmwareListing); sort by Name / Recently used / Recently updated
  (release dates from every RemoteFM1FW release, versions.json); the big Get more firmware button; the firmware
  sidebar, a swipe in from the left edge (a 200 dp band kept from the back gesture).
- **Get firmware:** thumbnails, All / Not installed / Recently updated, Update all (stoppable), a same-version rebuild
  offered as "Get the new build", a firmware's older versions.
- **Another device:** Settings › Send to another device… makes one .rfm1transfer (iOS's bytes: either app opens the
  other's); opened, everything is imported and the From another device page loads each current memory with one tap.
  A newer version's state offers "Switch to / Get <version> and load"; an older version's loads plainly.
- **Launch notice:** release/android.json (set `build` to the new versionCode at each release) or an update of
  RemoteFM1's own firmware: one dialog, closed until something newer comes. The "updatenotice" extra forces it.

## 11. Engine speed and audio input (E, 2026-11-04; iOS 836a983, 3828820, 14c9b96, dfbc827, 23bba90, c5d5556, c47ac4f)

- **PerfGuard** (PerfGuard.kt, AppModel.refresh): a Wasm core redraws every 33 ms with 12 ms of headroom; on
  dropouts each ~2 s window the headroom doubles (to 40 ms) and the screen slows to 50, then 66 ms; kept per firmware
  for the app's run. Its thread leaves the audio priority for an ordinary one at nice -19 (rfme_set_realtime 0;
  Android apps rarely get SCHED_FIFO, so this changes little here). frame() waits (up to 200 ms) while the ring is
  under half its target; the load is counted per chunk. The "too heavy" note on the Firmware page.
- **Android only:** the core thread reports each wake's work to ADPF (APerformanceHint, Android 13+), so the CPU
  speeds up before the sound is late (status `perf_hint`; the emulator has none). Diagnostics: core thread, screen
  (frame() avg/max, redraws, waits), audio input.
- **Battery hooks:** RfmEngine.setFrameMs (the slower of it and PerfGuard's), status `last_sound_ms`.
- **Measured** (PerfTest, scripts/emu_perftest.sh; the emulator's output has 20 ms bursts, so BUFFER=192 paces a
  phone-sized callback and SLOW=3/5 stretches the core thread's work like a mid-range tablet's CPU): Rainbow Mode
  298 -> 14 dropouts in 12 s at 3x (7.3 % -> 0.2 % silence), 562 -> 71 at 5x; Ravel 133 -> 2, JIANT 91 -> 25, X0X
  40 -> 0 at 5x; what is left falls in the first window, before PerfGuard has learnt the firmware. Latency 12 -> 17 ms
  (29-45 ms for a firmware that needed more).
- **ain1:** the engine's input ring and exports, an Oboe input (RFMOboeInput.cpp: AAudio, unprocessed, resampled to
  44.1 kHz in the engine), RECORD_AUDIO asked on turning it on, on and the input kept per firmware (iOS's keys
  ain.on.<id>, ain.port.<id>: here an AudioDeviceInfo id), Firmware page › Audio input. No AU on Android (b91ee30
  skipped). Recording in the background would need a microphone foreground-service type: not done.

### Editors, cartridges, the battery, menus (agent G, 2026-11-04)

From RemoteFM1IOS d629aa0 (f47cdd4, 95d5d16, f02182b, 1d88c11, eb3455c, e60d78a, f6e6eaa, 391497e, 14c9b96, dc771c8,
bd0711a, 41e8471, dfbc827, e527b74):

- **Editors:** always the author's page (the catalog's `editor`, https only, else ManagerList.authors), never
  remotefm1.com's copies; the port is the catalog's `port`, else the app's list. The Web MIDI is injected into every
  https page. The page's alert / confirm / prompt are dialogs; its file inputs open the app's picker in the folder last
  picked from; its downloads (blob: too) go to the share sheet; new windows open in the browser; its console errors go
  to the log (RFMManager); it may play sound untapped. The editor slides in from the right; a swipe in from the right
  edge opens it; the back gesture or Done closes it. The screen reader (Charles Vestal's) for X0X, FoMni, AMB-1,
  Polly, MONK-1, Mocky, CTL-1.
- **RemoteFM1's editor** is the cartridge page: "RemoteFM1 in this app" (CoreCartTransport) is a target beside the
  FM-1s while it runs; every cartridge sent is kept (CartKeep: Android/data/com.quixotic7.remotefm1/files/Cartridges)
  and "Copy this app's banks to <FM-1>" copies the app's banks one way, with their names.
- **The battery:** Settings › When you leave the app (Keep playing / Only with an FM-1 connected / Stop playing) and
  Stop after 10 quiet minutes, checked every 15 s in the background; the screen redrawn every 250 ms there with no
  FM-1; the notification is a media one with Pause (stops and saves) / Play / Stop.
- **Menus:** pages read AppModel.coreRunning (set on change), not the 66 ms engine status (the Firmware page
  recomposed 164 times in 12 s, now 0); the audio page's live lines recompose alone. Pickers open where their kind
  last picked from (PickerFolder). The key range snaps to whole keys on release.

## 12. Skins (2026-11-04; iOS 47da48a, ca42bbc)

An image behind the faceplate, like Koala Sampler's backgrounds (ui/theme/Skin.kt, SkinImages.kt,
ui/sheet/SkinSettings.kt; iOS PLAN.md section 23). Brian White's idea.
- **The user's:** the sheet's Background section, under Theme: Background image (the photo picker, PickVisualMedia:
  no storage permission; stored scaled to 2048 px as a JPEG in files/Skins), Dim (0–0.8, default 0.25: the
  faceplate's colour over the image, so its labels stay legible), Remove. The theme's colours still draw the controls.
  No AU here, so no App Group copy.
- **A firmware's:** RemoteFM1IOS docs/RFM1-FORMAT.md section 8 (`rfm1.skin` JSON with dim and colours,
  `rfm1.skin.background`, `.landscape`; iOS scripts/rfm1_skin.py adds one), read in Kotlin from the file's custom
  sections. Shown while that firmware plays in the app (mode core), with its colours in place of the theme; it wins
  over the user's image. Firmware skins (on by default) turns them off. Built-in native cores have none.
- **Speed:** files are read and images decoded once, on one background thread (ImageDecoder, scaled while decoding,
  hardware bitmaps); a firmware's skin is cached per file; the image is drawn in a layer of its own. Skins compare by
  id, dim and colours, so the faceplate is skipped when nothing changed (RecompositionTest: 0 main-screen
  recompositions in 12 s with a background image).
- Tests: SkinTest (JVM: sections read back from bytes and from the file, which skin wins, the store, sizes) and
  SkinDeviceTest (real PNGs, scaling, a skinned file still checks and plays, the JPEG stored, the faceplate's pixels).
