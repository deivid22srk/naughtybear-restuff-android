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
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>

#include <SDL3/SDL.h>
#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_joystick.h>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>
#include <rex/memory/utils.h>
#include <rex/platform.h>
#include <rex/thread.h>
#include <rex/ui/windowed_app.h>
#include <rex/ui/windowed_app_context_sdl.h>

#define ALOG(...) __android_log_print(ANDROID_LOG_INFO, "restuff", __VA_ARGS__)

namespace restuff_android {

// ----------------------------------------------------------------------
// Virtual gamepad P1 (SDL virtual joystick, tipo GAMEPAD)
// ----------------------------------------------------------------------

// Bitmask recebido do overlay Kotlin — MESMA ordem do enum
// SDL_GAMEPAD_BUTTON_*: A, B, X, Y, Back, Guide, Start, LS, RS,
// DUp, DDown, DLeft, DRight (bits 0..14). Bits 15/16 = LT/RT analógicos.
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
  for (int i = 0; i < kVirtualButtons; ++i) {
    SDL_SetJoystickVirtualButton(g_virtual_joystick, i,
                                 g_buttons[i] ? SDL_PRESSED : SDL_RELEASED);
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

// ----------------------------------------------------------------------
// SDL_main — fluxo principal do motor
// ----------------------------------------------------------------------

int main(int argc, char** argv) {
  for (int i = 0; i < argc; ++i) {
    ALOG("argv[%d]=%s", i, argv[i]);
  }

  // Opções do launcher chegam como flags --restuff-*: traduz para o formato
  // que o restuff espera (env do hook de 60fps + env do config path).
  for (int i = 0; i < argc; ++i) {
    const char* a = argv[i];
    if (strncmp(a, "--fps60=", 8) == 0) {
      if (strcmp(a + 8, "true") == 0) {
        setenv("RESTUFF_FPS60", "1", 1);
      }
    } else if (strncmp(a, "--config=", 9) == 0) {
      setenv("REX_CONFIG_PATH", a + 9, 1);
    }
  }

  // Hooks Android do SDK: dlopen de libc/libandroid + captura da JavaVM
  // para a ponte SAF (deve rodar na thread principal do SDL, antes de
  // qualquer thread/mapped memory do motor).
  rex::thread::AndroidInitialize();
  rex::memory::AndroidInitialize();
  rex::filesystem::AndroidInitialize();

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

    // Cria o app registrado por REX_DEFINE_APP(restuff, ...) no main.cpp do
    // ReStuff (XE_UI_WINDOWED_APPS_IN_LIBRARY=1 no Android).
    rex::ui::WindowedApp::Creator creator =
        rex::ui::WindowedApp::GetCreator("restuff");
    if (!creator) {
      ALOG("app creator 'restuff' not registered");
      return EXIT_FAILURE;
    }
    std::unique_ptr<rex::ui::WindowedApp> app = creator(app_context);

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

    result = app->OnInitialize() ? app_context.RunMainMessageLoop() : EXIT_FAILURE;

    app->InvokeOnDestroy();
  }

  rex::filesystem::AndroidShutdown();
  rex::memory::AndroidShutdown();
  rex::thread::AndroidShutdown();

  ALOG("restuff native main exiting with %d", result);
  return result;
}
