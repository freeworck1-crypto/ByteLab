# ByteLab – updated files (replace the same paths in your project)

## Fixed
1. **"Problem has been detected" crash** (`-boot order=c,d: Expected '=' after parameter 'd'`).
   Old builds saved `-boot order=c,d` inside each VM's config, so every existing VM kept failing even
   after the generator was fixed. `QemuArgFixer` now repairs it at launch (`order=cd`) for old AND new VMs,
   including VMs created with the "+" import button. (`StartVM.java`, `utils/QemuArgFixer.java`)
2. **UI lag / freezes**: the log viewer ran blocking `logcat` reads on the UI thread (now background threads);
   the store adapter was wiping its own master list on every filter (fixed); VM thumbnails were re-decoded from
   disk on every scroll (now cached by file timestamp and down-sized).
3. **Store list**: entries with no download source (the red "Not uploaded yet" rows) are no longer shown; every
   listed OS now shows "Available"; Available text colour no longer stays red on recycled rows.

## New / changed
- **Fast downloads** (`utils/FastDownloader.java`): 4–8 parallel connections with resume, pause/resume button,
  speed readout, redirect/mirror handling and a free-space check. Nothing is pre-downloaded; the file is only
  fetched when you tap Get, and the .part file is renamed in place (no duplicate copy).
- **Get → Run**: after the download finishes the VM is created and started automatically.
- **Import button removed** from the OS page. Importing your own file stays on the "+" button of the home screen.
- **Root (guest only)**: long-press a VM → "Root (guest only)". Forwards the guest's ADB to 127.0.0.1:5555.
  QEMU user-mode networking is NAT-isolated, so only the VM is reachable; the real phone is never rooted.

## Catalogue (`store/roms.json` and `app/src/main/assets/roms.json` – identical)
12 entries with real sources. The 10 ARM entries that had no URL were moved to `store/roms-pending-sources.json`.
To bring one back: put a direct image URL in `rom_url`, set `rom_avail` to true, add it to roms.json.
