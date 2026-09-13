#ifndef VORTEK_CONTEXT_H
#define VORTEK_CONTEXT_H

#include "vortek.h"
#include "xwindow_swapchain.h"
#include "texture_decoder.h"
#include "async_pipeline_creator.h"
#include "timeline_semaphore.h"

typedef struct VkContext {
    int clientFd;
    int vkMaxVersion;
    short maxDeviceMemory;
    short imageCacheSize;
    ResourceMemoryType resourceMemoryType;
    ArrayList* exposedDeviceExtensions;
    ArrayList* disabledDeviceExtensions;
    uint64_t totalAllocationSize;

    bool hasExternalMemoryFd;
    bool hasExternalMemoryDMABuf;

#if ENABLE_VALIDATION_LAYER
    VkDebugReportCallbackEXT debugReportCallback;
#endif

    char* inputBuffer;
    int inputBufferSize;
    MemoryPool memoryPool;

    pthread_t requestHandlerThread;
    RingBuffer* clientRing;
    RingBuffer* serverRing;
    VkResult status;

    int graphicsQueueIndex;

    TextureDecoder* textureDecoder;
    ShaderInspector* shaderInspector;
    ThreadPool* threadPool;

    ArrayList extraDataRequests;
    pthread_mutex_t extraDataRequestsMutex;

    JMethods jmethods;
    char* engineName;
    uint32_t engineVersion;
    VkDriverId driverID;
} VkContext;

typedef struct ExtraDataRequest {
    uint16_t requestId;
    void* data;
    int size;
} ExtraDataRequest;

// Port Android (naughtybear-restuff-android): opções da hospedagem C (sem JNI)
// — equivalentes aos campos da classe Options do VortekRendererComponent.
typedef struct VortekServerOptionsC {
    int vkMaxVersion;                    // ex.: VK_MAKE_VERSION(1, 3, 128)
    short maxDeviceMemory;               // 0 = sem limite artificial
    short imageCacheSize;                // ex.: 256
    signed char resourceMemoryType;      // 0 = default
    const char* const* exposedDeviceExtensions; // NULL = expor todas
} VortekServerOptionsC;

extern VkContext* createVkContext(JNIEnv *env, jobject obj, int clientFd, jobject options);
extern VkContext* createVkContextC(int clientFd, const VortekServerOptionsC* options);
extern void destroyVkContext(JNIEnv *env, VkContext* context);
extern bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength);

#endif
