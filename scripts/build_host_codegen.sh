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

echo "== [0/3] Diagnóstico std::expected (evidência p/ run #5) =="
TMPD="$(mktemp -d)"
printf '#include <expected>\n#include <cstdio>\nint main(){ std::expected<int,int> e = 42; return e.value(); }\n' > "$TMPD/t.cpp"
PROBE_CC="${CXX:-clang++}"
echo "  [probe] usando: $PROBE_CC ($($PROBE_CC --version | head -1))"
if $PROBE_CC -std=c++23 -fsyntax-only "$TMPD/t.cpp" 2>"$TMPD/err.txt"; then
    echo "  [probe] std::expected OK sem PCH"
else
    echo "  [probe] std::expected FALHOU sem PCH:"; cat "$TMPD/err.txt"
fi
printf '#include <expected>\n' > "$TMPD/h.cpp"
echo "  [probe] resolução de <expected>:"
$PROBE_CC -std=c++23 -H -fsyntax-only "$TMPD/h.cpp" 2>&1 | grep -E "^\." | head -2 || true
echo "  [probe] macros relevantes:"
printf '#include <version>\n' > "$TMPD/v.cpp"
$PROBE_CC -std=c++23 -dM -E "$TMPD/v.cpp" 2>/dev/null | grep -E "__cplusplus|__cpp_concepts|__cpp_lib_expected|_GLIBCXX_RELEASE" || true
echo "  [probe] GCC installs: $(ls /usr/lib/gcc/x86_64-linux-gnu/ 2>/dev/null | tr '\n' ' ')"
rm -rf "$TMPD"

echo "== [1/3] Default.xex =="
XEX_PATH="$RESTUFF_SRC/assets/Default.xex"
if [[ ! -f "$XEX_PATH" ]]; then
    mkdir -p "$RESTUFF_SRC/assets"
    echo "  baixando de $DEFAULT_XEX_URL"
    curl -L --fail --retry 3 -o "$XEX_PATH" "$DEFAULT_XEX_URL"
fi
ls -la "$XEX_PATH"

echo "== [2/3] Build do CLI rexglue (host) =="
HOST_ARCH_FLAGS="-march=x86-64-v2"  # mesmo piso do preset linux-base do SDK
if [[ "${SKIP_CLI_BUILD:-0}" != "1" || ! -d "$BUILD_DIR" ]]; then
    cmake -S "$REXSDK_SRC" -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_STANDARD=11 \
        -DCMAKE_CXX_STANDARD=23 \
        -DCMAKE_C_FLAGS="$HOST_ARCH_FLAGS" \
        -DCMAKE_CXX_FLAGS="$HOST_ARCH_FLAGS" \
        -DREXGLUE_BUILD_TESTS=OFF \
        -DREXGLUE_ENABLE_TRACY=OFF \
        -DREXGLUE_ENABLE_PERF_COUNTERS=OFF
fi
cmake --build "$BUILD_DIR" --target rexglue --parallel "$(nproc)"

echo "== [3/3] Codegen =="
# O SDK redireciona RUNTIME_OUTPUT p/ out/<plataforma>-<arch>/ (não fica em
# build/host-codegen/src/rexglue). Resolver o binário de forma robusta:
REXGLUE_CLI=""
for cand in \
    "$REXSDK_SRC/out/linux-amd64/rexglue" \
    "$BUILD_DIR/src/rexglue/rexglue" \
    "$REXSDK_SRC/out/linux-$(uname -m | sed 's/x86_64/amd64/')/rexglue"
do
    if [[ -x "$cand" ]]; then REXGLUE_CLI="$cand"; break; fi
done
if [[ -z "$REXGLUE_CLI" ]]; then
    REXGLUE_CLI=$(find "$REXSDK_SRC/out" "$BUILD_DIR" -type f -name rexglue -perm -u+x 2>/dev/null | head -1)
fi
if [[ -z "$REXGLUE_CLI" ]]; then
    echo "ERRO: binário rexglue não encontrado após o build" >&2
    exit 1
fi
echo "  CLI: $REXGLUE_CLI"
# Não regenerar se já existe (build Android consome o mesmo output).
if [[ -f "$RESTUFF_SRC/generated/default/sources.cmake" && "${FORCE_CODEGEN:-0}" != "1" ]]; then
    echo "  codegen já presente — pulando (FORCE_CODEGEN=1 para regenerar)"
else
    "$REXGLUE_CLI" codegen "$RESTUFF_SRC/restuff_manifest.toml"
fi
ls "$RESTUFF_SRC/generated/default/" | head -8 || true
echo "Codegen concluído."
