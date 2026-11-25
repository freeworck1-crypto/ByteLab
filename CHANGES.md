# ByteLab - update 3: build cleanup, NIAZNIX branding, permissions

NOT built or tested by me. If the release APK crashes at start-up, open app/build.gradle and set
minifyEnabled and shrinkResources back to false (release block) to check whether R8 is the cause.

## Build and size
- Release builds now use R8 + resource shrinking (app code is kept whole, only unused library code and resources go).
- One APK per CPU type, named bytelab-<abi>-<code>.apk (no universal APK). Install the arm64-v8a one on your phone.
- Fixed .gitignore (it had merge-conflict markers). Delete the stale .kts Gradle files (see DELETE-THESE.txt).
- The 7 MB googlesansflex.ttf was a corrupted file. The theme now uses the small valid variable font.

## Branding
- New NIAZNIX logo (transparent), new ByteLab launcher icon (adaptive + themed + legacy), About screen and splash credit.
- "FREE PALISTINE" banner removed from About. Old Vectras social/developer cards hidden, contact button hidden.
- Fixed hard-coded data path (/data/data/com.vectras.vm -> com.niaznix.bytelab). Storage folder renamed
  Documents/VectrasVM -> Documents/ByteLab (existing VMs in the old folder will not show; move them if needed).
- Patreon / Telegram / repo links now point to https://github.com/freeworck1-crypto/ByteLab (change them in
  AppConfig.java if that is not your repo).
- Credit to Vectras VM (GPL-2.0), QEMU and other projects is kept in About and Open source licenses. Do not remove it: the license requires it.

## Permissions
- New screen: About > App permissions. Nothing is asked automatically; each permission is requested only when tapped.
- Added: camera, location, Bluetooth, nearby Wi-Fi devices, Wi-Fi state, network state.
- Screen also covers: display over other apps, install unknown apps, unrestricted battery, all files access,
  microphone, notifications, and a shortcut to Developer options for ADB / Wireless debugging.
- Accessibility service: NOT added (see reply).

## Still depends on the original Vectras server
Setup files and the OS store list are still downloaded from github.com/xoureldeen/vectras-vm-bucket
(AppConfig.vectrasWebsiteRaw). If that repo changes or disappears, setup breaks. Next step: host your own copy.

## Files in this update (same paths as your project)
EDIT  .gitignore, app/build.gradle, app/proguard-rules.pro, AndroidManifest.xml, res/values/theme.xml,
      res/values/strings.xml, res/layout/content_about.xml, res/layout/activity_splash.xml,
      java/com/vectras/vm/{AppConfig,AboutActivity}.java, java/com/vectras/vm/utils/{FileUtils,ImageUtils}.java,
      java/com/vectras/vm/main/MainActivity.java
NEW   java/com/niaznix/bytelab/PermissionsActivity.java, res/drawable-nodpi/niaznix_logo.png
REPLACED  res/mipmap-*/ic_launcher*.png (all densities)

---

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
