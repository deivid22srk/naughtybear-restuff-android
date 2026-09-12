/*
 * Configurações REAIS do port Naughty Bear ReStuff.
 *
 * Cada opção deste modelo é de fato consumida (nada aqui é cenográfico):
 *
 *   fpsLimit          → cvar "fps_cap" do motor (hooks.cpp, cap por software)
 *   vblankHz          → cvar "vblank_hz" (thread de pump do backend Vulkan)
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
    FPS_60("60", 60),
    FPS_90("90", 90),
    FPS_120("120", 120),
    UNLIMITED("Ilimitado", 0),
}

/**
 * Snapshot imutável das preferências do port — todas aplicadas ao motor
 * ou ao launcher (ver cabeçalho do arquivo).
 */
data class PortSettings(
    // Desempenho (motor)
    val fpsLimit: FpsLimitOption = FpsLimitOption.FPS_60,
    val vblankHz: Int = 120,                    // 30 .. 240 (cvar vblank_hz)
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
class PortSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("port_screen_prefs", Context.MODE_PRIVATE)

    fun load(): PortSettings {
        return PortSettings(
            fpsLimit = enumOf(prefs.getString(K_FPS, null), FpsLimitOption.FPS_60),
            vblankHz = prefs.getInt(K_VBLANK, 120).coerceIn(30, 240),
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

    private companion object {
        const val K_FPS = "fps_limit"
        const val K_VBLANK = "restuff_vblank_hz"
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
