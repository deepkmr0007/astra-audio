# ADR 0003 — Encrypted local storage

**Status:** accepted

* Android Keystore AES-256-GCM master key (hardware-backed where available, randomized IVs required).
* Each file has its own random 256-bit data key, wrapped by the master key (envelope encryption) —
  one Keystore operation per file instead of per chunk.
* Files are segmented with the STREAM construction (nonce = 7-byte random prefix ‖ 32-bit counter ‖
  last-chunk flag; header as AAD): detects modification, reordering, truncation and extension, and
  allows random-access reads for playback/decoding without plaintext on disk.
* Only platform AES-GCM is used; no custom primitives. `androidx.security:security-crypto` was not used
  (deprecated upstream).
* "Erase everything" deletes the files **and** the master key (crypto-erase).
* The search index is in memory only and is rebuilt after unlock, so no plaintext index exists on disk.
