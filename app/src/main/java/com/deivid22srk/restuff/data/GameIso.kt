package com.deivid22srk.restuff.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Resolução e validação do ISO do jogo para o boot IN-PLACE (modelo XenDroid:
 * https://github.com/rfandango/XenDroid — FrontendLaunch.contentToPath).
 *
 * O usuário seleciona o .iso uma única vez (SAF, ACTION_OPEN_DOCUMENT). O
 * app resolve a FONTE que o motor nativo vai montar:
 *
 *  1. CAMINHO REAL no armazenamento quando dá para traduzir o content://
 *     (provider de documentos do armazenamento externo, caminho embutido no
 *     URI de FileProviders, coluna DATA do MediaStore) — o motor abre com
 *     open()+mmap POSIX direto (requer leitura por caminho: "Acesso a todos
 *     os arquivos" OU volume legado);
 *  2. CONTENT:// URI (fallback robusto) — o motor mapeia o arquivo pelo
 *     FileDescriptor do ContentResolver (rex::filesystem::
 *     OpenAndroidContentFileDescriptor → mmap). Funciona com o grant
 *     persistente do SAF, SEM "Acesso a todos os arquivos", inclusive para
 *     ISOs em pendrives OTG e downloads.
 *
 * Nos DOIS casos a imagem roda ONDE ESTÁ — nada é copiado para dentro do app.
 * A escolha entre (1) e (2) é automática: só usamos caminho real quando o
 * arquivo é legível pelo processo (File.canRead); sem permissão de caminho a
 * cascata cai no content://, que sempre funciona com o grant do SAF.
 */
object GameIso {

    /** Fonte de dados do ISO: caminho real OU content:// URI. */
    data class Source(
        /** Valor passado ao motor como game_data_root (path ou content://). */
        val value: String,
        /** Nome exibido ao usuário (nome do arquivo .iso). */
        val displayName: String,
        /** true quando [value] é um content:// URI (ponte SAF do motor). */
        val isContentUri: Boolean,
    )

    // ------------------------------------------------------------------
    // Resolução content:// → fonte do motor (cascata XenDroid)
    // ------------------------------------------------------------------

    /**
     * Resolve o URI devolvido pelo seletor de arquivos na fonte que o motor
     * deve montar. Retorna null apenas quando o arquivo não pode ser aberto
     * de forma nenhuma (URI inválido + sem fallback).
     */
    fun resolve(context: Context, uri: Uri): Source? {
        val displayName = queryDisplayName(context, uri)?.takeIf { it.isNotBlank() }
            ?: deriveName(uri.toString())

        if (uri.scheme != "content") {
            // file:// (frontends/launchers) ou caminho cru: usa direto.
            val path = uri.path ?: return null
            return if (File(path).canRead()) {
                Source(path, File(path).name, false)
            } else null
        }

        // 1) Provider de documentos do armazenamento externo:
        //    "content://…/document/primary:Download/jogo.iso"
        //      → /storage/emulated/0/Download/jogo.iso
        documentsProviderPath(context, uri)?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) return Source(path, file.name, false)
        }

        // 2) FileProviders que embutem o caminho real no segmento do URI.
        smuggledPath(uri)?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) return Source(path, file.name, false)
        }

        // 3) Coluna DATA do MediaStore (ISO indexado pela galeria/mídia).
        mediaStoreDataPath(context, uri)?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) return Source(path, file.name, false)
        }

        // 4) Fallback: o próprio content:// — o motor mapeia via ContentResolver.
        return Source(uri.toString(), displayName, true)
    }

    /** Reconstrói a [Source] a partir do valor persistido. */
    fun parseSource(value: String): Source {
        val isUri = value.startsWith("content://")
        return Source(value, deriveName(value), isUri)
    }

    /** Nome de exibição best-effort de um caminho/URI persistido. */
    private fun deriveName(value: String): String {
        val raw = value.substringAfterLast('/')
        // "primary:Download/jogo.iso" → "jogo.iso"; URI pura já cai certo aqui.
        return raw.substringAfterLast(':').ifBlank { "jogo.iso" }
    }

    /**
     * Acesso rápido (gate de lançamento/restauração): o arquivo continua
     * abrível pelo processo? Caminho real → canRead; content:// → abre e
     * fecha o FileDescriptor (uma chamada de IPC).
     */
    fun isAccessible(context: Context, source: Source): Boolean = runCatching {
        if (source.isContentUri) {
            val pfd = context.contentResolver.openFileDescriptor(
                Uri.parse(source.value), "r"
            )
            pfd?.close()
            pfd != null
        } else {
            File(source.value).canRead()
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Validação GDFX/XDVDFS — espelha DiscImageDevice::Verify (nativo)
    // ------------------------------------------------------------------

    /** Offsets candidatos da partição de jogo (xenia/XGD — layer de vídeo incluída). */
    private val GAME_OFFSETS = longArrayOf(
        0x00000000L, 0x0000FB20L, 0x00020600L, 0x02080000L, 0x0FD90000L
    )
    private const val SECTOR = 2048
    private const val MAGIC = "MICROSOFT*XBOX*MEDIA"

    /**
     * Confere se a fonte é uma imagem de disco Xbox 360: magic
     * "MICROSOFT*XBOX*MEDIA" no setor 32 da partição de jogo (em algum dos
     * offsets XGD) + saneamento do diretório raiz (mesmos limites do Verify
     * nativo: 13 bytes..32 MiB). Leitura total: ~28 bytes por candidato.
     */
    fun validateXboxDisc(context: Context, source: Source): Boolean {
        if (source.isContentUri) {
            // pfd precisa ficar ABERTO enquanto o canal é consumido (mesmo fd):
            // aninhamento use{}-dentro-de-use{} do padrão do port.
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(source.value), "r")
            }.getOrNull() ?: return false
            return pfd.use {
                FileInputStream(pfd.fileDescriptor).channel.use(::probeChannel)
            }
        }
        return runCatching {
            FileChannel.open(File(source.value).toPath(), StandardOpenOption.READ).use(::probeChannel)
        }.getOrDefault(false)
    }

    /** Núcleo da validação sobre um canal já aberto. */
    private fun probeChannel(channel: FileChannel): Boolean {
        val head = ByteBuffer.allocate(28)
        // XDVDFS é LITTLE-ENDIAN (herança do Xbox original x86) — igual ao
        // memory::load<uint32_t> (memcpy, host LE) do DiscImageDevice nativo
        // e ao xe::load do xenia. O magic é texto (independente de ordem).
        head.order(ByteOrder.LITTLE_ENDIAN)
        for (gameOffset in GAME_OFFSETS) {
            val at = gameOffset + 32L * SECTOR
            head.clear()
            if (!readFully(channel, at, head)) continue
            head.position(0)
            val magic = ByteArray(MAGIC.length)
            head.get(magic)
            if (!MAGIC.equals(String(magic, Charsets.US_ASCII), ignoreCase = true)) continue
            val rootSector = head.getInt(20).toLong() and 0xFFFFFFFFL
            val rootSize = head.getInt(24).toLong() and 0xFFFFFFFFL
            if (rootSector > 0 && rootSize >= 13 && rootSize <= 32L * 1024 * 1024) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private fun readFully(channel: FileChannel, position: Long, dst: ByteBuffer): Boolean {
        var pos = position
        while (dst.hasRemaining()) {
            val n = channel.read(dst, pos)
            if (n < 0) return false
            pos += n
        }
        return true
    }

    /** com.android.externalstorage.documents: "primary:ROMs/x.iso" → caminho real. */
    private fun documentsProviderPath(context: Context, uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                DocumentsContract.getTreeDocumentId(uri)
            }
        }.getOrNull() ?: return null
        val split = docId.split(':', limit = 2)
        if (split.size != 2) return null
        val base = if (split[0].equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()?.absolutePath ?: return null
        } else {
            "/storage/${split[0]}" // volume removível (id tipo XXXX-XXXX)
        }
        return "$base/${split[1]}"
    }

    /** FileProviders que embutem o caminho real no path do URI. */
    private fun smuggledPath(uri: Uri): String? {
        val p = uri.path ?: return null
        val candidates = buildList {
            add(p)
            add(p.removePrefix("/root"))
            val idx = p.indexOf("/storage/")
            if (idx > 0) add(p.substring(idx))
            val primary = Environment.getExternalStorageDirectory()?.absolutePath
            val extIdx = p.indexOf("/external_files/")
            if (extIdx >= 0 && primary != null) {
                add("$primary/${p.substring(extIdx + "/external_files/".length)}")
            }
        }
        return candidates.firstOrNull { File(it).canRead() }
    }

    /** Coluna DATA do MediaStore (caminho real do arquivo indexado). */
    private fun mediaStoreDataPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Nome de exibição via query do SAF (coluna DISPLAY_NAME). */
    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
