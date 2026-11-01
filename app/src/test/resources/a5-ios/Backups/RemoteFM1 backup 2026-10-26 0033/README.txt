RemoteFM1 backup

States: every state in the library, one .rfm1state per state, by firmware.
Current: each firmware's memory as it was at the backup ("<Firmware> current").
Safety copies: the newest 3 safety copies of each firmware.
manifest.json lists them.

To restore, in the app: Settings › Restore from backup…, then pick this folder (a zip: tap it in Files to unzip it first). The states in States and Current are added to the library; one already there is skipped, nothing is overwritten. Any single .rfm1state can also be opened by itself.
