package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.viewmodel.DataPhase

/**
 * Botão primário com glassmorphism (4 camadas desenhadas: vidro + tint do
 * accent + borda luminosa + highlight superior), sombra colorida, ícone de
 * play em disco accent, haptics no toque, micro-escala ao pressionar e
 * estado loading (spinner) durante a validação da pasta.
 *
 * Dois rótulos vindos do config: "Selecionar Dados" (não encontrado) e
 * "Iniciar Jogo" (dados prontos).
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val entrance = rememberEntrance(if (compact) 640 else 720, reduceMotion)
    val shape = RoundedCornerShape(18.dp)
    val radius = 18.dp

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )

    val label = when {
        validating -> config.labelValidating
        phase is DataPhase.Found -> config.labelStartGame
        else -> config.labelSelectData
    }

    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = !validating,
        shape = shape,
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.65f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 54.dp else 62.dp)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 36f
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                elevation = if (pressed) 6.dp else 18.dp,
                shape = shape,
                ambientColor = config.accent,
                spotColor = config.accent
            )
            .drawBehind {
                val r = radius.toPx()
                val cr = CornerRadius(r, r)
                // 1) vidro: gradiente vertical branco translúcido
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (pressed) 0.22f else 0.16f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    cornerRadius = cr
                )
                // 2) tint de acento esquerda → direita
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            config.accent.copy(alpha = 0.32f),
                            config.accentDeep.copy(alpha = 0.10f)
                        )
                    ),
                    cornerRadius = cr
                )
                // 3) borda luminosa (acende ao pressionar)
                drawRoundRect(
                    color = Color.White.copy(alpha = if (pressed) 0.55f else 0.32f),
                    style = Stroke(width = 1.2.dp.toPx()),
                    cornerRadius = cr
                )
                // 4) highlight superior: linha de luz do vidro
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                        startY = 0f,
                        endY = r * 2f
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, r * 2f),
                    cornerRadius = CornerRadius(r, r)
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (validating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.2.dp,
                    color = Color.White
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(config.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = config.contentDescPlay,
                        tint = Color(0xFF0B0B12),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
        }
    }
}
