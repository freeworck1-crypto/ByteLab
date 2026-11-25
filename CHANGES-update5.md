# ByteLab - update 5: store fixes and third-party links removed

- ROM store ("VM store"): no longer calls the original Vectras server. It shows the catalogue bundled in the APK
  immediately, then merges ByteLab's own online list (github.com/freeworck1-crypto/ByteLab/.../store/roms.json)
  in the background, so nothing waits on or freezes on the network.
- Fixed an endless loop in the search "random suggestions" code (could freeze the app on the main thread).
- Software store tab hidden: it only loaded from the original Vectras server ("Oops! Unable to connect").
  Bring it back later by deleting the android:visible="false" line in res/menu/bottom_navigation_home_menu.xml
  once you host your own software list.
- Drawer: Vectras website, Patreon, Help (Vectras site), QEMU docs and "Play Store version" entries hidden.
- About: old Vectras links removed from the code.
