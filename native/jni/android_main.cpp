// restuff - Android port (naughtybear-restuff-android)
//
// Entrada nativa do motor no Android. O SDLActivity Java (SDL3 embutido)
// cria a thread nativa e chama SDL_main(argc, argv) com os argumentos
// fornecidos por GameActivity#getArguments().
//
// Este arquivo replica o fluxo do windowed_app_main_sdl.cpp do SDK
// (cvar::Init → SDLWindowedAppContext → WindowedApp "restuff" → loop),
// adicionando as pontes específicas de Android:
//   - env RESTUFF_FPS60 (unlock de 60 fps do ReStuff, lido por getenv)
//   - virtual joystick P1 (overlay Compose → JNI → SDL)
//   - caminho do restuff.toml no armazenamento privado do app

#include <SDL3/SDL_main.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>

#include <SDL3/SDL.h>
#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_joystick.h>
#include <SDL3/SDL_system.h>  // SDL_GetAndroidActivity / SDL_GetAndroidJNIEnv

#include <cstdio>
#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/main_android.h>
#include <rex/memory/utils.h>
#include <rex/platform.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

// Port Android (naughtybear-restuff-android): crash handler nativo — ver
// InstallCrashHandler/RestuffCrashHandler abaixo.
#include <cerrno>
#include <cstdint>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/types.h>
#include <unistd.h>
#include <unwind.h>

#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, "restuff", __VA_ARGS__)

