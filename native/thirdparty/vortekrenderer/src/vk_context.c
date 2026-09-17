#include "vk_context.h"
#include "vulkan_helper.h"
#include "request_handler.h"
#include "sysvshared_memory.h"
#include "string_utils.h"
#include "jni_utils.h"

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->getWindowWidth = (*env)->GetMethodID(env, cls, "getWindowWidth", "(I)I");
    jmethods->getWindowHeight = (*env)->GetMethodID(env, cls, "getWindowHeight", "(I)I");
    jmethods->getWindowHardwareBuffer = (*env)->GetMethodID(env, cls, "getWindowHardwareBuffer", "(IZ)J");
    jmethods->updateWindowContent = (*env)->GetMethodID(env, cls, "updateWindowContent", "(I)V");
}

static ExtraDataRequest* waitForExtraDataRequest(VkContext* context, uint16_t requestId) {
    ExtraDataRequest* result = NULL;
    uint32_t busyWaitIter = 0;

    while (context->status >= 0) {
        result = NULL;
        pthread_mutex_lock(&context->extraDataRequestsMutex);
        for (int i = 0; i < context->extraDataRequests.size; i++) {
            ExtraDataRequest* extraDataRequest = context->extraDataRequests.elements[i];
            if (extraDataRequest->requestId == requestId) {
                result = extraDataRequest;
                ArrayList_removeAt(&context->extraDataRequests, i);
                break;
            }
        }
        pthread_mutex_unlock(&context->extraDataRequestsMutex);

        if (result) break;
        busyWait(&busyWaitIter);
    }

    return context->status >= 0 ? result : NULL;
}

static void* requestHandlerThread(void* param) {
    VkContext* context = param;
    // Port Android: no modo android-surface (hospedado via API C) não há
    // JVM/JObject — os callbacks XWindow do upstream não existem e o jmethods
    // fica zerado.
    if (context->jmethods.jvm) loadJMethods(&context->jmethods);
    ExtraDataRequest* extraDataRequest = NULL;

    while (context->status >= 0) {
        int requestCode = vt_recv(context->serverRing, &context->inputBuffer, &context->inputBufferSize, &context->memoryPool);
        if (requestCode < 0) break;

        if (requestCode > INT16_MAX) {
            uint16_t requestId = requestCode & 0xffff;
            requestCode = requestCode >> 16;
            extraDataRequest = waitForExtraDataRequest(context, requestId);
            if (!extraDataRequest) break;
            context->inputBufferSize = extraDataRequest->size;
            context->inputBuffer = extraDataRequest->data;
        }

#if DEBUG_MODE
        println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBufferSize);
#endif

        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (handleRequestFunc) handleRequestFunc(context);

        vt_free(&context->memoryPool);

        if (extraDataRequest) {
            MEMFREE(extraDataRequest->data);
            MEMFREE(extraDataRequest);
        }

        context->inputBuffer = NULL;
        context->inputBufferSize = 0;
    }

    if (context->jmethods.jvm) (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    vt_free(&context->memoryPool);
    return NULL;
}

static bool setupRingBuffers(VkContext* context) {
    int shmFds[2];
    shmFds[0] = ashmemCreateRegion("vt-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    shmFds[1] = ashmemCreateRegion("vt-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) return false;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) return false;

    int result = send_fds(context->clientFd, shmFds, 2, NULL, 0);
    close(shmFds[0]);
    close(shmFds[1]);

    if (result < 0) return false;

    pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context);
    return true;
}

