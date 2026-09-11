package com.deivid22srk.restuff.data

import android.content.Context
import java.io.File

/**
 * Caminhos de dados do port ReStuff.
 *
 * Modelo de assets (mesma semântica do goopie launcher no PC):
 *  - [gameRoot]    : "game_data_root" — pasta com Default.xex + conteúdo.
 *                    O runtime do rexglue monta esta pasta como
 *                    \Device\Harddisk0\Partition1 (HostPathDevice).
 *                    Pode ser (a) a pasta REAL no armazenamento do usuário
 *                    (fluxo de pasta, SEM cópia — o motor lê direto via
 *                    POSIX com "Acesso a todos os arquivos") ou (b) a pasta
 *                    extraída do ISO no armazenamento privado do app.
 *  - [gameDir]     : pasta extraída do ISO dentro do app (fluxo ISO).
 *  - [savesDir]    : "user_data_root" — saves/XCONTENT do jogo, separados
 *                    da pasta do jogo para que "limpar dados" não apague saves.
 *  - [configFile]  : restuff.toml gerado a partir das Configurações do app.
 */
object GamePaths {

    private const val GAME_DIR_NAME = "game"
    private const val SAVES_DIR_NAME = "saves"
    private const val EXTRACT_MARKER = ".extract_ok"
    const val XEX_NAME = "Default.xex"

    /** Mesmo arquivo de prefs usado pelo [com.deivid22srk.restuff.viewmodel.DataSelectionViewModel]. */
    private const val PREFS_NAME = "port_screen_prefs"
    private const val KEY_GAME_ROOT = "game_root_path"

    /**
     * Raiz do jogo que o motor deve usar: a pasta real persistida pelo fluxo
     * de pasta (sem cópia) quando ainda existe no disco; senão a pasta
     * extraída do ISO dentro do armazenamento privado do app.
     */
    fun gameRoot(context: Context): File {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_GAME_ROOT, null)?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }
        return gameDir(context)
    }

    fun gameDir(context: Context): File =
        File(context.filesDir, GAME_DIR_NAME)

    fun savesDir(context: Context): File =
        File(context.filesDir, SAVES_DIR_NAME)

    fun configFile(context: Context): File =
        File(context.filesDir, "restuff.toml")

    fun cacheDir(context: Context): File =
        File(context.filesDir, "cache").apply { mkdirs() }

    /** Marker da extração concluída com sucesso (fluxo ISO). */
    fun extractMarker(context: Context): File =
        File(gameDir(context), EXTRACT_MARKER)

    /** Jogo já está pronto (extração completa + Default.xex presente)? */
    fun isGameDataReady(context: Context): Boolean =
        extractMarker(context).exists() && File(gameDir(context), XEX_NAME).isFile

    /** Default.xex presente mesmo sem extração completa (modo pasta)? */
    fun hasRawXex(context: Context): Boolean =
        File(gameDir(context), XEX_NAME).isFile

    /**
     * Procura um arquivo na pasta comparando SEM diferenciar maiúsculas
     * (uma listagem POSIX — barato, sem IPC de SAF). O Android diferencia
     * caso no disco; o jogo pode trazer "Default.xex", "default.xex" etc.
     */
    fun findFileCaseInsensitive(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** O entrypoint do jogo (Default.xex, qualquer caso) dentro de [dir]. */
    fun findXex(dir: File): File? = findFileCaseInsensitive(dir, XEX_NAME)

    /** Persiste a raiz do jogo real (fluxo pasta sem cópia). null limpa. */
    fun setGameRoot(context: Context, path: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (path == null) prefs.edit().remove(KEY_GAME_ROOT).apply()
        else prefs.edit().putString(KEY_GAME_ROOT, path).apply()
    }

    /** Prepara a árvore de diretórios do port. */
    fun ensureDirs(context: Context) {
        gameDir(context).mkdirs()
        savesDir(context).mkdirs()
        cacheDir(context)
    }

    /** Apaga todo o conteúdo extraído (usado antes de re-extrair ou limpar). */
    fun wipeGameData(context: Context) {
        gameDir(context).deleteRecursively()
    }

    /** Espaço livre aproximado em bytes no armazenamento interno. */
    fun freeSpaceBytes(context: Context): Long =
        context.filesDir.usableSpace
}
