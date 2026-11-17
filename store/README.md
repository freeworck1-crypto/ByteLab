# ByteLab store list

`roms.json` is the list shown in the app's ROM store, above the built-in systems.
Edit it on GitHub, commit, and the app shows the change next time the store opens (GitHub can take a few minutes).
The repo must be public.

## Add a system
Copy one block inside `[ ... ]` (comma between blocks) and change it:

| field | meaning |
|---|---|
| `id`, `vecid` | any unique text, no spaces |
| `rom_name` | name shown in the store |
| `rom_icon` | link to the icon: `.../store/icons/android.png`, `windows.png`, `ios.png` or `linux.png` |
| `rom_arch` | `X86_64`, `i386`, `ARM64` or `PPC` |
| `rom_size`, `file_size` | text like `726 MB` |
| `rom_url` | DIRECT link to the file (your cloud storage works) |
| `rom_path`, `finalromfilename` | exact file name, e.g. `win10.iso` (Import checks this name) |
| `rom_extra` | QEMU options, using the file name where the disk goes: `-cdrom win10.iso -boot d` or `-hda win10.qcow2` |
| `rom_avail` | `true` to show as Available |
| `desc`, `creator`, `verified` | description, author, `true`/`false` |

Validate at https://jsonlint.com before committing; one missing comma hides the whole list.
