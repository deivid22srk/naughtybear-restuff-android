/*
 * Teste unitário de HOST (CI) do mapeamento de formatos vertex SCALED do
 * Vortek shader_inspector — regressão do bug log4 (renderização):
 *
 *   O emulador (Xenos→Vulkan, native_vk.cpp:XenosVtxVkFormat) mapeia
 *   k_2_10_10_10 não-normalizado para VK_FORMAT_A2B10G10R10_{U,S}SCALED_
 *   PACK32. O upstream do Vortek só convertia os formatos SCALED "planos"
 *   (R8/R16/R8G8/R16G16/R8G8B8A8/R16G16B16A16) para UINT/SINT + rewrite
 *   SPIR-V (ConvertUToF/ConvertSToF) — a família PACK32 10-10-10-2 caía no
 *   driver Adreno SEM suporte → rasterização indefinida → triângulos
 *   gigantes de cor sólida + malhas 3D ausentes.
 *
 * Este TU compila o shader_inspector.c REAL (árvore vortekrenderer) no host
 * com os stubs Android de tests/hoststub e linka com um vulkanWrapper dummy —
 * só as funções puras são exercitadas.
 */

#include "shader_inspector.h"
#include "vulkan_wrapper.h"

#include <stdio.h>

/* Símbolo que o shader_inspector.c referencia (definido em main.c no build
 * real). Zero é suficiente: nenhuma função pura o toca. */
VulkanWrapper vulkanWrapper = {0};

static int g_failures = 0;

#define CHECK(cond, name, fmt, actual)                                        \
    do {                                                                      \
        if (!(cond)) {                                                        \
            printf("FAIL %-46s " fmt "\n", name, actual);                     \
            g_failures++;                                                     \
        }                                                                     \
        else {                                                                \
            printf("ok   %s\n", name);                                        \
        }                                                                     \
    } while (0)

