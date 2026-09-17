/*
 * Configurações REAIS do port Naughty Bear ReStuff.
 *
 * Cada opção deste modelo é de fato consumida (nada aqui é cenográfico):
 *
 *   fpsLimit          → cvar "fps_cap" do motor (hooks.cpp, cap por software)
 *   vblankHz          → cvar "vblank_hz" (thread de pump do backend Vulkan)
 *   resScale          → env RESTUFF_RES_SCALE (escala interna do renderer,
 *                       native_vk.cpp M4.39 — 50 = 640x360, 100 = 1280x720)
 *   aniso             → env RESTUFF_ANISO (teto de anisotropia dos samplers
 *                       mip — 0/2/4/8)
 *   sustainedPerf     → Window.setSustainedPerformanceMode (GameActivity)
 *   showFpsCounter    → pill mono sobre o jogo lendo os presents Vulkan
 *                       REAIS (JNI nativeGetPresentCount — trampoline no
 *                       vulkan_device.cpp conta cada vkQueuePresentKHR)
 *   unlock60Fps       → flag --fps60 → env RESTUFF_FPS60 (hook de
 *                       PresentationInterval, 30 → 60)
 *   unlockAllCheat    → cvar "unlock_all" (cheats do motor)
 *   detailedLogs      → log_level (toml + REX_LOG_LEVEL) + log persistido
 *                       em /storage/emulated/0/Naughty Bear ReStuff/logs
 *   showOverlayControls / overlayOpacity / overlayScale / hapticFeedback
 *                     → VirtualGamepadView (gamepad virtual SDL sobre o jogo)
 *   particlesEnabled / reduceMotionOverride → efeitos da tela inicial
 *
 * A ponte Java→motor vive em GameActivity.getArguments() (argv do SDL_main +
 * restuff.toml gerado na hora) — é lá que se confere o que cada campo vira.
 */
package com.deivid22srk.restuff.settings

import android.content.Context

/**
 * Limite de quadros por segundo aplicado como cvar "fps_cap" do motor
 * (0 = ilimitado; vblank_hz continua limitando por hardware).
 */
enum class FpsLimitOption(val label: String, val fps: Int) {
    FPS_30("30", 30),
    FPS_40("40", 40),
    FPS_60("60", 60),
    FPS_90("90", 90),
    FPS_120("120", 120),
    UNLIMITED("Ilimitado", 0),
}

/**
 * Escala de resolução INTERNA do renderer (host scene target).
 * O guest continua vendo um console 1280x720 — a geometria chega em clip
 * space independente de resolução; cada resolve faz downscale-blit
 * host→guest (ver M4.38/M4.39 em native_vk.cpp).
 *
 * Custo é quadrático: 50% = 640x360 = 1/4 dos pixels E ~1/4 dos attachments
 * (179MB → ~45MB — cabe no tiling/GMEM do Adreno 619). No SD695 (log de
 * campo: main pass 77-97ms a 100%), 50% é o que leva o GPU de ~100ms para
 * ~25ms — o ajuste com maior impacto para sair de 9fps rumo aos 40fps.
 */
enum class ResolutionScaleOption(val label: String, val short: String, val percent: Int) {
    RES_50("Ultra performance - 50% (640x360)", "50%", 50),
    RES_60("Performance - 60% (768x432)", "60%", 60),
    RES_75("Equilibrado - 75% (960x540)", "75%", 75),
    RES_100("Qualidade - 100% (1280x720)", "100%", 100),
}

/**
 * Teto de filtragem anisotrópica dos samplers mip (packs HD / tex_mods).
 * 0 = desligada. Texturas base do jogo usam os samplers da UI (sem aniso) —
 * o ganho aparece com texture mods ou em superfícies com mip.
 */
enum class AnisoOption(val label: String, val value: Int) {
    OFF("Desligada", 0),
    X2("2x", 2),
    X4("4x", 4),
    X8("8x", 8),
}

/**
 * Defaults M4.39 com migração: instalação nova (sem nenhuma pref gravada)
 * abre no preset SD695 Ultra (50% + aniso 2x); quem atualiza de versão
 * anterior — onde o renderer era sempre 100% + aniso 8x fixo — mantém o
 * comportamento antigo até escolher outro valor (sem downgrade surpresa).
 * Puras (sem Android) para cobertura em JVM — ver PortSettingsDefaultsTest.
 */
fun resScaleDefault(hasPriorPrefs: Boolean): ResolutionScaleOption =
    if (hasPriorPrefs) ResolutionScaleOption.RES_100 else ResolutionScaleOption.RES_50

fun anisoDefault(hasPriorPrefs: Boolean): AnisoOption =
    if (hasPriorPrefs) AnisoOption.X8 else AnisoOption.X2

