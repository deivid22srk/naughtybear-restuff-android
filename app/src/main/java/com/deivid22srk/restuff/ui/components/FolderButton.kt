package com.deivid22srk.restuff.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.deivid22srk.restuff.config.PortBranding

/**
 * Botão secundário "Selecionar Pasta" (ghost). Quando os dados já estão
 * prontos, entra no estado desabilitado elegante: texto esmaecido, sem
 * ripple, mantendo o alvo de 48 dp para leitores de tela.
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
    val entrance = rememberEntrance(if (compact) 760 else 850, reduceMotion)

    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White.copy(alpha = if (enabled) 0.78f else 0.34f),
            disabledContentColor = Color.White.copy(alpha = 0.30f)
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 28f
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = config.contentDescFolder,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = config.labelSelectFolder,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.1.sp
        )
    }
}
