# Naughty Bear ReStuff — Port Android

Port Android (arm64-v8a) do **Naughty Bear ReStuff** — recompilação do
*Naughty Bear Gold Edition* (Xbox 360) feita com o [rexglue-SDK](https://github.com/rexglue/rexglue-sdk)
([MaxDeadBear/NaughtyBear_ReStuff](https://github.com/MaxDeadBear/NaughtyBear_ReStuff)).

Interface de seleção de dados baseada no
[port-screen-template](https://github.com/deivid22srk/port-screen-template)
(Kotlin + Jetpack Compose).

## Downloads

O APK é construído automaticamente pelo GitHub Actions em cada push:
aba **Actions** → build → artefato `naughtybear-restuff-apk`
(`NaughtyBear-ReStuff-arm64-release.apk`).

## Requisitos de dados do jogo (assets)

O port **não inclui** nenhum arquivo do jogo. Em primeira execução o app pede:

| Arquivo | Para quê | De onde |
|---|---|---|
| `Default.xex` | Build (codegen do recomp) **e** runtime | baixado pelo CI da [release Archive](https://github.com/deivid22srk/Naughty-Bear-archive/releases/download/Archive/Default.xex) |
| ISO da Naughty Bear Gold Edition | Runtime (conteúdo do jogo) | **você fornece** no app, via seletor de arquivos |

Como funciona o fluxo de dados (sem cópia do ISO):

1. Toque em **Selecionar ISO** e escolha o `.iso` do jogo (Storage Access
   Framework, com permissão persistida — o arquivo **não é copiado nem movido**).
2. O app abre o ISO via acesso aleatório (`ParcelFileDescriptor`) e extrai o
   conteúdo do disco (GDFX/XDVDFS) **uma única vez** para o armazenamento
   privado do app (~8 GB livres necessários).
3. O jogo roda da pasta extraída (montada como `\Device\Harddisk0\Partition1`
   pelo runtime do rexglue — mesma semântica do goopie launcher no PC).

Alternativa: **Selecionar Pasta Extraída** aceita uma pasta SAF que já contenha
o `Default.xex` e os arquivos do jogo soltos.

## Recursos do port

- **Renderer nativo Vulkan** do ReStuff (shaderc compila GLSL→SPIR-V em runtime)
- **Virtual gamepad minimalista** translúcido (padrão Xbox 360, P1 via SDL
  virtual joystick) com ajuste de opacidade/tamanho e haptics
- **Gamepads Bluetooth/USB** (P2+) via pipeline HID do SDL3
- **60 FPS unlock** (reescreve o PresentationInterval de 30→60) e
  **Unlock All** — opção "Motor ReStuff" nas Configurações
- **Driver Turnip customizado (AdrenoTools) de verdade**: carregado via
  `libadrenotools` (namespace linker + hooks) — o mesmo mecanismo do Winlator.
  Falha de carregamento é logada com o motivo real e o desfecho aparece em
  Configurações → Diagnóstico; em caso de falha o motor volta para o driver
  do sistema sem crashar
- **Log detalhado persistido em
  `/storage/emulated/0/Naughty Bear ReStuff/logs/`** (com fallback automático
  para o storage privado quando a permissão "Todos os arquivos" não foi
  concedida): timestamps, nível debug (toggle), rotação 5 MB × 20 e retenção
  de 10 sessões; espelho completo no logcat (tag `restuff-rex`) com mensagens
  longas fatiadas (o logd trunca ~4068 B/entrada)
- **Crash handler nativo**: SIGABRT/SIGBUS/SIGFPE sempre (e SIGSEGV/SIGILL na
  janela de boot) gravam backtrace (com símbolos via `dladdr`) no log da
  sessão e em `files/last_crash.txt` + logcat FATAL; o tombstone oficial do
  debuggerd continua sendo gerado (nota: após o início do runtime, faults de
  memória do guest passam pelo handler MMIO do SDK — sigfaults de host
  fora dessa janela contam com o tombstone do debuggerd)
- Tela de seleção de dados nível AAA (template): parallax, partículas,
  estados animados, créditos do portador
- Configurações persistidas: controles, cheat/60fps, log detalhado,
  limpar seleção

## Arquitetura

```
app/                      Kotlin + Compose (template) + GameActivity (SDLActivity)
native/CMakeLists.txt     librestuff.so: recomp + restuff src + SDK + JNI
native/jni/               SDL_main → cvar::Init → SDLWindowedAppContext → restuff
native/overlay/           arquivos sobrepostos aos submódulos (suporte Android)
scripts/                  codegen host + build NDK + overlays
restuff/                  (submódulo) NaughtyBear_ReStuff
rexglue-sdk/              (submódulo) SDK rexglue (Xenia-derived, BSD-3)
```

### Build local (Linux)

```bash
git clone --recurse-submodules https://github.com/deivid22srk/naughtybear-restuff-android
cd naughtybear-restuff-android
bash scripts/apply_overlays.sh
bash scripts/build_host_codegen.sh          # CLI rexglue + codegen do Default.xex
bash scripts/build_android_native.sh        # shaderc + librestuff.so (NDK arm64)
./gradlew :app:assembleDebug                # APK final
```

Variáveis: `ANDROID_NDK_HOME` (ou `ANDROID_HOME`), `ABI` (padrão arm64-v8a),
`API` (padrão 26), `FORCE_CODEGEN=1` para regenerar o recomp.

### Notas técnicas do port

- `REX_PLATFORM_ANDROID` do SDK: core POSIX já é bionic-friendly
  (ASharedMemory, pthreads). O port adiciona `surface_android.{h,cpp}`
  (`VK_KHR_android_surface` via `SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER`) e o
  branch Android em `window_sdl.cpp`/`src/ui/CMakeLists.txt`.
- Código recompilado usa SIMDE (SSE→NEON) — sem intrinsics x86 no arm64.
- Overlays x86-only do restuff (FPE guard por MXCSR, perf profiling) ficam
  compilados apenas em desktop Linux.
- SDL3 é linkado estático dentro de `librestuff.so` junto com o SDLActivity
  Java; o entry `SDL_main` recebe argv do `GameActivity#getArguments()`.
- Texturas BC/DXT: GPUs móveis raramente expõem `VK_EXT_texture_compression_bc`;
  o decoder CPU→RGBA8 do ReStuff cobre o caminho alternativo.
- **Compatibilidade com GPUs móveis**: `vulkan_require_geometry_shader` /
  `vulkan_require_fill_mode_non_solid` padrão **false** no Android (nenhum
  Adreno/Mali/Turnip expõe `geometryShader` — exigir rejeitava todos os
  devices e abortava o boot com tela preta; os caminhos de fallback do
  renderer já cobrem a ausência). O toml gerado a cada boot reforça ambos.
- **Carregamento do driver customizado**: `native/thirdparty/libadrenotools`
  (submódulo) é construído pelo `build_android_native.sh` (5 libs → jniLibs) e
  o APK usa `useLegacyPackaging=true` (os hooks precisam existir como
  arquivos em `nativeLibraryDir`). `vulkan_instance.cpp` chama
  `adrenotools_open_libvulkan(ADRENOTOOLS_DRIVER_CUSTOM, ...)` quando o
  Driver Manager exporta `REX_VULKAN_LOADER_PATH` — o handle devolvido é o
  loader do sistema com hooks que redirecionam a abertura do driver para o
  .so importado. `vkCmdBlitImage`/`vkCmdCopyImage`/`vkCmdClearColorImage`
  são resolvidos pela tabela da própria instância (`native_vk.cpp`), nunca
  por dlopen de soname.

## Créditos

- **Tom (crack)** e comunidade — [rexglue-SDK](https://github.com/rexglue/rexglue-sdk)
- **MaxDeadBear / Tynan / MadLadMikael** — [NaughtyBear_ReStuff](https://github.com/MaxDeadBear/NaughtyBear_ReStuff)
- **Xenia** e **XenonRecomp** — fundamentos do recomp Xbox 360
- **Hailgames (deivid22srk)** — port Android e template de interface
