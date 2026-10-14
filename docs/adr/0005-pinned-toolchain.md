# ADR 0005 — Pinned, conservative toolchain

**Status:** accepted

AGP 8.7.3, Kotlin 2.1.0 (Compose compiler plugin), Gradle 8.11.1 wrapper, Compose BOM 2024.12.01,
NDK 27.2.12479018, CMake 3.22.1, compileSdk/targetSdk 35, minSdk 26 (MediaDataSource needs 23;
26 gives adaptive icons and a large installed base). These are known-good together. Newer versions can
be adopted deliberately after a green CI run; dynamic versions are never used. The Gradle wrapper
`distributionSha256Sum` was not added because the distribution could not be downloaded in the authoring
environment to compute it — add it on first sync (see SETUP.md).
