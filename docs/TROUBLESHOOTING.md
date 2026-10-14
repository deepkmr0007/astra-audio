# Troubleshooting

| Problem | Cause / fix |
|---|---|
| Gradle sync fails: "NDK not configured" | Install NDK 27.2.12479018 and CMake 3.22.1 in SDK Manager › SDK Tools. |
| Native build very slow | First build compiles whisper.cpp for 3 ABIs. To speed up local debugging, temporarily keep only `arm64-v8a` in `engine-whisper/build.gradle.kts`. |
| "No speech model is installed" | Open *Models*: import a file or download one. |
| Download blocked immediately | Strict Offline Mode is on — the app asks to turn it off. Or the phone is offline (no prompt is shown then). |
| "Download was redirected to …" | The server used a mirror you had not approved; nothing was sent to it. Choose *Ask again* to approve a request that includes it. |
| Model rejected: checksum mismatch | File is corrupt or not the official model. Re-download from the source in MODEL_INVENTORY.md. |
| "Needs ~… MB RAM" / model will not load | Choose a smaller model (base or tiny), close other apps. |
| Transcription is slow / phone warm | Use *Fast* profile or a smaller model; Highest accuracy is 2–4× slower. Charge the phone for long files. |
| Wrong language / gibberish | Set the spoken language explicitly instead of *Detect automatically*. Use *small* for Hindi. |
| Hallucinated text in silences | Known Whisper limitation; check highlighted low-confidence lines and the quality warnings. |
| "This audio format could not be decoded" | Android's codecs cannot read it. Convert to WAV/MP3/M4A. |
| Live dictation unavailable | Needs Android 12+ and an on-device recognizer; otherwise use Record + Transcribe. |
| No TTS voices | Install offline voice data in Android › Settings › Text-to-speech. |
| "Could not open the library" | The Android keystore was reset (e.g. screen lock removed on some phones); old encrypted data cannot be read. |
| App lock cannot be enabled | Set up a screen lock or biometric in Android settings first. |
