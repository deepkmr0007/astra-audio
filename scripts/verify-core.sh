#!/usr/bin/env bash
# Offline verification of the pure-Kotlin core modules.
#
# Compiles core/* main + test sources and runs every JUnit 4 test, WITHOUT the
# Android SDK and WITHOUT network. Uses `kotlinc` if it is on PATH, otherwise the
# Kotlin compiler that ships inside a local Gradle distribution (GRADLE_HOME or
# /opt/gradle*). The real build (./gradlew test) runs the same tests; this script
# exists so the privacy-critical logic can be checked on a machine with no SDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/build/verify-core}"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/test-classes"

find_gradle_lib() {
  for d in "${GRADLE_HOME:-}" /opt/gradle /opt/gradle-*; do
    [ -n "$d" ] && [ -d "$d/lib" ] && ls "$d"/lib/kotlin-compiler-embeddable-*.jar >/dev/null 2>&1 && { echo "$d/lib"; return 0; }
  done
  return 1
}

LIB="$(find_gradle_lib || true)"
if [ -z "$LIB" ] && ! command -v kotlinc >/dev/null; then
  echo "No kotlinc on PATH and no Gradle distribution with an embedded Kotlin compiler found." >&2
  exit 2
fi

jar_of() { ls "$LIB"/$1 "$LIB"/plugins/$1 2>/dev/null | head -1 || true; }

STDLIB="$(jar_of 'kotlin-stdlib-[0-9]*.jar')"
COROUTINES="$(jar_of 'kotlinx-coroutines-core-jvm-*.jar')"
JUNIT="$(jar_of 'junit-4*.jar')"
HAMCREST="$(jar_of 'hamcrest-core-*.jar')"
[ -z "$HAMCREST" ] && HAMCREST="$(jar_of 'hamcrest-*.jar')"

kc() {
  if command -v kotlinc >/dev/null; then
    kotlinc "$@"
  else
    local cp
    cp="$(jar_of 'kotlin-compiler-embeddable-*.jar'):$STDLIB:$(jar_of 'kotlin-script-runtime-*.jar'):$(jar_of 'kotlin-reflect-*.jar'):$(jar_of 'kotlin-daemon-embeddable-*.jar'):$(jar_of 'trove4j-*.jar'):$(jar_of 'annotations-[0-9]*.jar'):$COROUTINES"
    local rc=0
    java -Xmx2g -cp "$cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib "$@" > "$OUT/kotlinc.log" 2>&1 || rc=$?
    grep -v "^Picked up JAVA_TOOL_OPTIONS" "$OUT/kotlinc.log" || true
    return $rc
  fi
}

MODULES=(common privacy text transcript audio security models)
MAIN_SRC=(); TEST_SRC=()
for m in "${MODULES[@]}"; do
  while IFS= read -r f; do MAIN_SRC+=("$f"); done < <(find "$ROOT/core/$m/src/main/kotlin" -name '*.kt' | sort)
  if [ -d "$ROOT/core/$m/src/test/kotlin" ]; then
    while IFS= read -r f; do TEST_SRC+=("$f"); done < <(find "$ROOT/core/$m/src/test/kotlin" -name '*.kt' | sort)
  fi
done

echo "== Compiling ${#MAIN_SRC[@]} main sources"
kc -jvm-target 17 -module-name astra-core -classpath "$STDLIB:$COROUTINES" -d "$OUT/classes" "${MAIN_SRC[@]}"

[ "${#TEST_SRC[@]}" -eq 0 ] && { echo "No tests found"; exit 0; }
echo "== Compiling ${#TEST_SRC[@]} test sources"
kc -jvm-target 17 -module-name astra-core -Xfriend-paths="$OUT/classes" -classpath "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$OUT/classes" -d "$OUT/test-classes" "${TEST_SRC[@]}"

# The source-scan test needs to know where the repository is.
export ASTRA_REPO_ROOT="$ROOT"

TEST_CLASSES=$(cd "$OUT/test-classes" && find . -name '*Test.class' ! -name '*$*' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
echo "== Running $(echo "$TEST_CLASSES" | wc -l) test classes"
java -Xshare:auto -cp "$STDLIB:$COROUTINES:$JUNIT:$HAMCREST:$OUT/classes:$OUT/test-classes" -Dastra.repo.root="$ROOT" \
  org.junit.runner.JUnitCore $TEST_CLASSES
