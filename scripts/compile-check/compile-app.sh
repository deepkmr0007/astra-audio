#!/usr/bin/env bash
# Type-checks the Android app module WITHOUT the Android Gradle Plugin or Google Maven:
#   - android.jar (any API 35 platform jar; pass its path as $1 or ANDROID_JAR)
#   - the real core/* and engine-whisper Kotlin sources
#   - compile-check stubs for AndroidX/Compose (scripts/compile-check/stubs, mirror real signatures)
#   - a generated R / BuildConfig
# This catches unresolved references, wrong argument names and type errors in app code. It is NOT a
# substitute for the real Gradle build (no Compose compiler plugin, no resource linking, no lint).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_JAR="${1:-${ANDROID_JAR:-}}"
[ -f "$ANDROID_JAR" ] || { echo "usage: $0 path/to/android.jar" >&2; exit 2; }
OUT="$ROOT/build/compile-check"; rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

python3 - "$ROOT" "$OUT/gen" <<'PY'
import os, re, sys
root, gen = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(root, "scripts"))
from strings_source import S
with open(os.path.join(gen, "R.kt"), "w") as f:
    f.write("package app.astra.audiotext\n\nobject R {\n    object string {\n")
    for i, (k, _, _) in enumerate(S):
        f.write(f"        const val {k} = {0x7f0e0000 + i}\n")
    f.write("    }\n}\n")
with open(os.path.join(gen, "BuildConfig.kt"), "w") as f:
    f.write('package app.astra.audiotext\n\nobject BuildConfig {\n    const val VERSION_NAME = "1.0.0"\n    const val VERSION_CODE = 1\n    const val DEBUG = true\n}\n')
PY

LIB=""
for d in "${GRADLE_HOME:-}" /opt/gradle /opt/gradle-*; do [ -n "$d" ] && ls "$d"/lib/kotlin-compiler-embeddable-*.jar >/dev/null 2>&1 && { LIB="$d/lib"; break; }; done
j() { ls "$LIB"/$1 | head -1; }
STD="$(j 'kotlin-stdlib-[0-9]*.jar')"; CO="$(j 'kotlinx-coroutines-core-jvm-*.jar')"
KCP="$(j 'kotlin-compiler-embeddable-*.jar'):$STD:$(j 'kotlin-script-runtime-*.jar'):$(j 'kotlin-reflect-*.jar'):$(j 'kotlin-daemon-embeddable-*.jar'):$(j 'trove4j-*.jar'):$(j 'annotations-[0-9]*.jar'):$CO"

mapfile -t SRC < <(find "$ROOT/core" -path '*/src/main/kotlin/*' -name '*.kt'; \
                    find "$ROOT/engine-whisper/src/main/kotlin" -name '*.kt'; \
                    find "$ROOT/app/src/main/kotlin" -name '*.kt'; \
                    find "$ROOT/scripts/compile-check/stubs" -name '*.kt' -o -name '*.java' | grep -E '\.(kt|java)$'; \
                    echo "$OUT/gen/R.kt"; echo "$OUT/gen/BuildConfig.kt")
echo "== Type-checking ${#SRC[@]} files against $(basename "$ANDROID_JAR")"
rc=0
java -Xmx3g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -jvm-target 17 \
  -opt-in=kotlin.RequiresOptIn -Xjava-source-roots="$ROOT/scripts/compile-check/stubs/java" \
  -classpath "$ANDROID_JAR:$STD:$CO" -d "$OUT/classes" "${SRC[@]}" > "$OUT/log.txt" 2>&1 || rc=$?
grep -v '^Picked up' "$OUT/log.txt" | grep -E 'error:|warning:.*(deprecated|unused)' -A2 | head -${LINES_MAX:-400} || true
echo "== errors: $(grep -c 'error:' "$OUT/log.txt" || true)"
exit $rc
