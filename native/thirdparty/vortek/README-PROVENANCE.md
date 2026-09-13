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
   `/data/data/com.winlator/`. The upstream `atexit(terminationCallback)` was
   REMOVED: the engine `dlclose()`s this library when the VkInstance is
   destroyed, and an atexit handler pointing into unloaded code would crash
   the process at exit.
2. **`src/vulkan_calls.c`** —
   - new standard loader exports (`vkGetInstanceProcAddr`, `vkCreateInstance`,
     `vkDestroyInstance`) so the engine can `dlopen` this library directly
     (upstream only exports the `vk_icd*` interface used by the glibc-rootfs
     Vulkan loader);
   - new `vt_call_vkCreateAndroidSurfaceKHR` forwarding (round-trips the
     `ANativeWindow*` to the server, which creates the real host surface);
   - `vkDestroySurfaceKHR` now forwards to the server;
   - `vkQueuePresentKHR` serializes the queue handle too, using the upstream
     full-call serializer `vt_serialize_vkQueuePresentKHR` (the server needs
     the queue for the real `vkQueuePresentKHR`; still fire-and-forget — the
     client does NOT wait for a response);
   - **`vkGetPhysicalDeviceSurfaceFormatsKHR` / `PresentModesKHR` now
     serialize the surface id** (upstream sent NULL because the X11 server
     fabricated the values) — the android-surface server queries the REAL
     surface, so both sides of the pair must agree (found by review 16-e1);
   - **`vkAcquireNextImage2KHR` now sends the full-call format**
     (device + acquireInfo) matching the server handler (also 16-e1);
   - the Xlib functions are guarded with `#ifdef VK_USE_PLATFORM_XLIB_KHR`
     (no X11 headers in the NDK).
3. **`include/vortek.h`** — the `#ifdef __ANDROID__ → VT_SERVER` block was
   REMOVED: upstream builds the client in the glibc rootfs (no `__ANDROID__`),
   so that macro only ever marked the server; with the NDK it would wrongly
   compile this client in server mode.
4. **`include/request_codes.h`** — added
   `REQUEST_CODE_VK_CREATE_ANDROID_SURFACE_KHR` (354) and
   `REQUEST_CODE_VK_DESTROY_SURFACE_KHR` (355); `REQUEST_CODE_VK_CALL_COUNT`
   bumped to 256. The server keeps the same numbering.
5. **`include/vortek_serializer.h`** — serializers for
   `vkCreateAndroidSurfaceKHR` / `vkDestroySurfaceKHR` (the
   `vkQueuePresentKHR` / `vkAcquireNextImage2KHR` full-call serializers
   already exist upstream).
6. **`CMakeLists.txt`** — builds as `libvulkan_vortek.so` for Android with
   `-DVK_USE_PLATFORM_ANDROID_KHR`.
7. **`include/vulkan/` + `include/vk_video/`** — Vulkan headers (Khronos,
   Apache-2.0) BUNDLED: the NDK r27 sysroot headers are older than what the
   upstream `vortek_serializer.h` requires (`VK_EXT_map_memory_placed` etc.).
   Same header copy the vortekrenderer uses (VK_HEADER_VERSION 302), plus
   `vk_icd.h`/`vk_layer.h`. Found by CI on the first build of this branch.

Upstream merges should diff against the files above; everything else is
byte-identical to the upstream commit.
