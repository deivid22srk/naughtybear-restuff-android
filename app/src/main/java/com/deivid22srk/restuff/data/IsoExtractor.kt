package com.deivid22srk.restuff.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Extrator de imagens de disco Xbox 360 (GDFX/XDVDFS) direto via SAF.
 *
 * O arquivo .iso NUNCA é copiado para o armazenamento do app: abrimos um
 * FileChannel sobre o ParcelFileDescriptor do documento SAF (acesso aleatório
 * seekable) e despejamos apenas o CONTEÚDO do disco (Default.xex + arquivos de
 * dados) para GamePaths.gameDir() — o mesmo resultado do "dump" que o goopie
 * launcher faz no PC, atendendo ao HostPathDevice do runtime rexglue.
 *
 * Layout GDFX (todos os campos multi-byte em big-endian, setor = 2048):
 *   setor 32: volume descriptor
 *     +0x00 (20 bytes)  "MICROSOFT*XBOX*MEDIA"
 *     +0x14 (uint32 BE) setor do diretório raiz
 *     +0x18 (uint32 BE) tamanho do diretório raiz em bytes
 *   diretório: árvore binária de entradas
 *     uint16 BE left   — offset (em entradas relativas) do ramo esquerdo
 *     uint16 BE right  — offset do ramo direito
 *     uint32 BE sector — setor inicial do arquivo
 *     uint32 BE size   — tamanho em bytes
 *     uint8  attributes— bit 4 (0x10) = diretório
 *     uint8  nameLen
 *     nome (nameLen bytes, sem NUL); entrada preenchida a 4 bytes
 */
class IsoExtractor(private val context: Context) {

    data class Progress(
        val phase: Phase,
        val currentFile: String = "",
        val filesDone: Int = 0,
        val totalBytesCopied: Long = 0L,
        val totalBytesEstimate: Long = 0L,
    )

    enum class Phase { READING, EXTRACTING, DONE, CANCELLED, ERROR }

    /** Exceção de ISO inválido (não é GDFX/XDVDFS). */
    class BadIsoException(message: String) : IOException(message)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Extrai o conteúdo do ISO apontado por [isoUri] para a pasta do jogo.
     * Executar em Dispatchers.IO. Retorna o Progress final (DONE/ERROR/...).
     */
    fun extract(isoUri: Uri, onProgress: (Progress) -> Unit): Progress {
        cancelled = false
        GamePaths.ensureDirs(context)

        val destRoot = GamePaths.gameDir(context)
        // Re-extrair sempre de base limpa: conteúdo antigo incompleto corrompe o jogo.
        if (destRoot.exists()) {
            destRoot.deleteRecursively()
        }
        destRoot.mkdirs()

        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(isoUri, "r")
                ?: return Progress(Phase.ERROR, currentFile = "ISO não acessível via SAF")
        } catch (e: Exception) {
            return Progress(Phase.ERROR, currentFile = "Falha ao abrir ISO: ${e.message}")
        }

