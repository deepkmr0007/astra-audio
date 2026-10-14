# Build report — what was built and what was actually tested

Authoring environment: Linux x86_64, OpenJDK 21, Kotlin 2.0.21 compiler (bundled with Gradle 8.14.3), CMake +
Ninja + GCC, git access to GitHub only. **No Android SDK, no Google Maven, no model downloads.**

## Inputs
Both attached prompt files are byte-identical (SHA-256 `5d8f81d4e2c1e117e3efd3fffeb35342065785be3e2ceca1fa5bf77e9b945222`),
so the "compare and choose" step had a single outcome: implement that spec.

## Phases
| Phase | Delivered |
|---|---|
| 1 Constraints & engine choice | whisper.cpp v1.9.5 pinned by commit and vendored (CPU-only, rpc/GPU removed); API inspected from `whisper.h`; model SHA-1s taken from upstream; ADRs 0001–0005 |
| 2 Shell, permissions, files, recording/playback, dashboard | Gradle multi-module project, Compose Material 3 UI (15 screens), manifest with minimal permissions, backup/extraction rules, network security config, recorder (encrypted, auto-pause), encrypted playback, library, privacy dashboard |
| 3 Offline transcription | JNI bridge (byte-safe UTF-8, polling progress/cancel, no native→Java callbacks), `WhisperEngine`, MediaCodec decoder with streaming resampler, quiet-point chunking, checkpointed resumable pipeline |
| 4 Transcript tools | Raw vs corrected layers, editing, speakers, uncertainty from engine token probabilities, verbatim/clean, glossary, TXT/SRT/VTT/JSON export with preview, SAF save, share warning, text tools, speech→English, TTS |
| 5 Consent gateway | Deny-by-default gateway, single-use fingerprint-bound tokens, per-hop tickets, redirect re-validation, kill switch (default on), background block, audit, consent dialog, verified model downloader with offline alternative |
| 6 Search, encryption, models, a11y, resources | In-memory index (phrase, prefix, date, tag); Keystore-wrapped STREAM AES-GCM; model registry/installer/verify/delete; resource advisor; app lock; FLAG_SECURE; Hindi + English UI (428 strings each); text scaling; semantics on key controls; retention rules with confirmation |
| 7 Test & fix | See evidence below |
| 8 Artifacts & docs | CI workflow, scripts, docs (this folder), SBOM, notices |

## Evidence
| Check | Command | Result |
|---|---|---|
| Core unit tests (gateway, tokens, redirects, crypto, WER/CER, summariser, importers, exporters, pipeline resume, DSP, resampler, model verification, bypass scan, manifest scan) | `scripts/verify-core.sh` | **96 tests, 0 failures** |
| Static network-bypass scan is effective | injected `java.net.URL(...).openStream()` into `Player.kt` | scan **failed as expected**, then restored |
| App logic unit tests | compiled & run with JUnit | **2 tests, 0 failures** |
| Native bridge builds; no network symbols | `scripts/verify-native.sh` (host CMake) | built `libastra_whisper`; undefined symbols contain **no** socket/connect/getaddrinfo/curl |
| JNI end-to-end on JVM | same script with `for-tests-ggml-tiny.bin` + `samples/jfk.wav` | loaded, progress 0→100, language reported, segments/word assembly path executed, freed; cancel test returned **CANCELLED** |
| App Kotlin type-check (all 36 app files + engine + core) | `scripts/compile-check/compile-app.sh android-35.jar` | **0 errors**, 1 deprecation warning; harness proven to catch injected errors |
| Composable calls inside event/coroutine lambdas | custom scan | **0 issues** |
| Strings complete in both languages, placeholders match | `python3 scripts/gen_strings.py` | **428 / 428**, all used keys present |

## Acceptance criteria (spec §Testing)
| # | Criterion | Status |
|---|---|---|
| 1 | Works with Wi-Fi/data off | By design (no network in any local path); verify on device |
| 2 | No network at launch/idle/recording/local work | Enforced: only the gateway can connect; startup scan test passes; instrumented test written |
| 3 | Every app-initiated request triggers approval first | Tested (gateway tests) |
| 4 | Denial → no request, no retry | Tested |
| 5 | Approval single-use, operation-specific | Tested |
| 6 | Redirects/retries revalidated | Tested (retries never happen automatically) |
| 7 | Recording only on user action, stops on request | Implemented; manual device test pending |
| 8 | Mic denial does not crash; import still works | Implemented; manual device test pending |
| 9 | Editable transcripts; original media unchanged | Raw/corrected tested; byte-for-byte import instrumented test written |
| 10 | WER/CER on disclosed sets; no fabricated claims | Tooling done & tested; **no numbers yet** |
| 11 | Uncertainty and unsupported languages disclosed | Implemented (engine probabilities, quality + language warnings) |
| 12 | No sensitive content in logs/analytics/crash/backup | No logging of content, Log stripped in release, no SDKs, backups excluded; tested statically |
| 13 | Model downloads verify integrity, show licence/source/size | Tested |
| 14 | Export/share user-initiated with destination shown | Implemented (SAF picker / share sheet + preview + warning) |
| 15 | Usable on low-memory devices | Resource advisor tested; device test pending |
| 16 | Automated tests for gateway, permissions(manifest), storage, parsers, cancellation | Present and passing (JVM); instrumented written |
| 17 | Clean-checkout build → signed-release-ready APK/AAB | Build config + CI provided; **not executed here** |
| 18 | Security review: no bypass of consent gateway | Automated scan in the test suite (passes) + THREAT_MODEL.md |
