/**
 * @file        rex/main_android.h
 * @brief       Ponte embedder ↔ SDK para Android.
 *
 * @copyright   Copyright (c) 2026 naughtybear-restuff-android port.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 *
 * @remarks     O SDK (threading_posix/memory_posix) declara o hook e o
 *              embedder fornece a implementação — mesmo padrão do Xenia
 *              (xenia/base/main_android.h). Implementação do port:
 *              native/jni/main_android.cpp.
 */

#pragma once

namespace rex {

/// Nível da API Android do dispositivo (ro.build.version.sdk), cacheado
/// na primeira chamada. Retorno 0 = propriedade indisponível.
int GetAndroidApiLevel();

}  // namespace rex
