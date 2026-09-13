package com.deivid22srk.restuff.game

import android.os.Environment
import android.view.ViewGroup
import android.widget.FrameLayout
import com.deivid22srk.restuff.data.GamePaths
import com.deivid22srk.restuff.data.GpuDriverManager
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
 * translúcido (P1 virtual); áreas sem botão deixam o toque passar para a
 * superfície SDL (útil para os overlays ImGui do jogo).
 */
class GameActivity : SDLActivity() {

    private var virtualPad: VirtualGamepadView? = null
    private var fpsCounter: FpsCounterView? = null

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

        // Overlay do virtual gamepad por cima da SDLSurface.
        val settings = PortSettingsRepository(this).load()
        val layout = SDLActivity.getContentView() as? ViewGroup
        if (layout != null && settings.showOverlayControls) {
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

        // Pill de FPS (design Mel & Carvão): mono âmbar no canto superior
        // direito, lendo os presents Vulkan REAIS via JNI — ativada nas
        // Configurações (Desempenho → Contador de FPS). Não consome toques.
        if (settings.showFpsCounter) {
            fpsCounter = FpsCounterView.addTo(this)
        }
    }

    override fun onDestroy() {
        fpsCounter?.stop()
        fpsCounter = null
        virtualPad?.shutdown()
        virtualPad = null
        super.onDestroy()
    }
}
