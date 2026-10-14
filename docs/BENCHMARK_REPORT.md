# Accuracy benchmark report

**Status: no accuracy numbers have been measured yet, and none are claimed.**

Real WER/CER figures require (a) a real model and (b) a lawfully usable, representative test set — neither was
available in the authoring environment (model hosts were unreachable there). Published numbers from other
sources are not repeated here because they were measured on other hardware, settings and data.

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
