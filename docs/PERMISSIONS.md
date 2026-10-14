# Permission inventory

| Permission | Type | Why | When requested |
|---|---|---|---|
| `RECORD_AUDIO` | Runtime (dangerous) | Recording and live dictation | Only when the user taps Record/Start dictation, after an in-app explanation. Denial is handled: import and text tools keep working. |
| `INTERNET` | Install-time (normal) | Model downloads through the Network Consent Gateway only | Android shows no prompt for it; ASTRA asks per request in its own consent dialog. |
| `ACCESS_NETWORK_STATE` | Install-time (normal) | Tell "device offline" apart from "declined" without sending traffic, so the user is not re-prompted | Never prompts. |
| `USE_BIOMETRIC` | Install-time (normal) | Optional app lock | Used only if the user enables App lock. |

**Not requested (verified by `NetworkBypassScanTest.manifestIsPrivacySafe`):** contacts, SMS, call logs,
location, phone state, `MANAGE_EXTERNAL_STORAGE`, legacy storage, media-audio read access (files are opened
through the Storage Access Framework one at a time), notification access, accessibility services, device
admin, boot-completed receivers, foreground services, `QUERY_ALL_PACKAGES`.

**Package visibility (`<queries>`):** the recognition service and TTS service intents, so the app can see
whether an on-device recognizer / TTS engine exists. No other apps are enumerated.

**Merged-manifest note:** AndroidX libraries add an `androidx.startup.InitializationProvider` (used by
`ProcessLifecycleOwner`) and `androidx.profileinstaller`; neither performs network I/O. Check the merged
manifest in Android Studio (*Merged Manifest* tab) after the first build.
