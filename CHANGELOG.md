# Changelog — naughtybear-restuff-android

## perf/sd695-40fps — DRS (resolução dinâmica) + verificação assíncrona de texturas (branch perf/sd695-40fps)

Base: `feat/vortek-driver-icone` + o downscale fracionário M4.39 portado da
`perf/sd695-ultra`. Evidência motriz (log 2026-09-17, SD695/Adreno 619):
gameplay 8.8-10.6fps com **main pass de 92-97ms** e SDKMS `trk` = 52-116ms
(**espera bloqueada de GPU**) — o "custo de CPU" de 79ms era majoritariamente
o paint thread esperando a GPU; CPU serial real ≈ prep 22ms + captura ~15ms.

| # | Mudança | Efeito esperado no SD695 |
|---|---|---|
| 1 | **M4.39 portado**: downscale fracionário 25-400% + presets Ultra/Perf/Equilibrado/Qualidade + aniso configurável + sustained perf mode | A 50%: ~4x menos pixels (main 92→~25-30ms) → sair de ~9fps para a casa dos 25-30fps |
| 2 | **M4.40 DRS (novo)**: controlador de resolução dinâmica — mede o tempo **busy** do ciclo de present (cyc-wait, imune ao pacer de 30fps dos menus) e o **gpuq** por janela de 30 presents e ajusta a escala interna em passos de -20/+10% dentro de [40%..preset] com histerese (2 janelas concordantes, ≥45 presents entre mudanças; passo imediato quando busy >2.5× alvo), reconstruindo attachments da cena com `vkDeviceWaitIdle` no callback top (passes/pipelines jamais destruídos). **Descer exige GPU-bound** (gpuq ≥ 0.80×busy): CPU-bound não paga imagem por nada. O **preset é o teto** (quem escolheu 50% nunca vê acima). Decisão pura em `renderer/res_scale.h` (`DrsWindowDirection`/`DrsStepPct`) com regressão host no CI | **Garantia adaptativa da meta**: cenas pesadas caem para 40% sozinhas (40% ≈ 1/6 dos pixels do 100%); cenas leves voltam ao preset; CPU-bound segura a resolução
| 3 | **M4.41 (novo)**: verificador assíncrono de conteúdo de texturas — thread em cadência de 2ms re-hash continuamente o corpus amostrado (leitura pura da arena guest) e publica {hash, frame}; o prep aceita o resultado só quando computado ≥ `decoded_frame` da entrada (contra re-decodes espúrios). Kill-switch `RESTUFF_ASYNC_TEXHASH=0` + auto-off com qualquer diagnóstico de dump | Corta a maior fatia do `textures=31μs/draw` do PREPLOOP (~10MB/frame de leituras de verificação) do caminho serial: prep 22ms → ~14-17ms |
| 4 | Fallback duplo do DRS: falha de rebuild → restaura a escala anterior (com teardown limpo do estado parcial); segunda falha → DRS desabilitado para sempre (nunca tela preta) | Segurança contra OOM/fragmentação de VRAM |
| 5 | Toggle "Resolução dinâmica (40fps)" em Configurações (default ON, env `RESTUFF_DRS=1` só quando ligado — desktop/CI ficam estáticos) | Usuário controla; verbo claro |
| 6 | Testes: `test_res_scale.c` 50→**75 checagens** (direção/step do DRS com gate de GPU-bound, clamps, bordas 1.30x/0.80x); 3 testes JVM (default ON, contrato do env, preset=teto do DRS) | Regressões de lógica pegadas em segundos no CI, antes do build de ~45min |
| 7 | Hardening da avaliação independente (notas e1 7.2 → e2 8.5): statics `scene_virgin`/`s_depth_ever` promovidos a file-scope e re-virginizados no teardown (o 1º segmento pós-rebuild usava LOAD sobre imagem UNDEFINED = lixo de EDRAM por ≥1 frame); tick do DRS fora do guard `RESTUFF_NO_FRAMEMS`; dumps de cena viram blockers do DRS; `failed` do athash → atômico; latch anti-leak de 3 falhas consecutivas do EnsureSceneTarget; saneamento do `RESTUFF_DRS_MAX`/piso<min; teto do DRS re-sincronizado com o clamp de VRAM pós-rebuild (fim do churn de hitch) | Sem frame de lixo pós-rebuild; kill-switch do log não congela o DRS; sem OOB em dumps; sem leak por frame em OOM persistente |
| 8 | **Convergência urgente corrigida (e2)**: busy >2.5× alvo pisa imediatamente com cadência relaxada (≥20 presents ≈ 2.5s a 8fps, era ≥45): 100%→40% em ~15s no cenário do log4 (era ~45s) | Chegada ao piso ~3x mais rápida exatamente quando mais importa |
| 9 | **Backoff do worker M4.41 (e2)**: só re-hasheia identidades amostradas desde o último hash (`wanted > hashed`) — texturas fora de tela não custam nada; sono esticado para 12ms quando idle, com wake imediato no arm via `notify_one` | Little core deixa de queimar 100% em menus/cenas paradas; latência de detecção em gameplay inalterada (2ms) |

