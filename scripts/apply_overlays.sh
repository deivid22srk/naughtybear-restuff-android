#!/usr/bin/env bash
# Aplica os overlays do port sobre as árvores dos submódulos:
#   - rexglue-sdk: surface_android (NOVOS arquivos), branch Android no
#     window_sdl.cpp/ui CMake (substituição), deps android/log
#   - NaughtyBear_ReStuff: gating __ANDROID__ em main.cpp/restuff_app.h
#
# Idempotente e determinístico: cada arquivo overlay substitui o original
# na árvore do submódulo (SHAs fixados, zero drift). Arquivos marcados como
# NOVOS são apenas copiados.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="$ROOT/native/overlay"

replace() {
    local src="$1" dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "overlay faltando: $src" >&2
        exit 1
    fi
    if [[ ! -f "$dst" ]]; then
        echo "arquivo original faltando: $dst (submódulo inicializado?)" >&2
        exit 1
    fi
    cp "$src" "$dst"
    echo "  replaced: ${dst#$ROOT/}"
}

addnew() {
    local src="$1" dst="$2"
    if [[ ! -f "$src" ]]; then
        echo "overlay faltando: $src" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    echo "  added:    ${dst#$ROOT/}"
}

echo "Aplicando overlays do port…"

# --- rexglue-sdk: substituições ------------------------------------------
for rel in \
    src/ui/window_sdl.cpp \
    src/ui/CMakeLists.txt \
    cmake/rex_pch.cmake
do
    replace "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- rexglue-sdk: arquivos novos (suporte Android) ------------------------
for rel in \
    include/rex/ui/surface_android.h \
    src/ui/surface_android.cpp
do
    addnew "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- NaughtyBear_ReStuff: substituições -----------------------------------
for rel in \
    src/main.cpp \
    src/restuff_app.h
do
    replace "$OVERLAY/NaughtyBear_ReStuff/$rel" "$ROOT/restuff/$rel"
done

echo "Overlays aplicados com sucesso."
