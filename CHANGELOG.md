# Changelog — naughtybear-restuff-android

## perf(arm64): otimização mobile ARM — bateria, thermal e I/O (branch perf/otimizacao-arm64-mobile)

Auditoria de arquitetura + performance sobre evidência (caminho:linha), implementação
cirúrgica e validação de compatibilidade. Base: `feat/iso-inplace-sem-copia` (e7edbbc).

### Contexto da auditoria (o que já estava correto — não mexer)

- **ABI**: arm64-v8a puro (`app/build.gradle.kts:22`, `scripts/build_android_native.sh:13`).
  Remanescentes de 32-bit são inertes (mapeamento defensivo de triple, sample do SDL).
- **Intrínsecas x86**: nenhum escape não-guardado no caminho Android pós-overlay.
  Todo `_mm_*`/`__m128` vivo está sob `#if REX_ARCH_AMD64` com fallback NEON
  (`rexglue-sdk/src/core/memory.cpp:205`, `primitive_processor.cpp`, `xma_context.cpp`).
- **Endianness/alinhamento**: acessos ao guest usam `__builtin_bswap32/64` explícito;
  casts voláteis com gate de alinhamento (`hooks.cpp:411-414`); fiber AArch64 própria
  (`fiber_android.cpp`). Zero suposição x86 ativa.
- **SIMDE**: auto-detecção NEON ativa (simde v0.8.2, `simde-features.h:342-348`).

### Alterações aplicadas

| # | Arquivo | Mudança | Motivo (evidência) |
|---|---|---|---|
| 1 | overlay `NaughtyBear_ReStuff/src/hooks.cpp` | Frame limiter: spin final de 2ms → `sleep_until(deadline)` em POSIX (spin mantido em `_WIN32`; `RESTUFF_SPIN_LIMITER=1` restaura para A/B) | `on_swap` girava `while (clock::now() < deadline)` até 2ms/frame a 60fps = ~12% de um core em spin, bloqueando race-to-idle e piorando thermal/bateria. O spin existia para compensar sleep grosso do Windows; nanosleep POSIX/hrtimer já acorda sub-ms. PresentThreadMain já usava pure `sleep_until` em POSIX (precedente no mesmo código) |
| 2 | overlay `NaughtyBear_ReStuff/src/renderer/guest_d3d_hooks.cpp` (NOVO overlay) + `up_draws.h` (NOVO overlay) + overlay `native_vk.cpp` | Present thread: poll idle de 3ms → espera em `std::condition_variable` com timeout de 100ms (`WaitForRawFrame`), notificada em `EndGuestFrame` pós-publicação. `RESTUFF_NO_FRAMECV=1` restaura o poll | Poll de 3ms = ~333 wakeups/s quando o jogo está em menu/pausa ou produz menos frames que a taxa de apresentação. CV com predicado `s_raw_ready_fresh`: sem wakeup perdido (revalida), acorda NA publicação (latência ≤ ao poll), timeout preserva o deadline de repaint do overlay (100ms) |
| 3 | overlay `rexglue-sdk/src/filesystem/devices/disc_image_device.cpp` | `madvise(MADV_RANDOM)` no mmap da imagem GDFX após o parse do boot; `RESTUFF_NO_MADVISE=1` desliga para A/B | Readahead default do kernel (128KB/fault) é benéfico no parse sequencial do boot (intocado) mas desperdiça flash/page cache no gameplay, onde leituras lazy de assets intercalam setores distantes pela imagem XGD. Zero mudança semântica de acesso (hint only). Pendência: medir loads sequenciais grandes em device |
| 4 | `native/CMakeLists.txt` + `scripts/build_android_native.sh` + `.github/workflows/build.yml` | `-g0` → `-gline-tables-only` no alvo `restuff` (clang; GCC recai em `-g0`); script stripa `--strip-debug` a lib para o APK e preserva `librestuff.so.unstripped`; CI upa artefato separado `naughtybear-restuff-debug-symbols` | O crash handler imprime "módulo+0xOFFSET" (dladdr/.dynsym); com `-g0` esses endereços nunca viram arquivo:linha em lugar nenhum. Custo MEDIDO: +85MB de .debug_line (348MB vs 263MB de .so) — por isso as tabelas vão para artefato SEPARADO (APK fica com o tamanho anterior; zero custo em runtime, seções .debug_* não são mapeadas pelo loader). Backtrace se resolve offline: `llvm-symbolizer --obj=librestuff.so.unstripped 0xOFFSET` |
| 5 | overlay `rexglue-sdk/src/core/CMakeLists.txt` + `src/system/CMakeLists.txt` | `SIMDE_ARM_NEON_A64V8_NATIVE=1` PUBLIC em `rexcore`/`rexruntime`, **guardado por `if(ANDROID)`** | Pin explícito do caminho NATIVO NEON do SIMDE contra drift de auto-detecção em bump do submódulo. Guard obrigatório: a mesma árvore compila o CLI host x86_64 do codegen (`build_host_codegen.sh`) — define NEON em x86 quebraria os headers |
| 6 | `app/.../settings/PortSettings.kt` | `detailedLogs` default `true` → `false` (log_level info) | Nível debug com sink spdlog síncrono + arquivo rotativo público pagava fmt+mutex+write em dezenas de sítios (kernel `REXSYS_DEBUG`, `ResolvePath` por arquivo aberto). Info mantém erros/warnings com stack trace; toggle de diagnóstico continua na UI |
| 7 | `app/.../game/GameActivity.kt` (restuff.toml gerado) | `vulkan_allow_present_mode_immediate/mailbox/fifo_relaxed = false` → FIFO | Preferência do SDK é IMMEDIATE > MAILBOX > FIFO_RELAXED > FIFO (decisão de latência desktop). Em painéis Android 60/90/120Hz com pacing de 60Hz wall-clock, MAILBOX/IMMEDIATE geram presents sem conteúdo novo (judder + consumo); FIFO alinha ao vsync do painel e é o único modo garantido em drivers mobile. Config-only (cvars existentes), mesmo padrão dos `vulkan_require_*` |

