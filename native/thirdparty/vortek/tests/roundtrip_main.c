/*
 * Round-trip test do protocolo Vortek — orquestra os TUs cliente/servidor e
 * afirma os valores. Roda no host (CI) ANTES do build Android: feedback em
 * segundos para a classe de bug "mudou só um lado do par serializador".
 * Ver README-PROVENANCE.md do cliente (motivação: bugs da avaliação 16-e1).
 */

#include "roundtrip_shared.h"

#include <stdio.h>
#include <string.h>

static int g_failures = 0;

#define RT_CHECK(cond, name, fmt, actual)                                    \
    do {                                                                     \
        if (!(cond)) {                                                       \
            printf("FAIL %-38s " fmt " (esperado passado ao cliente)\n",     \
                   name, actual);                                            \
            g_failures++;                                                    \
        }                                                                    \
        else {                                                               \
            printf("ok   %s\n", name);                                       \
        }                                                                    \
    } while (0)

int main(void) {
    RtBuffer buf;
    memset(&buf, 0, sizeof(buf));

    /* T1 — vkGetPhysicalDeviceSurfaceFormatsKHR: request com surface id
     * (bug 16-e1 #1: o cliente upstream enviava NULL e o servidor do port
     * lia um handle nunca escrito). */
    {
        uint64_t pd_id = 0, surface_id = 0;
        uint32_t count = 0;
        int rc = rt_client_serialize_surface_formats(0x1111ULL, 0xABCDULL, 0, &buf);
        RT_CHECK(rc == 0, "T1 cliente serializa formats", "rc=%d", rc);
        rc = rt_server_parse_surface_formats(&buf, &pd_id, &surface_id, &count);
        RT_CHECK(rc == 0, "T1 servidor parseia formats", "rc=%d", rc);
        RT_CHECK(pd_id == 0x1111ULL, "T1 physicalDevice id", "0x%llx",
                 (unsigned long long)pd_id);
        RT_CHECK(surface_id == 0xABCDULL, "T1 surface id (o bug 16-e1)", "0x%llx",
                 (unsigned long long)surface_id);
    }

    /* T2 — vkGetPhysicalDeviceSurfacePresentModesKHR: idem T1. */
    {
        uint64_t pd_id = 0, surface_id = 0;
        uint32_t count = 0;
        int rc = rt_client_serialize_present_modes(0x2222ULL, 0xBEEFULL, 0, &buf);
        RT_CHECK(rc == 0, "T2 cliente serializa present modes", "rc=%d", rc);
        rc = rt_server_parse_present_modes(&buf, &pd_id, &surface_id, &count);
        RT_CHECK(rc == 0, "T2 servidor parseia present modes", "rc=%d", rc);
        RT_CHECK(pd_id == 0x2222ULL, "T2 physicalDevice id", "0x%llx",
                 (unsigned long long)pd_id);
        RT_CHECK(surface_id == 0xBEEFULL, "T2 surface id", "0x%llx",
                 (unsigned long long)surface_id);
    }

    /* T3 — vkAcquireNextImage2KHR: full-call (device + acquireInfo) com
     * handles de 16 bytes nos membros do struct (bug 16-e1 #2). O servidor
     * deve receber os ids — convenção ids=handles end-to-end. */
    {
        uint64_t device_id = 0, swapchain_id = 0, semaphore_id = 0, fence_id = 0;
        uint64_t timeout = 0;
        uint32_t device_mask = 0;
        int rc = rt_client_serialize_acquire2(0x1234ULL, 0x7777ULL, 0x8888ULL,
                                              0x9999ULL, 123456ULL, 1, &buf);
        RT_CHECK(rc == 0, "T3 cliente serializa acquire2", "rc=%d", rc);
        rc = rt_server_parse_acquire2(&buf, &device_id, &swapchain_id, &semaphore_id,
                                      &fence_id, &timeout, &device_mask);
        RT_CHECK(rc == 0, "T3 servidor parseia acquire2", "rc=%d", rc);
        RT_CHECK(device_id == 0x1234ULL, "T3 device id", "0x%llx",
                 (unsigned long long)device_id);
        RT_CHECK(swapchain_id == 0x7777ULL, "T3 swapchain id (ids=handles)", "0x%llx",
                 (unsigned long long)swapchain_id);
        RT_CHECK(semaphore_id == 0x8888ULL, "T3 semaphore id", "0x%llx",
                 (unsigned long long)semaphore_id);
        RT_CHECK(fence_id == 0x9999ULL, "T3 fence id", "0x%llx",
                 (unsigned long long)fence_id);
        RT_CHECK(timeout == 123456ULL, "T3 timeout", "%llu",
                 (unsigned long long)timeout);
        RT_CHECK(device_mask == 1, "T3 deviceMask", "%u", device_mask);
    }

    /* T4 — vkCreateAndroidSurfaceKHR: request com o ANativeWindow* bruto. */
    {
        uint64_t instance_id = 0, window = 0;
        uint32_t flags = 0;
        int rc = rt_client_serialize_create_android_surface(0x55AAULL, 0xDEADBEEFULL,
                                                            0x1, &buf);
        RT_CHECK(rc == 0, "T4 cliente serializa create android surface", "rc=%d", rc);
        rc = rt_server_parse_create_android_surface(&buf, &instance_id, &window, &flags);
        RT_CHECK(rc == 0, "T4 servidor parseia create android surface", "rc=%d", rc);
        RT_CHECK(instance_id == 0x55AAULL, "T4 instance id", "0x%llx",
                 (unsigned long long)instance_id);
        RT_CHECK(window == 0xDEADBEEFULL, "T4 ANativeWindow*", "0x%llx",
                 (unsigned long long)window);
        RT_CHECK(flags == 0x1, "T4 flags", "0x%x", flags);
    }

    /* T5 — resposta do vkCreateAndroidSurfaceKHR: servidor serializa o handle
     * real da VkSurfaceKHR do host; cliente unserializa o id. */
    {
        uint64_t surface_id = 0;
        int rc = rt_server_serialize_surface_response(0xCAFEULL, &buf);
        RT_CHECK(rc == 0, "T5 servidor serializa surface response", "rc=%d", rc);
        rc = rt_client_unserialize_surface_response(&buf, &surface_id);
        RT_CHECK(rc == 0, "T5 cliente unserializa surface response", "rc=%d", rc);
        RT_CHECK(surface_id == 0xCAFEULL, "T5 surface id (handle do host)", "0x%llx",
                 (unsigned long long)surface_id);
    }

    /* T6 — vkDestroySurfaceKHR: request com (instance, surface). */
    {
        uint64_t instance_id = 0, surface_id = 0;
        int rc = rt_client_serialize_destroy_surface(0x55AAULL, 0xBEEFULL, &buf);
        RT_CHECK(rc == 0, "T6 cliente serializa destroy surface", "rc=%d", rc);
        rc = rt_server_parse_destroy_surface(&buf, &instance_id, &surface_id);
        RT_CHECK(rc == 0, "T6 servidor parseia destroy surface", "rc=%d", rc);
        RT_CHECK(instance_id == 0x55AAULL, "T6 instance id", "0x%llx",
                 (unsigned long long)instance_id);
        RT_CHECK(surface_id == 0xBEEFULL, "T6 surface id", "0x%llx",
                 (unsigned long long)surface_id);
    }

    if (g_failures > 0) {
        printf("\nROUND-TRIP: %d FALHA(S)\n", g_failures);
        return 1;
    }
    printf("\nROUND-TRIP: 6/6 pares OK — protocolo cliente↔servidor sincronizado\n");
    return 0;
}
