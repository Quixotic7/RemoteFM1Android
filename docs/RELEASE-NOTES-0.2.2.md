RemoteFM1 for Android 0.2.2

A small update to keep the app working with what's coming:

- Ready for RemoteFM1 0.3.7 on the FM-1. From 0.3.7 the RemoteFM1 firmware reports a new identity (FM-1_72…
  instead of FM-1_52…, because another firmware also uses FM-1_52). The app recognises both, so an FM-1 on 0.3.6 or
  older and one on 0.3.7 or newer both show as RemoteFM1, and the app keeps offering updates. Older app versions will
  call an FM-1 on 0.3.7 "another firmware".
- Get firmware: older versions show their real release dates again, and a firmware's card picture is fetched again
  when its author replaces it. (The firmware releases on GitHub moved to a rolling "current" release.)

Everything from 0.2.1 is here too: skins, smoother heavy .rfm1 firmwares, Fantasy CFW, audio input, and the iOS
app's firmware list, editor, cartridge and battery features.

Install
1. Download RemoteFM1-0.2.2.apk below on your phone or tablet.
2. Open it and allow your browser or file manager to install apps when Android asks.
3. Samsung: turn off Auto Blocker while you install (Settings › Security and privacy › Auto Blocker).
4. It installs over any earlier version and keeps your states, firmwares and settings.

You need Android 10 or newer on an arm64 phone or tablet. For the panel, an FM-1 with the RemoteFM1 firmware.

What I tested: the unit tests for both identities and the release dates, and installing over 0.2.1 on the Android
emulator. Not tried with a real FM-1 on 0.3.7 yet (it isn't released).

Known issues
- On some Samsung/Qualcomm devices sound through the FM-1's own USB audio doesn't play and can make the FM-1 drop off
  USB. It stays off unless you turn on Settings › Sound through USB devices (experimental).

Please report anything that doesn't install, play, save or connect as expected, with your device's model and Android
version: https://github.com/Quixotic7/RemoteFM1Android/issues

Check the download: SHA-256 of RemoteFM1-0.2.2.apk
4b9aaf570b565d3687ab9a6877f28e08400910681aed2f98c1133e356a490044
Signing certificate SHA-256 (apksigner verify --print-certs), the same as before:
6f:d9:95:6c:1c:e8:76:61:9e:71:0e:bb:33:ef:44:a5:d7:2c:f6:96:8c:a2:9b:27:86:13:93:8b:6a:17:99:cb
