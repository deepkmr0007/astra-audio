# Unfinished / not yet verified — honest list

Nothing below is presented anywhere in the app as working.

## Not verified in the authoring environment
1. **The APK was not built here** (no Android SDK or Google Maven access). App Kotlin was type-checked against
   `android.jar` API 35 with signature-faithful AndroidX/Compose stubs (0 errors), but the real Gradle/AGP build,
   Compose compiler, resource linking, R8 and lint have not run. First action: build via Android Studio or CI.
2. **No on-device runs.** Instrumented tests (`app/src/androidTest`) are written but not executed. Manual checks
   still to do on a phone: microphone-denial path, auto-pause on leaving the app, process-death resume, app lock,
   FLAG_SECURE, TalkBack pass, Hindi UI layout.
3. **No accuracy numbers** (see BENCHMARK_REPORT.md). The JNI path was exercised only with whisper.cpp's tiny
   random-weight test models, which load, run, detect a language, cancel and free correctly but produce no text.

## Features not implemented (deliberately not faked)
4. Rewrite / simplify / expand text (needs an on-device LLM such as llama.cpp + a licensed small model).
5. Offline text-to-text translation (e.g. English → Hindi); only speech → English via Whisper exists.
6. PDF text import (TXT, MD, DOCX are supported).
7. Sound-event labels.
8. Full speaker diarization: only tinydiarize speaker-*change* marks (English, small.en-tdrz), with alternating neutral labels the user corrects.
9. Optional online adapters (e.g. cloud transcription). The gateway supports text/audio payload consent with
   preview and hash binding, but no remote provider is integrated — by design for v1.0.
10. Live, progressive whisper transcription *while* recording (record-then-transcribe is implemented; live dictation uses Android's on-device recognizer on Android 12+).
11. Word-level karaoke highlighting (word timestamps are stored; the UI highlights the current segment).

## Engineering follow-ups
12. Runtime-dispatched ARMv8.2 (dotprod/fp16) native library for ~1.5–2× speed on newer phones; GPU backends.
13. Resumable model downloads (an interrupted download restarts and asks for consent again).
14. Gradle wrapper `distributionSha256Sum` and Gradle dependency-verification metadata.
15. Full transitive SBOM (CycloneDX plugin) and an online CVE check.
16. Mid-job thermal throttling (a warning is shown before starting heavy work only).
17. A few engine/core messages (e.g. model limitation notes, some error texts) are English-only in the Hindi UI.
18. The tinydiarize model licence is taken from its upstream repository and should be confirmed before redistribution.
