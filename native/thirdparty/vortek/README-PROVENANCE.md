# Vortek — client library (Vulkan ICD)

Vendored from **https://github.com/brunodev85/vortek** @ commit
`b1730c5def9b575672e671aee11d79ae7adc63d1` (the exact commit pinned by
Winlator's submodule).

Vortek is a compatibility layer on top of the host Vulkan driver, created by
Bruno SX (brunodev85) for the [Winlator](https://github.com/brunodev85/winlator)
project. All credit for the Vortek architecture and implementation belongs to
the original author. Licensed under the **GNU LGPL-2.1** (see `LICENSE`).

## Local modifications (naughtybear-restuff-android port)

This copy carries modifications required to run the Vortek **client** inside a
plain Android app process (no Wine/rootfs), talking to a Vortek server hosted
in-process by `libvortekrenderer.so` (see `../vortekrenderer/`):

1. **`src/main.c`** — the server socket path is now read from the
   `REX_VORTEK_SERVER_PATH` environment variable (falls back to the upstream
   `VORTEK_SERVER_PATH`), because our app obviously does not live under
   `/data/data/com.winlator/`.
2. **`src/vulkan_calls.c`** — new standard loader exports
   (`vkGetInstanceProcAddr`, `vkCreateInstance`, `vkDestroyInstance`) so the
   engine can `dlopen` this library directly (upstream only exports the
   `vk_icd*` interface used by the glibc-rootfs Vulkan loader); new
   `vt_call_vkCreateAndroidSurfaceKHR` forwarding (round-trips the
   `ANativeWindow*` to the server, which creates the real host surface);
   `vkDestroySurfaceKHR` now forwards to the server; `vkQueuePresentKHR`
   serializes the queue handle too (the server needs it for the real
   `vkQueuePresentKHR` call in android-surface mode).
3. **`include/request_codes.h`** — added
   `REQUEST_CODE_VK_CREATE_ANDROID_SURFACE_KHR` (354) and
   `REQUEST_CODE_VK_DESTROY_SURFACE_KHR` (355); `REQUEST_CODE_VK_CALL_COUNT`
   bumped to 256. The server keeps the same numbering.
4. **`include/vortek_serializer.h`** — serializers for the calls above.
5. **`CMakeLists.txt`** — builds as `libvulkan_vortek.so` for Android with
   `-DVK_USE_PLATFORM_ANDROID_KHR`.

Upstream merges should diff against the files above; everything else is
byte-identical to the upstream commit.
