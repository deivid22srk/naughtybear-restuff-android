cmake_minimum_required(VERSION 3.22.1)

project(VortekRenderer C)
message("Building ${PROJECT_NAME} (android-surface passthrough port)")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wno-discarded-qualifiers -Wimplicit-function-declaration -DVK_USE_PLATFORM_ANDROID_KHR")

# Port Android (naughtybear-restuff-android): o adrenotools é o submódulo do
# próprio projeto (native/thirdparty/libadrenotools), construído em passo
# separado pelo scripts/build_android_native.sh (mesmo esquema do winlator-app,
# onde vortekrenderer linka ../libadrenotools). Aqui apontamos por caminho:
#   -DADRENOTOOLS_INCLUDE_DIR=<...>/native/thirdparty/libadrenotools/include
#   -DADRENOTOOLS_LIBRARY=<build>/libadrenotools.so
set(ADRENOTOOLS_INCLUDE_DIR "" CACHE PATH "libadrenotools include dir")
set(ADRENOTOOLS_LIBRARY "" CACHE FILEPATH "path to built libadrenotools.so")

if(NOT ADRENOTOOLS_INCLUDE_DIR OR NOT EXISTS "${ADRENOTOOLS_INCLUDE_DIR}/adrenotools/driver.h")
    message(FATAL_ERROR "ADRENOTOOLS_INCLUDE_DIR must point at libadrenotools/include (got: '${ADRENOTOOLS_INCLUDE_DIR}')")
endif()

include_directories(include)
include_directories(winlator/include)
include_directories(${ADRENOTOOLS_INCLUDE_DIR})

add_library(vortekrenderer SHARED
            src/main.c
            src/vk_object.c
            src/request_handler.c
            src/vulkan_helper.c
            src/vk_context.c
            src/resource_memory.c
            src/xwindow_swapchain.c
            src/texture_decoder.c
            src/shader_inspector.c
            src/async_pipeline_creator.c
            src/timeline_semaphore.c
            winlator/src/arrays.c
            winlator/src/ring_buffer.c
            winlator/src/sysvshared_memory.c)

if(ADRENOTOOLS_LIBRARY)
    target_link_libraries(vortekrenderer ${ADRENOTOOLS_LIBRARY} log android)
else()
    # Fallback: alvo adrenotools no mesmo tree (ex.: build integrado futuro)
    target_link_libraries(vortekrenderer adrenotools log android)
endif()
