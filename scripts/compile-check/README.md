# Compile-check harness (verification tool, not part of the app)

Type-checks every Kotlin file of `:app`, `:engine-whisper` and `core/*` against a real Android platform jar
(`android.jar`, API 35) on a machine that has no Android Gradle Plugin or Google Maven access.

* `stubs/` declares the small subset of AndroidX / Jetpack Compose APIs the app uses, with the real names,
  parameter names and defaults, so wrong arguments, missing imports and type errors are reported.
* It cannot check Compose-compiler rules, resources, manifest merging or lint — the real Gradle build does.
* Usage: `scripts/compile-check/compile-app.sh /path/to/android.jar`
