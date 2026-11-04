RemoteFM1 for Android 0.2.1

Skins, as in the iOS app. Thanks to Brian White for the idea!

- Your own background: Controls › Background › Background image picks a photo (Android's photo picker; the app needs
  no storage permission). It sits behind the faceplate, dimmed and tinted with the faceplate's colour; Dim sets how
  much. Remove background image takes it away.
- Firmware skins: a .rfm1 firmware can come with its own skin (an image, a landscape image, colours and dim). It shows
  while that firmware plays. Turn off Controls › Background › Firmware skins to keep your own background and theme for
  every firmware. Firmware authors: iOS's scripts/rfm1_skin.py adds a skin to a .rfm1 (RFM1-FORMAT.md section 8).
- The faceplate stays as smooth as in 0.2.0: the image is decoded once in the background.

Everything from 0.2.0 is here too: smoother heavy .rfm1 firmwares, Fantasy CFW, audio input, and the iOS app's
firmware list, editor, cartridge and battery features.

Install
1. Download RemoteFM1-0.2.1.apk below on your phone or tablet.
2. Open it and allow your browser or file manager to install apps when Android asks.
3. Samsung: turn off Auto Blocker while you install (Settings › Security and privacy › Auto Blocker).
4. It installs over 0.1.0 or 0.2.0 and keeps your states, firmwares and settings.

You need Android 10 or newer on an arm64 phone or tablet. For the panel, an FM-1 with the RemoteFM1 firmware.

What I tested: on the Android emulator, a firmware with its own skin in portrait and landscape, the stored background
image and the dim, and installing over 0.2.0. Not tried on a real phone or tablet yet: picking your own photo there
(including HEIC photos from an iPhone) and very large photos.

Known issues
- On some Samsung/Qualcomm devices sound through the FM-1's own USB audio doesn't play and can make the FM-1 drop off
  USB. It stays off unless you turn on Settings › Sound through USB devices (experimental).

Please report anything that doesn't install, play, save or connect as expected, with your device's model and Android
version: https://github.com/Quixotic7/RemoteFM1Android/issues

Check the download: SHA-256 of RemoteFM1-0.2.1.apk
59ff8d2519cfe22a92b54f15ccbedb0b978e71420cbf78b745931d6023c42dfc
Signing certificate SHA-256 (apksigner verify --print-certs), the same as before:
6f:d9:95:6c:1c:e8:76:61:9e:71:0e:bb:33:ef:44:a5:d7:2c:f6:96:8c:a2:9b:27:86:13:93:8b:6a:17:99:cb
