#!/usr/bin/env bash
# Re-creates the vendored whisper.cpp sources (engine-whisper/src/main/cpp/whisper.cpp) from the
# pinned upstream release, verifying the exact commit, then prunes everything the app does not
# build (GPU backends, ggml-rpc with its socket code, examples, models, tests).
#
# The repository already contains the pruned sources, so this is only needed to audit or update them.
set -euo pipefail
TAG="v1.9.5"
COMMIT="d1be6fde11ac6e0407606b4e42fe72d34add8037"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/engine-whisper/src/main/cpp/whisper.cpp"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp "$TMP/src"
GOT="$(git -C "$TMP/src" rev-parse HEAD)"
if [ "$GOT" != "$COMMIT" ]; then
  echo "Commit mismatch: expected $COMMIT, got $GOT (tag moved?). Refusing to continue." >&2
  exit 1
fi
rm -rf "$DEST"; mkdir -p "$DEST"
cp -r "$TMP/src"/{CMakeLists.txt,LICENSE,cmake,include,src,ggml} "$DEST/"
cd "$DEST"
rm -rf ggml/src/{ggml-cuda,ggml-vulkan,ggml-sycl,ggml-opencl,ggml-metal,ggml-cann,ggml-hexagon,ggml-webgpu,ggml-openvino,ggml-et,ggml-virtgpu,ggml-musa,ggml-hip,ggml-zdnn,ggml-rpc,ggml-blas,ggml-kompute,ggml-zendnn}
rm -rf src/coreml src/openvino src/vitisai
echo "$TAG $COMMIT" > "$DEST/VENDORED_FROM"
echo "Vendored whisper.cpp $TAG ($COMMIT) into $DEST"
