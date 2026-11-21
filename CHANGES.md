# ByteLab – update 2: lightweight containers, auto-setup, gesture capture

## 1. Lightweight containers (new "Containers (lightweight)" chip in the store)
Full OS images are untouched. A new chip lists containers next to them: Alpine (~4 MB), Ubuntu 24.04 (~30 MB),
Ubuntu 22.04 (~28 MB). Tap one -> INSTALL (downloads the root filesystem once, unpacks it) -> OPEN (terminal inside the
container, starts in about a second). The newest file name is looked up at install time, so links do not go stale.
- **Root inside the container**: switch on the container page (ON by default). Runs through proot (`-0`), so
  `apk add` / `apt install` work immediately. Turn it off to run as a normal user. The phone itself is never rooted.
- Add more containers by adding entries to `app/src/main/assets/containers.json` (no code change).
- Android / Windows containers do not exist: they need a kernel (binder / Hyper-V) that an app cannot use,
  so those two stay full-image VMs.

## 2. Automatic setup / boot
- **Windows**: an `autounattend.xml` is generated and attached to the VM as a virtual USB stick, so Setup runs without
  clicks (language, disk, edition, licence screen, local account "ByteLab" with no password, auto-login). Windows 11
  checks (TPM / Secure Boot / RAM / disk / CPU) are bypassed. A key is pressed for "Press any key to boot from CD".
- **Android-x86 / Linux ISOs**: the first boot-menu entry is chosen for you. **Alpine** also logs in as root.
- Applies to VMs from the store AND VMs you import with "+" (detected from the name / disc file). Only the first 3 boots
  are assisted. Timings are longer on phones with 4 cores or fewer.

## 3. Gestures stay inside the VM
While a VM screen is open, status/navigation bars stay hidden, edge swipes only peek instead of leaving the app, and
the back-gesture edges are excluded. **Hold one finger on any screen edge for 5 seconds** and the phone's gestures and
bars are released for 10 seconds (then locked again). Works on the VNC screen and the X11 screen.

## Files in this update
NEW  container/ContainerEntry.java, container/ContainerManager.java, container/ContainerInfoActivity.java,
     autopilot/AutoPilot.java, autopilot/UnattendedWindows.java, utils/GestureGuard.java, assets/containers.json
EDIT AndroidManifest.xml, res/layout/fragment_home_rom_store.xml, main/romstore/{DataRoms,RomStoreFragment,RomStoreHomeAdpater}.java,
     main/core/MainStartVM.java, qemu/MainVNCActivity.java, x11/X11Activity.java

---

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
