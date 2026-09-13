/*
 * Compat winlator (naughtybear-restuff-android): o vortekrenderer upstream
 * linka contra a lib "winlator" completa do app, onde AHardwareBuffer_getFd
 * vive em gpu_image.c. Aqui vendoramos só o que o vortekrenderer precisa —
 * esta é a única função externa que faltava (link error do CI em 6f50155).
 * Implementação idêntica à do winlator-app (LGPL-2.1, ver ../LICENSE).
 */

#include <android/hardware_buffer.h>
#include <native_handle.h>

extern int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer);

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    return nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}
