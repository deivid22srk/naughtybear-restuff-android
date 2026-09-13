#include <jni.h>
#include <libgen.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "vk_context.h"
#include "vortek_serializer.h"
#include "request_handler.h"
#include "vulkan_helper.h"
#include "jni_utils.h"
#include "vortek_server_host.h"
#include "socket_utils.h"

#include "adrenotools/driver.h"

VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;

static void* openVulkanLibrary(JNIEnv* env, jstring nativeLibraryDir, jstring libvulkanPath) {
    void* libvulkan;
    if (libvulkanPath) {
        const char* nativeLibraryDirC = (*env)->GetStringUTFChars(env, nativeLibraryDir, NULL);
        const char* libvulkanPathC = (*env)->GetStringUTFChars(env, libvulkanPath, NULL);
        const char* libvulkanName = basename(libvulkanPathC);

        char libvulkanDir[PATH_MAX] = {0};
        strcpy(libvulkanDir, dirname(libvulkanPathC));
        strcat(libvulkanDir, "/");

        char* tmpDir;
        asprintf(&tmpDir, "%s%s", libvulkanDir, "tmp");
        mkdir(tmpDir, S_IRWXU | S_IRWXG);

        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibraryDirC, libvulkanDir, libvulkanName, NULL, NULL);

        (*env)->ReleaseStringUTFChars(env, nativeLibraryDir, nativeLibraryDirC);
        (*env)->ReleaseStringUTFChars(env, libvulkanPath, libvulkanPathC);
    }
    else libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);

    if (!libvulkan) println("vortek: unable to open libvulkan: %s", dlerror());
    return libvulkan;
}

// ---------------------------------------------------------------------------
// Port Android (naughtybear-restuff-android): hospedagem C do servidor
// ---------------------------------------------------------------------------
// Substitui VortekRendererComponent + XConnectorEpoll (Java) quando o jogo é
// um processo nativo puro: accept loop + leitor de extra-data em threads,
// sem JNI e sem XServer. O protocolo na linha é idêntico ao do Winlator —
// CREATE_CONTEXT no socket, requests via ring buffers em ASharedMemory,
// SEND_EXTRA_DATA (payloads grandes) e eventfds (timeline semaphores) pelo
// socket.
// ---------------------------------------------------------------------------

static int g_listenFd = -1;
static pthread_t g_acceptThread = 0;
static volatile bool g_serverRunning = false;
static VkContext* g_serverContext = NULL;
// Cópia rasa das opções (exposedDeviceExtensions aponta para memória do
// chamador — que deve ter lifetime estático, ver vortek_server_host.h).
static VortekServerOptionsC g_serverOptions;

int vortek_server_init(const char* nativeLibDir, const char* hostLibvulkanPath) {
    void* libvulkan;
    if (hostLibvulkanPath && hostLibvulkanPath[0]) {
        char libvulkanPathC[PATH_MAX] = {0};
        strncpy(libvulkanPathC, hostLibvulkanPath, sizeof(libvulkanPathC) - 1);

        char* pathCopy = strdup(libvulkanPathC);
        const char* libvulkanName = basename(pathCopy);

        char libvulkanDir[PATH_MAX] = {0};
        strcpy(libvulkanDir, dirname(libvulkanPathC));
        strcat(libvulkanDir, "/");

        char* tmpDir;
        asprintf(&tmpDir, "%s%s", libvulkanDir, "tmp");
        mkdir(tmpDir, S_IRWXU | S_IRWXG);

        libvulkan = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, nativeLibDir, libvulkanDir, libvulkanName, NULL, NULL);

        free(pathCopy);
        free(tmpDir);
    }
    else libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);

    if (!libvulkan) {
        println("vortek-server: unable to open libvulkan: %s", dlerror());
        return -1;
    }
    initVulkanWrapper(&vulkanWrapper, libvulkan);
    return 0;
}

// Lê do socket os pacotes SEND_EXTRA_DATA (payloads grandes que não cabem no
// ring buffer) — mesmo papel do handleRequest() do XConnectorEpoll Java.
static void* extraDataReaderThread(void* param) {
    VkContext* context = param;

    while (context->status >= 0) {
        char header[8];
        int n = sock_read(context->clientFd, header, 8);
        if (n != 8) break;

        int requestCode = *(int*)(header + 0);
        int requestLength = *(int*)(header + 4);
        if (requestCode > INT16_MAX && (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
            int requestId = requestCode & 0xffff;
            if (!handleExtraDataRequest(context, requestId, requestLength)) break;
        }
        else if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
            // Pacote inesperado (contexto já criado) — ignora.
            continue;
        }
        else break;
    }

    // Cliente caiu (engine encerrou): destrói o contexto.
    if (g_serverContext == context) {
        g_serverContext = NULL;
        destroyVkContext(NULL, context);
    }
    return NULL;
}

