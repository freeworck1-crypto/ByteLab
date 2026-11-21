# ByteLab ROM Store catalog

`roms.json` feeds the in-app ROM Store. The app also reads the bundled
`app/src/main/assets/roms.json` catalog so built-in entries remain visible offline.

## Catalog fields

| field | meaning |
|---|---|
| `id`, `vecid` | unique entry identifiers |
| `rom_name` | name shown in the store |
| `rom_icon` | app asset path such as `file:///android_asset/rom-icons/android-9.png` |
| `rom_category` | `android`, `linux`, `windows`, or `other` |
| `rom_version` | version filter label, for example `9.0` or `24.04` |
| `rom_arch` | guest architecture: `X86_64`, `I386`, `ARM64`, or `PPC` |
| `file_size`, `rom_size` | download size and display label |
| `rom_url` | direct file URL when `rom_direct` is true; official product pages are not direct files |
| `rom_path`, `finalromfilename` | exact downloaded filename |
| `rom_format` | use `disk` for a prebuilt disk image; ISO is the default |
| `rom_extra` | legacy QEMU options; the direct ISO runner attaches the ISO as a CD-ROM |
| `rom_avail` | `true` only when a usable download source is configured |
| `rom_direct` | `true` for direct downloads; `false` for official pages that require a browser |
| `rom_app` | `true` for an APK that should be installed, not booted as a VM |
| `root_supported` | `true` only when the guest image itself is verified to include root access |

The Android ARM rows copied from the supplied picker reference are marked
unavailable because this repository does not contain their image URLs. Do not
mark them available until a real, verified download source is configured.

## Direct run behavior

For direct ISO entries, ByteLab downloads the ISO into its app-managed download
folder, creates a sparse 16 GB QCOW2 virtual disk in the VM folder, attaches the
ISO as a virtual CD-ROM, registers the VM, and starts it. The user still has to
complete the operating-system installer inside the guest when the ISO is an
installer. A prebuilt disk image (`rom_format: "disk"`) is attached directly
without an import/copy step.

Windows download pages that generate temporary links remain browser-based until
a stable direct ISO URL is configured.

## Root access

Root is a guest-image capability, not a QEMU switch. A generic VM toggle cannot
install `su` or modify an arbitrary guest kernel. Set `root_supported` only for
a guest image that is actually rooted and tested.
