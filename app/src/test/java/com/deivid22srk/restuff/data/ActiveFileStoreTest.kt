package com.deivid22srk.restuff.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regressão do bug reportado pelo usuário (Task 17): selecionar
 * "Vortek (experimental)" nas Configurações falhava com
 * FileNotFoundException: <files>/drivers/active.txt: open failed: ENOENT
 * porque o fluxo Vortek (embutido no APK, sem import) nunca criava o
 * diretório files/drivers/.
 *
 * JVM puro (sem Robolectric): ActiveFileStore trabalha só com java.io.File.
 */
class ActiveFileStoreTest {

    private lateinit var root: File
    private lateinit var driversDir: File
    private lateinit var vortekClient: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("restuff-drivers").toFile()
        driversDir = File(root, "files/drivers")
        vortekClient = File(root, "nativeDir/libvulkan_vortek.so")
        vortekClient.parentFile!!.mkdirs()
        vortekClient.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Regressão do ENOENT (o bug original)
    // ------------------------------------------------------------------

    @Test
    fun `write em diretorio inexistente cria e persiste sem ENOENT`() {
        // Instalação limpa: files/drivers NÃO existe (cenário do reporte).
        assertFalse(driversDir.isDirectory)

        ActiveFileStore.write(
            driversDir,
            "id=vortek\nlib=${vortekClient.absolutePath}\n"
        )

        val active = ActiveFileStore.activeFile(driversDir)
        assertTrue("active.txt deveria existir", active.isFile)
        assertEquals("vortek", ActiveFileStore.readId(driversDir))
        assertEquals(vortekClient.absolutePath, ActiveFileStore.readLib(driversDir))
    }

    @Test
    fun `write nao deixa arquivo temporario para tras`() {
        ActiveFileStore.write(driversDir, "id=vortek\nlib=x\n")
        val leftovers = driversDir.listFiles { f -> f.name != "active.txt" }
        assertTrue("tmp deveria ser consumido pelo rename", leftovers!!.isEmpty())
    }

    @Test
    fun `write sobrescreve selecao anterior`() {
        ActiveFileStore.write(driversDir, "id=turnip_1\nlib=/old/driver.so\n")
        ActiveFileStore.write(driversDir, "id=vortek\nlib=${vortekClient.absolutePath}\n")
        assertEquals("vortek", ActiveFileStore.readId(driversDir))
        assertEquals(vortekClient.absolutePath, ActiveFileStore.readLib(driversDir))
    }

    // ------------------------------------------------------------------
    // Leitura tolerante
    // ------------------------------------------------------------------

    @Test
    fun `leitura tolera CRLF espacos e ordem invertida das linhas`() {
        driversDir.mkdirs()
        ActiveFileStore.activeFile(driversDir).writeText(
            "  lib=${vortekClient.absolutePath}\r\n  id=vortek \r\n"
        )
        assertEquals("vortek", ActiveFileStore.readId(driversDir))
        assertEquals(vortekClient.absolutePath, ActiveFileStore.readLib(driversDir))
    }

    @Test
    fun `leitura de arquivo ausente devolve null sem lancar`() {
        assertNull(ActiveFileStore.readId(driversDir))
        assertNull(ActiveFileStore.readLib(driversDir))
    }

    // ------------------------------------------------------------------
    // clear
    // ------------------------------------------------------------------

    @Test
    fun `clear remove o arquivo e e no-op quando ausente`() {
        ActiveFileStore.write(driversDir, "id=vortek\nlib=x\n")
        ActiveFileStore.clear(driversDir)
        assertFalse(ActiveFileStore.activeFile(driversDir).isFile)
        // Segundo clear (arquivo já removido) não pode lançar.
        ActiveFileStore.clear(driversDir)
    }

    // ------------------------------------------------------------------
    // reconcile — self-heal do caminho defasado pós-atualização
    // ------------------------------------------------------------------

    @Test
    fun `reconcile regrava quando o caminho do cliente defasou apos atualizar o app`() {
        ActiveFileStore.write(
            driversDir,
            "id=vortek\nlib=/data/app/~~ANTIGO/libvulkan_vortek.so\n"
        )
        val healed = ActiveFileStore.reconcile(driversDir, vortekClient, "vortek")
        assertTrue(healed)
        assertEquals(vortekClient.absolutePath, ActiveFileStore.readLib(driversDir))
        assertEquals("vortek", ActiveFileStore.readId(driversDir))
    }

    @Test
    fun `reconcile e idempotente quando o caminho esta em dia`() {
        ActiveFileStore.write(
            driversDir,
            "id=vortek\nlib=${vortekClient.absolutePath}\n"
        )
        val before = ActiveFileStore.activeFile(driversDir).readText()
        val healed = ActiveFileStore.reconcile(driversDir, vortekClient, "vortek")
        assertFalse(healed)
        assertEquals(before, ActiveFileStore.activeFile(driversDir).readText())
    }

    @Test
    fun `reconcile cura active_txt com id vortek e linha lib ausente`() {
        // Resíduo de escrita truncada pré-fix ou edição manual (review 17-e2).
        driversDir.mkdirs()
        ActiveFileStore.activeFile(driversDir).writeText("id=vortek\n")
        val healed = ActiveFileStore.reconcile(driversDir, vortekClient, "vortek")
        assertTrue(healed)
        assertEquals(vortekClient.absolutePath, ActiveFileStore.readLib(driversDir))
    }

    @Test
    fun `reconcile nao mexe em driver Turnip ativo`() {
        ActiveFileStore.write(driversDir, "id=turnip_849\nlib=/files/drivers/t849/driver.so\n")
        val before = ActiveFileStore.activeFile(driversDir).readText()
        val healed = ActiveFileStore.reconcile(driversDir, vortekClient, "vortek")
        assertFalse(healed)
        assertEquals(before, ActiveFileStore.activeFile(driversDir).readText())
    }

    @Test
    fun `reconcile nao mexe sem active_txt`() {
        val healed = ActiveFileStore.reconcile(driversDir, vortekClient, "vortek")
        assertFalse(healed)
        assertFalse(ActiveFileStore.activeFile(driversDir).isFile)
    }

    @Test
    fun `reconcile nao mexe quando o build nao embute o cliente Vortek`() {
        ActiveFileStore.write(
            driversDir,
            "id=vortek\nlib=/data/app/~~ANTIGO/libvulkan_vortek.so\n"
        )
        val before = ActiveFileStore.activeFile(driversDir).readText()
        val absentClient = File(root, "semVortek/libvulkan_vortek.so") // não existe
        val healed = ActiveFileStore.reconcile(driversDir, absentClient, "vortek")
        assertFalse(healed)
        assertEquals(before, ActiveFileStore.activeFile(driversDir).readText())
    }
}
