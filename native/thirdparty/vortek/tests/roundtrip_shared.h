#ifndef ROUNDTRIP_SHARED_H
#define ROUNDTRIP_SHARED_H

/*
 * Round-trip test do protocolo Vortek (16-e2, recomendado pela avaliação
 * independente): serializa no TU CLIENTE (vortek_serializer.h sem VT_SERVER,
 * árvore de native/thirdparty/vortek) e unserializa no TU SERVIDOR (mesma
 * cópia do serializer, árvore de native/thirdparty/vortekrenderer, com
 * -DVT_SERVER), exatamente como acontece na linha (socket + ring buffers).
 *
 * Motivação: os bugs 16-e1 foram exatamente desta classe — mudar só um lado
 * de um par serializador↔unserializador. Este harness pega a classe inteira.
 */

#include <stdint.h>
#include <stddef.h>

#define RT_MAX_BUFFER 8192

typedef struct RtBuffer {
    char data[RT_MAX_BUFFER];
    int size;
} RtBuffer;

/* ===== TU cliente (roundtrip_client.c) ===== */
extern int rt_client_serialize_surface_formats(uint64_t pd_id, uint64_t surface_id,
                                               uint32_t count, RtBuffer* out);
extern int rt_client_serialize_present_modes(uint64_t pd_id, uint64_t surface_id,
                                             uint32_t count, RtBuffer* out);
extern int rt_client_serialize_acquire2(uint64_t device_id, uint64_t swapchain_id,
                                        uint64_t semaphore_id, uint64_t fence_id,
                                        uint64_t timeout, uint32_t device_mask,
                                        RtBuffer* out);
extern int rt_client_serialize_create_android_surface(uint64_t instance_id, uint64_t window,
                                                       uint32_t flags, RtBuffer* out);
extern int rt_client_serialize_destroy_surface(uint64_t instance_id, uint64_t surface_id,
                                               RtBuffer* out);
extern int rt_client_serialize_queue_present(uint64_t queue_id, uint64_t swapchain_id,
                                             uint64_t semaphore_id, uint32_t image_index,
                                             RtBuffer* out);
extern int rt_client_unserialize_surface_response(RtBuffer* in, uint64_t* surface_id);

/* ===== TU servidor (roundtrip_server.c) ===== */
extern int rt_server_parse_surface_formats(RtBuffer* in, uint64_t* pd_id,
                                           uint64_t* surface_id, uint32_t* count);
extern int rt_server_parse_present_modes(RtBuffer* in, uint64_t* pd_id,
                                         uint64_t* surface_id, uint32_t* count);
extern int rt_server_parse_acquire2(RtBuffer* in, uint64_t* device_id, uint64_t* swapchain_id,
                                    uint64_t* semaphore_id, uint64_t* fence_id,
                                    uint64_t* timeout, uint32_t* device_mask);
extern int rt_server_parse_create_android_surface(RtBuffer* in, uint64_t* instance_id,
                                                  uint64_t* window, uint32_t* flags);
extern int rt_server_parse_destroy_surface(RtBuffer* in, uint64_t* instance_id,
                                           uint64_t* surface_id);
extern int rt_server_parse_queue_present(RtBuffer* in, uint64_t* queue_id, uint64_t* swapchain_id,
                                         uint64_t* semaphore_id, uint32_t* image_index);
extern int rt_server_serialize_surface_response(uint64_t surface_handle, RtBuffer* out);

#endif
