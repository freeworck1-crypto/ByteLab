# ByteLab - update 6

## Fixed: app freezes ("ByteLab isn't responding") when pressing Stop
- Cause: stopping, and also several "is this VM running?" checks, started a whole PRoot Linux shell just to run `pkill` / `ps`. That takes a long time on a phone, some of those checks ran on the main (UI) thread, and the "Shutting down" dialog could not be cancelled, so one slow or stuck shell froze the whole app.
- Fix: new `QemuProcessUtils` reads `/proc` directly and sends SIGTERM, then SIGKILL, to the QEMU processes. It needs no shell, finishes in milliseconds (3 seconds worst case), and the dialog is now always closed in a `finally` block.
- The same helper now answers `VMManager.isVMRunning(...)` and `ProcessManager.isQemuRunning(...)`, so tapping Run / Stop / a VM's options no longer waits on a shell either.

## Faster
- Faster Stop, Run and VM-options taps (see above).
- QEMU software acceleration (TCG) now gets a 256 MB translation cache (`tb-size=256`) unless the VM already sets its own. Less re-translation = smoother guests.
- Bundled font reduced: the 7 MB `googlesansflex.ttf` is no longer used (**delete `app/src/main/res/font/googlesansflex.ttf`** to shrink the APK).
- Tip: use the **release** APK. The debug APK is not optimised and is noticeably slower.

## One Credits page
- New **About → Credits** page (`CreditsActivity`) is now the only place that lists third-party software, authors and licences, with "Built by NIAZNIX" on top and a link to the source repository.
- Removed the open-source wording from the About screen and the QEMU picker note (all languages).
- Source-file copyright headers and the `LICENSE` file are intentionally untouched (see note in the hand-over message).

## New look: "Ink & Coral"
- New colour palette (violet / coral / lime accents on cool ink-grey surfaces) for light and dark mode, new corner sizes, and the Inter typeface.
- 170+ icons redrawn as a new line-icon set (same file names, so no code changes). Selected tabs use a soft-filled version.
- Restyled the Android system picker page to match.
- The launcher icon / NIAZNIX logo are unchanged.

## Not done in this update (needs a device or a larger project)
- No new Android guest engine. ByteLab still runs Android through QEMU; on phones without KVM access that is software emulation, so it is slower than a native hypervisor.
- Nothing here was compiled or run on a device (no Android SDK / network in the build sandbox). Build with GitHub Actions and test the Stop button first.
- Windows / Linux hosts were left as they are.
