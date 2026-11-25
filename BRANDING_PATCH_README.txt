ByteLab branding correction — changed files only

IMPORTANT: This is NOT a full project ZIP. Extract its contents into the root of your existing ByteLab project and allow replacement.

Logo roles:
- app/src/main/res/mipmap-*/ic_launcher*.png: existing ByteLab app launcher icon (already uses the supplied phone/N logo).
- app/src/main/res/drawable-nodpi/bytelab_app_icon.png: ByteLab app logo used by VM fallback icons and legacy launcher drawable.
- app/src/main/res/drawable-nodpi/bytelab_notification.png: monochrome ByteLab mark for Android status-bar/foreground notifications.
- app/src/main/res/drawable-nodpi/niaznix_company_logo.png: separate NIAZNIX company logo, used only in About attribution.

Changed existing files:
- app/src/main/res/layout/content_about.xml
- app/src/main/java/com/vectras/vm/MainService.java
- app/src/main/java/com/vectras/vm/x11/X11Activity.java
- app/src/main/res/drawable-anydpi-v26/ic_service_notification.xml
- app/src/main/res/drawable/ic_launcher_foreground.xml
- app/src/main/res/drawable/ic_computer_180dp_with_padding.xml
- README.md
- resources/web/icon-192.png
- resources/web/icon-512.png
- resources/web/icon-192-maskable.png
- resources/web/icon-512-maskable.png
- resources/web/apple-touch-icon.png
- resources/web/favicon.ico

New files:
- app/src/main/res/drawable-nodpi/bytelab_app_icon.png
- app/src/main/res/drawable-nodpi/bytelab_notification.png
- app/src/main/res/drawable-nodpi/niaznix_company_logo.png
- resources/bytelab-app-icon.png

See DELETE_OLD_VECTRAS_LOGOS.txt for obsolete logo files to remove.
Build/test not run in this patch environment; please run your Android IDE/GitHub Actions build.
