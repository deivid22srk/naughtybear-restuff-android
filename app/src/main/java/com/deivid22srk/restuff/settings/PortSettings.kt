/*
 * Modelo de configurações de exemplo do template.
 *
 * Cada opção é um PARÂMETRO TÍPICO de port Android (vídeo, desempenho, áudio,
 * controles). A tela de Configurações persiste tudo em SharedPreferences via
 * [PortSettingsRepository]; o port lê [PortSettings] e aplica ao motor.
 */
package com.deivid22srk.restuff.settings

import android.content.Context

/** Proporção de tela do frame do jogo (null = nativa do aparelho). */
enum class AspectRatioOption(val label: String, val ratio: Float?) {
    AUTO("Auto", null),
    R4_3("4:3", 4f / 3f),
    R16_9("16:9", 16f / 9f),
    R16_10("16:10", 16f / 10f),
    R21_9("21:9", 21f / 9f),
    STRETCHED("Esticada", -1f),
}

/** Backend de renderização que o motor deve usar. */
enum class RendererOption(val label: String) {
    AUTO("Auto"),
    OPENGL_ES("OpenGL ES"),
    VULKAN("Vulkan"),
}

/** Limite de quadros por segundo (0 = ilimitado). */
enum class FpsLimitOption(val label: String, val fps: Int) {
    FPS_30("30", 30),
    FPS_60("60", 60),
    FPS_90("90", 90),
    FPS_120("120", 120),
    UNLIMITED("Ilimitado", 0),
}

/** Filtro de textura do upscale. */
enum class TextureFilterOption(val label: String) {
    NEAREST("Nearest"),
    BILINEAR("Bilinear"),
    TRILINEAR("Trilinear"),
}

/**
 * Snapshot imutável de todas as preferências do template — inclusive os
 * efeitos visuais da tela inicial (partículas / reduzir movimento).
 */
data class PortSettings(
    // Vídeo
    val aspectRatio: AspectRatioOption = AspectRatioOption.AUTO,
    val resolutionScale: Float = 1f,            // 0.5 .. 3.0
    val textureFilter: TextureFilterOption = TextureFilterOption.BILINEAR,
    val vsync: Boolean = true,
    // Desempenho
    val renderer: RendererOption = RendererOption.AUTO,
    val fpsLimit: FpsLimitOption = FpsLimitOption.FPS_60,
    val frameSkip: Boolean = false,
    // Áudio
    val audioLatencyMs: Int = 80,               // 20 .. 200
    val audioMuted: Boolean = false,
    // Controles
    val showOverlayControls: Boolean = true,
    val overlayOpacity: Float = 0.65f,          // 0.2 .. 1.0
    val overlayScale: Float = 1f,               // 0.7 .. 1.6
    val hapticFeedback: Boolean = true,
    // Motor ReStuff (cvars do restuff)
    val unlockAllCheat: Boolean = false,        // unlock_all (cheats overlay)
    val unlock60Fps: Boolean = true,            // RESTUFF_FPS60 (unlock de vblank)
    val vblankHz: Int = 120,                    // vblank_hz do restuff.toml
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
            aspectRatio = enumOf(prefs.getString(K_ASPECT, null), AspectRatioOption.AUTO),
            resolutionScale = prefs.getFloat(K_SCALE, 1f).coerceIn(0.5f, 3f),
            textureFilter = enumOf(prefs.getString(K_FILTER, null), TextureFilterOption.BILINEAR),
            vsync = prefs.getBoolean(K_VSYNC, true),
            renderer = enumOf(prefs.getString(K_RENDERER, null), RendererOption.AUTO),
            fpsLimit = enumOf(prefs.getString(K_FPS, null), FpsLimitOption.FPS_60),
            frameSkip = prefs.getBoolean(K_FRAMESKIP, false),
            audioLatencyMs = prefs.getInt(K_LATENCY, 80).coerceIn(20, 200),
            audioMuted = prefs.getBoolean(K_MUTED, false),
            showOverlayControls = prefs.getBoolean(K_OVERLAY, true),
            overlayOpacity = prefs.getFloat(K_OPACITY, 0.65f).coerceIn(0.2f, 1f),
            overlayScale = prefs.getFloat(K_PADSCALE, 1f).coerceIn(0.7f, 1.6f),
            hapticFeedback = prefs.getBoolean(K_HAPTIC, true),
            unlockAllCheat = prefs.getBoolean(K_UNLOCK_ALL, false),
            unlock60Fps = prefs.getBoolean(K_UNLOCK_60FPS, true),
            vblankHz = prefs.getInt(K_VBLANK, 120).coerceIn(30, 240),
            particlesEnabled = prefs.getBoolean(K_PARTICLES, true),
            reduceMotionOverride = prefs.getBoolean(K_REDUCE_MOTION, false),
        )
    }

    fun save(s: PortSettings) {
        prefs.edit()
            .putString(K_ASPECT, s.aspectRatio.name)
            .putFloat(K_SCALE, s.resolutionScale)
            .putString(K_FILTER, s.textureFilter.name)
            .putBoolean(K_VSYNC, s.vsync)
            .putString(K_RENDERER, s.renderer.name)
            .putString(K_FPS, s.fpsLimit.name)
            .putBoolean(K_FRAMESKIP, s.frameSkip)
            .putInt(K_LATENCY, s.audioLatencyMs)
            .putBoolean(K_MUTED, s.audioMuted)
            .putBoolean(K_OVERLAY, s.showOverlayControls)
            .putFloat(K_OPACITY, s.overlayOpacity)
            .putFloat(K_PADSCALE, s.overlayScale)
            .putBoolean(K_HAPTIC, s.hapticFeedback)
            .putBoolean(K_UNLOCK_ALL, s.unlockAllCheat)
            .putBoolean(K_UNLOCK_60FPS, s.unlock60Fps)
            .putInt(K_VBLANK, s.vblankHz)
            .putBoolean(K_PARTICLES, s.particlesEnabled)
            .putBoolean(K_REDUCE_MOTION, s.reduceMotionOverride)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val K_ASPECT = "aspect_ratio"
        const val K_SCALE = "resolution_scale"
        const val K_FILTER = "texture_filter"
        const val K_VSYNC = "vsync"
        const val K_RENDERER = "renderer"
        const val K_FPS = "fps_limit"
        const val K_FRAMESKIP = "frame_skip"
        const val K_LATENCY = "audio_latency"
        const val K_MUTED = "audio_muted"
        const val K_OVERLAY = "overlay_controls"
        const val K_OPACITY = "overlay_opacity"
        const val K_PADSCALE = "overlay_pad_scale"
        const val K_HAPTIC = "haptic_feedback"
        const val K_UNLOCK_ALL = "restuff_unlock_all"
        const val K_UNLOCK_60FPS = "restuff_unlock_60fps"
        const val K_VBLANK = "restuff_vblank_hz"
        const val K_PARTICLES = "particles_enabled"     // mesma chave da v1.0
        const val K_REDUCE_MOTION = "reduce_motion"     // mesma chave da v1.0
    }
}