/**
 * N3: `firstInstallTime << lastUpdateTime` (margem 60s p/ skew) = o APK foi
 * atualizado por cima de uma versão anterior. Pura para cobertura em JVM.
 */
fun isUpgradeInstallTimes(firstInstallTime: Long, lastUpdateTime: Long): Boolean =
    firstInstallTime + 60_000L < lastUpdateTime

/**
 * Snapshot imutável das preferências do port — todas aplicadas ao motor
 * ou ao launcher (ver cabeçalho do arquivo).
 */
data class PortSettings(
    // Desempenho (motor)
    val fpsLimit: FpsLimitOption = FpsLimitOption.FPS_60,
    val vblankHz: Int = 120,                    // 30 .. 240 (cvar vblank_hz)
    // M4.39 (perf/sd695-ultra): escala interna do renderer → env
    // RESTUFF_RES_SCALE (SceneW/H = 1280/720 x pct). Fresh install abre em
    // 50%: em Adreno 6xx a diferença 100%→50% é ~4x menos pixels/attachments
    // — de ~9fps para a casa dos 30fps no SD695. Upgrade preserva 100%
    // (ver resScaleDefault); flagship sobe para 100% num toque.
    val resScale: ResolutionScaleOption = ResolutionScaleOption.RES_50,
    // M4.39: teto de anisotropia → env RESTUFF_ANISO (0/2/4/8). Fresh install
    // 2x; upgrade preserva 8x (comportamento antigo era 8x fixo).
    val aniso: AnisoOption = AnisoOption.X2,
    // Modo de performance sustentada do Android (API 24+): trava os clocks
    // num patamar sustentável em vez de pico-then-throttle — emulador que
    // esquenta o SD695 em 3 min cai de 30fps para 15fps sem isto.
    val sustainedPerf: Boolean = true,
    // M4.40 (perf/sd695-40fps): resolução dinâmica (DRS) → env RESTUFF_DRS.
    // O renderer mede o tempo BUSY do ciclo de present (cyc-wait, imune ao
    // pacer de menus a 30fps) e ajusta a escala interna em passos de 20/10%
    // dentro de [40..max(100, preset)]% segurando ~40fps — reconstruindo os
    // attachments da cena entre frames (passes/pipelines nunca são tocados).
    // O preset manual (resScale) é o PONTO DE PARTIDA; kill-switch no motor:
    // RESTUFF_NO_DRS=1.
    val dynamicRes: Boolean = true,
    // Contador de FPS sobre o jogo: lê o total de vkQueuePresentKHR do
    // dispositivo Vulkan (contado por um trampoline no vulkan_device.cpp)
    // e calcula a taxa no próprio overlay — nada de estimativa por
    // Choreographer (que mede o vsync do painel, não o jogo).
    val showFpsCounter: Boolean = false,
    // Motor ReStuff
    val unlockAllCheat: Boolean = false,        // unlock_all (cheats)
    val unlock60Fps: Boolean = true,            // RESTUFF_FPS60 (unlock vblank)
    // Texture mods do upstream PC (6b269c1): substituição de texturas por
    // content hash em <files>/texture_mods/<hash>.png (dump: tex_dump).
    // Sem root o usuário não edita restuff.toml — o toggle é a via oficial.
    val textureMods: Boolean = false,           // tex_mods (texture packs HD)
    // Controles (overlay do gamepad virtual)
    val showOverlayControls: Boolean = true,
    val overlayOpacity: Float = 0.65f,          // 0.2 .. 1.0
    val overlayScale: Float = 1f,               // 0.7 .. 1.6
    val hapticFeedback: Boolean = true,
    // Diagnóstico (log detalhado persistido)
    // ARM PERF: default false — o nível "info" mantém erros/warnings (com
    // stack traces) no log persistido sem pagar o custo mobile do "debug":
    // fmt+mutex+write síncrono por evento em dezenas de sítios do kernel/FS
    // do SDK, rotacionando arquivo no storage público. O toggle continua
    // disponível na tela de Configurações para sessões de diagnóstico.
    val detailedLogs: Boolean = false,         // log_level=info no toml + REX_LOG_LEVEL
    // Efeitos da tela inicial
    val particlesEnabled: Boolean = true,
    val reduceMotionOverride: Boolean = false,
)

/**
 * Persistência simples e sem dependências (SharedPreferences). Chaves
 * estáveis: upgrades de versão mantêm as preferências do usuário.
 */
