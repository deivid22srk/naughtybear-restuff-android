# VortekRenderer — server library

Vendored from **https://github.com/brunodev85/winlator-app**
(`app/src/main/cpp/vortekrenderer/`, branch `main`, matching the vortek client
commit `b1730c5def9b575672e671aee11d79ae7adc63d1` pinned by
[Winlator](https://github.com/brunodev85/winlator)) — plus the handful of
support files it needs from `app/src/main/cpp/winlator/` (`sysvshared_memory`,
`arrays`, `ring_buffer`, headers), kept under `winlator/` here.

Vortek and its renderer were created by **Bruno SX (brunodev85)**, author of
Winlator. All credit for the architecture and implementation belongs to the
original author. Licensed under the **GNU LGPL-2.1** (see `LICENSE`).

## Local modifications (naughtybear-restuff-android port)

Upstream, the Vortek server is hosted by the Winlator Java app
(`VortekRendererComponent` + `XConnectorEpoll`), renders into X11 windows
backed by `AHardwareBuffer`s and talks to a client living inside the Wine
rootfs. Our app is a plain native Android game process, so this copy carries
an **android-surface passthrough mode**:

1. **`src/main.c`** — new plain-C hosting API (`vortek_server_init`,
   `vortek_server_start`, `vortek_server_stop`) that replaces the Java
   `XConnectorEpoll` hosting: a Unix socket accept loop + the
   `SEND_EXTRA_DATA` reader, all on background threads, no JNI/XServer
   required. The original JNI entry points are kept (uncalled) so diffs
   against upstream stay minimal.
2. **`src/vk_context.c`** — `createVkContextC()` (no-JNI context creation
   used by the C hosting API); `loadJMethods`/`requestHandlerThread` tolerate
   a NULL `jmethods.jvm` (android-surface mode never touches the XWindow JNI
   callbacks).
3. **`src/request_handler.c`** — android-surface passthrough:
   - `vkCreateAndroidSurfaceKHR` (new handler) creates a **real** host
     surface from the `ANativeWindow*` sent by the client (same process);
     the surface id IS the host handle.
   - `vkDestroySurfaceKHR` (new handler) destroys the real surface.
   - `vkGetPhysicalDeviceSurfaceCapabilitiesKHR` / `FormatsKHR` /
     `PresentModesKHR` query the **real** surface instead of the fabricated
     X11 values.
   - `vkCreateSwapchainKHR` / `vkGetSwapchainImagesKHR` /
     `vkAcquireNextImageKHR` / `vkDestroySwapchainKHR` /
     `vkQueuePresentKHR` / `vkAcquireNextImage2KHR` pass through to the real
     host swapchain instead of `XWindowSwapchain` (AHardwareBuffer path).
   - `vkCreateInstance` keeps `VK_KHR_surface` + `VK_KHR_android_surface`
     enabled on the host instance (upstream stripped all surface extensions
     because the X11 swapchain was synthetic); instance extension
     enumeration no longer hides `VK_KHR_android_surface`.
4. **`include/vulkan_wrapper.h` + `src/vulkan_helper.c`** —
   `vkCreateAndroidSurfaceKHR` / `vkDestroySurfaceKHR` added to the wrapper.
5. **`CMakeLists.txt`** — builds as `libvortekrenderer.so`, linking our
   in-tree `libadrenotools`.

The X11/XWindow code paths still compile but are unreachable in this app
(no XServer, no X11 windows) — kept for upstream-merge friendliness.
6. **`src/winlator_compat.c`** (novo) — `AHardwareBuffer_getFd`: upstream
   links the full `winlator` native lib (where the function lives in
   `gpu_image.c`); we vendor only what vortekrenderer needs, so the single
   missing symbol is provided here (same implementation, LGPL-2.1). Found
   by CI linking libvortekrenderer.so.
