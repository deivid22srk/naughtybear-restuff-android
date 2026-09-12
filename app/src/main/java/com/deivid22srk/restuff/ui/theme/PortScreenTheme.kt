package com.deivid22srk.restuff.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.config.PortBranding

/**
 * ============================================================================
 *  DESIGN SYSTEM "MEL & CARVÃO" — minimalismo editorial do port
 * ============================================================================
 *
 * Um único acento (o âmbar da pelúcia do urso, vindo do [PortBranding.config])
 * sobre carvão quase-preto. A identidade nasce de TRÊS gestos, repetidos com
 * disciplina em todas as telas:
 *
 *   1. LINHA DO MEL — filete horizontal de 2 dp no accent, com no máximo
 *      ~28 dp de largura, separando identidade de conteúdo (nunca um traço
 *      completo: o acento é uma pincelada, não uma moldura).
 *
 *   2. RÓTULO EM VERSALETE — textos de contexto (eyebrows, títulos de seção)
 *      em 10–11 sp, SemiBold, letter-spacing 2–3 sp, cor terciária. Nada de
 *      caixas/chips para informação secundária.
 *
 *   3. ALVO ÚNICO POR TELA — um só elemento de peso (botão sólido âmbar ou
 *      título display); todo o resto é tipografia quieta sobre carvão.
 *
 * Fundo: a arte cinematográfica do port (bg_cinematic.jpg) permanece como
 * identidade, porém FORTEMENTE esmaecida (alpha ~0.18) sob um véu carvão —
 * presença sem ruído. Sem partículas, sem parallax, sem grain: o minimalismo
 * aqui é also uma decisão de bateria/clone de UI de emulador.
 *
 * Nenhum valor visual é fixado fora daqui — as telas consomem [PortPalette].
 */
object PortPalette {
    /** Carvão de fundo (azulado, quase preto). */
    val background = Color(0xFF0A0A0E)

    /** Carvão de superfície (painéis raros: diálogo, banner). */
    val surface = Color(0xFF14141A)

    /** Hairline entre linhas/seções (branco a 8%). */
    val hairline = Color(0x14FFFFFF)

    /** Texto primário (off-white quente). */
    val textPrimary = Color(0xFFF2F2F5)

    /** Texto secundário (subs, hints). */
    val textSecondary = Color(0xFF8E8E99)

    /** Texto terciário (rótulos, metadados mono). */
    val textTertiary = Color(0xFF5C5C66)

    /** Sucesso (dados prontos). */
    val success = Color(0xFF4ADE80)

    /** Erro (dados ausentes, permissão revogada). */
    val error = Color(0xFFFF6B6B)

    /** Contorno de botão fantasma (branco a 14%). */
    val ghostBorder = Color(0x24FFFFFF)
}

/** Tipografia editorial do port — escala com contraste agressivo. */
object PortType {
    val display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 46.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp
    )

    val displayStrong = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 46.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.5).sp
    )

    val titleScreen = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp
    )

    val body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )

    val rowLabel = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    )

    val rowSub = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )

    val label = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 2.5.sp
    )

    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.8.sp
    )
}

/**
 * Tema derivado do [PortBranding.config]: o colorScheme escuro é regenerado
 * a partir do accent do port (a arquitetura paramétrica de troca de identidade
 * por config permanece intacta).
 */
@Composable
fun PortScreenTheme(content: @Composable () -> Unit) {
    val config = PortBranding.config
    isSystemInDarkTheme() // sempre dark; chamada mantida por clareza de intenção

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = config.accent,
            onPrimary = Color(0xFF141008),
            secondary = config.accentDeep,
            background = PortPalette.background,
            onBackground = PortPalette.textPrimary,
            surface = PortPalette.surface,
            onSurface = PortPalette.textPrimary,
            outline = PortPalette.ghostBorder
        ),
        typography = Typography(
            displayLarge = PortType.display,
            displayMedium = PortType.displayStrong,
            headlineMedium = PortType.titleScreen,
            bodyLarge = PortType.body,
            bodyMedium = PortType.rowSub,
            titleMedium = PortType.rowLabel,
            labelMedium = PortType.label
        ),
        content = content
    )
}
