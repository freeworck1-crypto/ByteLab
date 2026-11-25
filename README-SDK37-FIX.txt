ByteLab SDK 37 build fix

Replace these files in the existing ByteLab repository, keeping the same paths:
- app/build.gradle
- .github/workflows/android.yml

This restores compileSdk 37 (required by the project's Glide 5.0.9 and AndroidX Core 1.19.0 dependencies), installs the published SDK platform package platforms;android-37.0, installs Build Tools 37.0.0, and prepares both NDK versions referenced/used by the project. The workflow creates an android-37 directory alias for Android Gradle Plugin versions that look up the major-only folder name.

Do not replace the whole project. Keep your existing source files, .git folder, and GitHub Secrets. This patch does not change targetSdk or minSdk.
