# ByteLab - update 4

- CI: creates ~/.android/debug.keystore before Gradle runs (fixes the shell-loader "debug.keystore (No such file or directory)" error).
- Signing: debug and release APKs are now signed with the same persistent ByteLab key (when the GitHub secret exists),
  so any ByteLab APK from CI can update the installed app without uninstalling.
- App icon: back to the ByteLab phone logo (launcher, install screen, recents, About). The NIAZNIX company logo now
  appears only as "Built by NIAZNIX" (splash) and "Designed & developed by NIAZNIX" (About).
- Smaller files: in-app ByteLab logo, home-screen logo and the Linux/Android/macOS icons are re-compressed
  (about 430 KB saved); unused logo_bg.jpg is removed.