static void* acceptThread(void* param) {
    (void)param;
    while (g_serverRunning) {
        int clientFd = accept(g_listenFd, NULL, NULL);
        if (clientFd < 0) {
            if (!g_serverRunning) break;
            continue;
        }

        // Espera REQUEST_CODE_CREATE_CONTEXT (8 bytes) — mesmo passo do
        // handleRequest() do XConnectorEpoll.
        char header[8];
        if (sock_read(clientFd, header, 8) != 8) {
            close(clientFd);
            continue;
        }
        int requestCode = *(int*)(header + 0);
        if (requestCode != REQUEST_CODE_CREATE_CONTEXT) {
            println("vortek-server: unexpected request code %d (expected CREATE_CONTEXT)", requestCode);
            close(clientFd);
            continue;
        }

        VkContext* context = createVkContextC(clientFd, &g_serverOptions);
        if (!context) {
            close(clientFd);
            continue;
        }
        g_serverContext = context;

        pthread_t extraDataThread;
        pthread_create(&extraDataThread, NULL, extraDataReaderThread, context);
        pthread_detach(extraDataThread);

        // Um único cliente (o motor, no mesmo processo).
        break;
    }
    return NULL;
}

int vortek_server_start(const char* socketPath, const VortekServerOptionsC* options) {
    if (g_serverRunning) return 0;
    if (!socketPath || !socketPath[0]) {
        println("vortek-server: invalid socket path");
        return -1;
    }

    unlink(socketPath);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        println("vortek-server: socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_LOCAL;
    strncpy(addr.sun_path, socketPath, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr*)&addr, sizeof(struct sockaddr_un)) < 0) {
        println("vortek-server: bind(%s) failed: %s", socketPath, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        println("vortek-server: listen() failed: %s", strerror(errno));
        close(fd);
        unlink(socketPath);
        return -1;
    }

    // Opções default equivalentes a VortekRendererComponent.Options padrão:
    // VK_MAX_VERSION 1.3.128, imageCache 256, sem memória máxima artificial.
    memset(&g_serverOptions, 0, sizeof(g_serverOptions));
    if (options) g_serverOptions = *options;
    else {
        g_serverOptions.vkMaxVersion = VK_MAKE_VERSION(1, 3, 128);
        g_serverOptions.imageCacheSize = 256;
    }

    g_listenFd = fd;
    g_serverRunning = true;
    if (pthread_create(&g_acceptThread, NULL, acceptThread, NULL) != 0) {
        println("vortek-server: pthread_create failed: %s", strerror(errno));
        g_serverRunning = false;
        close(fd);
        unlink(socketPath);
        g_listenFd = -1;
        return -1;
    }
    pthread_detach(g_acceptThread);
    return 0;
}

void vortek_server_stop(void) {
    if (!g_serverRunning) return;
    g_serverRunning = false;

    if (g_listenFd >= 0) {
        close(g_listenFd);
        g_listenFd = -1;
    }
    if (g_serverContext) {
        destroyVkContext(NULL, g_serverContext);
        g_serverContext = NULL;
    }
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext(JNIEnv *env,
                                                                                  jobject obj,
                                                                                  jint clientFd,
                                                                                  jobject options) {
    VkContext* context = createVkContext(env, obj, clientFd, options);
    return context ? (jlong)context : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_destroyVkContext(JNIEnv *env,
                                                                                   jobject obj,
                                                                                   jlong contextPtr) {
    destroyVkContext(env, (VkContext*)contextPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_initVulkanWrapper(JNIEnv *env,
                                                                                    jobject obj,
                                                                                    jstring nativeLibraryDir,
                                                                                    jstring libvulkanPath) {
    void* libvulkan = openVulkanLibrary(env, nativeLibraryDir, libvulkanPath);
    initVulkanWrapper(&vulkanWrapper, libvulkan);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_handleExtraDataRequest(JNIEnv *env,
                                                                                         jobject obj,
                                                                                         jlong contextPtr,
                                                                                         int requestId,
                                                                                         int requestLength) {
    return handleExtraDataRequest((VkContext*)contextPtr, requestId, requestLength);
}