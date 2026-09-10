/**
 * @file        rex/main_android.h
 * @brief       Ponte embedder ↔ SDK para Android.
 *
 * @copyright   Copyright (c) 2026 naughtybear-restuff-android port.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 *
 * @remarks     O SDK (threading_posix/memory_posix/filesystem.h) declara os
 *              hooks e o embedder fornece a implementação — mesmo padrão do
 *              Xenia (xenia/base/main_android.h). Implementação do port:
 *              src/system/main_android.cpp (compilada DENTRO do
 *              librexruntime.so, que é quem consome estes símbolos no link).
 */

#pragma once

#include <string_view>

namespace rex {

/// Nível da API Android do dispositivo (ro.build.version.sdk), cacheado
/// na primeira chamada. Retorno 0 = propriedade indisponível.
int GetAndroidApiLevel();

}  // namespace rex

namespace rex::filesystem {

/// Injeta o JNI bootstrap do embedder (chamado UMA vez do thread principal,
/// antes de qualquer uso de SAF):
///   - javavm: JavaVM* (obtido via JNIEnv::GetJavaVM);
///   - activity_context: jobject do Activity (um android.content.Context).
/// Ambos como void* para não puxar jni.h para headers do SDK.
void SetAndroidJniContext(void* javavm, void* activity_context);

/// Implementações dos hooks SAF declarados em rex/filesystem.h
/// (REX_PLATFORM_ANDROID): AndroidInitialize/AndroidShutdown,
/// IsAndroidContentUri e OpenAndroidContentFileDescriptor — a ponte que
/// abre o ISO via ContentResolver sem cópia (MappedMemory::
/// OpenForAndroidContentUri consome o fd).

}  // namespace rex::filesystem