        pfd.use { fd ->
            val channel = FileInputStream(fd.fileDescriptor).channel
            channel.use { ch ->
                return extractCore(ch, destRoot, isoUri, onProgress)
            }
        }
    }

    /** Núcleo da extração (com o FileChannel já aberto). */
    private fun extractCore(
        channel: FileChannel,
        destRoot: File,
        isoUri: Uri,
        onProgress: (Progress) -> Unit,
    ): Progress {
        try {
            val volume = readVolumeDescriptor(channel)
                ?: return Progress(
                    Phase.ERROR,
                    currentFile = "ISO não contém volume XDVDFS " +
                        "(não é uma imagem Xbox 360 válida)"
                )

            onProgress(Progress(Phase.EXTRACTING, currentFile = "Preparando…"))
            val stats = extractDirTree(channel, volume, destRoot, onProgress)

            if (cancelled) {
                onProgress(Progress(Phase.CANCELLED, filesDone = stats.files))
                return Progress(Phase.CANCELLED, filesDone = stats.files)
            }

            val xex = File(destRoot, GamePaths.XEX_NAME)
            if (!xex.isFile || xex.length() < 1_000_000) {
                return Progress(
                    Phase.ERROR,
                    currentFile = "${GamePaths.XEX_NAME} ausente no ISO — " +
                        "confirme que a imagem é a Naughty Bear Gold Edition"
                )
            }

            // Marker de conclusão: libera o botão "Iniciar Jogo".
            GamePaths.extractMarker(context).writeText(
                "iso=${isoUri}\nfiles=${stats.files}\nbytes=${stats.bytes}\n"
            )
            onProgress(Progress(Phase.DONE, filesDone = stats.files, totalBytesCopied = stats.bytes))
            return Progress(Phase.DONE, filesDone = stats.files, totalBytesCopied = stats.bytes)
        } catch (e: BadIsoException) {
            return Progress(Phase.ERROR, currentFile = e.message ?: "ISO inválido")
        } catch (e: Exception) {
            if (cancelled) {
                onProgress(Progress(Phase.CANCELLED))
                return Progress(Phase.CANCELLED)
            }
            return Progress(Phase.ERROR, currentFile = "Erro na extração: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Volume descriptor
    // ------------------------------------------------------------------

    private data class GdfxVolume(val rootSector: Long, val rootSizeBytes: Long)

    private fun readVolumeDescriptor(channel: FileChannel): GdfxVolume? {
        val buf = ByteBuffer.allocate(SECTOR)
        buf.order(ByteOrder.BIG_ENDIAN)
        readSector(channel, SECTOR.toLong() * 32, buf) // setor 32
        val magic = "MICROSOFT*XBOX*MEDIA"
        val head = ByteArray(magic.length)
        buf.position(0)
        buf.get(head)
        if (!String(head, Charsets.US_ASCII).equals(magic, ignoreCase = true)) return null
        val rootSector = buf.getInt(0x14).toLong() and 0xFFFFFFFFL
        val rootSize = buf.getInt(0x18).toLong() and 0xFFFFFFFFL
        if (rootSector <= 0L || rootSize <= 0L) return null
        return GdfxVolume(rootSector, rootSize)
    }

    // ------------------------------------------------------------------
    // Extração da árvore de diretórios
    // ------------------------------------------------------------------

    private class Stats(var files: Int = 0, var bytes: Long = 0L)

    private fun extractDirTree(
        channel: FileChannel,
        volume: GdfxVolume,
        destRoot: File,
        onProgress: (Progress) -> Unit,
    ): Stats {
        val stats = Stats()
        val dirBuf = ByteBuffer.allocate(rootDirBufferSize(volume.rootSizeBytes))
        dirBuf.order(ByteOrder.BIG_ENDIAN)
        readExtent(channel, volume.rootSector * SECTOR, volume.rootSizeBytes, dirBuf)

        // xDVDFS: as entradas formam uma árvore binária balanceada ordenada por
        // nome. O offset ESQUERDO conta para TRÁS (índice - left) e o direito
        // para frente (índice + right); 0 = ausente. Estrutura iterativa.
        data class Frame(val dir: ByteBuffer, val index: Int, val path: File)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(dirBuf, 0, destRoot))

        var lastReport = 0L
        while (stack.isNotEmpty()) {
            if (cancelled) return stats
            val frame = stack.removeLast()
            var idx = frame.index
            // Caminha a subárvore a partir do índice dado: visita a entrada e
            // segue o ramo direito; ramos esquerdos entram na pilha.
            while (idx >= 0 && idx * ENTRY_ALIGN < frame.dir.capacity()) {
                if (cancelled) return stats
                val entry = readEntry(frame.dir, idx) ?: break

                if (entry.left > 0) {
                    stack.addLast(Frame(frame.dir, idx - entry.left, frame.path))
                }

                val childPath = File(frame.path, entry.name)
                if (entry.isDirectory) {
                    childPath.mkdirs()
                    if (entry.sizeBytes > 0) {
                        val sub = ByteBuffer.allocate(rootDirBufferSize(entry.sizeBytes))
                        sub.order(ByteOrder.BIG_ENDIAN)
                        readExtent(channel, entry.sector * SECTOR, entry.sizeBytes, sub)
                        stack.addLast(Frame(sub, 0, childPath))
                    }
                } else if (entry.sizeBytes > 0) {
                    val copied = copyFile(channel, entry, childPath) { done ->
                        if (System.currentTimeMillis() - lastReport > PROGRESS_INTERVAL_MS) {
                            lastReport = System.currentTimeMillis()
                            onProgress(
                                Progress(
                                    Phase.EXTRACTING,
                                    currentFile = entry.name,
                                    filesDone = stats.files,
                                    totalBytesCopied = stats.bytes + done,
                                    totalBytesEstimate = 0L,
                                )
                            )
                        }
                    }
                    stats.bytes += copied
                    stats.files++
                    onProgress(
                        Progress(
                            Phase.EXTRACTING,
                            currentFile = entry.name,
                            filesDone = stats.files,
                            totalBytesCopied = stats.bytes,
                        )
                    )
                }

                if (entry.right > 0) {
                    idx += entry.right
                } else {
                    break
                }
            }
        }
        return stats
    }

    private data class Entry(
        val name: String,
        val sector: Long,
        val sizeBytes: Long,
        val isDirectory: Boolean,
        val left: Int,
        val right: Int,
    )

    /** Buffer sempre múltiplo do setor para leituras alinhadas. */
    private fun rootDirBufferSize(sizeBytes: Long): Int {
        val padded = ((sizeBytes + SECTOR - 1) / SECTOR) * SECTOR
        return padded.coerceAtMost(MAX_DIR_BUFFER.toLong()).toInt()
    }

    private fun readEntry(dir: ByteBuffer, index: Int): Entry? {
        var pos = index * ENTRY_ALIGN
        if (pos + 14 > dir.capacity()) return null
        val left = dir.getShort(pos).toInt() and 0xFFFF
        val right = dir.getShort(pos + 2).toInt() and 0xFFFF
        val sector = dir.getInt(pos + 4).toLong() and 0xFFFFFFFFL
        val size = dir.getInt(pos + 8).toLong() and 0xFFFFFFFFL
        val attrs = dir.get(pos + 12).toInt()
        val nameLen = dir.get(pos + 13).toInt() and 0xFF
        if (nameLen <= 0 || pos + 14 + nameLen > dir.capacity()) return null
        val nameBytes = ByteArray(nameLen)
        dir.position(pos + 14)
        dir.get(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)
        if (name.isEmpty() || name == "." || name == "..") return null
        return Entry(
            name = name,
            sector = sector,
            sizeBytes = size,
            isDirectory = (attrs and 0x10) != 0,
            left = left,
            right = right,
        )
    }

    private fun copyFile(
        channel: FileChannel,
        entry: Entry,
        dest: File,
        onChunk: (Long) -> Unit,
    ): Long {
        dest.parentFile?.mkdirs()
        var copied = 0L
        try {
            FileChannel.open(dest.toPath(), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER)
                var remaining = entry.sizeBytes
                var srcPos = entry.sector * SECTOR
                while (remaining > 0) {
                    val chunk = minOf(remaining, COPY_BUFFER.toLong()).toInt()
                    buffer.clear().limit(chunk)
                    val n = readExtentInto(channel, srcPos, buffer)
                    if (n <= 0) break // setor além do fim (imagem truncada)
                    buffer.flip()
                    while (buffer.hasRemaining()) out.write(buffer)
                    copied += n
                    remaining -= n
                    srcPos += n
                    onChunk(copied)
                }
            }
        } catch (e: Exception) {
            // Arquivo corrompido/ilegível: registra e segue (o jogo pode nem usar).
            dest.delete()
            dest.parentFile?.resolve("${dest.name}.extract_failed")?.writeText("${e.message}\n")
        }
        return copied
    }

    // ------------------------------------------------------------------
    // I/O alinhado a setor sobre o FileChannel do SAF
    // ------------------------------------------------------------------

    private fun readSector(channel: FileChannel, position: Long, dst: ByteBuffer) {
        dst.clear()
        var pos = position
        while (dst.hasRemaining()) {
            val n = channel.read(dst, pos)
            if (n < 0) throw BadIsoException("Imagem truncada (fim inesperado no setor ${pos / SECTOR})")
            pos += n
        }
    }

    /**
     * Lê [sizeBytes] a partir de [fileOffset] no buffer (grandes extents de
     * diretório podem ultrapassar o buffer: nesse caso trunca com segurança —
     * entradas além do buffer raramente existem em discos reais).
     */
    private fun readExtent(channel: FileChannel, fileOffset: Long, sizeBytes: Long, dst: ByteBuffer) {
        dst.clear()
        var pos = fileOffset
        var remaining = minOf(sizeBytes, dst.capacity().toLong())
        // Alinha leituras ao setor para FileChannels de documentos SAF.
        while (remaining > 0) {
            val chunk = minOf(remaining, SECTOR.toLong()).toInt()
            dst.limit(dst.position() + chunk)
            var got = 0
            while (got < chunk) {
                val n = channel.read(dst, pos + got)
                if (n < 0) break
                got += n
            }
            if (got < chunk) break
            pos += chunk
            remaining -= chunk
        }
        dst.limit(dst.capacity())
    }

    private fun readExtentInto(channel: FileChannel, fileOffset: Long, dst: ByteBuffer): Int {
        var pos = fileOffset
        var total = 0
        while (dst.hasRemaining()) {
            val n = channel.read(dst, pos)
            if (n < 0) break
            if (n == 0) break
            pos += n
            total += n
        }
        return total
    }

    companion object {
        const val SECTOR = 2048
        const val ENTRY_ALIGN = 4
        const val COPY_BUFFER = 1 shl 20 // 1 MiB
        const val MAX_DIR_BUFFER = 8 shl 20 // diretórios maiores que 8 MiB: trunca (não existe em discos reais)
        const val PROGRESS_INTERVAL_MS = 120L
    }
}
