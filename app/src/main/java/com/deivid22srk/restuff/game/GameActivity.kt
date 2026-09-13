package com.deivid22srk.restuff.game

import android.os.Environment
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.deivid22srk.restuff.data.GamePaths
import com.deivid22srk.restuff.data.GpuDriverManager
import com.deivid22srk.restuff.settings.PortSettings
import com.deivid22srk.restuff.settings.PortSettingsRepository
import org.libsdl.app.SDLActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity do jogo: estende o SDLActivity do SDL3 (classes Java embutidas de
 * thirdparty/sdl3) que cria a janela/superfície, injeta touch/gamepads físicos
 * e roda `SDL_main` na thread nativa do motor.
 *
 * O binário nativo é UMA biblioteca (librestuff.so) com o SDL3 linkado
 * estaticamente — ver [getLibraries] e android_main.cpp.
 *
 * Sobre o RelativeLayout do SDLActivity é adicionado o [VirtualGamepadView]
 * translúcido (P1 virtual). DECISÃO de input: quando visível, a view consome
 * TODOS os toques (o jogo é dirigido por gamepad — pass-through entregaria
 * o gesto multi-touch inteiro ao SDL e quebraria pressões simultâneas); o
 * painel de ajustes abre com 4 dedos, detectado aqui no nível da Activity.
 */
class GameActivity : SDLActivity() {

    private var virtualPad: VirtualGamepadView? = null
    private var fpsCounter: FpsCounterView? = null
    private var quickDialog: QuickSettingsDialog? = null
    private var exitDialog: ExitConfirmDialog? = null

    /** Gesto de 4 dedos capturado: o resto do fluxo é engolido até soltarem. */
    private var menuGestureCaptured = false

    private companion object {
        /** Nº de dedos simultâneos que abre o painel de ajustes rápidos. */
        const val MENU_FINGERS = 4

        /**
         * Janela p/ o acorde de 4 dedos contar como “tap”: os 4 downs precisam
         * ocorrer dentro deste intervalo desde o PRIMEIRO dedo do gesto. Uma
         * “garra” de gameplay (stick preso há segundos + gatilhos depois) tem
         * downTime antigo e não dispara o painel.
         */
        const val MENU_TAP_WINDOW_MS = 400L
    }

    /**
     * Resolve o diretório de logs no STORAGE PÚBLICO
     * (/storage/emulated/<user>/Naughty Bear ReStuff/logs), criando-o se
     * necessário e PROBANDO gravabilidade (arquivo .probe). Retorna null se
     * indisponível (permissão "Todos os arquivos" não concedida no Android
     * 11+, storage desmontado) — nesse caso o motor usa o storage privado.
     *
     * Nota: MANAGE_EXTERNAL_STORAGE já é pedida pela tela de seleção de dados
     * (DataSelectionScreen) para o fluxo de pasta sem cópia — aqui só se
     * CONFERE, com fallback silencioso. Environment.getExternalStorageDirectory()
     * resolve /storage/emulated/<userId> (não hardcode do usuário 0).
     */
    private fun resolvePublicLogDir(): File? = runCatching {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return@runCatching null
        val dir = File(Environment.getExternalStorageDirectory(), "Naughty Bear ReStuff/logs")
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
        val probe = File(dir, ".probe")
        probe.writeText("ok")
        probe.delete()
        dir
    }.getOrNull()

