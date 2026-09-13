/*
 * TU CLIENTE do round-trip test — compila SEM VT_SERVER, contra a árvore do
 * cliente (native/thirdparty/vortek/include). Reproduz exatamente o que os
 * vt_call_* fazem: handles top-level são ponteiros para o campo id
 * ((VkDevice)&object->id); membros de struct são handles de 16 bytes do
 * VkObject (convertidos dentro do serializer do struct).
 */

#include "vortek.h"
#include "vortek_serializer.h"
#include "roundtrip_shared.h"

#include <string.h>

int rt_client_serialize_surface_formats(uint64_t pd_id, uint64_t surface_id,
                                        uint32_t count, RtBuffer* out) {
    int bufferSize = vt_sizeof_vkGetPhysicalDeviceSurfaceFormatsKHR(
        (VkPhysicalDevice)&pd_id, (VkSurfaceKHR)&surface_id, &count, NULL);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkGetPhysicalDeviceSurfaceFormatsKHR(
        (VkPhysicalDevice)&pd_id, (VkSurfaceKHR)&surface_id, &count, NULL, out->data);
    out->size = bufferSize;
    return 0;
}

int rt_client_serialize_present_modes(uint64_t pd_id, uint64_t surface_id,
                                      uint32_t count, RtBuffer* out) {
    int bufferSize = vt_sizeof_vkGetPhysicalDeviceSurfacePresentModesKHR(
        (VkPhysicalDevice)&pd_id, (VkSurfaceKHR)&surface_id, &count, NULL);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkGetPhysicalDeviceSurfacePresentModesKHR(
        (VkPhysicalDevice)&pd_id, (VkSurfaceKHR)&surface_id, &count, NULL, out->data);
    out->size = bufferSize;
    return 0;
}

int rt_client_serialize_acquire2(uint64_t device_id, uint64_t swapchain_id,
                                 uint64_t semaphore_id, uint64_t fence_id,
                                 uint64_t timeout, uint32_t device_mask,
                                 RtBuffer* out) {
    VkObject* deviceObject = VkObject_create(VK_OBJECT_TYPE_DEVICE, device_id);
    VkObject* swapchainObject = VkObject_create(VK_OBJECT_TYPE_SWAPCHAIN_KHR, swapchain_id);
    VkObject* semaphoreObject = VkObject_create(VK_OBJECT_TYPE_SEMAPHORE, semaphore_id);
    VkObject* fenceObject = VkObject_create(VK_OBJECT_TYPE_FENCE, fence_id);
    if (!deviceObject || !swapchainObject || !semaphoreObject || !fenceObject) return -1;

    VkAcquireNextImageInfoKHR acquireInfo;
    memset(&acquireInfo, 0, sizeof(acquireInfo));
    acquireInfo.sType = VK_STRUCTURE_TYPE_ACQUIRE_NEXT_IMAGE_INFO_KHR;
    acquireInfo.swapchain = (VkSwapchainKHR)VkObject_toHandle(swapchainObject);
    acquireInfo.timeout = timeout;
    acquireInfo.semaphore = (VkSemaphore)VkObject_toHandle(semaphoreObject);
    acquireInfo.fence = (VkFence)VkObject_toHandle(fenceObject);
    acquireInfo.deviceMask = device_mask;

    int bufferSize = vt_sizeof_vkAcquireNextImage2KHR(
        (VkDevice)&deviceObject->id, &acquireInfo, NULL);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkAcquireNextImage2KHR(
        (VkDevice)&deviceObject->id, &acquireInfo, NULL, out->data);
    out->size = bufferSize;
    return 0;
}

int rt_client_serialize_create_android_surface(uint64_t instance_id, uint64_t window,
                                               uint32_t flags, RtBuffer* out) {
    int bufferSize = vt_sizeof_vkCreateAndroidSurfaceKHR(
        (VkInstance)&instance_id, window, flags);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkCreateAndroidSurfaceKHR(
        (VkInstance)&instance_id, window, flags, out->data);
    out->size = bufferSize;
    return 0;
}

int rt_client_serialize_destroy_surface(uint64_t instance_id, uint64_t surface_id,
                                        RtBuffer* out) {
    int bufferSize = vt_sizeof_vkDestroySurfaceKHR(
        (VkInstance)&instance_id, (VkSurfaceKHR)&surface_id, NULL);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkDestroySurfaceKHR(
        (VkInstance)&instance_id, (VkSurfaceKHR)&surface_id, NULL, out->data);
    out->size = bufferSize;
    return 0;
}

int rt_client_unserialize_surface_response(RtBuffer* in, uint64_t* surface_id) {
    MemoryPool pool;
    memset(&pool, 0, sizeof(pool));
    pool.data = malloc(MEMORY_POOL_MAX_SIZE);
    if (!pool.data) return -1;
    memset(pool.data, 0, MEMORY_POOL_MAX_SIZE);

    vt_unserialize_VkSurfaceKHR((VkSurfaceKHR)surface_id, in->data, &pool);
    free(pool.data);
    return 0;
}

int rt_client_serialize_queue_present(uint64_t queue_id, uint64_t swapchain_id,
                                      uint64_t semaphore_id, uint32_t image_index,
                                      RtBuffer* out) {
    VkObject* queueObject = VkObject_create(VK_OBJECT_TYPE_QUEUE, queue_id);
    VkObject* swapchainObject = VkObject_create(VK_OBJECT_TYPE_SWAPCHAIN_KHR, swapchain_id);
    VkObject* semaphoreObject = VkObject_create(VK_OBJECT_TYPE_SEMAPHORE, semaphore_id);
    if (!queueObject || !swapchainObject || !semaphoreObject) return -1;

    VkSemaphore waitSemaphores[1];
    waitSemaphores[0] = (VkSemaphore)VkObject_toHandle(semaphoreObject);
    VkSwapchainKHR swapchains[1];
    swapchains[0] = (VkSwapchainKHR)VkObject_toHandle(swapchainObject);
    uint32_t imageIndices[1];
    imageIndices[0] = image_index;

    VkPresentInfoKHR presentInfo;
    memset(&presentInfo, 0, sizeof(presentInfo));
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = waitSemaphores;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = swapchains;
    presentInfo.pImageIndices = imageIndices;
    presentInfo.pResults = NULL;

    int bufferSize = vt_sizeof_vkQueuePresentKHR((VkQueue)&queueObject->id, &presentInfo);
    if (bufferSize <= 0 || bufferSize > RT_MAX_BUFFER) return -1;
    vt_serialize_vkQueuePresentKHR((VkQueue)&queueObject->id, &presentInfo, out->data);
    out->size = bufferSize;
    return 0;
}
