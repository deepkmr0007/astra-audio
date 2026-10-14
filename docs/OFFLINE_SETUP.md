# Offline setup (no internet on the phone at all)

ASTRA never needs the internet if you provide the speech model yourself.

1. On any computer, download a model file from the official source listed in [MODEL_INVENTORY.md](MODEL_INVENTORY.md),
   e.g. `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`.
2. Optional but recommended: check its SHA-1 matches the table (`sha1sum ggml-base.bin` / `certutil -hashfile ggml-base.bin SHA1`).
3. Copy the file to the phone (USB, SD card, Bluetooth, etc.).
4. In ASTRA: *Models › Import a model file* (or *Import file* on a specific model) and pick it.
   ASTRA checks size, SHA-1, file header and vocabulary, then installs it into private storage.
   Unknown or modified files are rejected.
5. Keep **Strict Offline Mode** on (Privacy dashboard). ASTRA will then never attempt a connection.

Which model? 3–4 GB RAM phones: `ggml-base.bin`. 6–8 GB: `ggml-small.bin` (much better Hindi).
English-only use: the `.en` variants are slightly better at English.

For device-wide blocking (all apps), use Airplane mode or Android's per-app data settings; ASTRA can only
control its own traffic.
