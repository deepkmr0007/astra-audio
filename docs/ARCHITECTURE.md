# Architecture

## Modules

```mermaid
graph TD
  app[":app — Compose UI, Android glue"] --> engine[":engine-whisper — JNI + whisper.cpp"]
  app --> models[":core:models"]
  app --> transcript[":core:transcript"]
  app --> audio[":core:audio"]
  app --> security[":core:security"]
  app --> privacy[":core:privacy"]
  app --> text[":core:text"]
  engine --> transcript
  transcript --> text
  models --> privacy
  privacy --> common[":core:common"]
  text --> common
  audio --> common
  security --> common
  transcript --> common
```

All `core:*` modules are plain Kotlin/JVM with no Android dependency, so the privacy-critical and
accuracy-critical logic is unit-testable anywhere. Android-specific code lives in `:app` packages:

| Spec module | Where it lives |
|---|---|
| App UI | `app/…/ui`, `ui/screens` |
| Audio capture / playback | `app/…/audio/Recorder.kt`, `Player.kt` |
| File import / export | `core/text/DocumentImport.kt`, `app/…/export/ExportManager.kt`, `storage/Library.kt` |
| Transcription engine adapters | `core/transcript/Engine.kt` (interface), `engine-whisper` (whisper.cpp), `app/…/engine/OnDeviceDictation.kt` (Android on-device recognizer) |
| Text-processing adapters | `core/text/*` (deterministic tools) |
| Local model manager | `core/models/*`, `app/…/engine/EngineManager.kt` |
| Local search / index | `core/text/SearchIndex.kt` (in-memory, rebuilt from encrypted library) |
| Privacy / consent gateway | `core/privacy/*`, `app/…/net/HttpsTransport.kt`, `privacy/ConsentBridge.kt` |
| Encrypted storage | `core/security/StreamingAead.kt`, `app/…/security/KeystoreKeyWrapper.kt`, `storage/Library.kt` |
| Audit log | `core/privacy/Audit.kt`, `app/…/privacy/FileAuditLog.kt` |
| Settings | `app/…/settings/Settings.kt` |
| Test harness | `*/src/test`, `app/src/androidTest`, `scripts/verify-*.sh`, `scripts/compile-check` |

## Transcription data flow

```mermaid
flowchart LR
  mic[Microphone<br/>AudioRecord 16 kHz] -->|PCM16| enc1[(rec_raw.enc)]
  enc1 -->|re-wrap with WAV header| audio[(audio.enc<br/>original, unchanged)]
  file[User-picked file<br/>via SAF] -->|byte-for-byte copy| audio
  audio -->|EncryptedMediaDataSource| dec[MediaExtractor + MediaCodec]
  dec -->|mono float + streaming resampler| pcm[(pcm16k.enc cache)]
  pcm --> plan[ChunkPlanner<br/>cut at quiet points]
  plan --> pipe[TranscriptionPipeline<br/>checkpoint per chunk]
  pipe -->|float PCM| jni[libastra_whisper.so<br/>whisper_full]
  jni -->|segments + token probs<br/>as UTF-8 bytes| pipe
  pipe --> tr[(transcript.enc<br/>raw + corrected layers)]
  tr --> ui[Editor · Export · Search · Text tools · TTS]
```

* Plaintext never touches disk: decoding reads through a decrypting `MediaDataSource`, caches are encrypted.
* The **raw** layer is the engine's output and is never edited; user edits and approved glossary
  replacements go into the **corrected** layer with a correction record.
* Each chunk's result is saved in an encrypted checkpoint, so a killed process resumes from the next chunk.

## Network consent sequence

```mermaid
sequenceDiagram
  participant UI as Models screen
  participant D as ModelDownloader
  participant G as NetworkConsentGateway
  participant U as User (ConsentDialog)
  participant T as HttpsTransport
  UI->>D: download(spec)
  D->>G: execute(ConsentRequest)
  G->>G: kill switch? background? policy (HTTPS, host list, payload hash)? connectivity?
  G->>U: ask(request) — task, host(s), payload, reason, size, "Not verified"
  U-->>G: Allow once / Offline alternative / Cancel
  alt allowed
    G->>G: issue + consume single-use token bound to request fingerprint
    loop each hop (initial + redirects)
      G->>G: re-validate URL vs approved hosts, token alive, kill switch
      G->>T: exchange(ticket) — ticket.claim() re-checks again
      T-->>G: Redirect(location) or Done(status)
    end
    G->>G: retire token, audit, status back to Offline
  else declined
    G-->>D: Declined (zero connections)
  end
```

## Key decisions

See [adr/](adr/) for the decision records.
