#include <rex/platform.h>
#include <rex/platform/dynlib.h>

static_assert(REX_PLATFORM_LINUX || REX_PLATFORM_MAC, "This file is POSIX-only");

#include <dlfcn.h>

namespace rex::platform {

DynamicLibrary::~DynamicLibrary() {
  Close();
}

DynamicLibrary::DynamicLibrary(DynamicLibrary&& other) noexcept
    : handle_(other.handle_), last_error_(std::move(other.last_error_)) {
  other.handle_ = nullptr;
}

DynamicLibrary& DynamicLibrary::operator=(DynamicLibrary&& other) noexcept {
  if (this != &other) {
    Close();
    handle_ = other.handle_;
    last_error_ = std::move(other.last_error_);
    other.handle_ = nullptr;
  }
  return *this;
}

bool DynamicLibrary::Load(const std::filesystem::path& path, SymbolResolution mode) {
  Close();
  int flags = (mode == SymbolResolution::kImmediate) ? RTLD_NOW : RTLD_LAZY;
  handle_ = dlopen(path.c_str(), flags);
  if (handle_ == nullptr) {
    // Port Android (naughtybear-restuff-android): capturar o motivo REAL da
    // falha (ex.: "library libcutils.so not found" em drivers Turnip HAL
    // dlopenados fora do adrenotools) — sem isso o diagnóstico fica
    // "dlopen falhou" seco e a causa raiz invisível.
    const char* err = dlerror();
    last_error_ = err ? std::string(err) : std::string();
  } else {
    last_error_.clear();
  }
  return handle_ != nullptr;
}

void DynamicLibrary::Adopt(void* handle) {
  Close();
  handle_ = handle;
  last_error_.clear();
}

void DynamicLibrary::Close() {
  if (handle_) {
    dlclose(handle_);
    handle_ = nullptr;
  }
}

void* DynamicLibrary::GetRawSymbol(const char* name) const {
  if (!handle_)
    return nullptr;
  return dlsym(handle_, name);
}

}  // namespace rex::platform
