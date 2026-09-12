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
| 4 | `native/CMakeLists.txt` + `app/build.gradle.kts` | `-g0` → `-gline-tables-only` no alvo `restuff` (clang; GCC recai em `-g0`) + `keepDebugSymbols += "**/librestuff.so"` no packaging do gradle | O crash handler imprime "módulo+0xOFFSET" (dladdr/.dynsym); com `-g0` esses endereços nunca viram arquivo:linha em lugar nenhum. Com line tables PRESERVADAS no APK (sem keepDebugSymbols o llvm-strip do AGP remove `.debug_line`), qualquer backtrace de produção se resolve offline com llvm-symbolizer contra o próprio APK. Custo ~1-3% de tamanho; frame pointers já preservados |
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
  notify da CV movido para fora do lock (de verdade), `keepDebugSymbols` no gradle,
  generator expr clang-only, switch `RESTUFF_NO_MADVISE`.
- Overlays idempotentes; novos overlays registrados em `scripts/apply_overlays.sh`.
- Build completo validado no GitHub Actions (`build.yml`) — ver commit deste changelog.

### Pendências de medição em device (próxima iteração)

- `RESTUFF_NO_MADVISE` A/B: load de level sequencial grande (eMMC lento) vs acesso disperso.
- FIFO vs MAILBOX em painel 90Hz (judder/consumo).
- `RESTUFF_NO_FRAMECV=1` vs default (bateria em menu + latência de apresentação).
- Shutdown da present thread: join agora espera ≤100ms (era ≤3ms) — dentro do budget de
  exit de ~200ms documentado (M4.0); se incomodar, expor o flag de stop no predicado.
