package com.deivid22srk.restuff.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.deivid22srk.restuff.config.PortBranding

/**
 * Tema derivado 100% do [PortBranding.config]: nenhum valor visual é fixado
 * aqui. Qualquer port troca a identidade do app automaticamente ao editar a
 * config — o colorScheme escuro é regenerado a partir do accent do port.
 */
@Composable
fun PortScreenTheme(content: @Composable () -> Unit) {
    val config = PortBranding.config
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = config.accent,
            onPrimary = Color(0xFF0B0B12),
            secondary = config.accentDeep,
            background = Color(0xFF07070C),
            onBackground = Color(0xFFF4F4F8),
            surface = Color(0xFF101018),
            onSurface = Color(0xFFF4F4F8)
        ),
        content = content
    )
}
