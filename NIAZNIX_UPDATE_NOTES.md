# ByteLab — NIAZNIX engineering update

## Included in this source update
- Replaced the app launcher artwork with the supplied NIAZNIX teal/gold logo (adaptive, legacy, and monochrome launcher assets).
- Changed the visible application name and release APK naming to ByteLab.
- Added the `A NIAZNIX Product` credit to About and used the NIAZNIX mark in the setup completion UI.
- Removed the obsolete Free Palestine About row and its unused drawable/string.
- Removed the former upstream website, help, and donation entries from the navigation drawer; removed the old community/donation detour from the setup completion flow.
- Disabled remote loading of the inherited privacy/terms URLs and updated the bundled legal documents for ByteLab, while accurately disclosing the remaining inherited catalogue/bootstrap endpoints.
- Hid the inherited social-link panel and removed its automatic GitHub contributor-profile loading.
- Fixed the old hardcoded internal app-data fallback path for the current application ID.
- Added safe first-launch folder migration from `Documents/VectrasVM` to `Documents/ByteLab`, and from the app's old `data/Vectras` directory to `data/ByteLab`; if a rename fails, the app continues using the old directory so existing VM data is not discarded.
- Fixed the `.gitignore` merge-conflict markers.
- Removed duplicate Kotlin-DSL Gradle scripts; this project uses the existing Groovy Gradle scripts and multi-module layout.
- Removed an unused layout-like XML file incorrectly stored under `res/drawable`.
- Added this engineering plan and third-party notice documentation.

## Not implemented in this update
- No new Android guest hypervisor/runtime has been integrated yet.
- No claim of one-tap Android guest boot, guest APK installation, guest-root toggle, or 2–3 minute startup is made by this patch.
- QEMU and the existing PRoot/bootstrap stack remain in place for compatibility. A separate Android guest backend still needs implementation and device testing.
- The ROM/software catalogue and QEMU/bootstrap package URLs still include inherited upstream endpoints. They are retained for current functionality until NIAZNIX-hosted replacements exist.

## Validation
- Parsed all Android XML resources successfully with an XML parser.
- Checked changed resource references and removed the obsolete drawable references.
- A Gradle/Android build could not be run in this environment: the Gradle wrapper distribution was not cached, outbound DNS/network access was unavailable, and no Android SDK was configured. Please run the normal GitHub Actions build and device test before merging/publishing.

## Applying the archive
This is a full source tree archive without `.git`, local.properties, Gradle caches, or generated build outputs. Extract it over your working project while keeping your existing `.git` directory so your GitHub remote and commit history remain intact.
