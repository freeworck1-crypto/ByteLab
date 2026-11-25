# ByteLab hybrid engine update

This update is deliberately incremental. It preserves the existing VM/ROM store and QEMU full-system path, while making Linux rootfs workloads route through PRoot and never through QEMU.

## Architecture after this update

- Linux containers: native-architecture rootfs -> PRoot -> Termux terminal service. QEMU is bypassed.
- Android guest: existing QEMU full-system path remains. Android needs a guest kernel, virtual devices, boot process and guest display; PRoot is not a substitute for a complete Android phone.
- Other full-system guests: existing QEMU path remains for compatibility.
- Display: the uploaded project already has `x11/AndroidMode.java` and `x11/AddIn.java`. Android guests are detected, fullscreen is enabled, touchscreen input is selected, and the toolbar is hidden by default. This update preserves that code rather than replacing a working display path.
- Linux snapshots: stopped rootfs trees can be packed as tar.gz with SHA-256 sidecars. Restore verifies the digest, extracts into staging, then swaps the rootfs.
- Preconfigured Linux images: add `snapshot_url` and `snapshot_sha256` to an item in `app/src/main/assets/containers.json`. The app downloads the package, requires a matching SHA-256, restores it, and skips the normal package setup path.

Example optional catalogue fields:

```json
{
  "id": "ubuntu",
  "snapshot_url": "https://YOUR_HOST/ubuntu-arm64.tar.gz",
  "snapshot_sha256": "64_HEX_CHARACTER_SHA256"
}
```

Use the actual existing Ubuntu catalogue object and preserve its other fields; do not paste this example as a duplicate object. Publish the archive and SHA-256 from a trusted build pipeline over HTTPS. Never accept an unpinned snapshot. Each user receives a separate writable rootfs; do not share one mutable user data directory between installs.

## Android guest snapshots and root

The Linux snapshot manager is NOT an Android VM RAM snapshot. Android cold-boot avoidance requires a prepared base disk plus a per-user writable overlay, or a compatible hypervisor snapshot that captures guest RAM and virtual-device state. The current project does not contain a verified generic Android RAM snapshot/restore backend, so this patch does not pretend to implement one.

The selected root policy is guest-only. It does not request Android host root and does not root the physical phone. Whether Android guest `su` works depends on the selected Android image (for example, an image built with a supported root implementation). The app's existing Android guest images and boot pipeline are preserved; do not inject arbitrary `su` binaries into an unknown image.

## Namespace acceleration limitation

A normal, unrooted Android application cannot assume permission to create user/mount/PID namespaces, mount a rootfs, or configure cgroups. The router therefore uses PRoot when it is installed and the rootfs architecture matches the phone. It does not claim native namespace performance. A future native namespace backend must ship a reviewed native launcher and pass runtime capability probes; simply seeing ARM64 is not sufficient.

## Build setup

The source project uses Groovy Gradle files and has five modules. Remove the three conflicting Kotlin-DSL files listed in `REMOVE-CONFLICTING-GRADLE-FILES.txt`; keep the Groovy files. The GitHub Actions workflow now installs NDK `27.3.13750724`, matching `app/build.gradle`, and Android platform 37.

## Snapshot safety

- Stop the Linux session before creating/restoring a snapshot. The UI displays this requirement.
- Snapshot archives preserve regular files, directories, symlinks and permission bits. Device nodes, sockets and FIFOs are omitted; `/dev`, `/proc` and `/sys` are supplied at launch by bind mounts.
- A snapshot is a rootfs snapshot, not process memory. It will not resume a running shell at its previous instruction.
- Keep a separate writable state for each user. Use a versioned base snapshot plus a per-user overlay in a later storage-engine upgrade.

## Security action for the repository owner

The uploaded source archive contained a release keystore and a base64 copy of signing material. Do not include either in source ZIPs or public Git repositories. If those signing credentials have ever been pushed to a public repository, treat them as compromised and follow the signing-key rotation/recovery path appropriate to your distribution channel. Use GitHub Actions Secrets for signing inputs.
