# restuff-android — build nativo do ReStuff para Android (arm64-v8a)
#
# Consome:
#   -DRESTUFF_SOURCE_DIR=<NaughtyBear_ReStuff checkout>
#   -DREXSDK_SOURCE_DIR=<rexglue-sdk checkout, com overlays aplicados>
#   -DSHADERC_ANDROID_ROOT=<shaderc cross-build (contém include/ e lib/)>
#   -DGLSLC_EXECUTABLE=<glslc do host (para os shaders embutidos draw2d)>
#
# O código recompilado (generated/default/) é produzido ANTES deste build pelo
# CLI rexglue no host (scripts/build_host_codegen.sh) e entra via
# generated/default/sources.cmake.

cmake_minimum_required(VERSION 3.25)
project(restuff_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(RESTUFF_SOURCE_DIR "" CACHE PATH "Source tree of NaughtyBear_ReStuff")
set(REXSDK_SOURCE_DIR "" CACHE PATH "Source tree of rexglue-sdk")
set(SHADERC_ANDROID_ROOT "" CACHE PATH "shaderc install prefix for Android")
set(GLSLC_EXECUTABLE "" CACHE FILEPATH "Host glslc binary")

if(NOT RESTUFF_SOURCE_DIR OR NOT REXSDK_SOURCE_DIR)
    message(FATAL_ERROR "RESTUFF_SOURCE_DIR and REXSDK_SOURCE_DIR are required")
endif()

# ----------------------------------------------------------------------
# SDK (rexglue) consumido por add_subdirectory — mesmo contrato do
# find_package(rexglue) usado pelo generated/rexglue.cmake upstream.
# ----------------------------------------------------------------------
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
add_subdirectory("${REXSDK_SOURCE_DIR}" rexglue-sdk)

# ----------------------------------------------------------------------
# Código recompilado (produzido no host antes deste build)
# ----------------------------------------------------------------------
set(GENERATED_DIR "${RESTUFF_SOURCE_DIR}/generated/default")
set(GENERATED_SOURCES_CMAKE "${GENERATED_DIR}/sources.cmake")
if(NOT EXISTS "${GENERATED_SOURCES_CMAKE}")
    message(FATAL_ERROR
        "${GENERATED_SOURCES_CMAKE} not found. Run the host codegen first "
        "(scripts/build_host_codegen.sh downloads Default.xex and runs "
        "'rexglue codegen').")
endif()
include("${GENERATED_SOURCES_CMAKE}")

# ----------------------------------------------------------------------
# Fontes do restuff (mesma lista do CMakeLists desktop, menos rex_crt_shim
# que é Windows-only)
# ----------------------------------------------------------------------
set(RESTUFF_SOURCES
    "${RESTUFF_SOURCE_DIR}/src/main.cpp"
    "${RESTUFF_SOURCE_DIR}/src/hooks.cpp"
    "${RESTUFF_SOURCE_DIR}/src/native_vk.cpp"
    "${RESTUFF_SOURCE_DIR}/src/renderer/native_backend_vk.cpp"
    "${RESTUFF_SOURCE_DIR}/src/renderer/guest_d3d_hooks.cpp"
    "${RESTUFF_SOURCE_DIR}/src/renderer/guest_texture_decode.cpp"
    "${RESTUFF_SOURCE_DIR}/src/renderer/ucode_translator.cpp"
    "${RESTUFF_SOURCE_DIR}/src/renderer/shader_pipeline.cpp"
)

# ----------------------------------------------------------------------
# Ponte JNI/Android
# ----------------------------------------------------------------------
set(ANDROID_JNI_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/android_main.cpp"
    # main_android.cpp (hooks embedder) vive no target rexruntime —
    # ver native/overlay/rexglue-sdk/src/system/CMakeLists.txt.
)

# ----------------------------------------------------------------------
# Shaders draw2d embutidos (mesma receita do CMakeLists desktop)
# ----------------------------------------------------------------------
find_program(GLSLC_PROGRAM glslc HINTS "${GLSLC_EXECUTABLE}" "${GLSLC_EXECUTABLE}/..")
if(NOT GLSLC_PROGRAM)
    set(GLSLC_PROGRAM "${GLSLC_EXECUTABLE}")
endif()
if(NOT GLSLC_PROGRAM OR NOT EXISTS "${GLSLC_PROGRAM}")
    message(FATAL_ERROR "glslc not found — set GLSLC_EXECUTABLE (host build)")
endif()

set(RESTUFF_SHADER_GEN ${CMAKE_CURRENT_BINARY_DIR}/shaders)
file(MAKE_DIRECTORY ${RESTUFF_SHADER_GEN})
set(RESTUFF_SHADER_HEADERS "")
function(restuff_embed_shader SHADER_FILE VAR)
    set(_src "${RESTUFF_SOURCE_DIR}/src/shaders/${SHADER_FILE}")
    set(_spv "${RESTUFF_SHADER_GEN}/${SHADER_FILE}.spv")
    set(_hdr "${RESTUFF_SHADER_GEN}/${SHADER_FILE}.spv.h")
    add_custom_command(OUTPUT ${_hdr}
        COMMAND "${GLSLC_PROGRAM}" -O -o ${_spv} ${_src}
        COMMAND ${CMAKE_COMMAND} -DSPV=${_spv} -DOUT=${_hdr} -DVAR=${VAR}
                -P "${RESTUFF_SOURCE_DIR}/cmake/embed_spv.cmake"
        DEPENDS ${_src} "${RESTUFF_SOURCE_DIR}/cmake/embed_spv.cmake"
        COMMENT "glslc ${SHADER_FILE} -> ${VAR}"
        VERBATIM)
    set(RESTUFF_SHADER_HEADERS ${RESTUFF_SHADER_HEADERS} ${_hdr} PARENT_SCOPE)
endfunction()
restuff_embed_shader(draw2d.vert kDraw2DVS)
restuff_embed_shader(draw2d_quad.frag kDraw2DQuadFS)
restuff_embed_shader(draw2d_text.frag kDraw2DTextFS)
add_custom_target(restuff_shaders DEPENDS ${RESTUFF_SHADER_HEADERS})

# ----------------------------------------------------------------------
# librestuff.so — motor completo (SDL3 estático entra via rex::ui)
# ----------------------------------------------------------------------
add_library(restuff SHARED
    ${GENERATED_SOURCES}
    ${RESTUFF_SOURCES}
    ${ANDROID_JNI_SOURCES}
)
add_dependencies(restuff restuff_shaders)

target_include_directories(restuff PRIVATE
    "${RESTUFF_SOURCE_DIR}"
    "${RESTUFF_SOURCE_DIR}/src"
    "${GENERATED_DIR}"
    "${RESTUFF_SHADER_GEN}"
)

# Opções de compilação do código recompilado — mesmo conjunto do
# generated/rexglue.cmake upstream (Android: sem -fasync-exceptions, que é
# só para SEH Windows; SIMDE emite NEON no arm64).
target_compile_options(restuff PRIVATE
    $<$<CXX_COMPILER_ID:Clang,AppleClang,GNU>:-g0>
    -Wno-unused-parameter
    -Wno-unused-variable
)

# rex::runtime traz rexcore/rexfilesystem/rexui/rexaudio/rexinput (e SDL3).
target_link_libraries(restuff PRIVATE
    rex::runtime
    android
    log
)

# ----------------------------------------------------------------------
# shaderc (runtime GLSL→SPIR-V do shader_pipeline.cpp)
# ----------------------------------------------------------------------
if(SHADERC_ANDROID_ROOT AND EXISTS "${SHADERC_ANDROID_ROOT}/include/shaderc/shaderc.h")
    target_include_directories(restuff PRIVATE "${SHADERC_ANDROID_ROOT}/include")
    target_link_directories(restuff PRIVATE "${SHADERC_ANDROID_ROOT}/lib")
    target_link_libraries(restuff PRIVATE shaderc)
else()
    message(FATAL_ERROR
        "shaderc for Android not found at SHADERC_ANDROID_ROOT "
        "(scripts/build_android_native.sh builds it)")
endif()

# NOTA: NÃO usar -Wl,--exclude-libs: os símbolos JNI do SDL3 estático
# (Java_org_libsdl_app_*) precisam permanecer exportados em librestuff.so
# para o bind das classes Java org.libsdl.app.