// Corpo comum de criação do VkContext. env/obj opcionais: quando fornecidos
// (hospedagem JNI upstream do Winlator) os callbacks XWindow são registrados
// ANTES da thread de requests começar; quando NULL (hospedagem C do port
// Android), jmethods permanece zerado e o modo android-surface é assumido.
static VkContext* createVkContextInternal(int clientFd, int vkMaxVersion, short maxDeviceMemory,
                                          short imageCacheSize, signed char resourceMemoryType,
                                          ArrayList* exposedDeviceExtensions, JNIEnv* env, jobject obj) {
    VkContext* context = calloc(1, sizeof(VkContext));
    context->clientFd = clientFd;
    context->vkMaxVersion = vkMaxVersion;
    context->maxDeviceMemory = maxDeviceMemory;
    context->imageCacheSize = imageCacheSize;
    context->resourceMemoryType = resourceMemoryType;
    context->exposedDeviceExtensions = exposedDeviceExtensions;

    pthread_mutex_init(&context->extraDataRequestsMutex, NULL);

    context->memoryPool.data = calloc(MEMORY_POOL_MAX_SIZE, 1);
    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);

    if (env && obj) {
        (*env)->GetJavaVM(env, &context->jmethods.jvm);
        context->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    }

    if (!setupRingBuffers(context)) {
        destroyVkContext(env, context);
        return NULL;
    }

    return context;
}

VkContext* createVkContext(JNIEnv* env, jobject obj, int clientFd, jobject options) {
    jobjectArray exposedDeviceExtensions = getJFieldByName(env, options, "exposedDeviceExtensions", JSIGNATURE_ARRAY_STRING).l;

    return createVkContextInternal(
        clientFd,
        getJFieldByName(env, options, "vkMaxVersion", "I").i,
        getJFieldByName(env, options, "maxDeviceMemory", "S").s,
        getJFieldByName(env, options, "imageCacheSize", "S").s,
        getJFieldByName(env, options, "resourceMemoryType", "B").b,
        jstringArrayToCharArray(env, exposedDeviceExtensions),
        env, obj);
}

// Port Android (naughtybear-restuff-android): criação de contexto SEM JNI —
// usada pela hospedagem C (vortek_server_start, ver main.c).
VkContext* createVkContextC(int clientFd, const VortekServerOptionsC* options) {
    ArrayList* exposedExtensions = NULL;
    if (options && options->exposedDeviceExtensions) {
        int count = 0;
        while (options->exposedDeviceExtensions[count]) count++;
        if (count > 0) exposedExtensions = ArrayList_fromStrings(options->exposedDeviceExtensions, count);
    }
    return createVkContextInternal(
        clientFd,
        options ? options->vkMaxVersion : VK_MAKE_VERSION(1, 1, 128),
        options ? options->maxDeviceMemory : 0,
        options ? options->imageCacheSize : 256,
        options ? options->resourceMemoryType : 0,
        exposedExtensions,
        NULL, NULL);
}

void destroyVkContext(JNIEnv* env, VkContext* context) {
    context->status = VK_ERROR_DEVICE_LOST;

    if (context->requestHandlerThread) {
        RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
        RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
        pthread_join(context->requestHandlerThread, NULL);

        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;

        context->requestHandlerThread = 0;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);
    }

    if (context->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    context->graphicsQueueIndex = 0;

    if (context->textureDecoder) {
        TextureDecoder_destroy(context->textureDecoder);
        context->textureDecoder = NULL;
    }

    ArrayList_free(context->exposedDeviceExtensions, true);
    context->exposedDeviceExtensions = NULL;

    ArrayList_free(context->disabledDeviceExtensions, true);
    context->disabledDeviceExtensions = NULL;

    ArrayList_free(&context->extraDataRequests, true);
    pthread_mutex_destroy(&context->extraDataRequestsMutex);

    MEMFREE(context->memoryPool.data);
    MEMFREE(context->engineName);
    free(context);
}

bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength) {
    void* data = NULL;
    if (requestLength > 0) {
        data = calloc(requestLength, 1);
        int bytesRead = sock_read(context->clientFd, data, requestLength);
        if (bytesRead != requestLength) return false;
    }

    ExtraDataRequest* extraDataRequest = calloc(1, sizeof(ExtraDataRequest));
    extraDataRequest->requestId = requestId;
    extraDataRequest->size = requestLength;
    extraDataRequest->data = data;

    pthread_mutex_lock(&context->extraDataRequestsMutex);
    ArrayList_add(&context->extraDataRequests, extraDataRequest);
    pthread_mutex_unlock(&context->extraDataRequestsMutex);
    return true;
}