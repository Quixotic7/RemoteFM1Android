# The FM-1 device test, third round

**What the first round found (2026-11-01, build A1 567cbac).** The FM-1 was found and ChoralRoot played, but Android
sent the sound to the FM-1's USB audio by itself, and the app's low-latency (MMAP) stream to it through the Qualcomm
USB offload **restarted the tablet**. After the reboot the keys did nothing (a failed session write left the link
dead; fixed) and the FM-1 once left the USB bus with the cable in. Since then the sound never goes to a USB output
unless picked.

**What the second round found (2026-11-01, build 07809bd).** The FM-1's screen and buttons worked over USB, and the
sound on the tablet's speaker worked. Then:

- **FM-1 (USB)** picked by hand opened the careful way (shared, performance None, MMAP 0), but the tablet's audio HAL
  couldn't play it (about 400 `graph open failed` / `pal_stream_start failed -22` lines in the log): no sound. On the
  second try **the FM-1 dropped off USB** 3 s after the start (it came back 3 s later), as in round one right after a
  USB audio start.
- Picking **Speaker** then **froze the app**: closing the USB stream blocked the app's main thread inside Android's
  audio server until Android killed the app ("not responding"). After the relaunch the audio server was still busy
  (opening the speaker took 8 s), and switching the sound off and on froze it again.
- No Bluetooth was tried (the log has no Bluetooth connection: "last Bluetooth unit null" at every launch).

Fixed: the app never waits for Android's audio any more (all of it runs on its own audio thread; a call that hangs is
given up after 2 s and a new stream is opened; "The tablet's audio system is busy" shows while Android doesn't
answer), and the FM-1's USB audio is no longer offered (only with **Settings › Sound through USB devices
(experimental)**, off by default; **don't turn it on for this round**).

This round: Bluetooth, the sound switched on and off (and the output switched) as fast as you like on the speaker,
and a 5-minute play over USB MIDI with the sound on the tablet. It sends the FM-1 only the RemoteFM1 panel protocol
(HELLO, SESSION, TILES, LEDS, TOUCH); nothing here installs or updates firmware.

