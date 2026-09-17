package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Feedback de toque unificado do design "Mel & Carvão".
 *
 * Todo clicável do port usa esta extensão: sem ripple (o carvão não ondula),
 * com micro-escala 0.97 no pressed (resposta física imediata que diz "isto
 * é um alvo vivo") e haptic opcional. Um único gesto de interação repetido
 * em TODA a UI — a mesma disciplina da LINHA DO MEL, agora no toque.
 */
fun Modifier.portClickable(
    enabled: Boolean = true,
    haptic: Boolean = false,
    minTouchTarget: Boolean = false,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "portPressedScale"
    )
    val haptics = LocalHapticFeedback.current

    this
        .then(
            if (minTouchTarget) Modifier.heightIn(min = 48.dp) else Modifier
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role
        ) {
            if (haptic) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            onClick()
        }
}
