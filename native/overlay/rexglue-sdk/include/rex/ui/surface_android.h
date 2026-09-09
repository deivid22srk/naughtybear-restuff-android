#pragma once
/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2021 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 *
 * @modified    Tom Clay, 2026 - Adapted for ReXGlue runtime
 *
 * ANDROID PORT (naughtybear-restuff-android):
 * Superfície Vulkan sobre um ANativeWindow do SDL3 (janela criada pelo
 * SDLActivity Java). O presenter do SDK já referencia este header e usa
 * vkCreateAndroidSurfaceKHR com o ponteiro retornado por window().
 */

#include <rex/ui/surface.h>

#include <android/native_window.h>

namespace rex {
namespace ui {

class AndroidNativeWindowSurface final : public Surface {
 public:
  explicit AndroidNativeWindowSurface(ANativeWindow* window) : window_(window) {
    // Referência própria ao window: a SurfaceView Java pode ser recriada
    // enquanto o presenter ainda precisa consultar o tamanho.
    if (window_) {
      ANativeWindow_acquire(window_);
    }
  }
  ~AndroidNativeWindowSurface() override {
    if (window_) {
      ANativeWindow_release(window_);
      window_ = nullptr;
    }
  }

  TypeIndex GetType() const override { return kTypeIndex_AndroidNativeWindow; }
  ANativeWindow* window() const { return window_; }

 protected:
  bool GetSizeImpl(uint32_t& width_out, uint32_t& height_out) const override {
    if (!window_) {
      return false;
    }
    const int32_t width = ANativeWindow_getWidth(window_);
    const int32_t height = ANativeWindow_getHeight(window_);
    if (width <= 0 || height <= 0) {
      return false;
    }
    width_out = uint32_t(width);
    height_out = uint32_t(height);
    return true;
  }

 private:
  ANativeWindow* window_ = nullptr;
};

}  // namespace ui
}  // namespace rex
