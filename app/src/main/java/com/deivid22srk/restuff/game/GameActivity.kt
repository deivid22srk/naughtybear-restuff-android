package com.deivid22srk.restuff.game

import android.view.ViewGroup
import android.widget.FrameLayout
import com.deivid22srk.restuff.data.GamePaths
import com.deivid22srk.restuff.settings.PortSettingsRepository
import org.libsdl.app.SDLActivity

/**
 * Activity do jogo: estende o SDLActivity do SDL3 (classes Java embutidas de
 * thirdparty/sdl3) que cria a janela/superfície, injeta touch/gamepads físicos
 * e roda `SDL_main` na thread nativa do motor.
 *
 * O binário nativo é UMA biblioteca (librestuff.so) com o SDL3 linkado
 * estaticamente — ver [getLibraries] e android_main.cpp.
 *
 * Sobre o RelativeLayout do SDLActivity é adicionado o [VirtualGamepadView]
 * translúcido (P1 virtual); áreas sem botão deixam o toque passar para a
 * superfície SDL (útil para os overlays ImGui do jogo).
 */
class GameActivity : SDLActivity() {

    private var virtualPad: VirtualGamepadView? = null

    /** Argumentos passados ao SDL_main (argv do motor). */
    override fun getArguments(): Array<String> {
        val settings = PortSettingsRepository(this).load()
        val filesDir = filesDir.absolutePath
        val gameRoot = GamePaths.gameDir(this).absolutePath
        val savesRoot = GamePaths.savesDir(this).absolutePath
        val cacheRoot = GamePaths.cacheDir(this).absolutePath
        val configPath = GamePaths.configFile(this).absolutePath

        // Gera o restuff.toml do dispositivo ANTES do SDL_main ler o config.
        // log_file vazio => saída para logcat (app_name "restuff").
        GamePaths.ensureDirs(this)
        GamePaths.configFile(this).writeText(
            buildString {
                appendLine("# restuff.toml — gerado pelo port Android")
                appendLine("log_file = \"\"")
                appendLine("log_flush_interval = 1")
                appendLine("fullscreen = false")
                appendLine("fps_cap = ${settings.fpsLimit.fps}")
                appendLine("vblank_hz = ${settings.vblankHz}")
                appendLine("use_translated_shaders = true")
                appendLine("unlock_all = ${settings.unlockAllCheat}")
            }
        )

        // Formato estável lido por android_main.cpp (parse ArgLine).
        return arrayOf(
            "--rex-android=1",
            "--app-files-dir=$filesDir",
            "--game_data_root=$gameRoot",
            "--user_data_root=$savesRoot",
            "--cache_root=$cacheRoot",
            "--config=$configPath",
            "--unlock-all=${settings.unlockAllCheat}",
            "--fps60=${settings.unlock60Fps}",
            "--fps-cap=${settings.fpsLimit.fps}",
            "--overlay-opacity=${settings.overlayOpacity}",
            "--pad-scale=${settings.overlayScale}",
        )
    }

    /** SDL3 estático dentro de librestuff.so — um único load. */
    override fun getLibraries(): Array<String> = arrayOf("restuff")

    override fun getMainSharedObject(): String {
        return applicationInfo.nativeLibraryDir + "/librestuff.so"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.ensureLoaded()

        // Overlay do virtual gamepad por cima da SDLSurface.
        val settings = PortSettingsRepository(this).load()
        if (settings.showOverlayControls) {
            val layout = SDLActivity.getContentView() as? ViewGroup ?: return
            val pad = VirtualGamepadView(
                context = this,
                opacity = settings.overlayOpacity,
                scale = settings.overlayScale,
                haptics = settings.hapticFeedback,
            )
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layout.addView(pad, params)
            virtualPad = pad
        }
    }

    override fun onDestroy() {
        virtualPad?.shutdown()
        virtualPad = null
        super.onDestroy()
    }
}
