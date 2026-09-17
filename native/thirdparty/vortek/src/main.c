#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <sys/mman.h>
#include <stdlib.h>

#include "vortek.h"

int serverFd = -1;
uint16_t maxClientRequestId = 1;
MemoryPool globalMemoryPool = {0};
RingBuffer* serverRing = NULL;
RingBuffer* clientRing = NULL;

// Port Android (naughtybear-restuff-android): o caminho do socket do servidor
// Vortek é configurável via ambiente (REX_VORTEK_SERVER_PATH) porque o app não
// vive em /data/data/com.winlator. Sem a variável, mantém o padrão upstream —
// comportamento idêntico ao original no rootfs do Winlator.
static const char* vortekServerSocketPath() {
    static char cachedPath[sizeof(((struct sockaddr_un*)0)->sun_path)] = {0};
    static bool resolved = false;
    if (!resolved) {
        const char* env = getenv("REX_VORTEK_SERVER_PATH");
        if (env && env[0] != '\0') {
            strncpy(cachedPath, env, sizeof(cachedPath) - 1);
        } else {
            strncpy(cachedPath, VORTEK_SERVER_PATH, sizeof(cachedPath) - 1);
        }
        resolved = true;
    }
    return cachedPath;
}

static int vortekServerConnect() {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_LOCAL;

    strncpy(server_addr.sun_path, vortekServerSocketPath(), sizeof(server_addr.sun_path) - 1);

    int res;
    do {
        res = 0;
        if (connect(fd, (struct sockaddr*)&server_addr, sizeof(struct sockaddr_un)) < 0) res = -errno;
    } 
    while (res == -EINTR);    

    if (res < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

static bool createVkContext() {
    char header[HEADER_SIZE];
    *(int*)(header + 0) = REQUEST_CODE_CREATE_CONTEXT;
    *(int*)(header + 4) = 0;
    
    int res = write(serverFd, header, HEADER_SIZE);
    if (res < 0) return false;
    
    int shmFds[2];
    int numFds;
    recv_fds(serverFd, shmFds, &numFds, NULL, 0);
    if (numFds != 2) return false;
    
    serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!serverRing) return false;
    
    clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!clientRing) return false;
    
    close(shmFds[0]);
    close(shmFds[1]);
    
    if (!globalMemoryPool.data) {
        globalMemoryPool.data = malloc(MEMORY_POOL_MAX_SIZE);
        memset(globalMemoryPool.data, 0, MEMORY_POOL_MAX_SIZE);        
    }
    return true;
}

bool vortekInitOnce() {
    if (serverFd == -1) {
        serverFd = vortekServerConnect();
        
        if (serverFd > 0) {
            if (!createVkContext()) return false;
#if DEBUG_MODE
            println("vortek: connected serverFd=%d pid=%d\n", serverFd, getpid());
#endif
        }
        // Port Android: SEM atexit(terminationCallback) do upstream — o motor
        // faz dlclose() desta biblioteca ao destruir a VkInstance (shutdown),
        // e um callback registrado apontando para código descarregado
        // causaria SIGSEGV na saída do processo. O encerramento do processo
        // fecha os fds e devolve a shm ao kernel de qualquer forma.
    }
    
    return serverFd > 0;
}

// ---------------------------------------------------------------------------
// Port Android (naughtybear-restuff-android): exports padrão do loader Vulkan
// ---------------------------------------------------------------------------
// O upstream só exporta a interface ICD (vk_icdGetInstanceProcAddr /
// vk_icdNegotiateLoaderICDInterfaceVersion), usada pelo loader do glibc-rootfs
// do Winlator via vortek_icd.aarch64.json. O motor deste port carrega a
// biblioteca com dlopen() direto e resolve os símbolos padrão pelo nome —
// estes wrappers fornecem exatamente isso, com a mesma semântica da interface
// ICD (vortekInitOnce() conecta ao servidor na primeira resolução).
// ---------------------------------------------------------------------------

extern PFN_vkVoidFunction vt_call_vkGetInstanceProcAddr(VkInstance instance, const char* pName);
extern VkResult vt_call_vkCreateInstance(const VkInstanceCreateInfo* pCreateInfo, const VkAllocationCallbacks* pAllocator, VkInstance* pInstance);
extern void vt_call_vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* pAllocator);
extern void* findVkDispatchFuncWithNamePublic(const char* name);

PFN_vkVoidFunction vkGetInstanceProcAddr(VkInstance instance, const char* pName) {
    if (!vortekInitOnce()) return NULL;
    return findVkDispatchFuncWithNamePublic(pName);
}

VkResult vkCreateInstance(const VkInstanceCreateInfo* pCreateInfo, const VkAllocationCallbacks* pAllocator, VkInstance* pInstance) {
    if (!vortekInitOnce()) return VK_ERROR_INITIALIZATION_FAILED;
    return vt_call_vkCreateInstance(pCreateInfo, pAllocator, pInstance);
}

void vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* pAllocator) {
    vt_call_vkDestroyInstance(instance, pAllocator);
}