### Analisadas e REJEITADAS com evidência

| Proposta | Por que não |
|---|---|
| `vblank_hz` 120→60 (economizar wakeups do pump) | **Quebra o unlock de 60fps**: `native_backend_vk.cpp:4958-4961` — "This title presents on every SECOND vblank, so 60 Hz here is exactly the 30 fps cap the console shipped with" + "must stay one" (o default 120 é o clock de pacing do 60fps unlock). A UI já expõe `vblankHz` 30..240 para quem prefere |
| Hash de textura por geração de escrita (maior custo CPU/frame) | Precedente de regressão user-visible documentado: M3.295b (`native_vk.cpp:1160-1166`) — pular rehash exibiu sprite errada no HUD; a invalidação por fetch-constants requer instrumentação nova no caminho de captura. Risco de corretude > ganho; o memo por-frame + hash 4-lane já mitigam |
| LTO/ThinLTO no Release | Fibers com troca de contexto em asm inline + `-ffp-model=strict` nos alvos do SDK + risco de explosão de tempo de link num job de ~45min. Medir em device antes de adotar |
| `-fvisibility=hidden` na librestuff.so | `librestuff.so` ↔ `librexruntime.so` trocam objetos C++ (std::string/vector) entre DSOs (`build_android_native.sh:173-175`); visibility exige export-maps coordenados nos dois lados — projeto maior, ganho incerto |
| Prioridade/afinidade de threads (áudio alto etc.) | Android sem privilégio não pode elevar prioridade: o SCHED_FIFO do SDK já falha com EPERM (`threading_posix.cpp:838-852`); `setpriority` só permite REBAIXAR. Não há a quem rebaixar sem piorar |
| Maps/vetores por-frame → membros persistentes (`PrepareTranslatedDraws` etc.) | Ganho real (dezenas de allocs/frame) mas toca a semântica de estado do caminho de render quente; diferido para uma iteração com A/B em device |
| `-march=armv8.2-a+dotprod+fp16` global | Decisão de SUporte de dispositivos, não de toolchain: excluiria SoCs armv8.0/8.1. O NEON existente (vqtbl1q/vmlaq) é v8.0 e já cobre o código SIMDE |

### Validação

- Syntax-check C++23 modo Android simulado (`-D__ANDROID__ -D__aarch64__`, stubs de headers
  gerados em build): `guest_d3d_hooks.cpp` ✔ `hooks.cpp` ✔ `disc_image_device.cpp` ✔
  `native_vk.cpp` ✔ (g++ 14.2, `-fsyntax-only`).
