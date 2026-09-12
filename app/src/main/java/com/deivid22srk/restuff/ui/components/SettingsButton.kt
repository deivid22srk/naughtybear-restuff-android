package com.deivid22srk.restuff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette

/**
 * Engrenagem de acesso às Configurações — círculo fantasma de 48dp (alvo de
 * toque Android) com hairline quase invisível: presença constante sem
 * competir com o alvo primário da tela. Micro-escala no pressed.
 */
@Composable
fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val config = PortBranding.config

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.04f))
            .portClickable(haptic = true) { onClick() }
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = config.contentDescSettings,
            tint = PortPalette.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
