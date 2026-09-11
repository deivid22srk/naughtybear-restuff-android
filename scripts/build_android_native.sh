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
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -DSHADERC_SKIP_TESTS=ON \
        -DSHADERC_SKIP_EXAMPLES=ON \
        -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
        -DSPIRV_SKIP_EXECUTABLES=ON \
        -DENABLE_GLSLANG_BINARIES=OFF
    cmake --build "$SHADERC_BUILD" --target shaderc --parallel "$(nproc)"
    mkdir -p "$SHADERC_INSTALL/lib" "$SHADERC_INSTALL/include"
    # Layout antigo: include/shaderc/ na raiz; v2026.3+: libshaderc/include/shaderc/
    SHADERC_INC_DIR="$SHADERC_SRC/include"
    if [[ ! -d "$SHADERC_INC_DIR/shaderc" ]]; then
        SHADERC_INC_DIR="$SHADERC_SRC/libshaderc/include"
    fi
    cp -r "$SHADERC_INC_DIR/shaderc" "$SHADERC_INSTALL/include/"
    find "$SHADERC_BUILD" -name "*.a" -exec cp {} "$SHADERC_INSTALL/lib/" \;
else
    echo "shaderc Android já construído — pulando"
fi

# --- [1b] glslc do HOST (compila os shaders draw2d embutidos) --------------
GLSLC_HOST_DIR="$ROOT/build/glslc-host"
GLSLC_HOST="$GLSLC_HOST_DIR/glslc"
if [[ ! -x "$GLSLC_HOST" ]]; then
    echo "== shaderc: glslc do host =="
    # No v2026.3 o executável é o target 'glslc_exe' (OUTPUT_NAME glslc) e só
    # existe com SHADERC_ENABLE_EXECUTABLES=ON (SPIRV_SKIP_EXECUTABLES=ON o
    # desliga — o cache var explícito sobrevive ao if() do shaderc).
    cmake -S "$SHADERC_SRC" -B "$ROOT/build/shaderc-host" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DSHADERC_SKIP_TESTS=ON \
        -DSHADERC_SKIP_EXAMPLES=ON \
        -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
        -DSPIRV_SKIP_EXECUTABLES=ON \
        -DSHADERC_ENABLE_EXECUTABLES=ON \
        -DENABLE_GLSLANG_BINARIES=ON
    cmake --build "$ROOT/build/shaderc-host" --target glslc_exe --parallel "$(nproc)"
    mkdir -p "$GLSLC_HOST_DIR"
    GLSLC_BIN=$(find "$ROOT/build/shaderc-host" -type f -name "glslc" -perm -u+x 2>/dev/null | head -1)
    if [[ -z "$GLSLC_BIN" ]]; then
        echo "ERRO: executável glslc não encontrado após o build host" >&2
        exit 1
    fi
    cp "$GLSLC_BIN" "$GLSLC_HOST_DIR/"
    chmod +x "$GLSLC_HOST"
fi

# --- [1d] AdrenoTools (libadrenotools + hooks p/ driver Turnip custom) ------
# Motor do carregamento REAL do driver customizado: adrenotools_open_libvulkan
# devolve o handle do loader do sistema com hooks que redirecionam a abertura
# do driver para o .so importado (namespace ligado ao sphal, onde
# libcutils/libhardware resolvem). Requer:
#   - submódulo native/thirdparty/libadrenotools (com lib/linkernsbypass)
#   - useLegacyPackaging=true no APK (hooks como ARQUIVOS em nativeLibraryDir)
# API mínima 28 (linkernsbypass); em API < 28 o adrenotools devolve nullptr e
# o port cai no driver do sistema (fallback logado).
ADRENOTOOLS_SRC="${ADRENOTOOLS_SRC:-$ROOT/native/thirdparty/libadrenotools}"
if [[ ! -d "$ADRENOTOOLS_SRC/lib/linkernsbypass" ]]; then
    echo "ERRO: $ADRENOTOOLS_SRC sem lib/linkernsbypass (submódulos inicializados?)" >&2
    exit 1
fi
ADRENOTOOLS_BUILD="$ROOT/build/adrenotools-$ABI"
if [[ ! -f "$ADRENOTOOLS_BUILD/libadrenotools.so" ]]; then
    echo "== adrenotools: build Android ($ABI) =="
    # BUILD_SHARED_LIBS=ON: add_library(adrenotools) do upstream não declara
    # tipo → sem isso vira .a (e o --exclude-libs do upstream exige shared).
    # CMAKE_LIBRARY_OUTPUT_DIRECTORY: os 4 hooks vivem em src/hook/ sem isso —
    # unifica tudo na raiz do build p/ o loop de cópia abaixo.
    cmake -S "$ADRENOTOOLS_SRC" -B "$ADRENOTOOLS_BUILD" \
        -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-28" \
        -DANDROID_STL=c++_shared \
        -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$ADRENOTOOLS_BUILD" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$ADRENOTOOLS_BUILD" --parallel "$(nproc)"
