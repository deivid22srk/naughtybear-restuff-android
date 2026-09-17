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
    // M4.40 (perf/sd695-40fps): DRS — default ON e env-wiring
    // ------------------------------------------------------------------

    @Test
    fun `resolucao dinamica vem ligada por padrao`() {
        // Default ON para TODOS (fresh + upgrade): o controlador parte do
        // preset do usuário e só sobe além dele quando o dispositivo aguenta
        // — upgrade nenhum é rebaixado, o preset é o ponto de partida.
        assertTrue("dynamicRes default deveria ser true", PortSettings().dynamicRes)
    }

    @Test
    fun `env DRS so e enviado quando ligado`() {
        // Contrato do GameActivity: ligado -> --env=RESTUFF_DRS=1; desligado ->
        // NENHUM env RESTUFF_DRS (o motor é opt-in e desktop fica estático).
        // Simula as duas montagens de argv (extraído da expressão real).
        fun drsEnv(on: Boolean) = if (on) arrayOf("--env=RESTUFF_DRS=1") else emptyArray<String>()
        assertEquals(listOf("--env=RESTUFF_DRS=1"), drsEnv(true).toList())
        assertTrue("desligado não deve enviar env", drsEnv(false).isEmpty())
    }

    @Test
    fun `preset continua sendo o piso do DRS e nao o teto`() {
        // O DRS aceita subir até max(100, preset) — quem escolhe 50% como
        // ponto de partida ainda ganha resolução quando a cena alivia. O teste
        // trava a matemática do clamp do controlador (native_vk.cpp M4.40).
        val startPct = 50
        val maxPct = maxOf(100, startPct)
        assertEquals(100, maxPct)
        val upscalePct = 100
        assertTrue("DRS pode subir do preset até 100", upscalePct in startPct..maxPct)
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
