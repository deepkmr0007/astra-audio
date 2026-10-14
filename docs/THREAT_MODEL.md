# Threat model

**Assets:** recordings, transcripts, notes, the glossary, model files (integrity), the user's consent decisions.
**Trust boundary:** the app process and its private storage. Everything outside (other apps, the network,
imported files, model files, the TTS engine, the on-device recognizer service) is untrusted.

| Threat | Mitigation | Verified by |
|---|---|---|
| Accidental upload of audio/text | Only the gateway can connect; payload class + SHA-256 bound to consent; no feature uploads content in v1.0 | `NetworkConsentGatewayTest`, `NetworkBypassScanTest` |
| Unwanted network access (startup, idle, background) | No network code outside the gateway; background requests refused; no WorkManager/sync; Strict Offline default | `NetworkBypassScanTest.nothingNetworksAtStartup`, `backgroundRequestsAreBlocked`, instrumented `launchMakesNoNetworkAttempt` |
| Consent replay / reuse for another file or host | Single-use, 2-min, fingerprint-bound tokens; per-hop tickets re-checked in transport | `tokenCannotBeReplayedForADifferentRequest`, `tokenIsSingleUse`, `tokenExpires`, `ticketCannotBeClaimedTwice…` |
| Malicious/unexpected redirects, downgrade | Redirects re-validated before connecting; HTTPS-only; NSC trusts system CAs only, cleartext off | `redirectToUnapprovedHostIsBlockedBeforeConnecting`, `redirectDowngradeToHttpIsBlocked`, `manifestIsPrivacySafe` |
| Third-party SDK behaviour | No analytics/crash/ads SDKs; dependency scan in tests; ML Kit rejected | `buildHasNoTelemetryDependencies` |
| Malicious imported files (bombs, path tricks, XXE) | Size caps; zip entry/ratio limits; no XML entity resolution (regex extraction of `w:t`); sanitized names; content treated as data | `DocumentImportTest` |
| Prompt injection inside documents | No LLM consumes imported text; tools are deterministic; UI states that instructions in content are never followed | design |
| Untrusted model files | Registry-only installs; upstream SHA-1 + size cap + GGML magic + vocab check; SHA-256 pinned and re-checkable; no code loading (weights only; ggml dynamic backend loading compiled out) | `ModelInstallTest` |
| Insecure local storage / backups | Keystore-wrapped per-file AES-GCM; `allowBackup=false`; data-extraction rules exclude everything | `StreamingAeadTest`, `manifestIsPrivacySafe`, instrumented crypto tests |
| Tampered/truncated encrypted files | AES-GCM + STREAM last-chunk flag + header AAD | `detectsBitFlip`, `detectsTruncationAtChunkBoundary`, `detectsHeaderTampering` |
| Leaking content via logs | App never logs content; release build strips `android.util.Log`; whisper/ggml logging silenced in JNI; audit entries are content-free | `auditNeverContainsPayloadContent`, code review |
| Covert recording | Recording only after explicit tap; visible state + timer; auto-pause when app leaves foreground; Android also blocks background mic | code review, manual test |
| Crash due to invalid UTF-8 from tokens | Text crosses JNI as bytes; Kotlin decodes with replacement | `verify-native.sh` |
| Shoulder surfing / screenshots | Optional app lock (biometric/device credential) and FLAG_SECURE toggle | manual |

## Residual risks (documented to users in the Privacy dashboard)
* Rooted or compromised OS, malware with accessibility/overlay powers, or physical access while unlocked.
* Someone who knows the screen lock can unlock the app lock.
* Data the user exports or shares leaves ASTRA's control.
* The system TTS engine and the on-device recognizer are separate apps whose internal behaviour ASTRA cannot verify; ASTRA only selects offline voices / the on-device API.
* Vulnerabilities in whisper.cpp/ggml or Android media codecs parsing hostile audio files (mitigated only by keeping dependencies updated).
* SHA-1 (what upstream publishes) is collision-weak; risk is limited because the download host is fixed, TLS-protected and the SHA-256 is pinned at install.
* Flash storage may retain deleted encrypted blocks; they stay encrypted, and "Erase everything" destroys the master key.
