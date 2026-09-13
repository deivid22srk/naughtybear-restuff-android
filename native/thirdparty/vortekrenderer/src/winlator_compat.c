/*
 * Compat winlator (naughtybear-restuff-android): o vortekrenderer upstream
 * linka contra a lib "winlator" completa do app, onde AHardwareBuffer_getFd
 * vive em gpu_image.c. Aqui vendoramos só o que o vortekrenderer precisa —
 * esta é a única função externa que faltava (link error do CI em 6f50155).
 * Implementação idêntica à do winlator-app (LGPL-2.1, ver ../LICENSE).
 *
 * Autocontido DE LIBERDADE: nenhum include do NDK (o r27@android-28 do CI
 * não achava <native_handle.h> — run 34730147407). Tipos opacos declarados
 * localmente com os layouts ABI do AOSP:
 *   native_handle: { int version; int numFds; int numInts; int data[]; }
 * O símbolo exportado bate com o que resource_memory.c espera (C não tem
 * name mangling; só ponteiros opacos cruzam a fronteira).
 */

typedef struct AHardwareBuffer AHardwareBuffer;

typedef struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[1];
} native_handle_t;

extern const native_handle_t* AHardwareBuffer_getNativeHandle(const AHardwareBuffer* buffer);
extern int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer);

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    return nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}
