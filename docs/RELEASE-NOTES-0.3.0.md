# RemoteFM1 for Android 0.3.0

Ableton Move support: a Move running Move Remote becomes the FM-1's controller and sequencer, the same as on
remotefm1.com and in the iOS app.

- Settings › Ableton Move: type the Move's address (or tap USB-C when it's plugged in) and Connect. The Move's pads
  play the keys (or MIDI), its buttons and knobs are the FM-1's, and the app lights its pads and draws its screen.
  Track 1..4: FM-1, MIDI, Control, Sequencer. The app reconnects at start until you Disconnect.
- Pick firmwares on the Move: Track 1 held + step 1 opens the firmware browser with the app's own firmware list,
  pictures included. Jog to one and click to load it (the same as picking it on the Firmware page); one not on the
  phone yet downloads first. Fantasy CFWs have a badge and a filter.
- One firmware at a time and no MIDI or audio effects, as on the website. Several firmwares at once and the effects
  are for RemoteFM1 running on the Move itself.
- The knobs' names on the Move come from the loaded firmware, where it gives them.
- The volume is the FM-1's MASTER knob: the Move's level (Track 1 held + the volume knob) turns MASTER on the
  faceplate, and MASTER turned anywhere sets the Move's level.
- Projects and the sequencer's patterns are kept on the phone and come back when the Move reconnects.

The Move code is the shared rfm1cs-abletonmove core (v0.5.11), the one the website and the iOS app use, so Move
changes reach all three together.

Everything from 0.2.2 is here too: the FM-1_72 identity, skins, Fantasy CFW, audio input, the firmware list, editor,
cartridge and battery features.

Install
1. Download RemoteFM1-0.3.0.apk below on your phone or tablet.
2. Open it and allow your browser or file manager to install apps when Android asks.
3. Samsung: turn off Auto Blocker while you install (Settings › Security and privacy › Auto Blocker).
4. It installs over any earlier version and keeps your states, firmwares and settings.

You need Android 10 or newer on an arm64 phone or tablet. For the panel, an FM-1 with the RemoteFM1 firmware. For the
Move, an Ableton Move with Move Remote installed, on the same Wi-Fi or plugged in by USB-C.

What I tested: the unit tests (295) and the instrumented suite (127) on the Android emulator, and the Move end to end against Move Remote's simulator (pads, buttons, screen, the firmware browser and a load). Not tried with a real Move yet.

Known issues
- On some Samsung/Qualcomm devices sound through the FM-1's own USB audio doesn't play and can make the FM-1 drop off
  USB. It stays off unless you turn on Settings › Sound through USB devices (experimental).

Please report anything that doesn't install, play, save or connect as expected, with your device's model and Android
version: https://github.com/Quixotic7/RemoteFM1Android/issues
