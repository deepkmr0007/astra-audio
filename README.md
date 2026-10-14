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

## Install on your phone

Open **[Releases](https://github.com/deepkmr0007/astra-audio/releases/latest)** on your phone, download
`ASTRA-AudioText-v1.1-buildN.apk`, open it and allow installing from your browser/file manager. Every
push to `main` that passes the tests publishes a new build (newer builds install over older ones).

## New in v1.1 — speed and live transcription

* **Fastest CPU kernels for each phone** — ARMv8.2 dot-product/FP16, ARMv8.6 int8-matmul and ARMv9 SVE2/SME
  variants are shipped and the best compatible one is picked at start-up; older phones keep the safe baseline.
* **Flash attention, tiled matmul kernels, optimized native code in every build, performance-core threads.**
* **Silence skipping** — long pauses never reach the model; ~2× faster on a clip that is ¾ silence in CI,
  with timestamps mapped back exactly and no hallucinated text in silences.
* **Live transcript while recording** — text appears as you speak; the full transcript is ready moments after Stop.
* **Model pre-warming**, **speed report** on every transcript, **auto-titles** for untitled recordings.
* **Real verification in CI:** real Whisper models (WER/CER + speed), an Android emulator running the app's
  instrumented tests including real on-device speech recognition, and an R8-optimized 23 MB release APK.

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
tools/bench          Desktop benchmark harness (real models, WER/CER, speed) used by CI
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

See [docs/BUILD_REPORT.md](docs/BUILD_REPORT.md) and [docs/BENCHMARK_REPORT.md](docs/BENCHMARK_REPORT.md).
Since v1.1 the APK is built, unit-tested, benchmarked with real models and run on an Android 14 emulator by
GitHub Actions on every push. **Not yet verified:** speed on a physical ARM phone and accuracy on Hindi /
noisy real-world audio. Unfinished items: [docs/UNFINISHED.md](docs/UNFINISHED.md).

## License
The app's own code is unlicensed until the owner chooses a licence. Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
