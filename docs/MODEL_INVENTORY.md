# Model inventory

No model is bundled in the APK. Models are imported from a file or downloaded after per-request consent.
Source of checksums: whisper.cpp `models/README.md` at v1.9.5 (commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`).
Memory figures: whisper.cpp README (approximate); the turbo-q5_0 figure is an estimate.

| ID | File | Source | Licence | SHA-1 (upstream) | Size | RAM | Languages | Speech→EN | Speaker turns |
|---|---|---|---|---|---|---|---|---|---|
| whisper-tiny | ggml-tiny.bin | huggingface.co/ggerganov/whisper.cpp | MIT | bd577a113a864445d4c299885e0cb97d4ba92b5f | 75 MiB | ~273 MB | multilingual | ✓ | — |
| whisper-tiny-en | ggml-tiny.en.bin | same | MIT | c78c86eb1a8faa21b369bcd33207cc90d64ae9df | 75 MiB | ~273 MB | English | — | — |
| whisper-base | ggml-base.bin | same | MIT | 465707469ff3a37a2b9b8d8f89f2f99de7299dac | 142 MiB | ~388 MB | multilingual | ✓ | — |
| whisper-base-en | ggml-base.en.bin | same | MIT | 137c40403d78fd54d454da0f9bd998f78703390c | 142 MiB | ~388 MB | English | — | — |
| whisper-small | ggml-small.bin | same | MIT | 55356645c2b361a969dfd0ef2c5a50d530afd8d5 | 466 MiB | ~852 MB | multilingual | ✓ | — |
| whisper-small-en | ggml-small.en.bin | same | MIT | db8a495a91d927739e50b3fc1cc4c6b8f6c2d022 | 466 MiB | ~852 MB | English | — | — |
| whisper-small-en-tdrz | ggml-small.en-tdrz.bin | huggingface.co/akashmjn/tinydiarize-whisper.cpp | MIT per upstream repo — **verify before redistributing** | b6c6e7e89af1a35c08e6de56b66ca6a02a2fdfa1 | 465 MiB | ~852 MB | English | — | ✓ (changes only) |
| whisper-medium | ggml-medium.bin | huggingface.co/ggerganov/whisper.cpp | MIT | fd9727b6e1217c2f614f9b698455c4ffd82463b4 | 1.5 GiB | ~2.1 GB | multilingual | ✓ | — |
| whisper-large-v3-turbo-q5_0 | ggml-large-v3-turbo-q5_0.bin | same | MIT | e050f7970618a659205450ad97eb95a18d69c9ee | 547 MiB | ~1.1 GB (est.) | multilingual | — (turbo not trained to translate) | — |

**Verification at install:** size ≤ approx+5 %, SHA-1 equals the table, GGML magic `0x67676d6c`, expected
`n_vocab` (51864 English-only, 51865 multilingual, 51866 large-v3). SHA-256 is computed and pinned; the
*Verify* button re-hashes later.

**Recommended:** ≤3 GB RAM → tiny; 3–6 GB → base; 6–8 GB → small (best Hindi/Hinglish on mid-range);
8 GB+ → large-v3-turbo-q5_0 (transcription) or small (translation).

**Known limitations (all Whisper models):** hallucination during silence/music; reduced accuracy with
noise, overlapping speakers, strong accents, quiet audio; language ID can be wrong on short or mixed clips;
Hindi quality rises sharply from tiny → small. No accuracy figures are claimed — use the Benchmark screen.

**Live dictation** uses Android's on-device recognizer (not a model ASTRA ships). **TTS** uses the phone's
TTS engine voices marked `isNetworkConnectionRequired == false` and installed.
