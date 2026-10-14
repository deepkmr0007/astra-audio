# ADR 0006 — Making on-device transcription fast

**Status:** accepted (v1.1)

| Technique | Why it helps | Risk control |
|---|---|---|
| **Runtime CPU-variant selection** (ggml `GGML_CPU_ALL_VARIANTS` + `GGML_BACKEND_DL`, arm64 only) | Newer phones get ARMv8.2 dot-product + FP16, ARMv8.6 int8 matmul, SVE2/SME kernels — several times faster matrix maths than the ARMv8.0 baseline. | Each variant is a library inside the APK; ggml scores them against the CPU's HWCAP bits and loads only a compatible one, so older Cortex-A53/A73 phones never execute unsupported instructions. Libraries are extracted on install (`useLegacyPackaging = true`) so ggml can find them; nothing is downloaded. |
| **Flash attention** (`whisper_context_params.flash_attn = true`) | Fused attention: less memory traffic, faster decoder. | whisper.cpp default; v1.0 had it off. |
| **llamafile tiled matmul** (`GGML_LLAMAFILE`) | Faster F16/F32 GEMM on NEON. | Upstream-maintained kernels. |
| **Always -O3 native code** | Debug APKs previously built whisper.cpp at -O0 (5–10× slower). | Only the C/C++ is optimized; Kotlin debug builds stay debuggable. |
| **Performance-core threads** | Whisper splits work evenly; one thread on a slow efficiency core stalls the rest. Thread count = cores within 15 % of the fastest core's max frequency (sysfs), 2..8. | Pure function with tests for 4+3+1, 6+2, uniform and unknown layouts; user can override in Settings. |
| **Silence skipping** | Whisper always pays for full 30 s windows, so pauses cost as much as speech. Pauses > 1.2 s are removed (300 ms kept each side), fully silent chunks are skipped, timestamps are mapped back exactly. Also removes the main trigger of hallucinated text. | Never removes audio louder than −35 dBFS; skipped only when it saves ≥ 10 %; on by default, switchable per job; tested incl. quiet speakers and junction mapping. |
| **Pre-warming** | Loading a model takes seconds; it now happens while the user is on the Record screen or looking at an untranscribed item. | Background, cancellable; skipped if no model or not enough RAM. |
| **Live transcription** | Windows are transcribed during recording, so only the last ≤ 28 s remain after Stop. | Single worker, never transcribes audio twice; recording is unaffected if live mode fails. |
| **Optimized release APK (R8)** | Smaller dex and faster UI than the debug build. | Published alongside the debug APK. |

Measured numbers are produced by CI (`bench` job, real models) and the on-device emulator test; see
the GitHub Release notes and docs/BENCHMARK_REPORT.md. No speed-up figure is claimed without a measurement.
