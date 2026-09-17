// res_scale.h — parsing testável de RESTUFF_RES_SCALE / RESTUFF_ANISO.
//
// M4.39 (perf/sd695-ultra): a escala interna do renderer aceita downscale
// fracionário (25-400%). O parsing mora aqui — header self-contained, sem
// dependências do motor — para que o CI valide a tabela de casos em host
// (native/tests/test_res_scale.c) sem compilar o TU de 11k linhas do
// native_vk.cpp. O .cpp inclui este header e resolve uma vez via slot
// atômico (ResScalePct).
//
// Formatos aceitos (RESTUFF_RES_SCALE → porcento 25..400, default 100):
//   "50".."400"  → porcento direto ("50" = 0.5x = 640x360)
//   "1".."4"     → fator legado (byte-identical ao pré-M4.39: 1 = 100%)
//   "0.25".."4.0" (ponto decimal, pode ter 'e') → fator ×100 ("0.5" = 50%)
//   "50%"        → sufixo % explícito
//   resto (vazio, "abc", "5", "10", "24", "1e12", "0x32") → 100
// NOTA: "5"/"10"/"24" são INTENCIONALMENTE inválidos (nem fator 1..4, nem
// porcento 25..400) — cair em 400% aqui já causou tela preta via OOM de
// attachments; inválido sempre volta a 100% (comportamento antigo).
// Decimal usa PONTO ("1,5" pt-BR não é aceito → 100).
#ifndef RESTUFF_RES_SCALE_H
#define RESTUFF_RES_SCALE_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
inline
#else
static inline
#endif
uint32_t
ParseResScalePct(const char* e) {
  if (e == 0 || *e == '\0') return 100u;
  while (*e == ' ' || *e == '\t') ++e;
  size_t n = strlen(e);
  while (n > 0 && (e[n - 1] == ' ' || e[n - 1] == '\t' || e[n - 1] == '\n' ||
                   e[n - 1] == '\r'))
    --n;
  if (n == 0) return 100u;

  // Sufixo '%' explícito: só inteiro 25..400.
  if (e[n - 1] == '%') {
    char buf[16];
    if (n - 1 >= sizeof(buf)) return 100u;
    memcpy(buf, e, n - 1);
    buf[n - 1] = '\0';
    char* end = 0;
    long v = strtol(buf, &end, 10);
    if (end == buf || *end != '\0') return 100u;
    if (v < 25L || v > 400L) return 100u;
    return (uint32_t)v;
  }

  // Float quando há ponto ou expoente ("0.5", "1.5", "2e0").
  int is_float = 0;
  for (size_t i = 0; i < n; ++i) {
    if (e[i] == '.' || e[i] == 'e' || e[i] == 'E') {
      is_float = 1;
      break;
    }
  }
  if (is_float) {
    char buf[32];
    if (n >= sizeof(buf)) return 100u;
    memcpy(buf, e, n);
    buf[n] = '\0';
    char* end = 0;
    double d = strtod(buf, &end);
    if (end == buf || *end != '\0') return 100u;
    if (!(d >= 0.25) || !(d <= 4.0)) return 100u;  // fora de faixa (incl. NaN)
    uint32_t pct = (uint32_t)(d * 100.0 + 0.5);
    if (pct < 25u) pct = 25u;
    if (pct > 400u) pct = 400u;
    return pct;
  }

  // Inteiro estrito em base 10 (sem 0x, sem resto).
  {
    char buf[32];
    if (n >= sizeof(buf)) return 100u;
    memcpy(buf, e, n);
    buf[n] = '\0';
    char* end = 0;
    long v = strtol(buf, &end, 10);
    if (end == buf || *end != '\0') return 100u;
    if (v >= 1L && v <= 4L) return (uint32_t)v * 100u;  // fator legado
    if (v >= 25L && v <= 400L) return (uint32_t)v;      // porcento direto
    return 100u;
  }
}

// RESTUFF_ANISO → teto de anisotropia (0 = desligada). Inválido/ausente cai
// no default (4x no motor) em vez de desligar (regressão M1: "abc"→OFF).
// Trim simétrico ao irmão + rejeição de NaN/Inf (N1: "nan"/"inf"→16.0,
// " 2 "→default).
#ifdef __cplusplus
inline
#else
static inline
#endif
float
ParseResAniso(const char* e, float dflt) {
  if (e == 0) return dflt;
  while (*e == ' ' || *e == '\t') ++e;
  size_t n = strlen(e);
  while (n > 0 && (e[n - 1] == ' ' || e[n - 1] == '\t' || e[n - 1] == '\n' ||
                   e[n - 1] == '\r'))
    --n;
  if (n == 0) return dflt;
  char buf[32];
  if (n >= sizeof(buf)) return dflt;
  memcpy(buf, e, n);
  buf[n] = '\0';
  char* end = 0;
  double ad = strtod(buf, &end);
  if (end == buf || *end != '\0') return dflt;
  if (!(ad >= 0.0)) return dflt;  // NaN
  if (ad > 1e6) return dflt;      // Inf / absurdo
  if (ad <= 0.5) return 0.0f;
  if (ad <= 2.5) return 2.0f;
  if (ad <= 4.5) return 4.0f;
  if (ad <= 8.5) return 8.0f;
  return 16.0f;
}

#endif  // RESTUFF_RES_SCALE_H
