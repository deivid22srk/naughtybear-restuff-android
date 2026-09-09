package com.deivid22srk.restuff.data

import android.content.Context
import java.io.File

/**
 * Caminhos de dados do port ReStuff no armazenamento privado do app.
 *
 * Modelo de assets (mesma semântica do goopie launcher no PC):
 *  - [gameDir]     : "game_data_root" — pasta com Default.xex + conteúdo
 *                    extraído UMA única vez do ISO selecionado via SAF.
 *                    O runtime do rexglue monta esta pasta como
 *                    \Device\Harddisk0\Partition1 (HostPathDevice).
 *  - [savesDir]    : "user_data_root" — saves/XCONTENT do jogo, separados
 *                    da pasta do jogo para que "limpar dados" não apague saves.
 *  - [configFile]  : restuff.toml gerado a partir das Configurações do app.
 */
object GamePaths {

    private const val GAME_DIR_NAME = "game"
    private const val SAVES_DIR_NAME = "saves"
    private const val EXTRACT_MARKER = ".extract_ok"
    const val XEX_NAME = "Default.xex"

    fun gameDir(context: Context): File =
        File(context.filesDir, GAME_DIR_NAME)

    fun savesDir(context: Context): File =
        File(context.filesDir, SAVES_DIR_NAME)

    fun configFile(context: Context): File =
        File(context.filesDir, "restuff.toml")

    fun cacheDir(context: Context): File =
        File(context.filesDir, "cache").apply { mkdirs() }

    /** Marker da extração concluída com sucesso. */
    fun extractMarker(context: Context): File =
        File(gameDir(context), EXTRACT_MARKER)

    /** Jogo já está pronto (extração completa + Default.xex presente)? */
    fun isGameDataReady(context: Context): Boolean =
        extractMarker(context).exists() && File(gameDir(context), XEX_NAME).isFile

    /** Default.xex presente mesmo sem extração completa (modo pasta)? */
    fun hasRawXex(context: Context): Boolean =
        File(gameDir(context), XEX_NAME).isFile

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
