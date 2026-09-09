#!/usr/bin/env bash
# Aplica os overlays do port sobre as árvores dos submódulos:
#   - rexglue-sdk: surface_android, branch Android no window_sdl.cpp/ui CMake
#   - NaughtyBear_ReStuff: gating __ANDROID__ em main.cpp/restuff_app.h
#
# Idempotente e determinístico: cada arquivo overlay substitui o original
# na árvore do submódulo (SHAs fixados, zero drift).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="$ROOT/native/overlay"

apply() {
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
    echo "  overlaid: ${dst#$ROOT/}"
}

echo "Aplicando overlays do port…"

# --- rexglue-sdk ---------------------------------------------------------
for rel in \
    include/rex/ui/surface_android.h \
    src/ui/surface_android.cpp \
    src/ui/window_sdl.cpp \
    src/ui/CMakeLists.txt
do
    apply "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- NaughtyBear_ReStuff -------------------------------------------------
for rel in \
    src/main.cpp \
    src/restuff_app.h
do
    apply "$OVERLAY/NaughtyBear_ReStuff/$rel" "$ROOT/restuff/$rel"
done

echo "Overlays aplicados com sucesso."
