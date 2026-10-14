# Setup & build

## Requirements
* JDK 17+ (Android Studio's bundled JBR works)
* Android SDK Platform 35, Build-Tools, **NDK 27.2.12479018**, **CMake 3.22.1**
* ~3 GB free disk for the first build (native code is compiled for 3 ABIs)

## Android Studio
1. *File › Open* → select this folder. Accept the Gradle sync (it downloads the Gradle 8.11.1 wrapper and dependencies once).
2. If prompted, install NDK 27.2.12479018 and CMake 3.22.1 (*SDK Manager › SDK Tools*).
3. Pick a device (arm64 phone recommended; x86_64 emulator works but is slow for transcription) → **Run**.

## Command line
```bash
./gradlew test                 # all unit tests (core + app)
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest   # on-device acceptance tests (turn Wi-Fi/data off)
```

## Without an Android setup
Push the repository to GitHub. `.github/workflows/android.yml` installs the NDK, runs every test, builds the
debug APK and uploads it as a workflow artifact.

## Release build (signed)
Create `keystore.properties` in the project root (it is git-ignored — never commit it):
```
storeFile=/secure/path/release.jks
storePassword=…
keyAlias=astra
keyPassword=…
```
Then `./gradlew :app:bundleRelease` (AAB) or `:app:assembleRelease` (APK). R8 minification is on and all
`android.util.Log` calls are stripped. Without `keystore.properties` the release output is unsigned.

## Reproducibility notes
* All dependency versions are pinned (`gradle/libs.versions.toml`); no dynamic versions.
* whisper.cpp is vendored at a fixed commit (`engine-whisper/src/main/cpp/whisper.cpp/VENDORED_FROM`);
  `scripts/fetch-whisper.sh` re-creates it and verifies the commit hash.
* Dependency-info blocks are disabled in the APK/AAB.
* Recommended hardening on first sync: add `distributionSha256Sum` for Gradle 8.11.1 to
  `gradle/wrapper/gradle-wrapper.properties` (value published at gradle.org/release-checksums) and enable
  Gradle dependency verification (`./gradlew --write-verification-metadata sha256 help`).

## Verification without the Android SDK
```bash
scripts/verify-core.sh                       # compiles core/* and runs its JUnit tests
scripts/verify-native.sh model.bin audio.wav # builds the JNI bridge for the host and transcribes
scripts/compile-check/compile-app.sh android.jar   # type-checks all app Kotlin (see its README)
python3 scripts/gen_strings.py               # regenerates/validates English + Hindi strings
```