Nota honesta: a meta de **40fps** no Adreno 619 depende de cena + térmica. O
modo 50% + DRS (piso 40%) elimina o gargalo de fill e garante o **melhor FPS
que o dispositivo entrega** em cada momento; se a cena ainda assim não segurar
40fps, o FRAMEMS do log mostrará o próximo teto (prep/captura CPU) para a
próxima iteração de otimização.

## perf/sd695-ultra — downscale fracionário + presets SD695 rumo a 40fps (branch perf/sd695-ultra)

Evidência de campo (SD695/Adreno 619, Turnip, log 2026-09-17): gameplay 8-10fps
com main pass 77-97ms a 1280x720, 436-561 draws/present, wait~0ms (GPU-bound),
179 MB de attachments, aniso 8x. Gargalo = pixels, não shaders (639 pipelines
só no prewarm) nem texturas (12 decodes, readfail=0).

| # | Mudança | Efeito esperado no SD695 |
|---|---|---|
| 1 | `RESTUFF_RES_SCALE` fracionário (25-400%): 50%=640x360, 60%, 75% (`native_vk.cpp` M4.39 + `GameActivity --env=`; parsing isolado em `renderer/res_scale.h` com regressão host de 50 casos no CI) | ~4x menos pixels/attachments a 50% (179→~45 MB): GPU de ~100ms/frame rumo a ~25-35ms |
| 2 | Resolve/viewport/scissor/pgen refeitos em float (6 sítios assumiam S inteiro 1..4 — viewport zerada e div/0 em downscale) + clamp do fallback copy | Downscale renderiza correto (upscale-blit LINEAR host→guest; sem OOB) |
| 3 | `RESTUFF_ANISO` 0/2/4/8 (default 4x sem env, era 8x fixo) | Menos filtragem nos packs HD |
| 4 | UI Desempenho: presets Ultra 50 / Perf 60 / Eq. 75 / Qual 100 + chips de resolução/aniso + toggle performance sustentada; fresh install abre no Ultra, upgrade preserva 100% (sem downgrade surpresa) | Um toque para 50%+60fps+vblank120 |
| 5 | Chip de FPS 40, `setSustainedPerformanceMode(true)` (default) | Menos throttle térmico em sessão longa |
| 6 | Painel 4 dedos mostra resolução/aniso ativas (só mudam com reinício) | Sem confusão "mudei e nada aconteceu" |

Nota honesta: 40fps cravados num Adreno 619 dependem de cena + térmica + driver
Turnip. O modo 50% tira o gargalo de fill (GPU-bound a 100%) e deve entregar
~30fps típicos, ~40 em cenas leves com cooler e Turnip recente — a submissão CPU
(400-500 draws/frame) e o thermal sustentado continuam limitando o teto.
Flagships sobem para 100% num toque.

