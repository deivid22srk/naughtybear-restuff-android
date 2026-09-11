/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2025 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 *
 * @modified    Tom Clay, 2026 - Adapted for ReXGlue runtime
 */

#include <cstdlib>

#include <sstream>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#if REX_PLATFORM_ANDROID
// Port Android (naughtybear-restuff-android): integração AdrenoTools — ver
// TryLoadCustomAdrenoDriver abaixo.
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <system_error>
#endif

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/platform.h>
#include <rex/ui/vulkan/instance.h>
#include <rex/ui/vulkan/presenter.h>

#if REX_PLATFORM_MAC
#include "vulkan_moltenvk.h"
#endif

REXCVAR_DEFINE_BOOL(vulkan_log_debug_messages, true, "UI/Vulkan", "Log Vulkan debug messages");

namespace rex {
namespace ui {
namespace vulkan {

#if REX_PLATFORM_ANDROID
namespace {

// Escreve o desfecho do boot do driver para consumo da UI (Configurações →
// Diagnóstico): <files>/drivers/last_boot.txt. Falhas de escrita são
// silenciosas — diagnóstico é best-effort, nunca derruba o boot.
void WriteDriverBootOutcome(const char* status, const char* driver, const char* error) {
  const char* files_dir = std::getenv("REX_ANDROID_FILES_DIR");
  if (files_dir == nullptr || files_dir[0] == '\0') {
    return;
  }
  char path[512];
  if (std::snprintf(path, sizeof(path), "%s/drivers/last_boot.txt", files_dir) >=
      static_cast<int>(sizeof(path))) {
    return;
  }
  std::error_code ec;
  std::filesystem::create_directories(std::filesystem::path(path).parent_path(), ec);
  if (FILE* f = std::fopen(path, "w")) {
    std::fprintf(f, "status=%s\ndriver=%s\nerror=%s\n", status,
                 driver ? driver : "-", error ? error : "-");
    std::fclose(f);
  }
}

// Port Android (naughtybear-restuff-android):
// Carrega o driver customizado (padrão AdrenoTools/Turnip) pelo caminho
// CORRETO — via libadrenotools — em vez do dlopen direto do .so.
//
// [evidência] O dlopen direto NUNCA funcionou com drivers reais:
//  - Turnip HAL (K11MCH1/AdrenoToolsDrivers, ex.: vulkan.ad07xx.so): exporta
//    apenas `HMI` e depende de libcutils.so/libhardware.so — bibliotecas
//    NÃO públicas para processos de app (public.libraries) → dlopen falha
//    com "library libcutils.so not found" → fallback silencioso para o
//    driver do sistema.
//  - ICD (Winlator, libvulkan_freedreno.so): exporta vk_icd* e NÃO
//    vkGetInstanceProcAddr/vkDestroyInstance → GetSymbol retornava nulo →
//    VulkanInstance::Create retornava nullptr SEM fallback → boot morto.
//
// O libadrenotools (bylaws, BSD-2) resolve os dois casos: devolve o handle
// do LOADER do sistema (/system/lib64/libvulkan.so) numa instância única,
// com hooks (libmain_hook) que interceptam a abertura do driver pelo loader
// e a redirecionam para o .so customizado num namespace ligado ao sphal —
// onde as dependências do driver (libcutils/libhardware/libgsl...) resolvem.
// O handle devolvido exporta vkGetInstanceProcAddr de verdade, então todo o
// restante deste Create() funciona sem mudanças.
//
// Requisitos (garantidos pelo port): hooks construídos e empacotados no APK
// (useLegacyPackaging=true) e REX_ANDROID_NATIVE_LIB_DIR apontando para o
// nativeLibraryDir do app.
bool TryLoadCustomAdrenoDriver(platform::DynamicLibrary& loader, const char* custom_path) {
  const char* native_lib_dir = std::getenv("REX_ANDROID_NATIVE_LIB_DIR");
  if (native_lib_dir == nullptr || native_lib_dir[0] == '\0') {
    REXLOG_ERROR(
        "Vulkan: driver customizado solicitado ('{}') mas o launcher não "
        "informou --native-lib-dir (hooks do AdrenoTools indisponíveis); "
        "usando o driver do sistema",
        custom_path);
    WriteDriverBootOutcome("custom_failed", custom_path, "missing --native-lib-dir");
    return false;
  }

  std::string adrenotools_path = std::string(native_lib_dir) + "/libadrenotools.so";
  void* adrenotools = dlopen(adrenotools_path.c_str(), RTLD_NOW);
  if (adrenotools == nullptr) {
    const char* err = dlerror();
    REXLOG_ERROR("Vulkan: falha ao carregar '{}': {}", adrenotools_path,
                 err ? err : "motivo desconhecido");
    WriteDriverBootOutcome("custom_failed", custom_path,
                           err ? err : "libadrenotools dlopen failed");
    return false;
  }

  using OpenLibvulkanFn = void* (*)(int, int, const char*, const char*, const char*,
                                    const char*, const char*, void**);
  auto open_libvulkan = reinterpret_cast<OpenLibvulkanFn>(
      dlsym(adrenotools, "adrenotools_open_libvulkan"));
  if (open_libvulkan == nullptr) {
    REXLOG_ERROR("Vulkan: adrenotools_open_libvulkan ausente em '{}'", adrenotools_path);
    WriteDriverBootOutcome("custom_failed", custom_path, "adrenotools_open_libvulkan missing");
    return false;
  }

  // Dir com barra final: o adrenotools concatena dir + nome SEM separador
  // (stat + ld_library_path do namespace).
  std::filesystem::path driver_fs_path(custom_path);
  std::string driver_dir = driver_fs_path.parent_path().string();
  if (!driver_dir.empty() && driver_dir.back() != '/') {
    driver_dir += '/';
  }
  std::string driver_name = driver_fs_path.filename().string();

  constexpr int kAdrenoToolsDriverCustom = 1 << 0;  // ADRENOTOOLS_DRIVER_CUSTOM

  void* handle = open_libvulkan(RTLD_NOW, kAdrenoToolsDriverCustom, nullptr,
                                native_lib_dir, driver_dir.c_str(), driver_name.c_str(),
                                nullptr, nullptr);
  if (handle == nullptr) {
    REXLOG_ERROR(
        "Vulkan: AdrenoTools não conseguiu abrir o driver customizado '{}' "
        "(detalhes no logcat, tag 'hook_impl'); usando o driver do sistema",
        custom_path);
    WriteDriverBootOutcome("custom_failed", custom_path,
                           "adrenotools_open_libvulkan failed (see hook_impl logcat)");
    return false;
  }

  loader.Adopt(handle);
  REXLOG_INFO("Vulkan: driver customizado ATIVO via AdrenoTools: {}{}", driver_dir,
              driver_name);
  WriteDriverBootOutcome("custom_ok", custom_path, "-");
  return true;
}

}  // namespace
#endif  // REX_PLATFORM_ANDROID

std::unique_ptr<VulkanInstance> VulkanInstance::Create(const bool with_surface,
                                                       const bool try_enable_validation) {
  std::unique_ptr<VulkanInstance> vulkan_instance(new VulkanInstance());

  // Load the RenderDoc API if connected.

  vulkan_instance->renderdoc_api_ = RenderDocAPI::CreateIfConnected();

  // Load the loader library.

  Functions& ifn = vulkan_instance->functions_;

  bool functions_loaded = true;
  bool loader_loaded = false;
#if REX_PLATFORM_MAC
  const MacOSVulkanRuntimePaths macos_runtime_paths = DetectMacOSVulkanRuntimePaths();
  ConfigureMacOSVulkanEnvironment(macos_runtime_paths);

  std::vector<std::filesystem::path> loader_candidates = macos_runtime_paths.loader_candidates;
  loader_candidates.emplace_back(platform::lib_names::kVulkanLoader);
  loader_candidates.emplace_back("libvulkan.dylib");
  loader_candidates.emplace_back("libMoltenVK.dylib");

  std::vector<std::string> attempted_loader_paths;
  attempted_loader_paths.reserve(loader_candidates.size());
  for (const std::filesystem::path& candidate : loader_candidates) {
    if (candidate.empty()) {
      continue;
    }
    attempted_loader_paths.push_back(candidate.string());
    if (vulkan_instance->loader_.Load(candidate)) {
      loader_loaded = true;
      REXLOG_INFO("Loaded Vulkan runtime from {}", candidate.string());
      break;
    }
  }
  if (!loader_loaded) {
    REXLOG_ERROR("Failed to load a Vulkan runtime on macOS");
    for (const std::string& attempted_loader_path : attempted_loader_paths) {
      REXLOG_ERROR("* tried {}", attempted_loader_path);
    }
    return nullptr;
  }
#else
  // Port Android (naughtybear-restuff-android): driver Vulkan customizado no
  // padrão AdrenoTools (Turnip/Mesa). O Driver Manager do app exporta
  // REX_VULKAN_LOADER_PATH com o caminho absoluto do .so importado, ANTES de
  // qualquer init gráfico (android_main.cpp). Falha em qualquer etapa é
  // logada com o motivo real e cai para o driver do sistema (nunca crasha
  // o app). [evidência] ver TryLoadCustomAdrenoDriver acima — o dlopen direto
  // do .so do driver nunca funcionou (HAL exporta só `HMI` + deps não
  // públicas; ICD exporta vk_icd*, não vkGetInstanceProcAddr).
#if REX_PLATFORM_ANDROID
  const char* custom_loader = std::getenv("REX_VULKAN_LOADER_PATH");
  if (custom_loader != nullptr && custom_loader[0] != '\0') {
    loader_loaded = TryLoadCustomAdrenoDriver(vulkan_instance->loader_, custom_loader);
  }
#else
  // Desktop Linux: dlopen direto (ICDs de desktop exportam os símbolos do
  // loader) — mantido para setups LD_*, agora com o motivo real no log.
  if (const char* custom_loader = std::getenv("REX_VULKAN_LOADER_PATH");
      custom_loader != nullptr && custom_loader[0] != '\0') {
    loader_loaded = vulkan_instance->loader_.Load(custom_loader);
    if (loader_loaded) {
      REXLOG_INFO("Vulkan: driver customizado carregado: {}", custom_loader);
    } else {
      REXLOG_ERROR("Vulkan: falha ao carregar driver customizado '{}': {}; usando o "
                   "driver do sistema",
                   custom_loader, vulkan_instance->loader_.last_error());
    }
  }
#endif
  if (!loader_loaded) {
    loader_loaded = vulkan_instance->loader_.Load(platform::lib_names::kVulkanLoader);
    if (!loader_loaded) {
      REXLOG_ERROR("Failed to load {}: {}", platform::lib_names::kVulkanLoader,
                   vulkan_instance->loader_.last_error());
      return nullptr;
    }
#if REX_PLATFORM_ANDROID
    if (custom_loader != nullptr && custom_loader[0] != '\0') {
      REXLOG_WARN(
          "Vulkan: rodando com o DRIVER DO SISTEMA (driver customizado não pôde "
          "ser carregado)");
      // Nota: o outcome "custom_failed" já foi gravado pelo
      // TryLoadCustomAdrenoDriver — não sobrescrever com "system".
    } else {
      // Nenhum driver customizado solicitado: registra o estado normal.
      WriteDriverBootOutcome("system", "-", "-");
    }
#endif
  }
#endif

#define XE_VULKAN_LOAD_LOADER_FUNCTION(name) \
  functions_loaded &= (ifn.name = vulkan_instance->loader_.GetSymbol<PFN_##name>(#name)) != nullptr;
  XE_VULKAN_LOAD_LOADER_FUNCTION(vkGetInstanceProcAddr);
  XE_VULKAN_LOAD_LOADER_FUNCTION(vkDestroyInstance);
#undef XE_VULKAN_LOAD_LOADER_FUNCTION
  if (!functions_loaded) {
    REXLOG_ERROR("Failed to get Vulkan loader function pointers");
    return nullptr;
  }

  // Load global functions.

  functions_loaded &= (ifn.vkCreateInstance = PFN_vkCreateInstance(
                           ifn.vkGetInstanceProcAddr(nullptr, "vkCreateInstance"))) != nullptr;
  functions_loaded &= (ifn.vkEnumerateInstanceExtensionProperties =
                           PFN_vkEnumerateInstanceExtensionProperties(ifn.vkGetInstanceProcAddr(
                               nullptr, "vkEnumerateInstanceExtensionProperties"))) != nullptr;
  functions_loaded &=
      (ifn.vkEnumerateInstanceLayerProperties = PFN_vkEnumerateInstanceLayerProperties(
           ifn.vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceLayerProperties"))) != nullptr;
  if (!functions_loaded) {
    REXLOG_ERROR(
        "Failed to get Vulkan global function pointers via "
        "vkGetInstanceProcAddr");
    return nullptr;
  }
  // Available since Vulkan 1.1. If this is nullptr, it's a Vulkan 1.0 instance.
  ifn.vkEnumerateInstanceVersion = PFN_vkEnumerateInstanceVersion(
      ifn.vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));

  // Get the API version.

  if (ifn.vkEnumerateInstanceVersion) {
    ifn.vkEnumerateInstanceVersion(&vulkan_instance->api_version_);
  }

  // Enable extensions and layers.

  // Name pointers from `requested_extensions` will be used in the enabled
  // extensions vector.
  std::unordered_map<std::string, bool*> requested_extensions;
  if (vulkan_instance->api_version_ >= VK_MAKE_API_VERSION(0, 1, 1, 0)) {
    vulkan_instance->extensions_.ext_1_1_KHR_get_physical_device_properties2 = true;
  } else {
    // #60.
    requested_extensions.emplace(
        "VK_KHR_get_physical_device_properties2",
        &vulkan_instance->extensions_.ext_1_1_KHR_get_physical_device_properties2);
  }
  // #129.
  requested_extensions.emplace("VK_EXT_debug_utils",
                               &vulkan_instance->extensions_.ext_EXT_debug_utils);
  // #395.
  requested_extensions.emplace("VK_KHR_portability_enumeration",
                               &vulkan_instance->extensions_.ext_KHR_portability_enumeration);
  if (with_surface) {
    // #1.
    requested_extensions.emplace("VK_KHR_surface", &vulkan_instance->extensions_.ext_KHR_surface);
#ifdef VK_USE_PLATFORM_XCB_KHR
    // #6.
    requested_extensions.emplace("VK_KHR_xcb_surface",
                                 &vulkan_instance->extensions_.ext_KHR_xcb_surface);
#endif
#ifdef VK_USE_PLATFORM_WAYLAND_KHR
    // #7.
    requested_extensions.emplace("VK_KHR_wayland_surface",
                                 &vulkan_instance->extensions_.ext_KHR_wayland_surface);
#endif
#ifdef VK_USE_PLATFORM_ANDROID_KHR
    // #9.
    requested_extensions.emplace("VK_KHR_android_surface",
                                 &vulkan_instance->extensions_.ext_KHR_android_surface);
#endif
#ifdef VK_USE_PLATFORM_WIN32_KHR
    // #10.
    requested_extensions.emplace("VK_KHR_win32_surface",
                                 &vulkan_instance->extensions_.ext_KHR_win32_surface);
#endif
#ifdef VK_USE_PLATFORM_METAL_EXT
    // #217.
    requested_extensions.emplace("VK_EXT_metal_surface",
                                 &vulkan_instance->extensions_.ext_EXT_metal_surface);
#endif
  }

  std::vector<const char*> enabled_extensions;

  std::vector<VkExtensionProperties> supported_implementation_extensions;
  while (true) {
    uint32_t supported_implementation_extension_count = 0;
    const VkResult get_supported_implementation_extension_count_result =
        ifn.vkEnumerateInstanceExtensionProperties(
            nullptr, &supported_implementation_extension_count, nullptr);
    if (get_supported_implementation_extension_count_result != VK_SUCCESS &&
        get_supported_implementation_extension_count_result != VK_INCOMPLETE) {
      REXLOG_WARN("Failed to get the Vulkan instance extension count");
      return nullptr;
    }
    if (supported_implementation_extension_count) {
      supported_implementation_extensions.resize(supported_implementation_extension_count);
      const VkResult get_supported_implementation_extensions_result =
          ifn.vkEnumerateInstanceExtensionProperties(nullptr,
                                                     &supported_implementation_extension_count,
                                                     supported_implementation_extensions.data());
      if (get_supported_implementation_extensions_result == VK_INCOMPLETE) {
        continue;
      }
      if (get_supported_implementation_extensions_result != VK_SUCCESS) {
        REXLOG_WARN("Failed to get the Vulkan instance extensions");
        return nullptr;
      }
    }
    supported_implementation_extensions.resize(supported_implementation_extension_count);
    break;
  }

  for (const VkExtensionProperties& supported_extension : supported_implementation_extensions) {
    const auto requested_extension_it =
        requested_extensions.find(supported_extension.extensionName);
    if (requested_extension_it == requested_extensions.cend()) {
      continue;
    }
    assert_not_null(requested_extension_it->second);
    if (!*requested_extension_it->second) {
      enabled_extensions.emplace_back(requested_extension_it->first.c_str());
      *requested_extension_it->second = true;
    }
  }

  // If enabled layers are not present, will disable all extensions provided by
  // the layers by truncating the enabled extension vector to this size.
  const size_t enabled_implementation_extension_count = enabled_extensions.size();
  std::vector<bool*> enabled_layer_extension_enablement_bools;

  // Name pointers from `requested_layers` will be used in the enabled layer
  // vector.
  std::unordered_map<std::string, bool*> requested_layers;
  bool layer_khronos_validation = false;
  if (try_enable_validation) {
    requested_layers.emplace("VK_LAYER_KHRONOS_validation", &layer_khronos_validation);
  }

  std::vector<const char*> enabled_layers;

  if (!requested_layers.empty()) {
    std::vector<VkLayerProperties> available_layers;
    // "The list of available layers may change at any time due to actions
    // outside of the Vulkan implementation"
    while (true) {
      available_layers.clear();
      uint32_t available_layer_count = 0;
      const VkResult get_available_layer_count_result =
          ifn.vkEnumerateInstanceLayerProperties(&available_layer_count, nullptr);
      if (get_available_layer_count_result != VK_SUCCESS &&
          get_available_layer_count_result != VK_INCOMPLETE) {
        break;
      }
      if (available_layer_count) {
        available_layers.resize(available_layer_count);
        const VkResult get_available_layers_result =
            ifn.vkEnumerateInstanceLayerProperties(&available_layer_count, available_layers.data());
        if (get_available_layers_result == VK_INCOMPLETE) {
          // New layers were added.
          continue;
        }
        if (get_available_layers_result != VK_SUCCESS) {
          available_layers.clear();
          break;
        }
        // In case the second enumeration returned fewer layers.
        available_layers.resize(available_layer_count);
      }
      break;
    }

    if (!available_layers.empty()) {
      std::vector<VkExtensionProperties> supported_layer_extensions;

      for (const VkLayerProperties& available_layer : available_layers) {
        auto requested_layer_it = requested_layers.find(available_layer.layerName);
        if (requested_layer_it == requested_layers.cend()) {
          continue;
        }

        bool got_layer_extensions = true;
        // "Because the list of available layers may change externally between
        // calls to vkEnumerateInstanceExtensionProperties, two calls may
        // retrieve different results if a pLayerName is available in one call
        // but not in another."
        while (true) {
          uint32_t supported_layer_extension_count = 0;
          const VkResult get_supported_layer_extension_count_result =
              ifn.vkEnumerateInstanceExtensionProperties(nullptr, &supported_layer_extension_count,
                                                         nullptr);
          if (get_supported_layer_extension_count_result != VK_SUCCESS &&
              get_supported_layer_extension_count_result != VK_INCOMPLETE) {
            got_layer_extensions = false;
            break;
          }
          if (supported_layer_extension_count) {
            supported_layer_extensions.resize(supported_layer_extension_count);
            const VkResult get_supported_layer_extensions_result =
                ifn.vkEnumerateInstanceExtensionProperties(available_layer.layerName,
                                                           &supported_layer_extension_count,
                                                           supported_layer_extensions.data());
            if (get_supported_layer_extensions_result == VK_INCOMPLETE) {
              continue;
            }
            if (get_supported_layer_extensions_result != VK_SUCCESS) {
              got_layer_extensions = false;
              break;
            }
          }
          supported_layer_extensions.resize(supported_layer_extension_count);
          break;
        }
        if (!got_layer_extensions) {
          // The layer was possibly removed.
          continue;
        }

        for (const VkExtensionProperties& supported_extension : supported_layer_extensions) {
          const auto requested_extension_it =
              requested_extensions.find(supported_extension.extensionName);
          if (requested_extension_it == requested_extensions.cend()) {
            continue;
          }
          assert_not_null(requested_extension_it->second);
          // Don't add the extension to the enabled vector multiple times if
          // provided by the implementation itself or by another layer.
          if (!*requested_extension_it->second) {
            enabled_extensions.emplace_back(requested_extension_it->first.c_str());
            enabled_layer_extension_enablement_bools.push_back(requested_layer_it->second);
            *requested_extension_it->second = true;
          }
        }

        assert_not_null(requested_layer_it->second);
        if (!*requested_layer_it->second) {
          enabled_layers.emplace_back(requested_layer_it->first.c_str());
          *requested_layer_it->second = true;
        }
      }
    }
  }

  // Create the instance.

  VkApplicationInfo application_info;
  application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  application_info.pNext = nullptr;
  application_info.pApplicationName = "Xenia";
  application_info.applicationVersion = 1;
  application_info.pEngineName = nullptr;
  application_info.engineVersion = 0;
  // "The patch version number specified in apiVersion is ignored when creating
  // an instance object."
  // "Vulkan 1.0 implementations were required to return
  // VK_ERROR_INCOMPATIBLE_DRIVER if apiVersion was larger than 1.0."
  application_info.apiVersion = vulkan_instance->api_version_ >= VK_MAKE_API_VERSION(0, 1, 1, 0)
                                    ? VulkanDevice::kHighestUsedApiMinorVersion
                                    : VK_MAKE_API_VERSION(0, 1, 0, 0);

  VkInstanceCreateInfo instance_create_info;
  instance_create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  instance_create_info.pNext = nullptr;
  instance_create_info.flags = 0;
  // VK_KHR_get_physical_device_properties2 is needed to get the portability
  // subset features.
  if (vulkan_instance->extensions_.ext_KHR_portability_enumeration &&
      vulkan_instance->extensions_.ext_1_1_KHR_get_physical_device_properties2) {
    instance_create_info.flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
  }
  instance_create_info.pApplicationInfo = &application_info;
  instance_create_info.enabledLayerCount = uint32_t(enabled_layers.size());
  instance_create_info.ppEnabledLayerNames = enabled_layers.data();
  instance_create_info.enabledExtensionCount = uint32_t(enabled_extensions.size());
  instance_create_info.ppEnabledExtensionNames = enabled_extensions.data();
  VkResult instance_create_result =
      ifn.vkCreateInstance(&instance_create_info, nullptr, &vulkan_instance->instance_);

  if (instance_create_result == VK_ERROR_LAYER_NOT_PRESENT ||
      instance_create_result == VK_ERROR_EXTENSION_NOT_PRESENT) {
    // A layer was possibly removed. Try without layers.
    for (bool* const extension_enablement : enabled_layer_extension_enablement_bools) {
      *extension_enablement = false;
    }
    for (const std::pair<std::string, bool*>& requested_layer : requested_layers) {
      *requested_layer.second = false;
    }
    instance_create_info.enabledLayerCount = 0;
    instance_create_info.enabledExtensionCount = uint32_t(enabled_implementation_extension_count);
    instance_create_result =
        ifn.vkCreateInstance(&instance_create_info, nullptr, &vulkan_instance->instance_);
  }

  if (instance_create_result != VK_SUCCESS) {
    REXLOG_ERROR("Failed to create a Vulkan instance: {}",
                 vk::to_string(vk::Result(instance_create_result)));
    return nullptr;
  }

  // Load instance functions.

#define XE_UI_VULKAN_FUNCTION(name)                                                            \
  functions_loaded &=                                                                          \
      (ifn.name = PFN_##name(ifn.vkGetInstanceProcAddr(vulkan_instance->instance_, #name))) != \
      nullptr;

  // Vulkan 1.0.
#include <rex/ui/vulkan/functions/instance_1_0.inc>

  // Extensions promoted to a Vulkan version supported by the instance.
#define XE_UI_VULKAN_FUNCTION_PROMOTED(extension_name, core_name)                 \
  functions_loaded &= (ifn.core_name = PFN_##core_name(ifn.vkGetInstanceProcAddr( \
                           vulkan_instance->instance_, #core_name))) != nullptr;
  if (vulkan_instance->api_version_ >= VK_MAKE_API_VERSION(0, 1, 1, 0)) {
#include <rex/ui/vulkan/functions/instance_1_1_khr_get_physical_device_properties2.inc>
  }
#undef XE_UI_VULKAN_FUNCTION_PROMOTED

  // Non-promoted extensions, and extensions promoted to a Vulkan version not
  // supported by the instance.
#define XE_UI_VULKAN_FUNCTION_PROMOTED(extension_name, core_name)                 \
  functions_loaded &= (ifn.core_name = PFN_##core_name(ifn.vkGetInstanceProcAddr( \
                           vulkan_instance->instance_, #extension_name))) != nullptr;
  if (vulkan_instance->api_version_ < VK_MAKE_API_VERSION(0, 1, 1, 0)) {
    if (vulkan_instance->extensions_.ext_1_1_KHR_get_physical_device_properties2) {
#include <rex/ui/vulkan/functions/instance_1_1_khr_get_physical_device_properties2.inc>
    }
  }
#ifdef VK_USE_PLATFORM_XCB_KHR
  if (vulkan_instance->extensions_.ext_KHR_xcb_surface) {
#include <rex/ui/vulkan/functions/instance_khr_xcb_surface.inc>
  }
#endif
#ifdef VK_USE_PLATFORM_WAYLAND_KHR
  if (vulkan_instance->extensions_.ext_KHR_wayland_surface) {
#include <rex/ui/vulkan/functions/instance_khr_wayland_surface.inc>
  }
#endif
#ifdef VK_USE_PLATFORM_ANDROID_KHR
  if (vulkan_instance->extensions_.ext_KHR_android_surface) {
#include <rex/ui/vulkan/functions/instance_khr_android_surface.inc>
  }
#endif
#ifdef VK_USE_PLATFORM_WIN32_KHR
  if (vulkan_instance->extensions_.ext_KHR_win32_surface) {
#include <rex/ui/vulkan/functions/instance_khr_win32_surface.inc>
  }
#endif
#ifdef VK_USE_PLATFORM_METAL_EXT
  if (vulkan_instance->extensions_.ext_EXT_metal_surface) {
#include <rex/ui/vulkan/functions/instance_ext_metal_surface.inc>
  }
#endif
  if (vulkan_instance->extensions_.ext_KHR_surface) {
#include <rex/ui/vulkan/functions/instance_khr_surface.inc>
  }
  if (vulkan_instance->extensions_.ext_EXT_debug_utils) {
#include <rex/ui/vulkan/functions/instance_ext_debug_utils.inc>
  }
#undef XE_UI_VULKAN_FUNCTION_PROMOTED

#undef XE_UI_VULKAN_FUNCTION

  if (!functions_loaded) {
    REXLOG_ERROR("Failed to get all Vulkan instance function pointers");
    return nullptr;
  }

  // Check whether a surface can be created.

  if (with_surface &&
      !VulkanPresenter::GetSurfaceTypesSupportedByInstance(vulkan_instance->extensions_)) {
    REXLOG_ERROR("The Vulkan instance doesn't support surface types used by Xenia");
    return nullptr;
  }

  // Log instance properties.

  REXLOG_INFO("Vulkan instance API version {}.{}.{}. Enabled layers and extensions:",
              VK_VERSION_MAJOR(vulkan_instance->api_version_),
              VK_VERSION_MINOR(vulkan_instance->api_version_),
              VK_VERSION_PATCH(vulkan_instance->api_version_));
  for (uint32_t enabled_layer_index = 0;
       enabled_layer_index < instance_create_info.enabledLayerCount; ++enabled_layer_index) {
    REXLOG_INFO("* {}", instance_create_info.ppEnabledLayerNames[enabled_layer_index]);
  }
  for (uint32_t enabled_extension_index = 0;
       enabled_extension_index < instance_create_info.enabledExtensionCount;
       ++enabled_extension_index) {
    REXLOG_INFO("* {}", instance_create_info.ppEnabledExtensionNames[enabled_extension_index]);
  }

  // Create the debug messenger if requested and available.

  if (vulkan_instance->extensions_.ext_EXT_debug_utils && REXCVAR_GET(vulkan_log_debug_messages)) {
    VkDebugUtilsMessengerCreateInfoEXT debug_utils_messenger_create_info = {
        VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    auto gpu_logger = rex::GetLogger(rex::log::gpu());
    if (gpu_logger) {
      if (gpu_logger->should_log(spdlog::level::debug)) {
        debug_utils_messenger_create_info.messageSeverity |=
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT;
      }
      if (gpu_logger->should_log(spdlog::level::info)) {
        debug_utils_messenger_create_info.messageSeverity |=
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT;
      }
      if (gpu_logger->should_log(spdlog::level::warn)) {
        debug_utils_messenger_create_info.messageSeverity |=
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
      }
      if (gpu_logger->should_log(spdlog::level::err)) {
        debug_utils_messenger_create_info.messageSeverity |=
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
      }
    }
    // VUID-VkDebugUtilsMessengerCreateInfoEXT-messageSeverity-requiredbitmask:
    // "messageSeverity must not be 0"
    if (debug_utils_messenger_create_info.messageSeverity) {
      debug_utils_messenger_create_info.messageType =
          VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
          VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
          VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
      debug_utils_messenger_create_info.pfnUserCallback = DebugUtilsMessengerCallback;
      debug_utils_messenger_create_info.pUserData = vulkan_instance.get();
      const VkResult debug_utils_messenger_create_result = ifn.vkCreateDebugUtilsMessengerEXT(
          vulkan_instance->instance_, &debug_utils_messenger_create_info, nullptr,
          &vulkan_instance->debug_utils_messenger_);
      if (debug_utils_messenger_create_result != VK_SUCCESS) {
        REXLOG_WARN("Failed to create the Vulkan debug utils messenger: {}",
                    vk::to_string(vk::Result(debug_utils_messenger_create_result)));
      }
    }
  }

  return vulkan_instance;
}

VulkanInstance::~VulkanInstance() {
  if (instance_) {
    if (debug_utils_messenger_ != VK_NULL_HANDLE) {
      functions_.vkDestroyDebugUtilsMessengerEXT(instance_, debug_utils_messenger_, nullptr);
    }

    functions_.vkDestroyInstance(instance_, nullptr);
  }

  loader_.Close();
}

void VulkanInstance::EnumeratePhysicalDevices(
    std::vector<VkPhysicalDevice>& physical_devices_out) const {
  physical_devices_out.clear();
  while (true) {
    uint32_t physical_device_count = 0;
    const VkResult get_physical_device_count_result =
        functions_.vkEnumeratePhysicalDevices(instance_, &physical_device_count, nullptr);
    if ((get_physical_device_count_result != VK_SUCCESS &&
         get_physical_device_count_result != VK_INCOMPLETE) ||
        !physical_device_count) {
      return;
    }
    physical_devices_out.resize(physical_device_count);
    const VkResult get_physical_devices_result = functions_.vkEnumeratePhysicalDevices(
        instance_, &physical_device_count, physical_devices_out.data());
    if (get_physical_devices_result == VK_INCOMPLETE) {
      continue;
    }
    physical_devices_out.resize(get_physical_devices_result == VK_SUCCESS ? physical_device_count
                                                                          : 0);
    return;
  }
}

VkBool32 VulkanInstance::DebugUtilsMessengerCallback(
    VkDebugUtilsMessageSeverityFlagBitsEXT message_severity,
    VkDebugUtilsMessageTypeFlagsEXT message_types,
    const VkDebugUtilsMessengerCallbackDataEXT* callback_data, [[maybe_unused]] void* user_data) {
  std::ostringstream log_str;

  log_str << "Vulkan " << vk::to_string(vk::DebugUtilsMessageSeverityFlagBitsEXT(message_severity))
          << " (" << vk::to_string(vk::DebugUtilsMessageTypeFlagsEXT(message_types)) << ", ID "
          << callback_data->messageIdNumber;
  if (callback_data->pMessageIdName) {
    log_str << ": " << callback_data->pMessageIdName;
  }
  log_str << ')';

  if (callback_data->pMessage) {
    log_str << ": " << callback_data->pMessage;
  }

  bool annotations_begun = false;
  const auto begin_annotation = [&log_str, &annotations_begun]() {
    log_str << (annotations_begun ? ", " : " (");
    annotations_begun = true;
  };

  for (uint32_t queue_label_index = 0; queue_label_index < callback_data->queueLabelCount;
       ++queue_label_index) {
    begin_annotation();
    log_str << "queue label " << queue_label_index << ": "
            << callback_data->pQueueLabels[queue_label_index].pLabelName;
  }

  for (uint32_t cmd_buf_label_index = 0; cmd_buf_label_index < callback_data->cmdBufLabelCount;
       ++cmd_buf_label_index) {
    begin_annotation();
    log_str << "command buffer label " << cmd_buf_label_index << ": "
            << callback_data->pCmdBufLabels[cmd_buf_label_index].pLabelName;
  }

  for (uint32_t object_index = 0; object_index < callback_data->objectCount; ++object_index) {
    begin_annotation();
    const VkDebugUtilsObjectNameInfoEXT& object_info = callback_data->pObjects[object_index];
    // Lowercase hexadecimal digits in the handle to match the default Vulkan
    // debug utils messenger.
    log_str << "object " << object_index << ": "
            << vk::to_string(vk::ObjectType(object_info.objectType)) << " 0x" << std::hex
            << object_info.objectHandle << std::dec;
    if (object_info.pObjectName) {
      log_str << " '" << object_info.pObjectName << '\'';
    }
  }

  if (annotations_begun) {
    log_str << ')';
  }

  auto msg = log_str.str();
  if (message_severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
    REXGPU_ERROR("{}", msg);
  } else if (message_severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
    REXGPU_WARN("{}", msg);
  } else if (message_severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) {
    REXGPU_INFO("{}", msg);
  } else {
    REXGPU_DEBUG("{}", msg);
  }

  return VK_FALSE;
}

}  // namespace vulkan
}  // namespace ui
}  // namespace rex