**Before:** the FM-1 runs RemoteFM1 (0.2 or newer; 0.3.1 for velocity, pressure and TRS MIDI in). The tablet has the
new debug build (Claude installs it; `app/build/outputs/apk/debug/app-debug.apk`). Note the time you start, and the
time of anything odd (the FM-1's screen changes, its keys stop working, a disconnect, the app freezing): the logs are
read by time.

## 1. USB, the sound on the tablet (5 minutes)

1. Connect the FM-1 to the tablet with the USB-C cable (the tablet is the host). If Android asks *"Open RemoteFM1 to
   handle FM-1?"*, tap **OK**.
2. In RemoteFM1: **Sound source: FM-1 · its keys and screen**, **Mode: Test pattern**.

| What you see | What it means |
| --- | --- |
| Pill **Connected over USB**; the FM-1 shows the moving test screens and its LEDs chase | **Pass** |
| Pill **Not found**; the FM-1 shows its waiting screen | photograph Diagnostics' **MIDI devices** card |
| **Unit lost: searching…** again and again | photograph the **Link** card |

3. **Mode: Firmware core**, **ChoralRoot**, **Sound on**. **Audio output** lists Automatic and Speaker (and
   headphones or Bluetooth if connected) but **not the FM-1**. The line under it says "Opening Speaker…", then
   "Playing on Speaker". The sound comes from the tablet.
4. Play for **5 minutes**, pressing keys now and then. Watch for: keys that stop working, the FM-1's screen freezing,
   the FM-1 restarting (its boot or waiting screen), the pill changing, the sound stopping. Note the time of each.
5. Open **Diagnostics** and photograph the **Unit**, **Link** and **Core** cards (Core's `audio thread` row: its
   state, `stuck closes 0`, `streams 1`).

## 2. The sound on and off, fast (the speaker)

Still on USB, Firmware core, ChoralRoot:

1. Tap **Sound on / Sound off** quickly, 20 times or more, ending on **Sound on**. The app keeps responding (no
   "RemoteFM1 isn't responding"); within a second or two after the last tap the line says "Playing on Speaker" and
   the sound is back.
2. Tap **Automatic**, **Speaker**, **Automatic**, … quickly while it plays (with headphones or Bluetooth connected,
   switch between them too). Same: no freeze, the sound ends on the last pick.
3. If the line ever says **"The tablet's audio system is busy"** or **"Sound failed: …"**, note the time and what it
   says; wait 20 s and see whether it comes back by itself; if not, tap Sound off and on.
4. Switch to another core (Felucca), then back to ChoralRoot, with the sound on.

## 3. Bluetooth

Unplug the USB cable; the FM-1 runs its Bluetooth build and shows "FM-1 XXXX" on its waiting screen. In RemoteFM1
tap **Bluetooth…**, **Allow nearby devices**, then **Connect** on **FM-1 XXXX**.

| What you see | What it means |
| --- | --- |
| Pill **Connected over Bluetooth**; the test screens change whole, slower than USB | **Pass.** Diagnostics › Link says `ble 1, HOLD on` |
| The list stays empty | the FM-1 isn't advertising (connected elsewhere?), or Bluetooth is off |
| Connect fails, or **Not found** | photograph Diagnostics' **MIDI devices** card |

Then: press keys (LEDs light); **Firmware core** with **Sound on** (the tablet's speaker); play for a minute; quit
RemoteFM1 (swipe it away) and open it again: it reconnects by itself within a few seconds; turn the FM-1 off and on:
found again on its own (it retries after 2, 5, 10, 20, 30 s).

**No USB audio step this round.** The FM-1's USB audio failed on this tablet twice and made the FM-1 drop off USB;
leave Settings › Sound through USB devices (experimental) off.

**An FM-1 reset or a USB reset?** If the FM-1 drops off (the pill goes to Not found, then comes back): look at the
FM-1 while it happens: an FM-1 that restarts shows its boot screen, then RemoteFM1's waiting screen; if its screen
stays as it was, only the USB connection reset. The log tells too (`RFMLink: unit found: … its session 1` = it kept
running; `its session 0` = it started afresh).

## 4. The logs (Claude collects them)

Plug the tablet into the Mac afterwards; Claude runs (nothing is changed on the tablet)

    adb -s R95Y906QALT logcat -d -b all > build/round3/all.log
    adb -s R95Y906QALT logcat -d -s RFMMidi:I RFMLink:I RFMApp:I RFMAudio:I RFMService:W > build/round3/ours.log

What the app's lines show:

- `RFMMidi`: each device Android lists, why it is a candidate, every port pair opened and closed, why a link ended.
- `RFMLink`: the session's states and why, `unit found: … its session 0/1`, and every 5 s: rx/tx bytes, PANEL_R and
  events, frames, acks, sessions, units lost, link drops, silent reopens.
- `RFMApp`: what you tapped (Sound source, mode, sound on/off, audio output) and the audio thread's states
  (`audio: Opening(...)`, `audio: Playing(...)`, `audio: closing the stream on Speaker (sound off)`, `the close on …
  is stuck after 2000 ms: abandoned`, `playing on Speaker (device 2, AAudio exclusive, …) after N ms`).
- `RFMAudio`: each stream as Oboe opened it (`stream N open: asked device, got device, sharing, performance, MMAP`)
  and closed.

## 5. What each part proves

| Part | Proves |
| --- | --- |
| 1 | a long USB MIDI session with the sound on the tablet: no dead keys, no drops (or, if there are, which kind); the FM-1's USB audio not offered |
| 2 | the audio thread: no freeze from sound on/off or output switches, the latest choice wins, the sound comes back |
| 3 | Bluetooth LE MIDI: the scan page, `openBluetoothDevice`, the pacing, the remembered unit, the reconnect rule |