    /** Mantém apenas os [keep] logs mais recentes no diretório público. */
    private fun pruneOldLogs(dir: File, keep: Int) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("restuff_") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }

    /** Argumentos passados ao SDL_main (argv do motor). */
    override fun getArguments(): Array<String> {
        val settings = PortSettingsRepository(this).load()
        val filesDir = filesDir.absolutePath

        // Self-heal do driver Vortek ativo (review 17-e1 #1): o
        // nativeLibraryDir muda a cada atualização do app (Android 8+) e o
        // <files>/drivers/active.txt sobrevive à atualização — sem isto, o
        // jogo abriria no driver do sistema com o Vortek ainda
        // "selecionado" nas Configurações. Best-effort, nunca derruba o boot.
        runCatching { GpuDriverManager.reconcileActiveVortek(this) }
        // game_data_root: (1) ISO IN-PLACE — caminho real do .iso OU
        // content:// URI do SAF (o nativo monta a imagem GDFX onde ela está,
        // via DiscImageDevice — modelo XenDroid, sem cópia); (2) pasta REAL
        // do usuário (fluxo de pasta, sem cópia — POSIX + All Files Access);
        // (3) extraído legado (<files>/game). Decidido pelo GamePaths.
        val gameRoot = GamePaths.gameDataRootArgument(this)
        val savesRoot = GamePaths.savesDir(this).absolutePath
        val cacheRoot = GamePaths.cacheDir(this).absolutePath
        val configPath = GamePaths.configFile(this).absolutePath

        // --- Log detalhado persistido (requisito do port) -----------------
        // Storage público quando gravável (com retenção de 10 sessões);
        // senão cai para o storage privado (log_file vazio => numeração
        // sequencial automática em <files>/logs — nunca perde o log).
        val publicLogDir = resolvePublicLogDir()
        if (publicLogDir != null) pruneOldLogs(publicLogDir, keep = 10)
        val logLevel = if (settings.detailedLogs) "debug" else "info"
        val logFile: String? = publicLogDir?.let {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(it, "restuff_$stamp.log").absolutePath
        }
        // Onde o log desta sessão está (para a tela de Diagnóstico exibir):
        // "publico:<caminho>" ou "privado" (auto-numerado em <files>/logs).
        runCatching {
            File(filesDir, "last_log_location.txt").writeText(
                logFile?.let { "publico:$it" } ?: "privado"
            )
        }

        // Gera o restuff.toml do dispositivo ANTES do SDL_main ler o config.
        // log_file aponta para o arquivo público da sessão (ou vazio =>
        // auto-numeração no storage privado do app).
        GamePaths.ensureDirs(this)
        GamePaths.configFile(this).writeText(
            buildString {
                appendLine("# restuff.toml — gerado pelo port Android")
                // Caminho com espaços é válido em TOML basic string.
                appendLine("log_file = \"${logFile ?: ""}\"")
                appendLine("log_level = \"$logLevel\"")
                appendLine("log_flush_interval = 1")
                appendLine("fullscreen = false")
                appendLine("fps_cap = ${settings.fpsLimit.fps}")
                appendLine("vblank_hz = ${settings.vblankHz}")
                appendLine("use_translated_shaders = true")
                appendLine("unlock_all = ${settings.unlockAllCheat}")
                // Texture mods (upstream PC 6b269c1): packs em
                // <files>/texture_mods/<hash>.png — decoder stb no Android.
                // Toggle é a via oficial (sem root não se edita este arquivo).
                appendLine("tex_mods = ${settings.textureMods}")
                // GPUs móveis não expõem geometryShader (nem Turnip nem
                // Adreno/Mali) — exigir rejeita TODOS os devices → tela
                // preta. O default nativo também foi corrigido; isto é o
                // belt-and-suspenders (regenerado a cada boot).
                appendLine("vulkan_require_geometry_shader = false")
                appendLine("vulkan_require_fill_mode_non_solid = false")
                // ARM PERF (mobile): present mode FIFO-first. A preferência
                // do SDK é IMMEDIATE > MAILBOX > FIFO_RELAXED > FIFO — decisão
                // de latência de DESKTOP (tearing/VRR). Em mobile: painéis
                // 60/90/120Hz + pacing de 60Hz wall-clock com MAILBOX/IMMEDIATE
                // geram presents sem conteúdo novo (judder + consumo); FIFO
                // alinha a apresentação ao vsync do painel, deixa o compositor
                // agendar em baixa frequência e é o ÚNICO modo garantido em
                // drivers Android (Turnip/Adreno/Mali). Desligar os três
                // cvars faz a cascata do presenter cair no FIFO. Mesmo padrão
                // belt-and-suspenders dos vulkan_require_* acima — regenerado
                // a cada boot, zero código nativo.
                appendLine("vulkan_allow_present_mode_immediate = false")
                appendLine("vulkan_allow_present_mode_mailbox = false")
                appendLine("vulkan_allow_present_mode_fifo_relaxed = false")
            }
        )

        // Formato estável lido por android_main.cpp (parse explícito) e pelo
        // cvar::Init do SDK (flags --<cvar> com underscore: game_data_root,
        // user_data_root). As opções do motor (fps_cap, vblank_hz,
        // unlock_all, log_*) chegam via restuff.toml — gerado acima — que é
        // a via oficial de config do restuff; nada é passado "por garantia"
        // em flags que ninguém lê.
        return arrayOf(
            "--app-files-dir=$filesDir",
            "--native-lib-dir=${applicationInfo.nativeLibraryDir}",
            "--game_data_root=$gameRoot",
            "--user_data_root=$savesRoot",
            "--cache_root=$cacheRoot",
            "--config=$configPath",
            "--log-level=$logLevel",
            "--fps60=${settings.unlock60Fps}",
        ) + (logFile?.let { arrayOf("--log-file=$it") } ?: emptyArray())
    }

    /** SDL3 estático dentro de librestuff.so — um único load. */
    override fun getLibraries(): Array<String> = arrayOf("restuff")

    override fun getMainSharedObject(): String {
        return applicationInfo.nativeLibraryDir + "/librestuff.so"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.ensureLoaded()

        // Overlay do virtual gamepad por cima da SDLSurface. A view é SEMPRE
        // criada (visibilidade conforme a preferência): o diálogo de ajustes
        // rápidos liga/desliga AO VIVO, sem recriar nada.
        val settings = PortSettingsRepository(this).load()
        val layout = SDLActivity.getContentView() as? ViewGroup
        if (layout != null) {
            val pad = VirtualGamepadView(
                context = this,
                opacity = settings.overlayOpacity,
                scale = settings.overlayScale,
                haptics = settings.hapticFeedback,
            )
            pad.visibility =
                if (settings.showOverlayControls) View.VISIBLE else View.GONE
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layout.addView(pad, params)
            virtualPad = pad
        }

        // Pill de FPS (design Mel & Carvão): mono âmbar no canto superior
        // direito, lendo os presents Vulkan REAIS via JNI — ativada nas
        // Configurações, no painel de 4 dedos, ou aqui por padrão. Não
        // consome toques.
        if (settings.showFpsCounter) {
            setFpsCounterVisible(true)
        }
    }

    override fun onDestroy() {
        fpsCounter?.stop()
        fpsCounter = null
        virtualPad?.shutdown()
        virtualPad = null
        quickDialog?.dismiss()
        quickDialog = null
        exitDialog?.dismiss()
        exitDialog = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Gesto de 4 dedos → painel de ajustes rápidos
    // ------------------------------------------------------------------

    /**
     * Vê TODOS os eventos de toque (nível Activity, antes da árvore de
     * views) — funciona tanto com o overlay ligado (toques consumidos pelo
     * gamepad) quanto desligado (toques na superfície SDL). Ao detectar 4
     * dedos: manda ACTION_CANCEL para a árvore (o gamepad solta os botões,
     * o SDL solta os toques), engole o resto do gesto e abre o painel.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> menuGestureCaptured = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!menuGestureCaptured &&
                    ev.pointerCount >= MENU_FINGERS &&
                    ev.eventTime - ev.downTime <= MENU_TAP_WINDOW_MS &&
                    noDialogShowing()
                ) {
                    menuGestureCaptured = true
                    cancelActiveTouchGesture(ev)
                    showQuickSettings()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> menuGestureCaptured = false
        }
        if (menuGestureCaptured) return true // engole o resto do gesto
        return super.dispatchTouchEvent(ev)
    }

    private fun noDialogShowing(): Boolean =
        quickDialog?.isShowing != true && exitDialog?.isShowing != true

    /** Sintetiza um ACTION_CANCEL para o alvo atual do gesto em curso. */
    private fun cancelActiveTouchGesture(ev: MotionEvent) {
        runCatching {
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Botão voltar → confirmação antes de sair para a tela inicial
    // ------------------------------------------------------------------

    /**
     * O SDLActivity consome o KEYCODE_BACK como evento nativo do jogo — o
     * onBackPressed padrão nunca chega. Interceptamos aqui ANTES: back do
     * sistema (barra/gesto, fonte teclado) mostra o diálogo; back de
     * mouse/gamepad físicos segue para o SDL como sempre.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isSystemBackSource(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleBackRequested()
            }
            return true // consome DOWN e UP (não vai para o SDL)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Back “do sistema” (barra de navegação/gesto): fonte teclado ou virtual.
     * Exclui mouse (botão direito emulado), gamepads, joysticks e dpads
     * físicos — esses seguem para o SDL como sempre.
     *
     * ⚠️ Comparação por BITS DE DISPOSITIVO, sem o bit de CLASSE
     * (SOURCE_CLASS_BUTTON = 0x1): SOURCE_KEYBOARD (0x101), SOURCE_DPAD
     * (0x201) e SOURCE_GAMEPAD (0x401) compartilham o bit de classe — mascarar
     * a fonte inteira classificaria o back do teclado como “gamepad” e o
     * deixaria escapar para o SDL (o diálogo nunca abriria em vários aparelhos).
     */
    private fun isSystemBackSource(event: KeyEvent): Boolean {
        val src = event.source
        val classBit = InputDevice.SOURCE_CLASS_BUTTON
        val gamepadDeviceBits = (InputDevice.SOURCE_GAMEPAD or
            InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) and classBit.inv()
        val isGamepad = (src and gamepadDeviceBits) != 0
        val isMouse = (src and InputDevice.SOURCE_CLASS_POINTER) != 0
        return !isGamepad && !isMouse
    }

    override fun onBackPressed() {
        // Caminho do gesto de navegação (sem KeyEvent) e belt-and-suspenders.
        handleBackRequested()
    }

    private fun handleBackRequested() {
        when {
            quickDialog?.isShowing == true -> quickDialog?.dismiss()
            exitDialog?.isShowing == true -> exitDialog?.dismiss()
            else -> showExitConfirm()
        }
    }

    // ------------------------------------------------------------------
    // Painel de ajustes rápidos (aplicação ao vivo + persistência)
    // ------------------------------------------------------------------

    private fun showQuickSettings() {
        val settings = PortSettingsRepository(this).load()
        val dialog = QuickSettingsDialog(
            context = this,
            initial = settings,
            onChange = { updated ->
                applyOverlaySettings(updated)
                PortSettingsRepository(this).save(updated)
            },
            onExitRequested = { showExitConfirm() },
        )
        quickDialog = dialog
        dialog.setOnDismissListener { if (quickDialog === dialog) quickDialog = null }
        dialog.show()
    }

    private fun showExitConfirm() {
        val dialog = ExitConfirmDialog(
            context = this,
            onExit = {
                // Zera o pad ANTES de encerrar (nada de botão preso no fim).
                virtualPad?.shutdown()
                superOnBackPressed()
            },
        )
        exitDialog = dialog
        dialog.setOnDismissListener { if (exitDialog === dialog) exitDialog = null }
        dialog.show()
    }

    /** Aplica as preferências do painel AO VIVO (o jogo segue rodando). */
    private fun applyOverlaySettings(s: PortSettings) {
        virtualPad?.let { pad ->
            pad.setOpacity(s.overlayOpacity)
            pad.setScale(s.overlayScale)
            pad.setHaptics(s.hapticFeedback)
            pad.visibility = if (s.showOverlayControls) View.VISIBLE else View.GONE
        }
        setFpsCounterVisible(s.showFpsCounter)
    }

    private fun setFpsCounterVisible(visible: Boolean) {
        if (visible && fpsCounter == null) {
            fpsCounter = FpsCounterView.addTo(this)
        } else if (!visible) {
            fpsCounter?.let { fps ->
                (fps.parent as? ViewGroup)?.removeView(fps)
                fps.stop()
            }
            fpsCounter = null
        }
    }
}