int main(void) {
    /* G1 — família PACK32 10-10-10-2 agora é reconhecida como SCALED
     * (o gate que faltava; k_2_10_10_10 do Xenos). */
    CHECK(isFormatScaled(VK_FORMAT_A2B10G10R10_USCALED_PACK32),
          "G1 A2B10G10R10_USCALED_PACK32 e' SCALED", "%d", 0);
    CHECK(isFormatScaled(VK_FORMAT_A2B10G10R10_SSCALED_PACK32),
          "G1 A2B10G10R10_SSCALED_PACK32 e' SCALED", "%d", 0);
    CHECK(isFormatScaled(VK_FORMAT_A2R10G10B10_USCALED_PACK32),
          "G1 A2R10G10B10_USCALED_PACK32 e' SCALED", "%d", 0);
    CHECK(isFormatScaled(VK_FORMAT_A2R10G10B10_SSCALED_PACK32),
          "G1 A2R10G10B10_SSCALED_PACK32 e' SCALED", "%d", 0);

    /* G2 — legacy (presente no upstream) continua reconhecido. */
    CHECK(isFormatScaled(VK_FORMAT_R8G8B8A8_USCALED),
          "G2 R8G8B8A8_USCALED (upstream) intacto", "%d", 0);
    CHECK(isFormatScaled(VK_FORMAT_R16G16_SSCALED),
          "G2 R16G16_SSCALED (upstream) intacto", "%d", 0);
    CHECK(isFormatScaled(VK_FORMAT_R16G16B16A16_USCALED),
          "G2 R16G16B16A16_USCALED (upstream) intacto", "%d", 0);

    /* G3 — formatos NÃO-SCALED não viram SCALED (sem over-conversion). */
    CHECK(!isFormatScaled(VK_FORMAT_R8G8B8A8_UNORM),
          "G3 R8G8B8A8_UNORM nao e' SCALED", "%d", 0);
    CHECK(!isFormatScaled(VK_FORMAT_A2B10G10R10_UNORM_PACK32),
          "G3 A2B10G10R10_UNORM_PACK32 nao e' SCALED", "%d", 0);
    CHECK(!isFormatScaled(VK_FORMAT_R32G32_SFLOAT),
          "G3 R32G32_SFLOAT nao e' SCALED", "%d", 0);
    CHECK(!isFormatScaled(VK_FORMAT_R8G8B8A8_UINT),
          "G3 R8G8B8A8_UINT nao e' SCALED (ja' fallback)", "%d", 0);

    /* F1 — fallback PACK32: USCALED→UINT, SSCALED→SINT. */
    CHECK(getFallbackFormat(VK_FORMAT_A2B10G10R10_USCALED_PACK32) ==
              VK_FORMAT_A2B10G10R10_UINT_PACK32,
          "F1 A2B10G10R10 USCALED→UINT", "%d",
          (int)getFallbackFormat(VK_FORMAT_A2B10G10R10_USCALED_PACK32));
    CHECK(getFallbackFormat(VK_FORMAT_A2B10G10R10_SSCALED_PACK32) ==
              VK_FORMAT_A2B10G10R10_SINT_PACK32,
          "F1 A2B10G10R10 SSCALED→SINT", "%d",
          (int)getFallbackFormat(VK_FORMAT_A2B10G10R10_SSCALED_PACK32));
    CHECK(getFallbackFormat(VK_FORMAT_A2R10G10B10_USCALED_PACK32) ==
              VK_FORMAT_A2R10G10B10_UINT_PACK32,
          "F1 A2R10G10B10 USCALED→UINT", "%d",
          (int)getFallbackFormat(VK_FORMAT_A2R10G10B10_USCALED_PACK32));
    CHECK(getFallbackFormat(VK_FORMAT_A2R10G10B10_SSCALED_PACK32) ==
              VK_FORMAT_A2R10G10B10_SINT_PACK32,
          "F1 A2R10G10B10 SSCALED→SINT", "%d",
          (int)getFallbackFormat(VK_FORMAT_A2R10G10B10_SSCALED_PACK32));

    /* F2 — fallback legacy intacto. */
    CHECK(getFallbackFormat(VK_FORMAT_R8G8B8A8_USCALED) == VK_FORMAT_R8G8B8A8_UINT,
          "F2 R8G8B8A8 USCALED→UINT (upstream)", "%d",
          (int)getFallbackFormat(VK_FORMAT_R8G8B8A8_USCALED));
    CHECK(getFallbackFormat(VK_FORMAT_R8G8B8A8_SSCALED) == VK_FORMAT_R8G8B8A8_SINT,
          "F2 R8G8B8A8 SSCALED→SINT (upstream)", "%d",
          (int)getFallbackFormat(VK_FORMAT_R8G8B8A8_SSCALED));

    /* F3 — formatos não-SCALED inalterados (identidade). */
    CHECK(getFallbackFormat(VK_FORMAT_R32G32B32A32_SFLOAT) ==
              VK_FORMAT_R32G32B32A32_SFLOAT,
          "F3 identidade p/ nao-SCALED", "%d",
          (int)getFallbackFormat(VK_FORMAT_R32G32B32A32_SFLOAT));

    /* S1 — signedness do rewrite SPIR-V: UINT (ConvertUToF) = 0,
     * SINT (ConvertSToF) = 1. O default do upstream era 1 — o UINT PACK32
     * PRECISA de 0 ou o ConvertSToF negativiza componentes 512-1023
     * (geometria espelhada). */
    CHECK(formatIntSignedness(VK_FORMAT_A2B10G10R10_UINT_PACK32) == 0,
          "S1 A2B10G10R10_UINT_PACK32 signedness=0 (UToF)", "%d",
          formatIntSignedness(VK_FORMAT_A2B10G10R10_UINT_PACK32));
    CHECK(formatIntSignedness(VK_FORMAT_A2R10G10B10_UINT_PACK32) == 0,
          "S1 A2R10G10B10_UINT_PACK32 signedness=0 (UToF)", "%d",
          formatIntSignedness(VK_FORMAT_A2R10G10B10_UINT_PACK32));
    CHECK(formatIntSignedness(VK_FORMAT_A2B10G10R10_SINT_PACK32) == 1,
          "S1 A2B10G10R10_SINT_PACK32 signedness=1 (SToF)", "%d",
          formatIntSignedness(VK_FORMAT_A2B10G10R10_SINT_PACK32));
    CHECK(formatIntSignedness(VK_FORMAT_R8G8B8A8_UINT) == 0,
          "S1 R8G8B8A8_UINT signedness=0 (upstream)", "%d",
          formatIntSignedness(VK_FORMAT_R8G8B8A8_UINT));
    CHECK(formatIntSignedness(VK_FORMAT_R8G8B8A8_SINT) == 1,
          "S1 R8G8B8A8_SINT signedness=1 (upstream)", "%d",
          formatIntSignedness(VK_FORMAT_R8G8B8A8_SINT));

    if (g_failures > 0) {
        printf("\nFORMAT-CONV: %d FALHA(S)\n", g_failures);
        return 1;
    }
    printf("\nFORMAT-CONV: 21/21 checagens OK — PACK32 10-10-10-2 coberto\n");
    return 0;
}
