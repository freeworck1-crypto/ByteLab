# ByteLab

**ByteLab** is a free app that runs virtual machines on your Android phone or tablet — Android, Linux and Windows guests — with a simple Get → Run flow.

Built by **NIAZNIX**.

## What you can do

- Download a ready-made system from the built-in store and run it in one tap.
- Create your own virtual machine, or import your own disk image.
- Export your own VM project so you can share it or test it somewhere else.
- Use the built-in desktop (X11) or VNC display, plus a terminal.

## Getting the app

Every push to `main` builds the app with GitHub Actions (`.github/workflows/android.yml`).
Download the APK from the **Actions** tab of the latest successful run:

- `ByteLab-debug-apk` — one APK that works on every phone.
- `ByteLab-release-apks` — smaller, faster per-CPU APKs (needs the signing secrets from `SIGNING_SETUP.md`). **Use the release APK for everyday use; the debug APK is noticeably slower.**

## Third-party software

ByteLab builds on work by many other people and projects. They are all credited, with their licences, on one page inside the app (**About → Credits**) and in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The licence texts that must stay with the source are in [LICENSE](LICENSE).

## Policies

- [Terms of Service](TERMSOFSERVICE.md)
- [Privacy Policy](PRIVACYANDPOLICY.md)
