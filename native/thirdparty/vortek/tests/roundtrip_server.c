/*
 * TU SERVIDOR do round-trip test — compila com -DVT_SERVER=1 contra a árvore
 * do servidor (native/thirdparty/vortekrenderer/include), com os stubs de
 * jni.h/android/log.h/android/hardware_buffer.h (tests/hoststub). Reproduz o
 * que os vt_handle_* fazem: unserializa com ponteiros para locais
 * ((VkDevice)&deviceId) e VT_SERIALIZE_CMD para as respostas.
 */

#include "vortek_serializer.h"
#include "roundtrip_shared.h"

#include <stdlib.h>
#include <string.h>

static int rt_pool_init(MemoryPool* pool) {
    memset(pool, 0, sizeof(*pool));
    pool->data = malloc(MEMORY_POOL_MAX_SIZE);
    if (!pool->data) return -1;
    memset(pool->data, 0, MEMORY_POOL_MAX_SIZE);
    return 0;
}

static void rt_pool_free(MemoryPool* pool) {
    vt_free(pool);
    free(pool->data);
    pool->data = NULL;
}

int rt_server_parse_surface_formats(RtBuffer* in, uint64_t* pd_id,
                                    uint64_t* surface_id, uint32_t* count) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    uint32_t c = *count;
    vt_unserialize_vkGetPhysicalDeviceSurfaceFormatsKHR(
        (VkPhysicalDevice)pd_id, (VkSurfaceKHR)surface_id, &c, NULL, in->data, &pool);
    *count = c;

    rt_pool_free(&pool);
    return 0;
}

int rt_server_parse_present_modes(RtBuffer* in, uint64_t* pd_id,
                                  uint64_t* surface_id, uint32_t* count) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    uint32_t c = *count;
    vt_unserialize_vkGetPhysicalDeviceSurfacePresentModesKHR(
        (VkPhysicalDevice)pd_id, (VkSurfaceKHR)surface_id, &c, NULL, in->data, &pool);
    *count = c;

    rt_pool_free(&pool);
    return 0;
}

int rt_server_parse_acquire2(RtBuffer* in, uint64_t* device_id, uint64_t* swapchain_id,
                             uint64_t* semaphore_id, uint64_t* fence_id,
                             uint64_t* timeout, uint32_t* device_mask) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    VkAcquireNextImageInfoKHR acquireInfo;
    memset(&acquireInfo, 0, sizeof(acquireInfo));
    acquireInfo.sType = VK_STRUCTURE_TYPE_ACQUIRE_NEXT_IMAGE_INFO_KHR;
    acquireInfo.swapchain = (VkSwapchainKHR)swapchain_id;    // fake handle → escreve AQUI
    acquireInfo.semaphore = (VkSemaphore)semaphore_id;
    acquireInfo.fence = (VkFence)fence_id;

    vt_unserialize_vkAcquireNextImage2KHR(
        (VkDevice)device_id, &acquireInfo, NULL, in->data, &pool);

    // No servidor, os membros do struct recebem os ids (= handles do host).
    *swapchain_id = (uint64_t)acquireInfo.swapchain;
    *semaphore_id = (uint64_t)acquireInfo.semaphore;
    *fence_id = (uint64_t)acquireInfo.fence;
    *timeout = acquireInfo.timeout;
    *device_mask = acquireInfo.deviceMask;

    rt_pool_free(&pool);
    return 0;
}

int rt_server_parse_create_android_surface(RtBuffer* in, uint64_t* instance_id,
                                           uint64_t* window, uint32_t* flags) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    vt_unserialize_vkCreateAndroidSurfaceKHR(
        (VkInstance)instance_id, window, flags, in->data, &pool);

    rt_pool_free(&pool);
    return 0;
}

int rt_server_parse_destroy_surface(RtBuffer* in, uint64_t* instance_id,
                                    uint64_t* surface_id) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    vt_unserialize_vkDestroySurfaceKHR(
        (VkInstance)instance_id, (VkSurfaceKHR)surface_id, NULL, in->data, &pool);

    rt_pool_free(&pool);
    return 0;
}

int rt_server_serialize_surface_response(uint64_t surface_handle, RtBuffer* out) {
    MemoryPool pool;
    if (rt_pool_init(&pool) != 0) return -1;

    int bufferSize = vt_sizeof_VkSurfaceKHR((VkSurfaceKHR)surface_handle);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) {
        rt_pool_free(&pool);
        return -1;
    }
    vt_serialize_VkSurfaceKHR((VkSurfaceKHR)surface_handle, out->data);
    out->size = bufferSize;

    rt_pool_free(&pool);
    return 0;
}
