# ADR 0001 — On-device speech engine: whisper.cpp

**Status:** accepted · **Date:** 2026-10-12

## Options evaluated
| Option | Licence | Offline | Hindi | Notes |
|---|---|---|---|---|
| **whisper.cpp v1.9.5** + OpenAI Whisper GGML models | MIT / MIT | Yes, in-process | Yes (multilingual models) | Mature, maintained, CPU NEON kernels, token probabilities and timestamps, language ID, speech→English translation, tinydiarize speaker turns, abort callback. |
| Vosk (Kaldi) | Apache-2.0 | Yes | Small Hindi model exists | Fast streaming, lower accuracy than Whisper small on mixed Hindi/English; separate model zoo. |
| Android `SpeechRecognizer` (classic) | Platform | **Not guaranteed** — may stream audio to a cloud service | Varies | Rejected for transcription: offline behaviour cannot be verified. |
| Android on-device recognizer (API 31+ `createOnDeviceSpeechRecognizer`) | Platform | Yes by API contract | Depends on installed packs | Used **only** for live dictation, and only when `isOnDeviceRecognitionAvailable()` is true. |

## Decision
whisper.cpp is the transcription engine, behind the `TranscriptionEngine` interface so other engines
(e.g. Vosk, or a future NPU runtime) can be added without changing the app. Built CPU-only for
arm64-v8a, armeabi-v7a and x86_64 (emulator). GPU (Vulkan/OpenCL) backends are excluded for
reliability across devices and to keep the native surface small; `ggml-rpc` (contains socket code)
is removed from the vendored source.

## Consequences
* Accuracy/speed trade-off exposed as Fast / Balanced / Highest profiles (greedy, best-of-5 with
  temperature fallback, beam-5).
* armv8.2 dot-product/fp16 kernels are **not** enabled (would crash older Cortex-A53/A73 phones);
  a runtime-dispatched second library is a listed follow-up.
