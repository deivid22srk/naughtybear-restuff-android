package com.deivid22srk.restuff.data

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Gerenciador de drivers gráficos Vulkan no padrão **AdrenoTools** (Turnip).
 *
 * Formato suportado (mesmo dos repositórios públicos de drivers, ex.:
 * K11MCH1/AdrenoToolsDrivers — zips Mesa/Turnip):
 *
 * ```
 * driver.zip
 *  ├── meta.json                    { "libName", "author", "description",
 *  │                                  "packageVersion"/"version", "vendor",
 *  │                                  "gpus", "name" ... }
 *  └── lib/ARM64-v8A/libvulkan_freedreno.so   (ou o .so na raiz / outro dir)
 * ```
 *
 * O .so localizado é extraído para o armazenamento privado do app
 * (`files/drivers/<id>/driver.so`) e o driver ativo é persistido em
 * `files/drivers/active.txt` no formato `lib=<caminho>`. O lado NATIVO
 * (android_main.cpp) lê esse arquivo antes de qualquer init gráfico e o
 * loader do SDK (vulkan_instance.cpp) faz dlopen do driver — i.e. o driver
 * selecionado é usado DE VERDADE para criar instance/device/swapchain.
 *
 * Compatibilidade de chaves: aceita `libName` (AdrenoTools) e `libraryName`
 * (variações da comunidade); `name` e `description` são usados como rótulo.
 */
object GpuDriverManager {

    /** Driver importado (metadados + caminho do .so extraído). */
    data class GpuDriver(
        val id: String,
        val libName: String,
        val name: String,
        val author: String,
        val version: String,
        val vendor: String,
        val libPath: String,
    )

    /** Erro de import com mensagem pronta para exibir ao usuário. */
    class DriverImportException(message: String) : IOException(message)

    // ----------------------------------------------------------------------
    // Caminhos
    // ----------------------------------------------------------------------

    fun driversDir(context: Context): File = File(context.filesDir, "drivers")

    fun activeFile(context: Context): File = File(driversDir(context), "active.txt")

    private fun driverJson(dir: File): File = File(dir, "driver.json")

    // ----------------------------------------------------------------------
    // Listagem
    // ----------------------------------------------------------------------

    /** Lista os drivers importados (metadados válidos), mais recentes primeiro. */
    fun list(context: Context): List<GpuDriver> {
        val dir = driversDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { sub -> readDriverJson(sub) }
            ?.sortedByDescending { it.id }
            ?: emptyList()
    }

    private fun readDriverJson(dir: File): GpuDriver? = runCatching {
        val json = driverJson(dir)
        if (!json.isFile) return null
        val o = JSONObject(json.readText())
        GpuDriver(
            id = dir.name,
            libName = o.optString("libName"),
            name = o.optString("name", "").ifEmpty { o.optString("libName") },
            author = o.optString("author", ""),
            version = o.optString("version", ""),
            vendor = o.optString("vendor", ""),
            libPath = o.getString("libPath"),
        )
    }.getOrNull()

    // ----------------------------------------------------------------------
    // Import (.zip AdrenoTools via SAF)
    // ----------------------------------------------------------------------

