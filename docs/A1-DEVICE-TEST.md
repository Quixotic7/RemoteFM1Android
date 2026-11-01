# The FM-1 device test, second round

**What the first round found (2026-11-01, build A1 567cbac).** The FM-1 was found and ChoralRoot played, but:

- Android sent the sound to the FM-1's USB audio by itself (USB audio becomes Android's default output when it is
  plugged in), and the app's low-latency (MMAP) stream to it through the Qualcomm USB offload **restarted the
  tablet** (kernel: SMMU fault in the audio DSP's memory, then a fatal NoC error). After the reboot Android's normal
  path to the FM-1 failed instead (its audio HAL retried about 450 times a second for 17 s). Now the sound never
  goes to a USB output unless you pick it (README, "Audio output" and "Known issues").
- "Couldn't switch to the tablet speaker; then it disconnected": the switch that was tried is **Sound source**,
  which chooses the panel (This tablet = on-screen keys, the link stopped), not where the sound goes. The log shows
  the link stopped at 01:24:04 and started again at 01:24:14. The chips now say so; the speaker is under **Audio
  output**.
- After the reboot the keys and knobs did nothing while the screen showed ChoralRoot. Two things could do that: a
  session write that failed (A1 then left the link dead while the device stayed, so nothing reached the unit again;
  fixed: the link is dropped and its ports opened again), or the unit's side going quiet (now reopened after 6 s).
  The FM-1 also left the USB bus once at 01:24:14 with the cable in (USB disconnect, then the Type-C partner gone
  4 s later, back after 3 s, enumerated at 01:24:27): something on the FM-1's side reset. Nothing the app sends
  could have caused that one (its ports were reopening at that moment; no byte had gone out yet).

This round checks the new audio rule and gathers the logs that tell those causes apart. It sends the FM-1 only the
RemoteFM1 panel protocol (HELLO, SESSION, TILES, LEDS, TOUCH); nothing here installs or updates firmware.

**Before:** the FM-1 runs RemoteFM1 (0.2 or newer; 0.3.1 for velocity, pressure and TRS MIDI in). The tablet has the
new debug build (Claude installs it; `app/build/outputs/apk/debug/app-debug.apk`). Note the time you start, and the
time of anything odd (the FM-1's screen changes, its keys stop working, a disconnect): the logs are read by time.

## 1. USB, the sound on the tablet's speaker

1. Connect the FM-1 to the tablet with the USB-C cable (the tablet is the host). If Android asks *"Open RemoteFM1 to
   handle FM-1?"*, tap **OK**.
2. In RemoteFM1: **Sound source: FM-1 · its keys and screen**, **Mode: Test pattern**.

| What you see | What it means |
| --- | --- |
| Pill **Connected over USB**; the FM-1 shows the moving test screens and its LEDs chase | **Pass** |
| Pill **Not found**; the FM-1 shows its waiting screen | photograph Diagnostics' **MIDI devices** card |
| **Unit lost: searching…** again and again | photograph the **Link** card |

3. Press keys and buttons, turn knobs and MASTER: their LEDs light, and Diagnostics › **Panel** lists the events.
4. **Mode: Firmware core**, **ChoralRoot**, **Audio output: Automatic** (or **Speaker**), **Sound on**. Play.
   - The sound comes from **the tablet's speaker**, not the FM-1. The line under the picker says "Playing on
     Speaker"; Diagnostics › **Core** › output says `Speaker (pick: Automatic), device 2` or similar, `MMAP 0` or 1.
   - The amber line under the picker ("Some Samsung tablets restart…") shows because the FM-1's USB audio is listed.
5. Play for **5 minutes**, pressing keys now and then. Watch for: keys that stop working, the FM-1's screen freezing,
   the FM-1 restarting (its boot or waiting screen), the pill changing. Note the time of each.
6. Open **Diagnostics** and photograph the **Unit** and **Link** cards. The Link card now has: `events … last N s
   ago`, `PANEL_R … heard, units lost …, session restarts …`, `Android MIDI … rx … B`, `port drops …`.
7. **Unplug nothing.** Switch **Sound off**, then on again: the speaker again, never the FM-1.
8. If the FM-1 has a MIDI cable in its TRS MIDI IN (0.3+): play something into it; **TRS MIDI in** counts up.

**An FM-1 reset or a USB reset?** If the FM-1 drops off (the pill goes to Not found, then comes back):

- Look at the FM-1 while it happens: an FM-1 that restarts shows its boot screen, then RemoteFM1's waiting screen.
  If its screen stays as it was (ChoralRoot's), only the USB connection reset.
- The log tells too (Claude reads it): `RFMLink: unit found: … its session 1` after the drop = the FM-1 kept running
  (it still had its session); `its session 0` = it started afresh (or its keepalive ran out). The tablet's own log
  says whether the USB data went first (`usb-service.qti: Disconnect received`) or the cable's Type-C connection
  (`typec/port0/port0-partner` removed), and when.

## 2. Bluetooth

As before: unplug the USB cable; the FM-1 runs its Bluetooth build and shows "FM-1 XXXX" on its waiting screen. In
RemoteFM1 tap **Bluetooth…**, **Allow nearby devices**, then **Connect** on **FM-1 XXXX**.

| What you see | What it means |
| --- | --- |
| Pill **Connected over Bluetooth**; the test screens change whole, slower than USB | **Pass.** Diagnostics › Link says `ble 1, HOLD on` |
| The list stays empty | the FM-1 isn't advertising (connected elsewhere?), or Bluetooth is off |
| Connect fails, or **Not found** | photograph Diagnostics' **MIDI devices** card |

Then: press keys (LEDs light); **Firmware core** with sound (the tablet's speaker); quit RemoteFM1 (swipe it away)
and open it again: it reconnects by itself within a few seconds; turn the FM-1 off and on: found again on its own
(it retries after 2, 5, 10, 20, 30 s).

## 3. Optional, last: the sound to the FM-1's USB audio

**This may restart the tablet** (it did in round one, with the old build's low-latency stream). Do it only at the
end, with nothing unsaved on the tablet, and only if you want to know whether the careful path works.

1. USB again, **Firmware core**, **Sound on**.
2. **Audio output: FM-1 (USB)** (the row with the amber warning).
3. Play for one minute. The sound should come from the FM-1. Diagnostics › Core › output says
   `FM-1 (USB) (pick: FM-1 (USB)), device N, MMAP 0, USB careful mode`.
4. Pick **Speaker** again. If the tablet restarted, just tell Claude the time; the logs survive the reboot.

The USB pick is not saved: after quitting the app it is Automatic (the speaker) again.

## 4. The logs (Claude collects them)

The tablet's USB port is taken by the FM-1, so the logs are read afterwards: plug the tablet into the Mac, and Claude
runs (nothing is changed on the tablet)

    adb -s R95Y906QALT logcat -d -b all > build/fm1-round2-all.log
    adb -s R95Y906QALT logcat -d -s RFMMidi:I RFMLink:I RFMApp:I RFMAudio:I RFMService:W > build/fm1-round2.log

What the app's lines show:

- `RFMMidi`: each device Android lists (`device #N USB name=... usb=1209:0001`), why it is a candidate, every port
  pair opened and closed (`ports open: … input port 0 (we send to it), output port 0 (we read it)`), the first bytes
  after the ports open and after each session start, and why a link ended (`link gone: <name>: <why>`: the device
  was removed, a write failed, a read failed, nothing from the unit for N s, the app stopped the link).
- `RFMLink`: the session's states and why, `unit found: … its session 0/1`, and every 5 s: rx/tx bytes, PANEL_R and
  events (with the change since the last line), frames, acks, sessions, units lost, link drops, silent reopens.
- `RFMApp`: what you tapped (Sound source, mode, sound on/off, audio output) and where each audio stream really went
  (`audio: asked Speaker (device 2); playing on device 2, AAudio exclusive, performance 12, MMAP 1`).
- `RFMAudio`: the stream as Oboe opened it (asked device, got device, sharing, performance mode, MMAP).

The key question this round answers: **with the sound on the speaker, does the FM-1 still drop off USB?** If it
doesn't, the drops were the USB audio's; if it does, the `RFMLink`/`RFMMidi` lines and the times tell whether the
FM-1 restarted, the USB reset, or the link stopped hearing it.

## 5. What each part proves

| Part | Proves |
| --- | --- |
| 1.2–1.3 | discovery, the candidate rule, SysEx both ways over USB MIDI, the panel's events and LEDs |
| 1.4, 1.7 | the audio rule: the speaker by default with the FM-1 plugged in, never the FM-1 by itself |
| 1.5–1.6 | a long USB session: no dead keys, no drops (or, if there are, which kind) |
| 2 | Bluetooth LE MIDI: the scan page, `openBluetoothDevice`, the pacing, the remembered unit, the reconnect rule |
| 3 | (optional) the careful USB audio path on this tablet |