namespace restuff_android {

// ----------------------------------------------------------------------
// Crash handler nativo (stack trace persistido)
// ----------------------------------------------------------------------
// [motivação] Antes: crash de código nativo (SIGSEGV/SIGABRT/...) só gerava
// "Fatal signal" no logcat e um tombstone em /data/tombstones — inacessível
// sem root/adb. O usuário não conseguia anexar o stack trace a um relatório.
// Agora: backtrace via _Unwind_Backtrace é gravado (best-effort,
// async-signal-safe o máximo possível) no ARQUIVO DE LOG ativo (mesmo arquivo
// do spdlog — caminho passado via --log-file=) e em <files>/last_crash.txt
// (sempre gravável, lido pela tela de Diagnóstico), além do logcat (FATAL).
// Depois o handler restaura o default e re-raise — o debuggerd ainda gera o
// tombstone oficial.

constexpr size_t kMaxCrashFrames = 32;

struct CrashGlobals {
  char log_path[512];      // arquivo de log da sessão ("" = nenhum)
  char private_path[512];  // <files>/last_crash.txt ("" = nenhum)
};

CrashGlobals g_crash = {};

struct UnwindState {
  void* frames[kMaxCrashFrames];
  size_t count;
  size_t skip;
};

_Unwind_Reason_Code CrashUnwindCallback(struct _Unwind_Context* context, void* arg) {
  auto* state = static_cast<UnwindState*>(arg);
  if (state->skip > 0) {
    --state->skip;
    return _URC_NO_REASON;
  }
  const uintptr_t pc = _Unwind_GetIP(context);
  if (pc != 0 && state->count < kMaxCrashFrames) {
    state->frames[state->count++] = reinterpret_cast<void*>(pc);
  }
  return _URC_NO_REASON;
}

// write() é async-signal-safe; tudo mais aqui é best-effort.
void CrashWriteAll(int fd, const char* text) {
  if (fd < 0) return;
  size_t len = strlen(text);
  while (len > 0) {
    const ssize_t n = write(fd, text, len);
    if (n <= 0) return;
    text += n;
    len -= static_cast<size_t>(n);
  }
}

extern "C" void RestuffCrashHandler(int sig, siginfo_t* info, void*) {
  const char* signame = "?";
  switch (sig) {
    case SIGSEGV: signame = "SIGSEGV"; break;
    case SIGBUS: signame = "SIGBUS"; break;
    case SIGABRT: signame = "SIGABRT"; break;
    case SIGFPE: signame = "SIGFPE"; break;
    case SIGILL: signame = "SIGILL"; break;
    default: break;
  }

  __android_log_print(ANDROID_LOG_FATAL, "restuff-rex",
                      "=== CRASH NATIVO (%s, si_code=%d, addr=%p) — coletando "
                      "backtrace ===",
                      signame, info ? info->si_code : 0,
                      info ? info->si_addr : nullptr);

  // Backtrace (best-effort; pula os 2 frames internos do handler).
  UnwindState state = {};
  // skip=1: _Unwind_Backtrace começa no chamador (RestuffCrashHandler);
  // o frame do signal trampoline (__restore_rt) pode ou não ser contado pelo
  // unwinder — prefere-se MOSTRAR um frame inútil a PERDER o frame que
  // faultou (o tombstone oficial do debuggerd cobre qualquer dúvida).
  state.skip = 1;
  _Unwind_Backtrace(CrashUnwindCallback, &state);

  // Logcat: cada frame com módulo/offset via dladdr (best-effort).
  for (size_t i = 0; i < state.count; ++i) {
    Dl_info dlinfo = {};
    const bool has_info = dladdr(state.frames[i], &dlinfo) != 0;
    if (has_info && dlinfo.dli_sname != nullptr) {
      __android_log_print(ANDROID_LOG_FATAL, "restuff-rex", "  #%02zu  %p  %s + %td",
                          i, state.frames[i], dlinfo.dli_sname,
                          reinterpret_cast<char*>(state.frames[i]) -
                              reinterpret_cast<char*>(dlinfo.dli_saddr));
    } else {
      __android_log_print(ANDROID_LOG_FATAL, "restuff-rex", "  #%02zu  %p  (%s%s)", i,
                          state.frames[i],
                          has_info && dlinfo.dli_fname ? dlinfo.dli_fname : "?",
                          has_info && dlinfo.dli_sname ? "" : " sem símbolo");
    }
  }

  // Arquivo de log da sessão (se houver) + last_crash.txt (sempre).
  for (const char* target : {g_crash.log_path, g_crash.private_path}) {
    if (target[0] == '\0') continue;
    const int fd = open(target, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) continue;
    char header[256];
    snprintf(header, sizeof(header),
             "\n=== CRASH NATIVO: %s (si_code=%d, addr=%p) — backtrace ===\n",
             signame, info ? info->si_code : 0, info ? info->si_addr : nullptr);
    CrashWriteAll(fd, header);
    for (size_t i = 0; i < state.count; ++i) {
      Dl_info dlinfo = {};
      char line[512];
      if (dladdr(state.frames[i], &dlinfo) != 0 && dlinfo.dli_sname != nullptr) {
        snprintf(line, sizeof(line), "  #%02zu  %p  %s + %td\n", i, state.frames[i],
                 dlinfo.dli_sname,
                 reinterpret_cast<char*>(state.frames[i]) -
                     reinterpret_cast<char*>(dlinfo.dli_saddr));
      } else {
        snprintf(line, sizeof(line), "  #%02zu  %p  (%s)\n", i, state.frames[i],
                 dladdr(state.frames[i], &dlinfo) != 0 && dlinfo.dli_fname
                     ? dlinfo.dli_fname
                     : "?");
      }
      CrashWriteAll(fd, line);
    }
    CrashWriteAll(fd, "=== fim do backtrace (tombstone oficial no logcat) ===\n");
    close(fd);
  }

  // Re-raise com handler default: o debuggerd gera o tombstone oficial.
  signal(sig, SIG_DFL);
  raise(sig);
}

void InstallCrashHandler(const char* log_file_path, const char* files_dir) {
  if (log_file_path != nullptr) {
    snprintf(g_crash.log_path, sizeof(g_crash.log_path), "%s", log_file_path);
  }
  if (files_dir != nullptr && files_dir[0] != '\0') {
    snprintf(g_crash.private_path, sizeof(g_crash.private_path), "%s/last_crash.txt",
             files_dir);
  }
  static char alt_stack[64 * 1024];  // stack separado p/ o handler (SA_ONSTACK)
  stack_t ss = {};
  ss.ss_sp = alt_stack;
  ss.ss_size = sizeof(alt_stack);
  ss.ss_flags = 0;
  if (sigaltstack(&ss, nullptr) != 0) {
    ALOG("crash handler: sigaltstack falhou (%s)", strerror(errno));
  }
  struct sigaction sa = {};
  sa.sa_sigaction = RestuffCrashHandler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);
  const int signals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL};
  for (const int s : signals) {
    sigaction(s, &sa, nullptr);
  }
  ALOG("crash handler instalado (log: %s)", g_crash.log_path[0] ? g_crash.log_path : "<privado>");
}

// ----------------------------------------------------------------------
// Virtual gamepad P1 (SDL virtual joystick, tipo GAMEPAD)
// ----------------------------------------------------------------------

