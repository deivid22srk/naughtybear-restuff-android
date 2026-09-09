package com.deivid22srk.restuff.data

import androidx.documentfile.provider.DocumentFile
import com.deivid22srk.restuff.config.PortBrandingConfig

/**
 * Busca, dentro da pasta escolhida via SAF, o arquivo de dados que o motor
 * do port espera encontrar.
 *
 * Estratégia em duas passadas (mesma da maioria dos launchers de ports):
 *  1. Nomes exatos — qualquer item de [PortBrandingConfig.expectedDataFiles]
 *     presente na raiz da pasta (comparação case-insensitive) valida a pasta.
 *  2. Extensões — se nenhum nome exato existir, o primeiro arquivo cujo nome
 *     termina com uma extensão de [PortBrandingConfig.acceptableExtensions]
 *     valida a pasta (útil para imagens de disco renomeadas).
 *
 * Somente a RAIZ da pasta é inspecionada (rápido e previsível); varredura
 * recursiva fica por conta do port, se necessário.
 */
object GameDataScanner {

    /**
     * @return o nome real do arquivo detectado (como aparece no disco),
     *         ou null quando a pasta não contém dados reconhecíveis.
     */
    fun findExpectedFile(folder: DocumentFile, config: PortBrandingConfig): String? {
        val children = folder.listFiles()

        // Passada 1: nomes exatos, na ordem declarada no config.
        for (expected in config.expectedDataFiles) {
            val hit = children.firstOrNull {
                it.isFile && it.name?.equals(expected, ignoreCase = true) == true
            }
            if (hit?.name != null) return hit.name
        }

        // Passada 2: extensões aceitáveis (se configuradas).
        if (config.acceptableExtensions.isNotEmpty()) {
            for (child in children) {
                val name = child.name ?: continue
                if (!child.isFile) continue
                val lower = name.lowercase()
                if (config.acceptableExtensions.any { lower.endsWith(it.lowercase()) }) {
                    return name
                }
            }
        }

        return null
    }
}