fi
# libadrenotools + hooks (precisam existir COMO ARQUIVOS — useLegacyPackaging)
for adrlib in libadrenotools.so libmain_hook.so libhook_impl.so \
              libfile_redirect_hook.so libgsl_alloc_hook.so; do
    if [[ ! -f "$ADRENOTOOLS_BUILD/$adrlib" ]]; then
        echo "ERRO FATAL: $adrlib não construído (build adrenotools)" >&2
        exit 1
    fi
    cp "$ADRENOTOOLS_BUILD/$adrlib" "$JNILIBS_DIR/"
done
echo "  adrenotools: 5 libs copiadas para jniLibs"

# --- [2] librestuff.so -----------------------------------------------------
echo "== librestuff.so ($ABI) =="
cmake -S "$ROOT/native" -B "$BUILD_OUT" \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="${CONFIGURATION:-Release}" \
    -DRESTUFF_SOURCE_DIR="$RESTUFF_SRC" \
    -DREXSDK_SOURCE_DIR="$REXSDK_SRC" \
    -DSHADERC_ANDROID_ROOT="$SHADERC_INSTALL" \
    -DGLSLC_EXECUTABLE="$GLSLC_HOST"
cmake --build "$BUILD_OUT" --parallel "$(nproc)"

# --- [3] Empacotar jniLibs -------------------------------------------------
cp "$BUILD_OUT/librestuff.so" "$JNILIBS_DIR/"
# O SDK constrói rexruntime como SHARED e despeja binários em
# rexglue-sdk/out/<REX_PLATFORM>/ (no NDK a detecção do SDK não tem branch
# Android → vira "linux-arm64"). Buscar nos dois lugares:
REXRUNTIME=$(find "$REXSDK_SRC/out" "$BUILD_OUT" -name "librexruntime.so" -type f 2>/dev/null | head -1)
if [[ -n "$REXRUNTIME" ]]; then
    cp "$REXRUNTIME" "$JNILIBS_DIR/"
    echo "  librexruntime: $REXRUNTIME"
else
    echo "AVISO: librexruntime.so não encontrada" >&2
fi
# STL compartilhada: OBRIGATÓRIA com c++_shared (rexruntime SHARED e
# librestuff passam std::string/vector entre si — duas libc++ estáticas
# num mesmo processo = heaps duplicados = crash em tempo de execução).
# ⚠️ O sysroot do NDK usa o TRIPLE LLVM, não o nome do ABI Android:
#    arm64-v8a → aarch64-linux-android (procurar por "arm64-v8a" falha
#    silenciosamente e o APK sai sem libc++_shared.so → dlopen crash).
case "$ABI" in
    arm64-v8a)   TRIPLE="aarch64-linux-android" ;;
    armeabi-v7a) TRIPLE="armv7a-linux-androideabi" ;;
    x86_64)      TRIPLE="x86_64-linux-android" ;;
    *)           TRIPLE="i686-linux-android" ;;
esac
SYSROOT_LIB="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE"
LIBCXX_SHARED="$SYSROOT_LIB/libc++_shared.so"
if [[ ! -f "$LIBCXX_SHARED" ]]; then
    # Fallback: busca genérica pelo triple no prebuilt
    LIBCXX_SHARED=$(find "$NDK_DIR/toolchains/llvm/prebuilt" \
        -path "*/sysroot/usr/lib/$TRIPLE/libc++_shared.so" -type f 2>/dev/null | head -1)
fi
if [[ -z "$LIBCXX_SHARED" || ! -f "$LIBCXX_SHARED" ]]; then
    echo "ERRO FATAL: libc++_shared.so não encontrada no NDK (triple: $TRIPLE)" >&2
    echo "  Sem ela o APK crasha no device: dlopen failed: libc++_shared.so not found" >&2
    exit 1
fi
cp "$LIBCXX_SHARED" "$JNILIBS_DIR/"
echo "  libc++_shared: $LIBCXX_SHARED"
# Garantia final: a lib TEM que estar no jniLibs
test -f "$JNILIBS_DIR/libc++_shared.so" || { echo "ERRO FATAL: cp falhou" >&2; exit 1; }
ls -la "$JNILIBS_DIR/"
echo "Build nativo Android concluído."