    /**
     * Importa um driver Turnip (.zip) escolhido via SAF. Valida zip, meta.json
     * e a biblioteca .so (assinatura ELF + arquitetura arm64 + tipo), extrai
     * TODOS os .so (companheiros com nomes originais — pacotes adpkg dependem
     * deles) e persiste metadados. Retorna o driver importado. Lança
     * [DriverImportException] com mensagem amigável.
     */
    fun importFromZip(context: Context, zipUri: Uri): GpuDriver {
        val dir = driversDir(context).apply { mkdirs() }

        // ---- Passada 1: nomes das entradas + meta.json ------------------
        val entries = ArrayList<String>()
        var metaJson: String? = null
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input.buffered(DEFAULT_BUFFER)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name ?: ""
                        if (!e.isDirectory) {
                            if (isMetaJson(name) && metaJson == null) {
                                metaJson = zip.readBytes().toString(Charsets.UTF_8)
                            } else {
                                entries.add(name)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw DriverImportException("Não foi possível abrir o arquivo selecionado.")
        } catch (e: DriverImportException) {
            throw e
        } catch (e: ZipException) {
            throw DriverImportException("Arquivo .zip inválido ou corrompido.")
        } catch (e: IOException) {
            throw DriverImportException("Falha ao ler o .zip: ${e.message ?: "erro de E/S"}")
        }

        if (entries.isEmpty() && metaJson == null) {
            throw DriverImportException("O .zip está vazio.")
        }

        // ---- Valida meta.json (padrão AdrenoTools) ----------------------
        if (metaJson == null) {
            throw DriverImportException(
                "meta.json não encontrado no .zip — não é um driver no padrão AdrenoTools."
            )
        }
        val meta = try {
            JSONObject(metaJson!!)
        } catch (e: Exception) {
            throw DriverImportException("meta.json do driver é inválido (JSON corrompido).")
        }

        val libName = (meta.optString("libName", "")
            .ifEmpty { meta.optString("libraryName", "") }).trim()
        val displayName = meta.optString("name", "")
            .ifEmpty { meta.optString("description", "") }
            .ifEmpty { "Driver Turnip" }
        val author = meta.optString("author", "").ifEmpty { "desconhecido" }
        val version = meta.optString("packageVersion", "")
            .ifEmpty { meta.optString("version", "") }.ifEmpty { "?" }
        val vendor = meta.optString("vendor", "")

        // [evidência] minApi dos meta.json reais (K11MCH1 26.0.0 = 27;
        // adpkg 849 = 35): respeitar evita importar driver que o adrenotools
        // não consegue carregar no aparelho.
        val minApi = meta.optInt("minAPI", meta.optInt("minApi", 0))
        if (minApi > 0 && minApi > Build.VERSION.SDK_INT) {
            throw DriverImportException(
                "Este driver exige Android $minApi (minAPI do meta.json); o " +
                    "aparelho está no Android ${Build.VERSION.SDK_INT}."
            )
        }

        // ---- Localiza a biblioteca .so do driver ------------------------
        // Caminhos reais variam: raiz do zip, lib/ARM64-v8A/, arm64-v8a/ etc.
        // Prioridade: basename == libName (case-insensitive) → .so com
        // "vulkan"/"turnip"/"freedreno" no nome → primeiro .so.
        val soEntries = entries.filter { it.endsWith(".so", ignoreCase = true) }
        if (soEntries.isEmpty()) {
            throw DriverImportException(
                "Nenhuma biblioteca .so encontrada no .zip — driver incompleto."
            )
        }
        val target = libName.takeIf { it.isNotEmpty() }
            ?.let { ln -> soEntries.firstOrNull { it.substringAfterLast('/').equals(ln, true) } }
            ?: soEntries.firstOrNull {
                val n = it.lowercase()
                n.contains("vulkan") || n.contains("turnip") || n.contains("freedreno")
            }
            ?: soEntries.first()
        if (libName.isNotEmpty() &&
            !target.substringAfterLast('/').equals(libName, true)
        ) {
            throw DriverImportException(
                "Biblioteca do driver ('$libName' esperado) não encontrada no .zip."
            )
        }

        // ---- Passada 2: extrai TODOS os .so (nomes ORIGINAIS) ------------
        // O carregador real (libadrenotools) abre o driver pelo NOME no
        // diretório dele num namespace próprio — e pacotes multi-arquivo
        // (adpkg) têm companheiros (libgsl.so, libadreno_utils.so, not*.so)
        // resolvidos por DT_NEEDED no MESMO diretório. Renomear tudo para
        // "driver.so" quebrava o adpkg e dispensava os companheiros.
        // A biblioteca principal mantém o basename do zip (alvo da seleção
        // acima) e recebe validação completa de ELF/arm64/ET_DYN.
        val id = newDriverId(displayName, version)
        val destDir = File(dir, id).apply { mkdirs() }
        val destSo = File(destDir, target.substringAfterLast('/'))
        try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input.buffered(DEFAULT_BUFFER)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory && e.name.endsWith(".so", ignoreCase = true)) {
                            val dest = File(destDir, e.name.substringAfterLast('/'))
                            if (dest.name == destSo.name) {
                                copySoValidating(zip, dest, validateArch = true)
                            } else {
                                copySoValidating(zip, dest, validateArch = false)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw IOException("SAF fechou o stream")
            if (!destSo.isFile) {
                throw DriverImportException(
                    "Biblioteca principal ('$target') não foi extraída — zip incompleto."
                )
            }
        } catch (e: Exception) {
            destDir.deleteRecursively()
            if (e is DriverImportException) throw e
            throw DriverImportException("Falha ao extrair o driver: ${e.message ?: "erro de E/S"}")
        }

        // ---- Persiste metadados ----------------------------------------
        val driver = GpuDriver(
            id = id,
            libName = libName.ifEmpty { target.substringAfterLast('/') },
            name = displayName,
            author = author,
            version = version,
            vendor = vendor,
            libPath = destSo.absolutePath,
        )
        try {
            driverJson(destDir).writeText(
                JSONObject()
                    .put("libName", driver.libName)
                    .put("name", driver.name)
                    .put("author", driver.author)
                    .put("version", driver.version)
                    .put("vendor", driver.vendor)
                    .put("libPath", driver.libPath)
                    .put("source", zipUri.toString())
                    .toString(2)
            )
        } catch (e: IOException) {
            destDir.deleteRecursively()
            throw DriverImportException("Falha ao salvar metadados do driver.")
        }
        return driver
    }

    /**
     * Copia um .so do zip validando: magic ELF (0x7F 'E' 'L' 'F') sempre;
     * quando [validateArch], também classe ELF64, máquina EM_AARCH64 (183) e
     * tipo ET_DYN (3) — drivers x86_64 ou objetos relocáveis eram aceitos
     * antes e falhavam só no boot, sem mensagem útil.
     */
    private fun copySoValidating(zip: ZipInputStream, dest: File, validateArch: Boolean) {
        dest.outputStream().use { out ->
            val header = ByteArray(20)
            var read = 0
            while (read < 20) {
                val n = zip.read(header, read, 20 - read)
                if (n < 0) throw DriverImportException("Biblioteca do driver está truncada.")
                read += n
            }
            if (!(header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte())
            ) {
                throw DriverImportException(
                    "A biblioteca dentro do .zip não é um binário ELF válido (driver incompatível)."
                )
            }
            if (validateArch) {
                val eiClass = header[4].toInt() and 0xFF            // 2 = ELF64
                val eType = ((header[17].toInt() and 0xFF) shl 8) or (header[16].toInt() and 0xFF)
                val eMachine = ((header[19].toInt() and 0xFF) shl 8) or (header[18].toInt() and 0xFF)
                if (eiClass != 2 || eMachine != 183 || eType != 3) {
                    throw DriverImportException(
                        "Biblioteca principal não é um ELF arm64-v8a compartilhado " +
                            "(classe=$eiClass máquina=$eMachine tipo=$eType) — driver incompatível."
                    )
                }
            }
            out.write(header)
            zip.copyTo(out, DEFAULT_BUFFER)
        }
    }

    private fun isMetaJson(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "meta.json" || lower.endsWith("/meta.json")
    }

    private fun newDriverId(name: String, version: String): String {
        val stamp = System.currentTimeMillis().toString(36)
        val slug = name.lowercase().filter { it.isLetterOrDigit() }.take(16).ifEmpty { "driver" }
        return "${slug}_${version.filter { it.isLetterOrDigit() || it == '.' }}_$stamp"
    }

    // ----------------------------------------------------------------------
    // Seleção do driver ativo
    // ----------------------------------------------------------------------

    /** Define o driver ativo (o nativo fará dlopen dele no próximo boot). */
    fun setActive(context: Context, id: String) {
        val driver = readDriverJson(File(driversDir(context), id))
            ?: throw DriverImportException("Driver não encontrado.")
        // [evidência] seleção SEM validação aceitava .so apagado/corrompido
        // (commit 3a8c08c) — confere existência + magic ELF antes de ativar.
        val so = File(driver.libPath)
        if (!so.isFile) {
            throw DriverImportException(
                "Arquivo do driver não existe (${so.name}) — importe-o novamente."
            )
        }
        so.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) < 4 ||
                !(magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte())
            ) {
                throw DriverImportException(
                    "Arquivo do driver não é um ELF válido — importe-o novamente."
                )
            }
        }
        activeFile(context).writeText("id=$id\nlib=${driver.libPath}\n")
    }

    /** Volta para o driver Vulkan do sistema (remove active.txt). */
    fun clearActive(context: Context) {
        activeFile(context).delete()
    }

    /** id do driver ativo, ou null se usando o driver do sistema. */
    fun activeId(context: Context): String? {
        val f = activeFile(context)
        if (!f.isFile) return null
        return f.useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.startsWith("id=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    /** Remove um driver importado (e desativa, se era o ativo). */
    fun remove(context: Context, id: String) {
        if (activeId(context) == id) clearActive(context)
        File(driversDir(context), id).deleteRecursively()
    }

    // ----------------------------------------------------------------------
    // Diagnóstico do último boot (escrito por vulkan_instance.cpp)
    // ----------------------------------------------------------------------

    /** Desfecho do carregamento do driver no último boot do jogo. */
    data class DriverBootOutcome(
        val status: String,   // custom_ok | custom_failed | system
        val driver: String,
        val error: String,
    )

    /**
     * Lê files/drivers/last_boot.txt (formato chave=valor). null se o jogo
     * ainda não bootou desde a instalação.
     */
    fun lastBootOutcome(context: Context): DriverBootOutcome? {
        val f = File(driversDir(context), "last_boot.txt")
        if (!f.isFile) return null
        return runCatching {
            val map = f.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
                }
                .toMap()
            DriverBootOutcome(
                status = map["status"] ?: "?",
                driver = map["driver"] ?: "-",
                error = map["error"] ?: "-",
            )
        }.getOrNull()
    }

    /**
     * Local do log da última sessão (escrito por GameActivity):
     * "publico:<caminho>" ou "privado".
     */
    fun lastLogLocation(context: Context): String? =
        File(context.filesDir, "last_log_location.txt").takeIf { it.isFile }?.readText()

    private const val DEFAULT_BUFFER = 1 shl 16 // 64 KiB
}