// Bitmask recebido do overlay Kotlin — ORDEM CANÔNICA do enum
// SDL_GAMEPAD_BUTTON_* (o SDL mapeia o botão virtual i para o botão
// padrão i): A, B, X, Y, Back, Guide, Start, LS(7), RS(8), LB(9), RB(10),
// DUp(11), DDown(12), DLeft(13), DRight(14) — bits 0..14.
// Bits 15/16 = LT/RT analógicos (vão pelos eixos 4/5, não pelo bitmask).
constexpr int kBitLt = 15;
constexpr int kBitRt = 16;

constexpr int kVirtualAxes = 6;    // LX, LY, RX, RY, LT, RT (ordem SDL_GAMEPAD_AXIS_*)
constexpr int kVirtualButtons = 15;

SDL_JoystickID g_virtual_device_id = 0;
SDL_Joystick* g_virtual_joystick = nullptr;
std::mutex g_pad_mutex;
int g_buttons[kVirtualButtons] = {};
Sint16 g_lx = 0, g_ly = 0, g_rx = 0, g_ry = 0;
Sint16 g_lt = 0, g_rt = 0;  // 0..32767 (normalização do gamepad SDL)

void AttachVirtualGamepad() {
  // O overlay é anexado antes do input driver do SDK — garante o subsistema.
  if (!SDL_InitSubSystem(SDL_INIT_GAMEPAD)) {
    ALOG("SDL_InitSubSystem(GAMEPAD) failed: %s", SDL_GetError());
    return;
  }
  SDL_VirtualJoystickDesc desc;
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.naxes = kVirtualAxes;
  desc.nbuttons = kVirtualButtons;
  desc.button_mask = 0;  // 0 => todos os botões padrão assumidos
  desc.axis_mask = (1u << SDL_GAMEPAD_AXIS_LEFTX) | (1u << SDL_GAMEPAD_AXIS_LEFTY) |
                   (1u << SDL_GAMEPAD_AXIS_RIGHTX) | (1u << SDL_GAMEPAD_AXIS_RIGHTY) |
                   (1u << SDL_GAMEPAD_AXIS_LEFT_TRIGGER) |
                   (1u << SDL_GAMEPAD_AXIS_RIGHT_TRIGGER);
  desc.name = "Restuff Virtual Pad";
  g_virtual_device_id = SDL_AttachVirtualJoystick(&desc);
  if (g_virtual_device_id == 0) {
    ALOG("virtual gamepad attach failed: %s", SDL_GetError());
    return;
  }
  g_virtual_joystick = SDL_OpenJoystick(g_virtual_device_id);
  if (!g_virtual_joystick) {
    ALOG("virtual gamepad open failed: %s", SDL_GetError());
  }
}

void PushVirtualPadState() {
  if (!g_virtual_joystick) return;
  // Botões: cada bit vira SDL_SetJoystickVirtualButton na MESMA posição.
  // SDL3: estado de botão é bool (SDL_PRESSED/SDL_RELEASED são SDL2).
  for (int i = 0; i < kVirtualButtons; ++i) {
    SDL_SetJoystickVirtualButton(g_virtual_joystick, i, g_buttons[i] != 0);
  }
  // Eixos: ordem SDL_GAMEPAD_AXIS_ (0 LX, 1 LY, 2 RX, 3 RY, 4 LT, 5 RT).
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_LEFTX, g_lx);
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_LEFTY, g_ly);
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_RIGHTX, g_rx);
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_RIGHTY, g_ry);
  // Gatilhos: joystick espera faixa completa (MIN..MAX); gamepad normaliza.
  Sint16 lt16 = static_cast<Sint16>(static_cast<int>(g_lt) * 2 - 32768);
  Sint16 rt16 = static_cast<Sint16>(static_cast<int>(g_rt) * 2 - 32768);
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, lt16);
  SDL_SetJoystickVirtualAxis(g_virtual_joystick, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, rt16);
}

void ApplyVirtualPad(int buttons, int lx, int ly, int rx, int ry, int lt, int rt) {
  std::lock_guard<std::mutex> lock(g_pad_mutex);
  for (int i = 0; i < kVirtualButtons; ++i) {
    g_buttons[i] = (buttons & (1 << i)) ? 1 : 0;
  }
  g_lx = static_cast<Sint16>(lx);
  g_ly = static_cast<Sint16>(ly);
  g_rx = static_cast<Sint16>(rx);
  g_ry = static_cast<Sint16>(ry);
  g_lt = static_cast<Sint16>(std::clamp(lt, 0, 32767));
  g_rt = static_cast<Sint16>(std::clamp(rt, 0, 32767));
  PushVirtualPadState();
}

}  // namespace restuff_android

