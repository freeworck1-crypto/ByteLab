# NIAZNIX ByteLab — Engineering Notes

## Product direction
ByteLab is an open-source virtual-machine manager by NIAZNIX. The target user experience is: download an OS once, prepare its virtual disk once, then launch the saved VM with one Run action. The guest should expose a minimal app set (Files and a browser initially); guest APK installation and guest-root capability depend on the selected guest image.

## Current codebase baseline
- The existing application is based on a QEMU-oriented Android VM manager with VNC/X11 display paths, Termux/PRoot setup components, and ROM/OS catalogue code.
- The existing VM engine remains the Windows/Linux compatibility path for now. It has not been replaced by a new hypervisor in this patch.
- The experimental `com.niaznix.bytelab.MainActivity` is not the app's launcher; the manifest still launches the existing setup flow and main VM UI.
- The app namespace remains `com.vectras.vm` because the existing 700+ source files and generated resource references depend on it. The install/application ID remains `com.niaznix.bytelab`. A full package rename is a separate migration, not a safe cosmetic edit.

## Performance work to do next
1. Measure cold-start, warm-start, guest boot, and resume times independently on physical ARM64 devices.
2. Cache completed VM preparation and never re-extract bootstrap/runtime files on every Run.
3. Keep guest disk images and optional firmware outside the base APK where licensing, integrity verification, and first-run UX permit.
4. Use ABI-specific native packages and remove only binaries proven unused by dependency and runtime tracing.
5. Keep Android guest execution as a separate backend from QEMU full-system emulation. Validate AVF/crosvm availability on the target device; ordinary apps cannot assume privileged AVF access.
6. Keep QEMU for guest architectures and OS types that require full-system emulation.

## Important limitations
This patch does not claim to add a working Android guest hypervisor, one-click Android boot, snapshot restore, or guest root toggle. Those require a compatible guest image, native runtime, display/input integration, lifecycle management, and device testing. The current code must be tested on the target phone before those features can be marked complete.

## Branding and upstream compliance
- Product display name and release APK naming use ByteLab.
- NIAZNIX branding assets are included in the Android resources.
- Existing upstream license and required notices must remain in distributed source/builds.
- The current ROM catalogue/bootstrap JSON endpoints are still upstream-hosted because no NIAZNIX catalogue endpoint was supplied. Replace these only after the equivalent NIAZNIX catalogue and package files are deployed; removing them now would break existing OS Store/setup flows.
