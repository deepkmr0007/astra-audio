# ASTRA Audio–Text

**Local-first, privacy-by-design speech & text toolkit for Android.** Transcribe recordings and audio files, correct transcripts, translate speech to English, summarise and analyse text, read text aloud and search everything — on the phone, offline by default. Every app-initiated internet request needs fresh, specific approval, enforced in code and by tests.

Built from the *ASTRA AUDIO–TEXT Master Build Prompt v1.0* (the two attached prompt files were byte-identical — SHA-256 `5d8f81d4…5222` — so there was nothing to merge; this project follows that single spec).

| | |
|---|---|
| Platform | Android 8.0+ (API 26), target API 35, Kotlin + Jetpack Compose + Material 3 |
| Speech engine | whisper.cpp v1.9.5 (MIT), in-process, CPU, vendored & pruned (no GPU backends, no `ggml-rpc` socket code) |
| Models | OpenAI Whisper GGML models (MIT) — imported from a file, or downloaded after per-request consent; SHA-1 verified against upstream, SHA-256 pinned |
| Network | One `NetworkConsentGateway`; deny by default; Strict Offline Mode on by default; single-use, request-bound tokens; redirects re-checked |
| Storage | App-private, AES-256-GCM per-file envelope encryption, master key in Android Keystore; no cloud backup |
| Languages | UI in English and हिन्दी; speech in ~99 languages (model-dependent), Hindi + English prioritised |

## What's in the box

```
app/                 Android app (Compose UI, recorder, decoder, player, TTS, consent dialog, Keystore, library)
engine-whisper/      JNI bridge + Kotlin adapter + vendored whisper.cpp (CMake/NDK)
core/common          JSON, hashing, formatting           (pure Kotlin, unit-tested)
core/privacy         Network Consent Gateway, tokens, audit (pure Kotlin, unit-tested)
core/text            WER/CER, summariser, proofreader, search index, safe importers
core/transcript      Transcript model (raw + corrected), TXT/SRT/VTT/JSON, resumable pipeline
core/audio           WAV I/O, resampling, cleanup DSP, quality checks, chunk planner
core/security        Streaming AES-GCM (STREAM construction) with random access
core/models          Model registry, verification, consented downloader, resource advisor
docs/                Architecture, threat model, privacy policy, permissions, models, SBOM, guides
scripts/             verify-core.sh, verify-native.sh, compile-check, gen_strings.py, fetch-whisper.sh
.github/workflows/   CI that builds the debug APK and runs all tests
```

## Build

- **Android Studio** (Ladybug or newer): *Open* this folder → let it sync → *Run*. Needs the NDK 27.2.12479018 and CMake 3.22.1 (Studio offers to install them).
- **Command line:** `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **No Android setup at all:** push to GitHub; the included workflow builds the APK and attaches it to the run.

Details, release signing and reproducibility notes: [docs/SETUP.md](docs/SETUP.md). First-time model install without internet: [docs/OFFLINE_SETUP.md](docs/OFFLINE_SETUP.md).

## Verification status (honest)

See [docs/BUILD_REPORT.md](docs/BUILD_REPORT.md). In short: the pure-Kotlin core (96 JVM tests, plus 2 app-logic tests, incl. the gateway, crypto, metrics, exporters and a static network-bypass scan) passes; the whisper.cpp JNI bridge was compiled and exercised end-to-end on a host JVM; all app Kotlin was type-checked against `android.jar` (API 35). **The APK itself was not built in the authoring environment** (no Android SDK / Google Maven access there), no accuracy numbers exist yet, and nothing has run on a phone. Unfinished items are listed in [docs/UNFINISHED.md](docs/UNFINISHED.md).

## License
The app's own code is unlicensed until the owner chooses a licence. Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
