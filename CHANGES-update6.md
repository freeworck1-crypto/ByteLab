# Update 6 - "NIAZNIX Phone": one screen, one Android phone

- The app now opens on a single screen: Download -> Run (Pause/Resume while downloading), a Phone frame / Full screen
  choice, and a Root switch. Everything inside the phone (browser, files, Wi-Fi, installing APKs) is the Android image.
- Phone frame: rounded bezel + camera dot drawn over the virtual phone (built-in X11 screen). Full screen: no frame.
- Android phones show no keyboard/arrow/mouse buttons by default (existing Android mode).
- Wi-Fi switch is enabled inside the phone. Root = guest-only ADB forward (turn on USB debugging + Root access in the phone).
- Image: ByteLab-android-x86_64-5.1.iso from your GitHub release (about 450 MB, downloaded once, works offline).
- Long-press the "Android Phone" title to open the old advanced screen.
- App name is now "NIAZNIX Phone". Same package, so it updates the installed app.
NEW   java/com/vectras/vm/phone/{PhoneHomeActivity,PhoneFrame}.java
EDIT  AndroidManifest.xml, res/values/strings.xml, SplashActivity.java, setupwizard/SetupWizard2Activity.java, x11/X11Activity.java
