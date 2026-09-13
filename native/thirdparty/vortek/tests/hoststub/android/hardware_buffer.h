/* Stub de android/hardware_buffer.h para checagem de sintaxe no host. */
#ifndef _STUB_ANDROID_HARDWARE_BUFFER_H
#define _STUB_ANDROID_HARDWARE_BUFFER_H
#include <stdint.h>
typedef struct AHardwareBuffer AHardwareBuffer;
enum { AHARDWAREBUFFER_FORMAT_BLOB = 0x21 };
enum { AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN = 0x6, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN = 0x30 };
typedef struct AHardwareBuffer_Desc {
    uint32_t width; uint32_t height; uint32_t layers; uint32_t format;
    uint64_t usage; uint32_t stride; uint32_t rfu0; uint64_t rfu1;
} AHardwareBuffer_Desc;
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc*, AHardwareBuffer**);
void AHardwareBuffer_describe(const AHardwareBuffer*, AHardwareBuffer_Desc*);
void AHardwareBuffer_release(AHardwareBuffer*);
#endif
