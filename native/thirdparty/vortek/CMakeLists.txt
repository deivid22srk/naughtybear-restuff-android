cmake_minimum_required(VERSION 3.22.1)

project(Vortek C)
message("Building ${PROJECT_NAME} (client, android-surface passthrough port)")

set(WARNS "-Wall -Wno-discarded-qualifiers")
# VK_USE_PLATFORM_ANDROID_KHR: habilita vkCreateAndroidSurfaceKHR no headers do
# NDK (o modo Xlib do upstream não existe num app Android puro).
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 ${WARNS} -DVK_USE_PLATFORM_ANDROID_KHR")

# Headers Vulkan BUNDLED em include/vulkan/ + include/vk_video/ (Khronos,
# Apache-2.0 — ver include/vulkan/LICENSE-HEADERS.txt): os do sysroot do NDK
# (r27) são mais antigos que os exigidos pelo vortek_serializer.h upstream
# (ex.: VK_EXT_map_memory_placed). O -Iinclude resolve <vulkan/vulkan.h> para
# a cópia bundled ANTES do sysroot — mesma cópia que o vortekrenderer usa.
include_directories(include)

add_library(vulkan_vortek SHARED
            src/main.c
            src/vulkan_calls.c
            src/vk_object.c
            src/vk_object_pool.c
            src/arrays.c
            src/descriptor_update_template.c
            src/ring_buffer.c)

# log: __android_log_print usado em modo VT_SERVER; mantido porque o cliente
# pode ser buildado com -DVT_SERVER em debug. android: liblog/libandroid.
target_link_libraries(vulkan_vortek PRIVATE log)