Limitação conhecida: passes volumétricos/sun-shaft do chain src_2x foram
validados a 100%; abaixo disso o fast-path depth-fill desliga (cai no bounce
geral) e os shafts podem perder fidelidade — se notar, suba para 75%.

## v1.1.0 — feat(vortek): driver Vulkan Vortek + redesign da UI + controles de toque + limitador de FPS real (branch feat/vortek-driver-icone)

Primeira release assinada com keystore de **release** dedicado (`keystore/release.keystore`,
via `.github/workflows/release.yml`). Builds anteriores de CI usavam a assinatura de debug —
veja o aviso de instalação no fim desta seção.

### O que entrou

| # | Item | O que é | Valor no Android |
|---|---|---|---|
| 1 | **Driver Vortek (experimental)** | Camada de compatibilidade Vulkan do [brunodev85](https://github.com/brunodev85)/Winlator: cliente `libvulkan_vortek.so` (dlopen do motor) + servidor `libvortekrenderer.so` (hospedado pelo `android_main.cpp` no mesmo processo), embutidos no APK; seleção em **Configurações → GPU Drivers**, persistência em `<files>/drivers/active.txt` (escrita atômica + self-heal do caminho `nativeLibraryDir` pós-update) | Driver Vulkan alternativo (fork Turnip/Mesa) com tradução de chamadas — caminho para melhor compatibilidade e performance em Adreno. **Créditos: brunodev85** |
| 2 | **Novo ícone do app** | Identidade visual própria (easyappicon) | — |
| 3 | **Redesign minimalista "Mel & Carvão"** | UI repensada (paleta mel/carvão) + **contador de FPS real** medido dos presents Vulkan — não template genérico | Navegação mais leve e diagnóstico de performance honesto |
| 4 | **Campanha de performance SD695** | Instrumentação always-on do ciclo de frame + dedups de conteúdo; corte do imposto de log I/O (spdlog síncrono em dezenas de sítios por frame) | Menos overhead por frame em gameplay; base de medição para a meta de 30fps |
| 5 | **Instrumentação de diagnóstico** | "Resolução de cegos" sempre disponível (env sem rebuild) + line tables em artefato CI separado | Diagnóstico de crash/perf em device sem custo no APK |

### Correções estabilizando o Vortek (bugs reportados com logs, em sequência)

| Bug do usuário | Root cause | Fix |
|---|---|---|
| **ENOENT ao selecionar Vortek** nas Configurações (log pastebin `8EBkzgjB`) | `<files>/drivers/` nunca era criado no fluxo embutido (o Vortek não tem passo de import, diferente do Turnip) | `writeActiveFile()` com `mkdirs()` + escrita atômica (tmp+rename); self-heal do reconcile; 12 testes JVM de regressão rodando no CI antes do build nativo |
| **Crash (SIGSEGV) ao iniciar o jogo** com Vortek ativado (log pastebin `uYKCijUd`) | `VK_KHR_swapchain` era removida da criação do device do host (skip list legada do modo X11 sintético do upstream) → `vkGetDeviceProcAddr` devolvia NULL → chamada de ponteiro NULL no primeiro `vkCreateSwapchainKHR` | Extensão fora da skip list + guards anti-NULL na família swapchain + `vkGetPhysicalDevicePresentRectanglesKHR` reescrito como passthrough real |
| **Renderização corrompida com Vortek** (log4.zip: gameplay + logs) — triângulos gigantes sólidos, malhas 3D ausentes, strobing em transições | Vertex fetch `USCALED PACK32 10-10-10-2` (formato de posição/normal do Xenos, o mais comum em jogos Xbox 360) não era convertido pelo Vortek → pipeline criado com vertex fetch sem suporte no Adreno → rasterização indefinida. 2D/título renderizavam OK (formatos planos, cobertos pelo rewrite SPIR-V existente) — consistente com o vídeo | Conversão USCALED/SSCALED da família PACK32 (A2B10G10R10/A2R10G10B10/A8B8G8R8) + signedness correta no rewrite SPIR-V; fence-wait robusto (fds inicializados, fallback de espera real no servidor, status propagado); índice devolvido também em `VK_SUBOPTIMAL_KHR` (framebuffer OOB em transições). Teste unitário host (21 checagens) no CI |

### Controles de toque, limitador de FPS e orientação (gameplay)

| # | Item | O que é | Valor no Android |
|---|---|---|---|
| 6 | **Gamepad virtual completo** | **Stick analógico direito** (câmera/aim), rótulos **LT/RT** nos gatilhos, e o botão Voltar agora pede confirmação ("voltar à tela inicial?") — nada de sair do jogo por engano | Paridade de fato com o gamepad físico |
| 7 | **Painel de ajuste rápido (toque com 4 dedos)** | Durante o jogo, tocar com 4 dedos abre o painel: overlay on/off, **opacidade**, **tamanho dos controles**, vibração, contador de FPS e **limite de FPS ao vivo (chips 30–120)**. Layout v2: cabeçalho fixo + conteúdo em ScrollView + rodapé fixo (botões sempre acessíveis mesmo em landscape), salvamento com debounce no dismiss e flush no `onStop` — nenhuma preferência se perde | Ajuste sem sair do jogo, como num console |
| 8 | **Limitador de FPS que limita de verdade** | O cap (30/60/…/120) passou a ser aplicado no **present thread do host** (lê o cvar a cada iteração — antes o paceamento era fixo em ~60Hz hardcoded, ignorando a configuração) e também no guest (`on_swap`); espelho `g_rexrestuff_live_fps_cap` re-aplicado pós-LoadConfig + priming no `onCreate` garante que as preferências da sessão sempre vencem o toml | O contador de FPS da UI reflete o cap real; menos consumo e thermal |
| 9 | **Landscape travado durante o jogo** | A UI Kotlin continua girando normalmente (portrait/landscape); ao **iniciar o jogo** a orientação trava em `SENSOR_LANDSCAPE` (override do `setOrientationBis` do SDL, que destruía o lock do manifest via JNI) e é liberada ao voltar à tela inicial | Experiência de console — sem giro acidental para portrait no gameplay |

### Como usar o Vortek

1. **Configurações → GPU Drivers → Vortek (experimental)**.
2. Inicie o jogo normalmente — o motor carrega o driver Vulkan selecionado.
3. Para voltar ao driver do sistema (ou Turnip, se importado), selecione o outro na mesma tela.

### ⚠️ Aviso de instalação (mudança de assinatura)

APKs de CI anteriores à v1.1.0 eram assinados com o keystore de **debug**. Esta é a primeira
build com keystore de **release** — assinaturas diferentes não permitem atualização in-place.
**Desinstale o app anterior antes de instalar a v1.1.0** e, depois de instalar:

1. Reautorize a pasta do jogo (permissão SAF).
2. Reative o driver desejado em **Configurações → GPU Drivers** (Vortek ou padrão).
3. Reconfigure as preferências (as configurações antigas são perdidas com a desinstalação).

A partir da v1.1.0, todas as builds de release compartilham a mesma assinatura — atualizações
futuras instalam por cima sem desinstalar.

### Validação

- Loop de avaliação independente por tarefa: ENOENT 7.5 → 9.2; crash do swapchain 9/10;
  renderização 8.3 + follow-ups (UB, vazamento, guards, família A8B8G8R8); controles de
  toque/limitador/landscape 8.0 → 8.6 → **9.2/10** — findings aplicados
  no mesmo branch.
- CI: testes de unidade Kotlin (12, ENOENT + self-heal), round-trip do protocolo Vortek (8
  checagens, incl. `vkQueuePresentKHR`), teste de conversão de formatos vertex (21 checagens)
  — todos rodam ANTES do build nativo de ~45min.
- Build completo validado no GitHub Actions (`build.yml`) a cada commit do branch.

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
