// test_res_scale.c — regressão host do parser M4.39 (RESTUFF_RES_SCALE/ANISO).
//
// Roda no CI (build.yml) em segundos, ANTES do build nativo de ~45min.
// Cobre a classe de bug I1 (input manual via perf_env.txt caindo em 400%:
// "5"/"10"/"24" devem voltar a 100%, nunca a 400%).
#include <stdio.h>
#include <string.h>

#include "renderer/res_scale.h"

static int failures = 0;
static int checks = 0;

static void expect_pct(const char* in, uint32_t want) {
  ++checks;
  uint32_t got = ParseResScalePct(in);
  if (got != want) {
    ++failures;
    printf("FAIL res_scale(%s) = %u, esperado %u\n", in ? in : "<null>",
           got, want);
  }
}

static void expect_aniso(const char* in, float want) {
  ++checks;
  float got = ParseResAniso(in, 4.0f);
  if (got != want) {
    ++failures;
    printf("FAIL aniso(%s) = %.1f, esperado %.1f\n", in ? in : "<null>",
           got, want);
  }
}

// M4.40: helpers de checagem do DRS puro (DrsWindowDirection/DrsStepPct).
static void expect_dir(double busy_us, double draws, uint32_t cur, uint32_t target_us,
                       uint32_t min_pct, uint32_t max_pct, int want, const char* what) {
  ++checks;
  int got = DrsWindowDirection(busy_us, draws, cur, target_us, min_pct, max_pct);
  if (got != want) {
    ++failures;
    printf("FAIL drs_dir(%s) = %d, esperado %d\n", what, got, want);
  }
}

static void expect_step(int dir, uint32_t cur, uint32_t min_pct, uint32_t max_pct,
                        uint32_t want, const char* what) {
  ++checks;
  uint32_t got = DrsStepPct(dir, cur, min_pct, max_pct);
  if (got != want) {
    ++failures;
    printf("FAIL drs_step(%s) = %u, esperado %u\n", what, got, want);
  }
}

int main(void) {
  // Caminho feliz da UI (GameActivity envia estes literais).
  expect_pct("50", 50);
  expect_pct("60", 60);
  expect_pct("75", 75);
  expect_pct("100", 100);
  // Legado inteiro 1..4.
  expect_pct("1", 100);
  expect_pct("2", 200);
  expect_pct("4", 400);
  // Fator float com ponto.
  expect_pct("0.5", 50);
  expect_pct("0.75", 75);
  expect_pct("0.25", 25);
  expect_pct("1.5", 150);
  expect_pct("2.0", 200);
  // Sufixo %.
  expect_pct("50%", 50);
  expect_pct("100%", 100);
  // I1: inteiros fora das duas faixas voltam a 100 (NUNCA 400).
  expect_pct("5", 100);
  expect_pct("10", 100);
  expect_pct("24", 100);
  expect_pct("0", 100);
  expect_pct("401", 100);
  expect_pct("1000", 100);
  // Inválidos voltam a 100.
  expect_pct(0, 100);
  expect_pct("", 100);
  expect_pct("   ", 100);
  expect_pct("abc", 100);
  expect_pct("50x", 100);
  expect_pct("0x32", 100);
  expect_pct("1e12", 100);
  expect_pct("99999999999999999999", 100);
  expect_pct("1,5", 100);  // vírgula pt-BR não é aceita (documentado)
  // Fora da faixa float (0.25..4.0) volta a 100; piso via "25"/"0.25".
  expect_pct("0.2", 100);
  expect_pct("0.24", 100);
  expect_pct("4.5", 100);
  expect_pct("25", 25);
  expect_pct("400", 400);
  // Whitespace é tolerado.
  expect_pct(" 75 ", 75);
  expect_pct("0.5%", 100);  // % só com inteiro

  // Aniso (default 4x).
  expect_aniso(0, 4.0f);
  expect_aniso("", 4.0f);
  expect_aniso("0", 0.0f);
  expect_aniso("2", 2.0f);
  expect_aniso("4", 4.0f);
  expect_aniso("8", 8.0f);
  expect_aniso("16", 16.0f);
  expect_aniso("abc", 4.0f);  // M1: inválido cai no default, não OFF
  expect_aniso(" 4 ", 4.0f);
  // N1: trim simétrico + NaN/Inf rejeitados.
  expect_aniso(" 2 ", 2.0f);
  expect_aniso("2 ", 2.0f);
  expect_aniso("nan", 4.0f);
  expect_aniso("inf", 4.0f);
  expect_aniso("-inf", 4.0f);

  // ---- M4.40 (perf/sd695-40fps): decisão DRS ---------------------------
  // Alvo default 24ms; range default [40..100].
  // Gameplay pesado do log4: busy ~92-105ms, draws ~500 -> DESCE.
  expect_dir(100000.0, 500.0, 100, 24000, 40, 100, -1, "pesado@100");
  expect_dir(100000.0, 500.0, 40, 24000, 40, 100, 0, "pesado preso no piso");
  // Menu a 30fps: busy ~2ms (o pacer estaciona em wait) -> SOBE.
  expect_dir(2000.0, 400.0, 50, 24000, 40, 100, 1, "leve sobe do preset");
  // Zona morta (nem 1.30x nem 0.72x do alvo).
  expect_dir(28000.0, 300.0, 70, 24000, 40, 100, 0, "zona morta");
  expect_dir(20000.0, 300.0, 70, 24000, 40, 100, 0, "zona morta baixa");
  // Poucos draws (tela trivial/menu) -> nada mesmo com busy alto.
  expect_dir(100000.0, 10.0, 100, 24000, 40, 100, 0, "poucos draws");
  // Borda: exatamente 1.30x não desce (>)…
  expect_dir(31200.0, 300.0, 100, 24000, 40, 100, 0, "exatamente 1.30x");
  // …e logo acima desce.
  expect_dir(31201.0, 300.0, 100, 24000, 40, 100, -1, "acima de 1.30x");
  // Upscale preso no teto.
  expect_dir(2000.0, 400.0, 100, 24000, 40, 100, 0, "leve preso no teto");

  // Passos: desce 20 (100->80->60->40), sobe 10, clamp nos limites.
  expect_step(-1, 100, 40, 100, 80, "down 100->80");
  expect_step(-1, 80, 40, 100, 60, "down 80->60");
  expect_step(-1, 60, 40, 100, 40, "down 60->40");
  expect_step(-1, 50, 40, 100, 40, "down 50 clampa no piso");
  expect_step(-1, 40, 40, 100, 0, "down no piso = nada");
  expect_step(1, 40, 40, 100, 50, "up 40->50");
  expect_step(1, 90, 40, 100, 100, "up 90->100");
  expect_step(1, 100, 40, 100, 0, "up no teto = nada");
  expect_step(1, 95, 40, 100, 100, "up 95 clampa no teto");
  expect_step(0, 80, 40, 100, 0, "dir 0 = nada");
  // Teto personalizado (preset 200): o DRS pode subir além de 100.
  expect_step(1, 150, 40, 200, 160, "up 150->160 teto 200");
  expect_step(1, 195, 40, 200, 200, "up 195 clampa em 200");

  if (failures == 0) {
    printf("test_res_scale: OK (%d checagens)\n", checks);
    return 0;
  }
  printf("test_res_scale: %d FALHAS em %d checagens\n", failures, checks);
  return 1;
}