- Revisão adversarial independente (agente de compatibilidade): zero BUG-1/2 — sem
  deadlock, lost-wakeup (predicado canônico + toda mutação de `s_raw_ready_fresh` sob
  `s_frame_mutex`), inversão de ordem de lock ou vazamento do define SIMDE para o build
  host (guard `if(ANDROID)` verificado ponta a ponta). Achados aplicados antes do commit:
  notify da CV movido para fora do lock (de verdade), generator expr clang-only,
  switch `RESTUFF_NO_MADVISE`.
- Pós-build (medição real do CI): line tables = +85MB no .so (348 vs 263MB) —
  primeira abordagem (keepDebugSymbols no APK) revertida; símbolos migrados para
  artefato CI separado + llvm-strip --strip-debug no script. APK volta ao tamanho
  original; AGP deste runner nunca conseguiu stripar nada ("Unable to strip…
  packaging them as they are") — o strip agora é responsabilidade do script.
- Overlays idempotentes; novos overlays registrados em `scripts/apply_overlays.sh`.
- Build completo validado no GitHub Actions (`build.yml`) — ver commit deste changelog.

### Pendências de medição em device (próxima iteração)

- `RESTUFF_NO_MADVISE` A/B: load de level sequencial grande (eMMC lento) vs acesso disperso.
- FIFO vs MAILBOX em painel 90Hz (judder/consumo).
- `RESTUFF_NO_FRAMECV=1` vs default (bateria em menu + latência de apresentação).
- Shutdown da present thread: join agora espera ≤100ms (era ≤3ms) — dentro do budget de
  exit de ~200ms documentado (M4.0); se incomodar, expor o flag de stop no predicado.

## feat(port): melhorias da versão PC upstream 6b269c1 — texture mods + fix de céu/título (branch feat/port-upstream-pc-texture-mods)

Port do commit upstream `MaxDeadBear/NaughtyBear_ReStuff@6b269c1` ("Preserve non-yellow
cxforms on sky CIDs and hide warm overlay to prevent blocking title overlays") para o
Android. Base: `perf/otimizacao-arm64-mobile` (dcaded3). Bump do submódulo `restuff`
0f46a6b → 6b269c1 + rebase dos overlays sobre a nova árvore.

### O que veio do upstream (PC)

| Melhoria | O que é | Valor no Android |
|---|---|---|
| **Texture mods** (novo `src/renderer/texture_mods.cpp/h`) | Dump de texturas (`tex_dump` → TGA por content hash) + **substituição de texturas** (`tex_mods` → packs HD em `texture_mods/<hash>.png`), com live reload por geração por-hash, loader thread assíncrono (render thread nunca decodifica), mip chains CPU 2x2 (staging único, N copy regions) e samplers mip com anisotropia até 8x | **O recurso de maior impacto**: habilita texture packs HD no Android — qualquer resolução funciona (UVs do guest são normalizadas), sem recompile |
| **Fix de céu/título** (`hooks.cpp`) | `apply_sky_hide` esconde o warm overlay (cid 15, alpha=0) que bloqueava o overlay vermelho de "bad effect" do jogo; cxforms não-amarelas em sky CIDs passam intactas (só o tint amarelo do dia é recolorido) | Corretude visual na tela de título — portátil por si (patch de memória guest) |
| **Attract-mode robusto** (`hooks.cpp`) | Timer armado em múltiplos sítios (`on_set_bg_color`, `on_gfx_place` em stream startmenu, `MenuState`, `play_attract_video`) + fallback de 8s pós-boot; getters/setters para a UI | Vem grátis; o player de vídeo em si continua stub no Android (Media Foundation é Windows-only, caminho legítimo já tratado) |
| **score_objective imediato** (`hooks.cpp`) | Toggle-off apaga o texto no Flash via invoke do guest (`kFnSetObjText`) e força rebuild do painel; toggle-on força rebuild para anexar | Portátil (GuestToHostFunction — padrão já provado no port) |
| Cheats/trophy overlays (`cheats_overlay.h`/`trophy_overlay.h`) | UI ImGui desktop com score HUD, dump de texturas, attract trailers | Compila e fica inerte no Android (OnCreateDialogs retorna cedo — decisões M3.146/147 do port) |
| Limpeza | Probes de co-op M1/M2 removidos (+ entrada no manifest) | Menos código morto |

### Adaptâncias do port (o que não veio pronto)

| # | Arquivo | Mudança | Motivo |
|---|---|---|---|
| 1 | overlays `hooks.cpp`/`native_vk.cpp` | **Rebase**: regenerados a partir do 6b269c1 + re-aplicados os 4+2 deltas Android (POINT POSIX, limiter sem spin, tolower de extensão, mcontext arm64; WaitForRawFrame CV, LoaderGdpa(dev) via tabela da instance) | Sem o rebase, os overlays velhos encobririam TODO o upstream novo (hooks/native_vk são os dois arquivos mais alterados upstream) |
| 2 | overlay `texture_mods.cpp` (NOVO) | Decoder não-Windows: `stb_image` com `STB_IMAGE_STATIC` + `STB_IMAGE_IMPLEMENTATION`; `LoadBlocking` sem o gate `#ifdef _WIN32`; `#include <cctype>` (`::tolower` sem include quebra no libc++ do NDK — libstdc++ vaza ctype.h, daí o upstream nunca ter visto) | O decode upstream é WIC (Windows-only); sem isso o recurso seria no-op no Android. STATIC é obrigatório: o SDK já embute stb com linkage EXTERNO (`src/ui/image_decode.cpp`) — duplicate symbol no lld sem ele |
| 3 | overlay `texture_mods.h` (NOVO) | `ModDir()`/`DumpDir()` viram declarações sob `__ANDROID__` (definidos no .cpp ancorados em `REX_ANDROID_FILES_DIR`) | O cwd do processo no Android é `/` — o relativo `texture_mods` upstream jamais resolveria. Packs ficam em `<files>/texture_mods/` (mesmo lar de restuff.toml/saves/drivers) |
| 4 | `native/CMakeLists.txt` | `texture_mods.cpp` em RESTUFF_SOURCES + include `thirdparty/stb` | Nova unidade de compilação; símbolos `get_tex_dump*` referenciados pelo cheats_overlay novo |
| 5 | `scripts/apply_overlays.sh` | `texture_mods.cpp/h` na lista de replace | Registro dos novos overlays |
| 6 | Kotlin (PortSettings/GameActivity/SettingsScreen) | Toggle "Texture Mods (packs HD)" → `tex_mods = <bool>` no restuff.toml gerado | Sem root não se edita `/data/data/.../restuff.toml` — o toggle é a via oficial de ativar/desativar |

### Como usar (usuário final)

1. Ativar **Configurações → Motor ReStuff → Texture Mods (packs HD)**.
2. Empurrar packs (mesmos arquivos dos packs da versão PC — hash de conteúdo é
   independente de plataforma):
   `adb push <HASH>.png /data/data/com.deivid22srk.restuff/files/texture_mods/`
3. Live reload a 4 Hz: editar/salvar um arquivo troca SÓ aquela textura (geração
   por-hash — sem re-decode do corpus). Nome = hash de 16 hex (gerado pelo dump
   `tex_dump` no PC, ou pelo RESTUFF_TEX_DUMP=1 aqui — TGA em `<files>/texture_dump/`).
4. Formatos aceitos no Android: PNG, TGA, BMP, JPG (stb_image). `.tif` não (log WARN).

### Validação

- Rebase conferido por diff: overlay = upstream 6b269c1 + exatamente os deltas Android
  (nada perdido, nada extra) — hooks.cpp e native_vk.cpp.
- `texture_mods.cpp` compilado a .o real (g++ 14.2, C++23, `-D__ANDROID__`):
  símbolos `stbi_*` todos **locais** (`t` minúsculo no nm) — zero colisão com
  `image_decode.cpp` do SDK (achado da revisão adversarial, corrigido antes do commit).
- Fluxo CI simulado localmente: reset dos submódulos → `apply_overlays.sh` →
  melhorias upstream + deltas Android presentes na árvore final.
- Revisão adversarial independente: 2 bloqueantes (colisão stb, `<cctype>`) — ambos
  corrigidos; rebase, link de símbolos, threads (CV sem lost-wakeup), samplers mip
  (fallback correto se vkCreateSampler falhar), caminho do cvar no toml e CMake
  confirmados ponto a ponto.
- Build completo validado no GitHub Actions (`build.yml`) — ver commit deste changelog.

### Limitações conhecidas

- Dump em PNG (WIC) continua Windows-only — no Android o `tex_dump_format=png` cai em
  TGA com WARN (comportamento upstream documentado).
- SIOF teórico no keybind F8 em static-init — padrão herdado do upstream (hooks.cpp
  registra binds idênticos em static init), dívida técnica documentada, não regressão.
- Samplers mip vazam no shutdown (nunca destruídos) — herdado do upstream; o título
  hard-exita o processo, irrelevante na prática.