// ----------------------------------------------------------------------
// JNI
// ----------------------------------------------------------------------

extern "C" JNIEXPORT jstring JNICALL
Java_com_deivid22srk_restuff_game_NativeBridge_nativeGetAbi(JNIEnv* env, jclass) {
#if defined(__aarch64__)
  return env->NewStringUTF("arm64-v8a");
#elif defined(__x86_64__)
  return env->NewStringUTF("x86_64");
#else
  return env->NewStringUTF("unknown");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_deivid22srk_restuff_game_NativeBridge_nativeSetVirtualPadState(
    JNIEnv*, jclass, jint buttons, jint lx, jint ly, jint rx, jint ry, jint lt,
    jint rt) {
  restuff_android::ApplyVirtualPad(buttons, lx, ly, rx, ry, lt, rt);
}

// Contador de FPS (design "Mel & Carvão"): total acumulado de quadros
// apresentados pelo backend Vulkan. O contador vive no vulkan_device.cpp
// (overlay do rexglue-sdk) como g_rexrestuff_vk_present_count com linkage
// C — um atomic relaxed increment por present via trampolim sobre
// vkQueuePresentKHR. O overlay Kotlin (FpsCounterView) diferencia duas
// leituras para calcular a taxa real do MOTOR.
extern "C" std::atomic<uint64_t> g_rexrestuff_vk_present_count;

extern "C" JNIEXPORT jlong JNICALL
Java_com_deivid22srk_restuff_game_NativeBridge_nativeGetPresentCount(JNIEnv*, jclass) {
  return static_cast<jlong>(
      g_rexrestuff_vk_present_count.load(std::memory_order_relaxed));
}

// ----------------------------------------------------------------------
// SDL_main — fluxo principal do motor
// ----------------------------------------------------------------------

int main(int argc, char** argv) {
  for (int i = 0; i < argc; ++i) {
    ALOG("argv[%d]=%s", i, argv[i]);
  }

  // Opções do launcher chegam como flags --restuff-*: traduz para o formato
  // que o restuff espera (env do hook de 60fps + env do config path).
  const char* app_files_dir = nullptr;
  const char* log_file_path = nullptr;
  for (int i = 0; i < argc; ++i) {
    const char* a = argv[i];
    if (strncmp(a, "--app-files-dir=", 16) == 0) {
      // Pasta de arquivos do app para o SDK: GetExecutableFolder/GetUserFolder
      // resolvem /proc/self/exe → /system/bin (read-only) no Android, o que
      // abortava o motor no InitLogging (create_directories /system/bin/logs).
      app_files_dir = a + 16;
      setenv("REX_ANDROID_FILES_DIR", app_files_dir, 1);
    } else if (strncmp(a, "--native-lib-dir=", 17) == 0) {
      // Port Android: nativeLibraryDir do app — casa dos hooks do AdrenoTools
      // (libmain_hook.so etc., exigem useLegacyPackaging=true) e da própria
      // libadrenotools.so. Lida por vulkan_instance.cpp ANTES de abrir o
      // driver customizado.
      setenv("REX_ANDROID_NATIVE_LIB_DIR", a + 17, 1);
    } else if (strncmp(a, "--log-file=", 11) == 0) {
      // Port Android: arquivo de log da sessão (storage público quando
      // gravável) — usado pelo crash handler p/ persistir o backtrace e como
      // sinalização p/ UI (o restuff.toml log_file aponta pro mesmo lugar).
      log_file_path = a + 11;
      setenv("RESTUFF_LOG_FILE", log_file_path, 1);
    } else if (strncmp(a, "--log-level=", 12) == 0) {
      // Port Android: nível de log pedido pelo launcher (toggle "Log
      // detalhado"). Vale para a fase EARLY (InitLoggingEarly lê REX_LOG_LEVEL)
      // e é coerente com o log_level do restuff.toml (mesma origem).
      setenv("REX_LOG_LEVEL", a + 12, 1);
    } else if (strncmp(a, "--fps60=", 8) == 0) {
      if (strcmp(a + 8, "true") == 0) {
        setenv("RESTUFF_FPS60", "1", 1);
      }
    } else if (strncmp(a, "--config=", 9) == 0) {
      setenv("REX_CONFIG_PATH", a + 9, 1);
    } else if (strncmp(a, "--cache_root=", 13) == 0) {
      // Os caches do renderer (shader_spv.bin / pipeline_cache.bin /
      // pipeline_prewarm.bin) são ancorados no diretório do executável via
      // /proc/self/exe → /system/bin (read-only no Android: "M4.40 SPIR-V
      // cache save failed to open /system/bin/..."). Todos respeitam um env
      // de override — redireciona para o cache dir privado do app.
      const char* cache_root = a + 13;
      std::error_code fs_ec;
      std::filesystem::create_directories(cache_root, fs_ec);
      std::string spv = std::string(cache_root) + "/shader_spv.bin";
      std::string pipe = std::string(cache_root) + "/pipeline_cache.bin";
      std::string prewarm = std::string(cache_root) + "/pipeline_prewarm.bin";
      setenv("RESTUFF_SPVCACHE_FILE", spv.c_str(), 1);
      setenv("RESTUFF_PIPE_CACHE", pipe.c_str(), 1);
      setenv("RESTUFF_PREWARM_FILE", prewarm.c_str(), 1);
    } else if (strncmp(a, "--env=", 6) == 0) {
      // perf/sd695-30fps v2 (15-e3): pass-through genérico KEY=VALUE para
      // variáveis RESTUFF_* lidas por statics FUNCIONAIS (inicializados na
      // primeira chamada, depois do SDL_main — a imensa maioria: todos os
      // gates de diagnóstico do native_vk/hooks/xma/xthread/presenter).
      // EXCEÇÕES conhecidas (statics de ESCOPO DE NAMESPACE, avaliados no
      // dlopen do librestuff.so, ANTES daqui — o override aqui não os alcança):
      // RESTUFF_DUMP_DRAWS, RESTUFF_EXT_TRUE, RESTUFF_EXTQPROBE
      // (native_backend_vk.cpp). Cada aplicação é logada via ALOG e
      // espelhada no log de ARQUIVO pela linha [ENV] (RestuffEnvSummary varre
      // environ no início do present thread) — a corrida carrega a própria
      // configuração.
      const char* kv = a + 6;
      if (const char* eq = strchr(kv, '=')) {
        const std::string key(kv, eq - kv);
        setenv(key.c_str(), eq + 1, 1);
        ALOG("[env] %s", a + 6);
      }
    }
  }

  // perf/sd695-30fps v2 (15-e3): overrides de env de diagnóstico a partir de
  // <files>/perf_env.txt (linhas KEY=VALUE; '#' comenta; linha vazia e espaço
  // em branco à esquerda ignorados). Mesma motivação do --env=: a CORRIDA DE
  // CONTROLE da instrumentação always-on tem que ser possível sem rebuild —
  // com o arquivo, o usuário cria um texto com "RESTUFF_NO_GPUPASS=1" em
  // Android/data/com.deivid22srk.restuff/files/ com qualquer gerenciador de
  // arquivos e a próxima sessão roda o A/B. Cada linha aplicada é logada
  // (ALOG + aparece no log de ARQUIVO via [ENV]) para que o log de campo
  // seja auto-descritivo. Vars RESTUFF_* setadas aqui sobrevivem até a linha
  // [ENV] — exceto as 3 exceções de statics de load-time (ver comentário do
  // --env=). RENOMEIE/APAGUE o arquivo após o A/B: um arquivo esquecido
  // re-aplica silenciosamente a cada sessão (detectável pela [ENV]).
  if (app_files_dir != nullptr) {
    char env_path[512];
    snprintf(env_path, sizeof(env_path), "%s/perf_env.txt", app_files_dir);
    if (FILE* f = fopen(env_path, "r")) {
      char line[512];
      while (fgets(line, sizeof(line), f)) {
        if (char* nl = strchr(line, '\n')) *nl = '\0';
        if (char* cr = strchr(line, '\r')) *cr = '\0';
        // 15-e4: trim de espaços/tabs à esquerda — " KEY=1" com indentação
        // setava uma var com espaço no nome (inócua mas confusa no [ENV]).
        char* p = line;
        while (*p == ' ' || *p == '\t') ++p;
        if (*p == '#' || *p == '\0') continue;
        char* eq = strchr(p, '=');
        if (!eq || eq == p) continue;
        *eq = '\0';
        setenv(p, eq + 1, 1);
        ALOG("[perf_env] %s=%s", p, eq + 1);
      }
      fclose(f);
    }
  }

  // Driver Vulkan customizado (padrão AdrenoTools/Turnip): o Driver Manager
  // (tela Configurações) persiste <files>/drivers/active.txt com
  // "lib=<caminho absoluto do .so>". Lê aqui, ANTES de qualquer init
  // gráfico — vulkan_instance.cpp carrega via libadrenotools (hooks) e cai
  // para o driver do sistema em caso de falha, logando o motivo real.
  if (app_files_dir != nullptr) {
    char active_path[512];
    snprintf(active_path, sizeof(active_path), "%s/drivers/active.txt",
             app_files_dir);
    if (FILE* f = fopen(active_path, "r")) {
      char line[512];
      while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "lib=", 4) == 0) {
          if (char* nl = strchr(line + 4, '\n')) *nl = '\0';
          if (line[4] != '\0') {
            setenv("REX_VULKAN_LOADER_PATH", line + 4, 1);
            ALOG("Vulkan driver custom (AdrenoTools): %s", line + 4);
          }
          break;
        }
      }
      fclose(f);
    }
  }

  // Crash handler: o MAIS CEDO possível (args já parseados → caminhos
  // conhecidos), antes de qualquer init do motor/threads/memória mapeada.
  restuff_android::InstallCrashHandler(log_file_path, app_files_dir);

  // Hooks Android do SDK: dlopen de libc/libandroid no SDK + bootstrap JNI
  // da ponte SAF (JavaVM/Context do thread principal do SDL, antes de
  // qualquer thread/mapped memory do motor).
  rex::thread::AndroidInitialize();
  rex::memory::AndroidInitialize();
  {
    JNIEnv* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    JavaVM* vm = nullptr;
    if (env) {
      env->GetJavaVM(&vm);
    } else {
      ALOG("SDL_GetAndroidJNIEnv() nulo no bootstrap JNI");
    }
    rex::filesystem::SetAndroidJniContext(vm, SDL_GetAndroidActivity());
    rex::filesystem::AndroidInitialize();
  }

  auto remaining = rex::cvar::Init(argc, argv);
  rex::cvar::ApplyEnvironment();
  rex::InitLoggingEarly();
  ALOG("restuff native main starting");

  int result;
  {
    rex::ui::SDLWindowedAppContext app_context;
    if (!app_context.Initialize()) {
      ALOG("SDLWindowedAppContext::Initialize failed: %s", SDL_GetError());
      return EXIT_FAILURE;
    }
    ALOG("boot: contexto SDL inicializado");

    // Cria o app registrado por REX_DEFINE_APP(restuff, ...) no main.cpp do
    // ReStuff (XE_UI_WINDOWED_APPS_IN_LIBRARY=1 no Android).
    rex::ui::WindowedApp::Creator creator =
        rex::ui::WindowedApp::GetCreator("restuff");
    if (!creator) {
      ALOG("app creator 'restuff' not registered");
      return EXIT_FAILURE;
    }
    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);
    ALOG("boot: app 'restuff' (ReXApp) criado");

    // Casamento de argumentos posicionais restantes (mesma semântica do
    // entry point desktop do SDK).
    const auto& option_names = app->GetPositionalOptions();
    std::map<std::string, std::string> parsed;
    const size_t count = std::min(remaining.size(), option_names.size());
    for (size_t i = 0; i < count; ++i) {
      parsed[option_names[i]] = remaining[i];
    }
    app->SetParsedArguments(std::move(parsed));

    restuff_android::AttachVirtualGamepad();

    const bool init_ok = app->OnInitialize();
    ALOG("boot: ReXApp::OnInitialize -> %s", init_ok ? "ok" : "FALHOU");
    result = init_ok ? app_context.RunMainMessageLoop() : EXIT_FAILURE;
    ALOG("boot: RunMainMessageLoop retornou %d", result);

    app->InvokeOnDestroy();
  }

  rex::filesystem::AndroidShutdown();
  rex::memory::AndroidShutdown();
  rex::thread::AndroidShutdown();

  ALOG("restuff native main exiting with %d", result);
  return result;
}
