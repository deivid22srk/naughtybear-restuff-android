package com.deivid22srk.restuff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import com.deivid22srk.restuff.viewmodel.DataPhase

/**
 * AÇÃO PRIMÁRIA da tela inicial — o alvo único de peso do design
 * "Mel & Carvão":
 *
 *   - dados prontos → botão SÓLIDO no accent (texto carvão) com glifo de play;
 *   - sem dados     → botão FANTASMA EM DESTAQUE (borda 32%) convidando à
 *                     seleção — no empty state este é o único caminho, então
 *                     ele não pode ser o elemento mais fraco da tela;
 *   - validando     → fantasma translúcido (alpha 0.6) com spinner fino.
 *
 * Sem gradiente, sem glow: sólido quando pronto é o único momento de cor
 * cheia da tela — é isso que o torna o alvo. Feedback: micro-escala no
 * pressed via [portClickable] + haptic.
 */
@Composable
fun PrimarySelectButton(
    phase: DataPhase,
    validating: Boolean,
    compact: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = PortBranding.config
    val entrance = rememberEntrance(260, reduceMotion)

    val ready = phase is DataPhase.Found
    val enabled = !validating

    val label = when {
        validating -> config.labelValidating
        ready -> config.labelStartGame
        else -> config.labelSelectData
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 14f
            }
            .heightIn(min = if (compact) 52.dp else 56.dp)
            .clip(RoundedCornerShape(PortPalette.radiusMd))
            .background(if (ready) config.accent else Color.Transparent)
            .alpha(if (validating) 0.6f else 1f)
            .border(
                width = 1.dp,
                color = when {
                    ready -> Color.Transparent
                    validating -> PortPalette.ghostBorder
                    else -> PortPalette.ghostBorderStrong
                },
                shape = RoundedCornerShape(PortPalette.radiusMd)
            )
            .portClickable(
                enabled = enabled,
                haptic = true
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (validating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = config.accent
                )
                Spacer(Modifier.width(10.dp))
            } else if (ready) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = config.contentDescPlay,
                    tint = PortPalette.onAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                color = if (ready) PortPalette.onAccent else PortPalette.textPrimary,
                style = PortType.action
            )
        }
    }
}
