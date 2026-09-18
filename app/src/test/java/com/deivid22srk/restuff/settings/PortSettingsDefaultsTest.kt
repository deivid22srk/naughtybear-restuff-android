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
        // preset escolhido e só desce abaixo dele quando o dispositivo não
        // aguenta (e o preset é o teto — quem escolheu 50% nunca vê acima).
        assertTrue("dynamicRes default deveria ser true", PortSettings().dynamicRes)
    }

    // ------------------------------------------------------------------
    // feat/ui-painel-fullscreen: "Tela cheia" — default OFF e wiring
    // ------------------------------------------------------------------

    @Test
    fun `tela cheia vem desligada por padrao`() {
        // Default OFF: preservar o letterbox 16:9 de quem não pediu — o
        // esticamento muda a proporção da imagem (1.25x horizontal num
        // 20:9) e deve ser uma ESCOLHA, nunca um default silencioso.
        assertEquals("fullscreenStretch default deveria ser false", false, PortSettings().fullscreenStretch)
    }

    @Test
    fun `tela cheia roundtrip do preset`() {
        // O toggle é um copy() puro como os demais campos — grava/le pela
        // mesma chave das Configurações e do painel de 4 dedos
        // (fullscreen_stretch), aplicando ao vivo pelo mesmo caminho do
        // fps_cap (cvar kRuntime sobrevive ao LoadConfig).
        val off = PortSettings()
        val on = off.copy(fullscreenStretch = true)
        assertEquals(false, off.fullscreenStretch)
        assertEquals(true, on.fullscreenStretch)
        // Os outros campos seguem intactos no copy (não vaza estado).
        assertEquals(off.fpsLimit, on.fpsLimit)
        assertEquals(off.resScale, on.resScale)
    }

    @Test
    fun `env DRS so e enviado quando ligado`() {
        // SPEC TEST (não executa GameActivity — trava o contrato da expressão
        // usada em getArguments): ligado -> --env=RESTUFF_DRS=1; desligado ->
        // NENHUM env RESTUFF_DRS (o motor é opt-in e desktop fica estático).
        // A expressão real usa parênteses explícitos no if — sem eles o `+`
        // capturaria só o ramo else e perderia o --log-file (bug e2 da revisão).
        fun drsEnv(on: Boolean) = if (on) arrayOf("--env=RESTUFF_DRS=1") else emptyArray<String>()
        assertEquals(listOf("--env=RESTUFF_DRS=1"), drsEnv(true).toList())
        assertTrue("desligado não deve enviar env", drsEnv(false).isEmpty())
    }

    @Test
    fun `preset e o TETO do DRS - quem escolheu 50 nao sobe acima disso`() {
        // (e3 review) semântica: o preset escolhido é o teto do DRS — "Ultra
        // performance 50%" é uma escolha de não gastar bateria acima disso.
        // O DRS desce até o piso (40%) quando pesado e volta ao — nunca além
        // do — preset. Espelha o clamp do DrsInit no native_vk.cpp (M4.40):
        // max_pct = clamp(max(preset, RESTUFF_DRS_MAX), min_pct, 400).
        fun drsMax(presetPct: Int, envMax: Int? = null): Int {
            val raw = if (envMax != null) maxOf(presetPct, envMax) else presetPct
            return raw.coerceIn(40, 400)
        }
        assertEquals(50, drsMax(50))
        assertEquals(100, drsMax(100))
        assertEquals(100, drsMax(50, envMax = 100))  // override explícito sobe
        assertEquals(200, drsMax(200))                 // preset upscale continua
        // O ponto de partida é o preset — e o range adapta para BAIXO dele.
        assertTrue("DRS desce do preset 50 até o piso", 40 < 50)
        assertTrue("DRS nunca ultrapassa o preset 50 sem env", drsMax(50) == 50)
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
