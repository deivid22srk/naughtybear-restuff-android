#!/usr/bin/env bash
# Build nativo Android do ReStuff:
#   [1] shaderc para Android (arm64-v8a, estático) — GLSL→SPIR-V em runtime
#   [2] librestuff.so (código recompilado + fontes do restuff + SDK + JNI)
#   [3] cópia para app/src/main/jniLibs/arm64-v8a
#
# Requer: ANDROID_NDK_HOME (ou NDK via ANDROID_HOME/ndk/<ver>), cmake, ninja.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESTUFF_SRC="${RESTUFF_SRC:-$ROOT/restuff}"
REXSDK_SRC="${REXSDK_SRC:-$ROOT/rexglue-sdk}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"
JNILIBS_DIR="$ROOT/app/src/main/jniLibs/$ABI"

# --- Localizar o NDK ------------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1)
    elif [[ -d "$HOME/Android/Sdk/ndk" ]]; then
        NDK_DIR=$(ls -d "$HOME"/Android/Sdk/ndk/* | sort | tail -1)
    else
        echo "ANDROID_NDK_HOME não definido e nenhum NDK encontrado" >&2
        exit 1
    fi
else
    NDK_DIR="$ANDROID_NDK_HOME"
fi
export ANDROID_NDK_HOME="$NDK_DIR"
TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
echo "NDK: $NDK_DIR"

BUILD_OUT="$ROOT/build/android/$ABI"
mkdir -p "$BUILD_OUT" "$JNILIBS_DIR"

# --- [1] shaderc (cross Android) ------------------------------------------
SHADERC_SRC="${SHADERC_SRC:-$ROOT/thirdparty-src/shaderc}"
SHADERC_BUILD="$ROOT/build/shaderc-android"
SHADERC_INSTALL="$ROOT/build/shaderc-install-$ABI"
if [[ ! -d "$SHADERC_SRC" ]]; then
    echo "== shaderc: clonando (com glslang/spirv-tools) =="
    mkdir -p "$(dirname "$SHADERC_SRC")"
    git clone --depth 1 --branch "${SHADERC_TAG:-v2026.3}" \
        https://github.com/google/shaderc.git "$SHADERC_SRC"
    (cd "$SHADERC_SRC" && ./utils/git-sync-deps)
fi
if [[ ! -f "$SHADERC_INSTALL/lib/libshaderc.a" ]]; then
    echo "== shaderc: build Android ($ABI) =="
    cmake -S "$SHADERC_SRC" -B "$SHADERC_BUILD" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DSHADERC_SKIP_TESTS=ON \
        -DSHADERC_SKIP_EXAMPLES=ON \
        -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
        -DSPIRV_SKIP_EXECUTABLES=ON \
        -DENABLE_GLSLANG_BINARIES=OFF
    cmake --build "$SHADERC_BUILD" --target shaderc --parallel "$(nproc)"
    mkdir -p "$SHADERC_INSTALL/lib" "$SHADERC_INSTALL/include"
    cp -r "$SHADERC_SRC/include/shaderc" "$SHADERC_INSTALL/include/"
    find "$SHADERC_BUILD" -name "*.a" -exec cp {} "$SHADERC_INSTALL/lib/" \;
else
    echo "shaderc Android já construído — pulando"
fi

# --- [1b] glslc do HOST (compila os shaders draw2d embutidos) --------------
GLSLC_HOST_DIR="$ROOT/build/glslc-host"
GLSLC_HOST="$GLSLC_HOST_DIR/glslc"
if [[ ! -x "$GLSLC_HOST" ]]; then
    echo "== shaderc: glslc do host =="
    cmake -S "$SHADERC_SRC" -B "$ROOT/build/shaderc-host" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DSHADERC_SKIP_TESTS=ON \
        -DSHADERC_SKIP_EXAMPLES=ON \
        -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
        -DSPIRV_SKIP_EXECUTABLES=ON \
        -DENABLE_GLSLANG_BINARIES=ON
    cmake --build "$ROOT/build/shaderc-host" --target glslc --parallel "$(nproc)"
    mkdir -p "$GLSLC_HOST_DIR"
    find "$ROOT/build/shaderc-host" -name "glslc" -type f -exec cp {} "$GLSLC_HOST_DIR/" \;
    chmod +x "$GLSLC_HOST"
fi

# --- [2] librestuff.so -----------------------------------------------------
echo "== librestuff.so ($ABI) =="
cmake -S "$ROOT/native" -B "$BUILD_OUT" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE="${CONFIGURATION:-Release}" \
    -DRESTUFF_SOURCE_DIR="$RESTUFF_SRC" \
    -DREXSDK_SOURCE_DIR="$REXSDK_SRC" \
    -DSHADERC_ANDROID_ROOT="$SHADERC_INSTALL" \
    -DGLSLC_EXECUTABLE="$GLSLC_HOST"
cmake --build "$BUILD_OUT" --parallel "$(nproc)"

# --- [3] Empacotar jniLibs -------------------------------------------------
cp "$BUILD_OUT/librestuff.so" "$JNILIBS_DIR/"
# O SDK constrói rexruntime como SHARED — entra no APK também.
if [[ -f "$BUILD_OUT/librexruntime.so" ]]; then
    cp "$BUILD_OUT/librexruntime.so" "$JNILIBS_DIR/"
fi
ls -la "$JNILIBS_DIR/"
echo "Build nativo Android concluído."
