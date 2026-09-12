#!/usr/bin/env bash
# Aplica os overlays do port sobre as árvores dos submódulos:
#   - rexglue-sdk: surface_android (NOVOS arquivos), branch Android no
#     window_sdl.cpp/ui CMake (substituição), deps android/log, dynlib
#     (Adopt/dlerror p/ AdrenoTools), vulkan_device (defaults mobile),
#     vulkan_instance (driver custom via libadrenotools), runtime/rex_app/
#     disc_image_device (boot in-place do ISO — modelo XenDroid)
#   - NaughtyBear_ReStuff: gating __ANDROID__ em main.cpp/restuff_app.h,
#     native_vk.cpp (fix do LoaderGdpa — vkCmd* via tabela da instance)
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
    cmake/rex_pch.cmake \
    include/rex/string/numeric.h \
    include/rex/chrono/chrono.h \
    src/core/timer_queue.cpp \
    src/core/CMakeLists.txt \
    include/rex/thread/fiber.h \
    src/core/memory_posix.cpp \
    src/core/threading_posix.cpp \
    src/core/filesystem_posix.cpp \
    src/core/logging.cpp \
    src/input/sdl/sdl_input_driver.cpp \
    src/ui/vulkan/vulkan_instance.cpp \
    src/core/dynlib_posix.cpp \
    include/rex/platform/dynlib.h \
    src/system/CMakeLists.txt \
    include/rex/ui/vulkan/device.h \
    include/rex/ui/vulkan/functions/device_1_0.inc \
    src/ui/vulkan/vulkan_device.cpp \
    src/ui/vulkan/vulkan_presenter.cpp \
    src/audio/xma_decoder.cpp \
    src/system/xthread.cpp \
    src/system/runtime.cpp \
    src/ui/rex_app.cpp \
    src/filesystem/devices/disc_image_device.cpp
do
    replace "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- rexglue-sdk: arquivos novos (suporte Android) ------------------------
for rel in \
    include/rex/ui/surface_android.h \
    src/ui/surface_android.cpp \
    src/core/fiber_android.cpp \
    include/rex/main_android.h \
    src/system/main_android.cpp \
    include/renderdoc/renderdoc_app.h
do
    addnew "$OVERLAY/rexglue-sdk/$rel" "$ROOT/rexglue-sdk/$rel"
done

# --- NaughtyBear_ReStuff: substituições -----------------------------------
for rel in \
    src/main.cpp \
    src/restuff_app.h \
    src/video_player.h \
    src/hooks.cpp \
    src/native_vk.cpp \
    src/renderer/guest_d3d_hooks.cpp \
    src/renderer/native_backend_vk.cpp \
    src/renderer/up_draws.h \
    src/renderer/texture_mods.cpp \
    src/renderer/texture_mods.h
do
    replace "$OVERLAY/NaughtyBear_ReStuff/$rel" "$ROOT/restuff/$rel"
done

echo "Overlays aplicados com sucesso."
