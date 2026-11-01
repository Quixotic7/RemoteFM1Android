# A1 on a real FM-1: the device test

What is already verified without hardware (2026-11-01): the whole link on libremotefm1's fake unit, in the app's
process and as a virtual MIDI device reached through Android's own MIDI service and AMidi (the same path a USB FM-1
takes), on the emulator and on the Galaxy Tab A9+ (`scripts/emu_linktest.sh`, 14 tests). What only a real FM-1 can
show: that Android lists it, that our candidate rule picks it, and how Android's USB and Bluetooth MIDI carry the
SysEx frames. This is that test. It sends the FM-1 only the RemoteFM1 panel protocol (HELLO, SESSION, TILES, LEDS,
TOUCH); nothing here installs or updates firmware.

**Before:** the FM-1 runs RemoteFM1 (0.2 or newer; 0.3.1 for velocity, pressure and TRS MIDI in). The tablet has the
debug build of RemoteFM1 installed (Claude installs it; `app/build/outputs/apk/debug/app-debug.apk`).

## 1. USB

1. Connect the FM-1 to the tablet with a USB-C cable (the tablet is the host). Android may ask *"Open RemoteFM1 to
   handle FM-1?"*: tap **OK** (tick *Always* if offered).
2. Open RemoteFM1. On the main page pick **Sound source: FM-1** and **Mode: Test pattern**.
3. Look at the pill at the top right and at the FM-1:

| What you see | What it means |
| --- | --- |
| Pill **Connected over USB**; the FM-1 shows the moving test screens (a UI, a gradient, boxes, noise, every 4 s) and its LEDs chase | **Pass.** Android listed the FM-1, the rule chose it, HELLO, SESSION and frames work over USB MIDI |
| Pill **Not found** for more than a few seconds; the FM-1 shows its own waiting screen | Android lists the FM-1 but the rule didn't choose it, or the unit didn't answer HELLO. Open **Diagnostics**, card **MIDI devices (Android)**: send a photo of it (it says why: e.g. "USB 1209:000x is not 1209:0001") |
| **MIDI devices** card says *None* | Android has no MIDI device for it: another cable or adapter; check the FM-1 is in RemoteFM1 (not its updater) |
| Connects, then **Unit lost: searching…** again and again | Frames get lost on the way: send a photo of the **Link** card (ack timeouts, short writes) |

4. Press a few keys and buttons on the FM-1: each one's LED lights while held, and the strip at the bottom of the
   FM-1's screen marks it. In **Diagnostics**, the **Panel** card lists the events (`key 7 C4 down vel 93`, ...).
   Turn a knob and MASTER: they show there too.
5. Back on the main page: **Mode: Firmware core**, **ChoralRoot**, **Sound on**. The FM-1 shows ChoralRoot's screen;
   its keys play ChoralRoot on the tablet's speaker. (The FM-1's own USB audio output is A2.)
6. Open **Diagnostics** and photograph the **Unit** and **Link** cards (they hold the numbers: frames, ack times,
   fps, bytes, "touch", "TRS MIDI in", "Android MIDI").
7. If the FM-1 has a MIDI cable in its TRS MIDI IN (0.3+): play something into it; **TRS MIDI in** counts up.

## 2. Bluetooth

1. Unplug the USB cable. The FM-1 runs its Bluetooth build of RemoteFM1 and shows "FM-1 XXXX" on its waiting screen.
2. In RemoteFM1 tap **Bluetooth…**, then **Allow nearby devices** (asked only here, once). The list shows
   **FM-1 XXXX** in bold within a few seconds; tap **Connect**.

| What you see | What it means |
| --- | --- |
| Pill **Connected over Bluetooth**; the FM-1 shows the test screens, changing whole (slower than USB: a few frames a second at a page change) | **Pass.** Diagnostics › Link says `ble 1, HOLD on`; **budget** is 550 (or 3000 on a 0.2.2+ unit with the fast radio) |
| The list stays empty | The FM-1 isn't advertising (it's connected to another phone or a computer?), or Bluetooth is off |
| Connect fails, or **Not found** | Photograph Diagnostics' **MIDI devices** card |
| Connected over **USB** while on Bluetooth | (can't happen with the cable out; if it does, tell Claude) |

3. Press keys: their LEDs light, as over USB. Try **Firmware core** with sound, as above.
4. Quit RemoteFM1 (swipe it away), open it again: it reconnects to the same FM-1 by itself within a few seconds.
5. Turn the FM-1 off and on: the app finds it again on its own (it retries after 2, 5, 10, 20, 30 s).

## 3. The logs (Claude collects them)

The tablet's USB port is taken by the FM-1 during part 1, so the log is read afterwards (Android keeps it): plug the
tablet back into the Mac, and Claude runs

    adb -s R95Y906QALT logcat -d -s RFMMidi:I RFMApp:I RFMService:W > build/fm1-device.log

`RFMMidi` lines show exactly what Android reported for the FM-1 (`device #N USB name=... manufacturer=... product=...
usb=1209:0001 ...`), whether it was a candidate and why, and the links the session opened. That line settles the
USB name rule (README, "The FM-1 candidate rule").

## 4. What each part proves

| Part | Proves |
| --- | --- |
| 1.3 pattern moves | Android MIDI discovery, the candidate rule on USB, AMidi SysEx out (frames up to ~1.2 KB, cut by AMidi into 1015-byte packets), SysEx in (HELLO_R, FRAME_ACK) |
| 1.4 LEDs and events | the panel's plain MIDI in, LEDS out |
| 1.5 core plays | Core mode: the FM-1 as the panel of a core running on the tablet |
| 2 | Bluetooth LE MIDI: our scan page, `openBluetoothDevice`, the pacing libremotefm1 applies when HELLO_R says Bluetooth |
| 2.4 / 2.5 | the remembered unit and the reconnect rule |
