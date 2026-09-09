#!/usr/bin/env bash
# Build do CLI rexglue no HOST (linux-x64) + codegen do ReStuff.
#
# O codegen lê assets/Default.xex (baixado do release do portador) e gera
# generated/default/*.cpp — a fonte recompilada que o build Android consome.
#
# Entradas:
#   RESTUFF_SRC   (default: <repo>/restuff)
#   REXSDK_SRC    (default: <repo>/rexglue-sdk)
#   DEFAULT_XEX_URL  (default: release Archive de deivid22srk)
#   SKIP_CLI_BUILD=1  — reusa build-host existente
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESTUFF_SRC="${RESTUFF_SRC:-$ROOT/restuff}"
REXSDK_SRC="${REXSDK_SRC:-$ROOT/rexglue-sdk}"
BUILD_DIR="$ROOT/build/host-codegen"
DEFAULT_XEX_URL="${DEFAULT_XEX_URL:-https://github.com/deivid22srk/Naughty-Bear-archive/releases/download/Archive/Default.xex}"

echo "== [1/3] Default.xex =="
XEX_PATH="$RESTUFF_SRC/assets/Default.xex"
if [[ ! -f "$XEX_PATH" ]]; then
    mkdir -p "$RESTUFF_SRC/assets"
    echo "  baixando de $DEFAULT_XEX_URL"
    curl -L --fail --retry 3 -o "$XEX_PATH" "$DEFAULT_XEX_URL"
fi
ls -la "$XEX_PATH"

echo "== [2/3] Build do CLI rexglue (host) =="
if [[ "${SKIP_CLI_BUILD:-0}" != "1" || ! -d "$BUILD_DIR" ]]; then
    cmake -S "$REXSDK_SRC" -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DREXGLUE_BUILD_TESTS=OFF \
        -DREXGLUE_ENABLE_TRACY=OFF \
        -DREXGLUE_ENABLE_PERF_COUNTERS=OFF
fi
cmake --build "$BUILD_DIR" --target rexglue --parallel "$(nproc)"

echo "== [3/3] Codegen =="
# Não regenerar se já existe (build Android consome o mesmo output).
if [[ -f "$RESTUFF_SRC/generated/default/sources.cmake" && "${FORCE_CODEGEN:-0}" != "1" ]]; then
    echo "  codegen já presente — pulando (FORCE_CODEGEN=1 para regenerar)"
else
    "$BUILD_DIR/src/rexglue/rexglue" codegen "$RESTUFF_SRC/restuff_manifest.toml"
fi
ls "$RESTUFF_SRC/generated/default/" | head -8
echo "Codegen concluído."