class PortSettingsRepository(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("port_screen_prefs", Context.MODE_PRIVATE)

    fun load(): PortSettings {
        // N3: prefs vazias são ambíguas — fresh install OU upgrade de quem
        // nunca abriu Configurações (o ViewModel só salva em update(), então
        // upgrade passivo não tem nenhuma chave). Desempata pelo
        // PackageManager: atualizado por cima ⇒ lastUpdate > firstInstall.
        // Após o primeiro save da nova versão as chaves novas existem e esta
        // heurística deixa de importar (enumOf usa o valor gravado).
        val hasPriorPrefs = prefs.contains(K_FPS) || hasPriorInstall()
        return PortSettings(
            fpsLimit = enumOf(prefs.getString(K_FPS, null), FpsLimitOption.FPS_60),
            vblankHz = prefs.getInt(K_VBLANK, 120).coerceIn(30, 240),
            resScale = enumOf(prefs.getString(K_RESSCALE, null), resScaleDefault(hasPriorPrefs)),
            aniso = enumOf(prefs.getString(K_ANISO, null), anisoDefault(hasPriorPrefs)),
            sustainedPerf = prefs.getBoolean(K_SUSTAINED, true),
            dynamicRes = prefs.getBoolean(K_DRS, true),
            showFpsCounter = prefs.getBoolean(K_SHOW_FPS, false),
            unlockAllCheat = prefs.getBoolean(K_UNLOCK_ALL, false),
            unlock60Fps = prefs.getBoolean(K_UNLOCK_60FPS, true),
            textureMods = prefs.getBoolean(K_TEX_MODS, false),
            showOverlayControls = prefs.getBoolean(K_OVERLAY, true),
            overlayOpacity = prefs.getFloat(K_OPACITY, 0.65f).coerceIn(0.2f, 1f),
            overlayScale = prefs.getFloat(K_PADSCALE, 1f).coerceIn(0.7f, 1.6f),
            hapticFeedback = prefs.getBoolean(K_HAPTIC, true),
            detailedLogs = prefs.getBoolean(K_DETAILED_LOGS, false),
            particlesEnabled = prefs.getBoolean(K_PARTICLES, true),
            reduceMotionOverride = prefs.getBoolean(K_REDUCE_MOTION, false),
        )
    }

    fun save(s: PortSettings) {
        prefs.edit()
            .putString(K_FPS, s.fpsLimit.name)
            .putInt(K_VBLANK, s.vblankHz)
            .putString(K_RESSCALE, s.resScale.name)
            .putString(K_ANISO, s.aniso.name)
            .putBoolean(K_SUSTAINED, s.sustainedPerf)
            .putBoolean(K_DRS, s.dynamicRes)
            .putBoolean(K_SHOW_FPS, s.showFpsCounter)
            .putBoolean(K_UNLOCK_ALL, s.unlockAllCheat)
            .putBoolean(K_UNLOCK_60FPS, s.unlock60Fps)
            .putBoolean(K_TEX_MODS, s.textureMods)
            .putBoolean(K_OVERLAY, s.showOverlayControls)
            .putFloat(K_OPACITY, s.overlayOpacity)
            .putFloat(K_PADSCALE, s.overlayScale)
            .putBoolean(K_HAPTIC, s.hapticFeedback)
            .putBoolean(K_DETAILED_LOGS, s.detailedLogs)
            .putBoolean(K_PARTICLES, s.particlesEnabled)
            .putBoolean(K_REDUCE_MOTION, s.reduceMotionOverride)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    /** Best-effort (N3): false em qualquer falha (vale fresh install). */
    private fun hasPriorInstall(): Boolean = runCatching {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(appContext.packageName, 0)
        isUpgradeInstallTimes(info.firstInstallTime, info.lastUpdateTime)
    }.getOrDefault(false)

    private companion object {
        const val K_FPS = "fps_limit"
        const val K_VBLANK = "restuff_vblank_hz"
        const val K_RESSCALE = "restuff_res_scale"
        const val K_ANISO = "restuff_aniso"
        const val K_SUSTAINED = "sustained_perf"
        const val K_DRS = "dynamic_res"
        const val K_SHOW_FPS = "show_fps_counter"
        const val K_UNLOCK_ALL = "restuff_unlock_all"
        const val K_UNLOCK_60FPS = "restuff_unlock_60fps"
        const val K_TEX_MODS = "restuff_tex_mods"
        const val K_OVERLAY = "overlay_controls"
        const val K_OPACITY = "overlay_opacity"
        const val K_PADSCALE = "overlay_pad_scale"
        const val K_HAPTIC = "haptic_feedback"
        const val K_DETAILED_LOGS = "restuff_detailed_logs"
        const val K_PARTICLES = "particles_enabled"     // mesma chave da v1.0
        const val K_REDUCE_MOTION = "reduce_motion"     // mesma chave da v1.0
    }
}
