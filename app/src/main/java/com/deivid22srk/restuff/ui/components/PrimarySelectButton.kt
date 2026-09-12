package com.deivid22srk.restuff.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.viewmodel.DataPhase

/**
 * AÇÃO PRIMÁRIA da tela inicial — o alvo único de peso do design
 * "Mel & Carvão":
 *
 *   - dados prontos → botão SÓLIDO no accent (texto carvão) com glifo de play;
 *   - sem dados     → botão FANTASMA (hairline) convidando à seleção;
 *   - validando     → fantasma desabilitado com spinner fino.
 *
 * Sem gradiente, sem glow: sólido quando pronto é o único momento de cor
 * cheia da tela — é isso que o torna o alvo.
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
    val haptics = LocalHapticFeedback.current
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
            .clip(RoundedCornerShape(12.dp))
            .background(if (ready) config.accent else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (ready) Color.Transparent else PortPalette.ghostBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
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
                    tint = Color(0xFF141008),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                color = if (ready) Color(0xFF141008) else PortPalette.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp
            )
        }
    }
}
