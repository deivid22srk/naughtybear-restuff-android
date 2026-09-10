/**
 * @file        rex/system/main_android.cpp
 * @brief       Ponte embedder ↔ SDK no Android (compilada no rexruntime).
 *
 * @copyright   Copyright (c) 2026 naughtybear-restuff-android port.
 *
 * @license     BSD 3-Clause License
 *              See LICENSE file in the project root for full license text.
 *
 * @remarks     Implementa os hooks Android que o SDK espera do embedder:
 *                - rex::GetAndroidApiLevel()      (threading_posix,
 *                                                  memory_posix: dlsym
 *                                                  condicional de
 *                                                  pthread_getname_np e
 *                                                  ASharedMemory_create);
 *                - rex::filesystem::AndroidInitialize/AndroidShutdown,
 *                  IsAndroidContentUri,
 *                  OpenAndroidContentFileDescriptor
 *                                               (mapped_memory_posix:
 *                                                  MappedMemory::
 *                                                  OpenForAndroidContentUri).
 *
 *              O caminho SAF abre o ISO selecionado via persistable URI
 *              permission SEM cópia: ContentResolver.openFileDescriptor →
 *              ParcelFileDescriptor.detachFd().
 *
 *              Este arquivo vive dentro do librexruntime.so (que é quem
 *              referencia estes símbolos no link). Não usa SDL — a JavaVM
 *              e o Context chegam via rex::filesystem::SetAndroidJniContext
 *              (chamado pelo android_main do embedder, no thread principal).
 */

#include <sys/system_properties.h>

#include <jni.h>

#include <android/log.h>

#include <cstdlib>
#include <cstring>
#include <string>

#include <rex/filesystem.h>
#include <rex/main_android.h>

#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, "restuff", __VA_ARGS__)

namespace {

JavaVM* g_javavm = nullptr;
jobject g_context = nullptr;  // GlobalRef do Activity (android.content.Context)

// JNIEnv com attach escopo-privado (threads do motor não são JNI-attachadas).
class ScopedJNIEnv {
 public:
  explicit ScopedJNIEnv(JavaVM* vm) : vm_(vm) {
    env_ = nullptr;
    attached_ = false;
    if (!vm_) return;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) {
      return;
    }
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("restuff-native"), nullptr};
    if (vm_->AttachCurrentThread(&env_, &args) == JNI_OK) {
      attached_ = true;
    } else {
      env_ = nullptr;
    }
  }
  ~ScopedJNIEnv() {
    if (attached_ && vm_) {
      vm_->DetachCurrentThread();
    }
  }
  JNIEnv* env() const { return env_; }

 private:
  JavaVM* vm_;
  JNIEnv* env_;
  bool attached_;
};

bool CheckAndClearException(JNIEnv* env, const char* what) {
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    ALOG("SAF: exceção JNI em %s", what);
    return true;
  }
  return false;
}

}  // namespace

namespace rex {

int GetAndroidApiLevel() {
  static const int kLevel = [] {
    char value[PROP_VALUE_MAX] = {0};
    const int len = __system_property_get("ro.build.version.sdk", value);
    if (len <= 0) {
      return 0;
    }
    return std::atoi(value);
  }();
  return kLevel;
}

}  // namespace rex

namespace rex::filesystem {

void SetAndroidJniContext(void* javavm, void* activity_context) {
  // Substitui refs anteriores, se houver.
  if (g_context && g_javavm) {
    JNIEnv* env = nullptr;
    if (g_javavm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
      env->DeleteGlobalRef(g_context);
    }
    g_context = nullptr;
  }
  g_javavm = static_cast<JavaVM*>(javavm);
  if (activity_context && g_javavm) {
    ScopedJNIEnv scoped(g_javavm);
    if (scoped.env()) {
      g_context = scoped.env()->NewGlobalRef(static_cast<jobject>(activity_context));
    }
  }
}

void AndroidInitialize() {
  // Bootstrap JNI chega via SetAndroidJniContext (embedder); nada a fazer.
}

void AndroidShutdown() {
  if (g_context && g_javavm) {
    JNIEnv* env = nullptr;
    if (g_javavm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
      env->DeleteGlobalRef(g_context);
    }
  }
  g_context = nullptr;
  // g_javavm permanece (VM vive além do processo do jogo).
}

bool IsAndroidContentUri(const std::string_view source) {
  return source.size() >= 8 && source.substr(0, 8) == "content:";
}

int OpenAndroidContentFileDescriptor(const std::string_view uri, const char* mode) {
  if (!g_javavm || !g_context) {
    ALOG("SAF: JNI não inicializado (SetAndroidJniContext não chamado?)");
    return -1;
  }
  ScopedJNIEnv scoped(g_javavm);
  JNIEnv* env = scoped.env();
  if (!env) {
    ALOG("SAF: falha ao obter JNIEnv");
    return -1;
  }

  // Uri.parse(uri_string)
  jclass uri_class = env->FindClass("android/net/Uri");
  if (!uri_class) return -1;
  jmethodID uri_parse = env->GetStaticMethodID(
      uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
  if (!uri_parse) return -1;
  jstring juri_str = env->NewStringUTF(std::string(uri).c_str());
  if (!juri_str) return -1;
  jobject juri = env->CallStaticObjectMethod(uri_class, uri_parse, juri_str);
  env->DeleteLocalRef(juri_str);
  if (!juri || CheckAndClearException(env, "Uri.parse")) return -1;

  // context.getContentResolver()
  jclass ctx_class = env->FindClass("android/content/Context");
  if (!ctx_class) return -1;
  jmethodID get_resolver =
      env->GetMethodID(ctx_class, "getContentResolver", "()Landroid/content/ContentResolver;");
  if (!get_resolver) return -1;
  jobject resolver = env->CallObjectMethod(g_context, get_resolver);
  if (!resolver || CheckAndClearException(env, "getContentResolver")) return -1;

  // resolver.openFileDescriptor(uri, mode) → ParcelFileDescriptor
  jclass resolver_class = env->FindClass("android/content/ContentResolver");
  if (!resolver_class) return -1;
  jmethodID open_fd = env->GetMethodID(
      resolver_class, "openFileDescriptor",
      "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;");
  if (!open_fd) return -1;
  jstring jmode = env->NewStringUTF(mode);
  if (!jmode) return -1;
  jobject pfd = env->CallObjectMethod(resolver, open_fd, juri, jmode);
  env->DeleteLocalRef(jmode);
  if (CheckAndClearException(env, "openFileDescriptor")) return -1;
  if (!pfd) {
    ALOG("SAF: PFD nulo para %s", std::string(uri).c_str());
    return -1;
  }

  // pfd.detachFd() — fd fica com o processo nativo
  jmethodID detach_fd =
      env->GetMethodID(env->GetObjectClass(pfd), "detachFd", "()I");
  if (!detach_fd) return -1;
  const jint fd = env->CallIntMethod(pfd, detach_fd);
  if (CheckAndClearException(env, "detachFd")) return -1;
  return static_cast<int>(fd);
}

}  // namespace rex::filesystem
