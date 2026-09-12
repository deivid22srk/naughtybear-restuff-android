package com.deivid22srk.restuff.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType

/**
 * AÇÃO SECUNDÁRIA da tela inicial no design "Mel & Carvão": um convite
 * quieto — texto discreto com glifo pequeno, sem caixa, sem borda. A
 * hierarquia fica clara por contraste tipográfico (o primário é sólido).
 * Micro-escala no pressed via [portClickable].
 */
@Composable
fun FolderButton(
    icon: ImageVector,
    enabled: Boolean,
    compact: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = PortBranding.config
    val entrance = rememberEntrance(360, reduceMotion)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { alpha = entrance.value }
            .heightIn(min = 48.dp)
            .portClickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = config.contentDescFolder,
                tint = if (enabled) PortPalette.textSecondary else PortPalette.textTertiary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = config.labelSelectFolder,
                color = if (enabled) PortPalette.textSecondary else PortPalette.textTertiary,
                style = PortType.chip
            )
        }
    }
}
