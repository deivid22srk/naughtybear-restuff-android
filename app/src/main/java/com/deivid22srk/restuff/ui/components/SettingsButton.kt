package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding

/**
 * Botão de engrenagem (canto inferior direito) com rotação sutil acumulativa
 * a cada toque (+90 graus com spring bouncy) e brilho ao pressionar.
 * Alvo de toque: 48 dp.
 */
@Composable
fun SettingsButton(onClick: () -> Unit) {
    val config = PortBranding.config
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var rotations by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(
        targetValue = rotations * 90f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "gearRotation"
    )

    IconButton(
        onClick = {
            rotations++
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        interactionSource = interaction,
        modifier = Modifier.size(48.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = config.contentDescSettings,
            tint = Color.White.copy(alpha = if (pressed) 0.9f else 0.62f),
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}
