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
 * ANDROID PORT (naughtybear-restuff-android): implementação da
 * AndroidNativeWindowSurface — o tamanho é consultado direto no ANativeWindow
 * (não via SDL, que não acompanha resize do Java sem eventos).
 */

#include <rex/ui/surface_android.h>

namespace rex {
namespace ui {

// GetSizeImpl está no header (usa a API pública do NDK).

}  // namespace ui
}  // namespace rex
