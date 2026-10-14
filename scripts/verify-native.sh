#!/usr/bin/env bash
# Builds the JNI bridge + vendored whisper.cpp for the HOST (Linux/macOS, needs cmake, ninja or make,
# a C++17 compiler and a JDK), then runs an end-to-end transcription through WhisperEngine on the JVM.
#
#   scripts/verify-native.sh <model.bin> <audio.wav> [cancel-after-ms]
#
# With whisper.cpp's tiny "for-tests" models this proves the native path loads, runs, reports
# progress, detects language, cancels and frees cleanly. With a real model (e.g. ggml-base.bin)
# it prints real transcripts. Run scripts/verify-core.sh first (it builds the core classes).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="${1:?model path}"; WAV="${2:?wav path}"; CANCEL="${3:-}"
NB="$ROOT/build/native-host"; OUT="$ROOT/build/native-smoke"
mkdir -p "$NB" "$OUT"
cmake -S "$ROOT/engine-whisper/src/main/cpp" -B "$NB" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$NB" --target astra_whisper -j"$(nproc 2>/dev/null || echo 4)" >/dev/null
echo "== native symbols that could reach the network (expect none):"
nm -D --undefined-only "$NB"/libastra_whisper.* | grep -Ei ' (socket|connect|getaddrinfo|gethostbyname|curl_)' || echo "   none"

[ -d "$ROOT/build/verify-core/classes" ] || "$ROOT/scripts/verify-core.sh"
LIB=""
for d in "${GRADLE_HOME:-}" /opt/gradle /opt/gradle-*; do [ -n "$d" ] && ls "$d"/lib/kotlin-compiler-embeddable-*.jar >/dev/null 2>&1 && { LIB="$d/lib"; break; }; done
j() { ls "$LIB"/$1 | head -1; }
STD="$(j 'kotlin-stdlib-[0-9]*.jar')"; CO="$(j 'kotlinx-coroutines-core-jvm-*.jar')"
KCP="$(j 'kotlin-compiler-embeddable-*.jar'):$STD:$(j 'kotlin-script-runtime-*.jar'):$(j 'kotlin-reflect-*.jar'):$(j 'kotlin-daemon-embeddable-*.jar'):$(j 'trove4j-*.jar'):$(j 'annotations-[0-9]*.jar'):$CO"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -jvm-target 17 \
  -classpath "$STD:$CO:$ROOT/build/verify-core/classes" -d "$OUT" \
  "$ROOT"/engine-whisper/src/main/kotlin/app/astra/engine/whisper/*.kt "$ROOT/scripts/native-smoke/NativeSmoke.kt" 2>&1 | grep -v '^Picked up' || true
java -Djava.library.path="$NB" -cp "$OUT:$ROOT/build/verify-core/classes:$STD:$CO" NativeSmokeKt "$MODEL" "$WAV" $CANCEL
