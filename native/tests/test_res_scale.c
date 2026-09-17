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

  if (failures == 0) {
    printf("test_res_scale: OK (%d checagens)\n", checks);
    return 0;
  }
  printf("test_res_scale: %d FALHAS em %d checagens\n", failures, checks);
  return 1;
}
