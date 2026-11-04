RemoteFM1 for Android 0.2.0

Thanks for all the feedback on 0.1.0! This release brings the Android app up to date with the iOS app: heavy .rfm1
firmwares play more smoothly, Fantasy CFW support, audio input and a lot of the iOS app's newer features.

Smoother .rfm1 firmwares
- Heavy firmwares such as JIANT, Rainbow Mode, X0X and Ravel drop out much less. The screen of a .rfm1 firmware now
  redraws 30 times a second with more audio buffered ahead, the sound comes first when the device is busy, and the app
  gives a heavy firmware more room (and a slower screen) by itself when it hears dropouts. The first second or two
  after starting a heavy firmware can still drop out while the app learns it; it remembers until you close the app.
- Settings › Diagnostics shows the firmware's load, underruns and screen rate if something still stutters.

Fantasy CFW
- Firmwares that run only in RemoteFM1 (marked FANTASY CFW), like Ripple, can load code and samples from a RemoteFM1
  folder. It lives inside the folder you pick for States and Backups: <your folder>/RemoteFM1/ with audio, code and
  data. The firmware's screen tells you when it needs a folder.

Audio input
- Firmwares that take audio input (Elasticat, for example) get Firmware › Audio input: turn it on (Android asks for
  the microphone then) and pick the input.

Firmware list and Get firmware
- Thumbnails and descriptions from the firmware catalog, only each firmware's newest version, sorting (Name, Recently
  used, Recently updated) and filters (All, Not installed, Recently updated).
- Update all, and a rebuilt firmware with the same version number is offered as an update.
- Older versions of a firmware, and a firmware sidebar: swipe in from the left edge to switch.
- Send to another device (Settings): every state and the firmwares they need in one .rfm1transfer file, which opens in
  RemoteFM1 on Android or iOS.
- At launch the app tells you when a newer RemoteFM1 for Android or RemoteFM1 firmware is out.

Editors, cartridges and the battery
- Every firmware's editor is its author's own page; swipe in from the right edge to open it. Page dialogs (OK/Cancel)
  work now. The FM-1 Screen Reader opens for the firmwares it supports.
- RemoteFM1's editor sends DX7 cartridges to RemoteFM1 in the app or to an FM-1. Cartridges you send are kept, and
  "Copy this app's banks to FM-1" copies the ones the FM-1 lacks.
- Settings › When you leave the app: keep playing, only with an FM-1, or stop; and Stop after 10 quiet minutes. The
  notification has Pause and Stop.
- Smoother menus, pickers open in the last folder you used, and the key range snaps to whole keys.

Install
1. Download RemoteFM1-0.2.0.apk below on your phone or tablet.
2. Open it and allow your browser or file manager to install apps when Android asks.
3. Samsung: turn off Auto Blocker while you install (Settings › Security and privacy › Auto Blocker).
4. It installs over 0.1.0 and keeps your states, firmwares and settings.

You need Android 10 or newer on an arm64 phone or tablet. For the panel, an FM-1 with the RemoteFM1 firmware.

What I tested: everything on the Android emulator (270 unit tests, 101 on the emulator), and the speed changes in a
test that runs the firmwares the way a slower phone would. Not tried on a real phone or tablet yet: the new speed,
audio input, the battery settings, and copying cartridges to a real FM-1. Audio input goes quiet while the app is in
the background.

Known issues
- On some Samsung/Qualcomm devices sound through the FM-1's own USB audio doesn't play and can make the FM-1 drop off
  USB. It stays off unless you turn on Settings › Sound through USB devices (experimental).

Please report anything that stutters, doesn't install, play, save or connect as expected, with your device's model
and Android version: https://github.com/Quixotic7/RemoteFM1Android/issues

Check the download: SHA-256 of RemoteFM1-0.2.0.apk
36a80ab36aa7660ee7bddd7db7f07e72c600a77772e3054937bf6b1aa92a96d8
Signing certificate SHA-256 (apksigner verify --print-certs), the same as 0.1.0:
6f:d9:95:6c:1c:e8:76:61:9e:71:0e:bb:33:ef:44:a5:d7:2c:f6:96:8c:a2:9b:27:86:13:93:8b:6a:17:99:cb
