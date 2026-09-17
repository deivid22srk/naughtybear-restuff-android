package com.deivid22srk.restuff.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão M4.39 (perf/sd695-ultra): defaults de performance com migração.
 *
 * JVM puro (sem Robolectric, sem Context): cobre as funções puras de default
 * e o mapeamento enum→motor. A lógica que exige SharedPreferences
 * (PortSettingsRepository.load) delega a [resScaleDefault]/[anisoDefault];
 * estes testes travam o contrato para que um refactor não reintroduza:
 *  - downgrade silencioso em upgrade (I2: usuário antigo 100% → 50%), nem
 *  - renomeação de enum (os names são persistidos como String nas prefs).
 */
class PortSettingsDefaultsTest {

    // ------------------------------------------------------------------
    // Migração fresh-install vs upgrade (I2)
    // ------------------------------------------------------------------

    @Test
    fun `fresh install abre no preset SD695 Ultra`() {
        assertEquals(ResolutionScaleOption.RES_50, resScaleDefault(hasPriorPrefs = false))
        assertEquals(AnisoOption.X2, anisoDefault(hasPriorPrefs = false))
    }

    @Test
    fun `upgrade preserva comportamento antigo 100 por cento + aniso 8x`() {
        assertEquals(ResolutionScaleOption.RES_100, resScaleDefault(hasPriorPrefs = true))
        assertEquals(AnisoOption.X8, anisoDefault(hasPriorPrefs = true))
    }

    // ------------------------------------------------------------------
    // N3: detecção de upgrade via tempos do pacote (upgrade never-saved)
    // ------------------------------------------------------------------

    @Test
    fun `instalacao nova tem tempos iguais`() {
        assertEquals(false, isUpgradeInstallTimes(1_700_000_000_000L, 1_700_000_000_000L))
        assertEquals(false, isUpgradeInstallTimes(1_000L, 2_000L))
    }

    @Test
    fun `atualizacao por cima tem lastUpdate bem depois`() {
        assertEquals(true, isUpgradeInstallTimes(1_000L, 100_000L))
        assertEquals(true, isUpgradeInstallTimes(0L, 60_001L))
    }

    @Test
    fun `margem de 60s absorve skew`() {
        assertEquals(false, isUpgradeInstallTimes(0L, 60_000L))
    }

    // ------------------------------------------------------------------
    // Mapeamento enum → motor (o que GameActivity envia via --env=)
    // ------------------------------------------------------------------

    @Test
    fun `escalas resolvem geometria exata sem resto`() {
        // (percent → largura/altura esperadas em 1280x720)
        val expected = mapOf(
            ResolutionScaleOption.RES_50 to (640 to 360),
            ResolutionScaleOption.RES_60 to (768 to 432),
            ResolutionScaleOption.RES_75 to (960 to 540),
            ResolutionScaleOption.RES_100 to (1280 to 720),
        )
        for ((opt, wh) in expected) {
            val w = (1280 * opt.percent) / 100
            val h = (720 * opt.percent) / 100
            assertEquals("largura ${opt.name}", wh.first, w)
            assertEquals("altura ${opt.name}", wh.second, h)
        }
    }

    @Test
    fun `aniso cobre off 2x 4x 8x na ordem`() {
        assertEquals(
            listOf(0, 2, 4, 8),
            AnisoOption.entries.sortedBy { it.value }.map { it.value }
        )
    }

    @Test
    fun `fps 40 existe para a meta SD695`() {
        val opt = FpsLimitOption.entries.firstOrNull { it.fps == 40 }
        assertTrue("chip de 40fps deveria existir", opt != null)
        assertEquals("40", opt!!.label)
    }

    // ------------------------------------------------------------------
    // Estabilidade dos names persistidos (renomear quebra prefs salvas)
    // ------------------------------------------------------------------

    @Test
    fun `names dos enums sao estaveis`() {
        assertEquals(
            listOf("RES_50", "RES_60", "RES_75", "RES_100"),
            ResolutionScaleOption.entries.map { it.name }
        )
        assertEquals(
            listOf("OFF", "X2", "X4", "X8"),
            AnisoOption.entries.map { it.name }
        )
        assertTrue(FpsLimitOption.entries.map { it.name }.contains("FPS_40"))
    }
}
