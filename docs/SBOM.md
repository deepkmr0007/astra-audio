# Software bill of materials & dependency / licence report

`docs/sbom.cdx.json` (CycloneDX 1.5) lists every **direct** dependency with its pinned version and licence.
It was written by hand from `gradle/libs.versions.toml` because the authoring environment could not resolve
Maven dependencies. For a full transitive SBOM, add the CycloneDX Gradle plugin and run `./gradlew cyclonedxBom`.

| Component | Version | Licence | Purpose |
|---|---|---|---|
| whisper.cpp + ggml (vendored, pruned to CPU) | v1.9.5 · d1be6fde | MIT | Speech recognition |
| OpenAI Whisper model weights (downloaded/imported by the user, not shipped) | — | MIT | Speech models |
| Kotlin stdlib, kotlinx.coroutines | 2.1.0 · 1.9.0 | Apache-2.0 | Language/runtime |
| AndroidX core, lifecycle, activity, fragment, biometric | see SBOM | Apache-2.0 | Platform integration |
| Jetpack Compose (BOM 2024.12.01): ui, foundation, material3, icons-core | BOM | Apache-2.0 | UI |

**Deliberately absent:** analytics, crash reporting, ads, Firebase, Google Play services, ML Kit, OkHttp/Retrofit,
WorkManager, WebView-based UI. `NetworkBypassScanTest.buildHasNoTelemetryDependencies` enforces this.

**Vulnerability review:** no known-vulnerable versions were knowingly chosen, but an online CVE lookup was
not possible in the authoring environment. Run `./gradlew dependencyUpdates` / GitHub Dependabot and review
ggml/whisper.cpp security advisories before release. The native library only parses local model files and
PCM buffers the app produced itself; untrusted audio is parsed by Android's own media codecs.
