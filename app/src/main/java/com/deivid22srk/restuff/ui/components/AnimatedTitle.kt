package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.R
import com.deivid22srk.restuff.config.PortBranding

/**
 * Bloco de hero: eyebrow espaçado + marca vetorial com glow pulsante +
 * título com halo multi-camada (fake bloom barato, sem Modifier.blur —
 * funciona igual em API 26 e 36).
 */
@Composable
fun AnimatedTitle(compact: Boolean, reduceMotion: Boolean) {
    val config = PortBranding.config
    val entrance = rememberEntrance(180, reduceMotion)

    val pulse: Float = if (reduceMotion || !config.titleGlowEnabled) 1f else {
        val transition = rememberInfiniteTransition(label = "titlePulse")
        val p by transition.animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(2400, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "pulse"
        )
        p
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            alpha = entrance.value
            translationY = (1f - entrance.value) * 42f
        }
    ) {
        // ---- Eyebrow ----------------------------------------------------
        Text(
            text = config.portSubtitle.uppercase(),
            color = config.accent.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 5.sp,
            textAlign = TextAlign.Center
        )

        // ---- Marca com glow --------------------------------------------
        if (config.showLogo) {
            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (compact) 150.dp else 190.dp)
                        .graphicsLayer { alpha = 0.5f * pulse }
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        config.accent.copy(alpha = 0.30f),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2f
                                )
                            )
                        }
                )
                Image(
                    painter = painterResource(R.drawable.ic_logo_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(if (compact) 84.dp else 106.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                        }
                )
            }
        }

        // ---- Título com halo -------------------------------------------
        Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
        Box {
            if (config.titleGlowEnabled) {
                repeat(3) { layer ->
                    Text(
                        text = config.portTitle,
                        color = config.accent,
                        fontSize = if (compact) 30.sp else 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            val s = 1f + 0.014f * (layer + 1) * pulse
                            scaleX = s
                            scaleY = s
                            alpha = (0.16f - 0.045f * layer) * pulse
                        }
                    )
                }
            }
            Text(
                text = config.portTitle,
                color = Color(0xFFF4F4F8),
                fontSize = if (compact) 30.sp else 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
