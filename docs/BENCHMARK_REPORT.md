# Accuracy benchmark report

## Measured so far (v1.1, CI)

Every CI run downloads real Whisper models from the official source, verifies their SHA-1, and runs the
app's own `WhisperEngine` + JNI bridge (`tools/bench`) on the public-domain JFK inaugural clip (11 s,
English). Results from CI run #4 (GitHub `ubuntu-latest` runner, x86-64, **4 threads — not a phone**):

| Model | Profile | Time for 11 s audio | Real-time factor | WER | CER |
|---|---|---|---|---|---|
| tiny.en | Fast | 0.51 s | 0.047 | 0.0 % | 0.0 % |
| tiny.en | Balanced | 0.50 s | 0.045 | 0.0 % | 0.0 % |
| tiny.en | Highest | 0.69 s | 0.063 | 0.0 % | 0.0 % |
| base.en | Fast | 1.04 s | 0.094 | 0.0 % | 0.0 % |
| base.en | Balanced | 1.01 s | 0.091 | 0.0 % | 0.0 % |
| base.en | Highest | 1.33 s | 0.121 | 0.0 % | 0.0 % |
| base (multilingual, auto language) | Fast | 1.83 s | 0.167 | 0.0 % | 0.0 % |
| base (multilingual, auto language) | Balanced | 1.78 s | 0.162 | 0.0 % | 0.0 % |

**Silence skipping** (same clip with 20 s of silence before and after, 51 s total, Fast profile):

| Model | Without skipping | With skipping | Speed-up | First word mapped to |
|---|---|---|---|---|
| tiny.en | 0.997 s | 0.509 s | 1.96× | 19.71 s (true onset ≈ 20 s) |
| base.en | 1.990 s | 1.049 s | 1.90× | 19.71 s |

Read these honestly: one short, clean, well-known English clip is a smoke test, not a benchmark of
real-world accuracy (WER 0 % here says nothing about noisy Hindi meetings), and x86 desktop speed is not
phone speed. Each new CI run republishes the numbers in its GitHub Release notes; the on-device emulator
test logs its own timings. The protocol below is still needed for representative Hindi/Hinglish results.

## What exists
* `core/text/ErrorRate.kt`: WER and CER with visible alignment (S/D/I), corpus aggregation (total errors ÷ total
  reference length), and recorded normalisation (NFC, lowercase, punctuation removed, whitespace collapsed).
  Unit-tested, including Devanagari combining-mark handling.
* In-app **Accuracy benchmark** screen (`BenchmarkRunner`): runs the active model on user-selected recordings with
  user-supplied reference transcripts, reports per-case WER/CER, real-time factor, model ID + SHA-256, profile,
  thread count, device, and caveats; exports JSON. Everything stays on the phone.
* `scripts/verify-native.sh`: host-side harness to time the same engine on a desktop.

## Protocol to produce the report
1. Test set: ≥30 min Hindi, ≥30 min Indian English, ≥15 min code-switched (Hinglish) speech you have the right to
   use; mix of quiet/noisy, 1 and 2+ speakers. Write verbatim reference transcripts (Devanagari for Hindi).
2. Models: tiny, base, small, large-v3-turbo-q5_0. Profiles: Fast, Balanced, Highest. Language: explicit and auto.
3. Device: record make/model, SoC, RAM, Android version, thermal state at start.
4. Run the Benchmark screen per model/profile; save each JSON report.
5. Fill in the table below from the JSON files only.

| Model | Profile | Set | WER | CER | RTF | Device | Date |
|---|---|---|---|---|---|---|---|
| — | — | — | not measured | not measured | — | — | — |

## Caveats
WER depends on normalisation and tokenisation; Hindi spelling variants (nukta, chandrabindu vs anusvara) count
as errors; CER counts Unicode code points; results apply only to the recordings, model, settings and phone tested.
