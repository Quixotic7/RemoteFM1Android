# Test fixtures (A7, copied from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/Fixtures/RemoteFM1)

- `remote-0.2.3.fwsc` (611,496 bytes, SHA-256 211f06f63e8ca09de2812c017db9bb229713ce8e5a102eba887031551b2b403c):
  RemoteFM1 0.2.3's default package (MPL-2.0, https://github.com/Quixotic7/RemoteFM1 tag v0.2.3), downloaded unchanged
  from https://remotefm1.com/installer/firmware/remote-0.2.3.fwsc on 2026-10-29; it installs as `FM-1_52000203`.
- `releases.json`: https://remotefm1.com/installer/releases.json as it was that day (0.2.3 the newest).

FirmwareInstallTest and FM1KitPortTest check both and install the package on the ported SimulatedFM1.
