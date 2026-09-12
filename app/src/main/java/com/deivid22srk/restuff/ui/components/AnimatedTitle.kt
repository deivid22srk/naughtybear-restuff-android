package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType

/**
 * Identidade da tela inicial no design "Mel & Carvão":
 *
 *   eyebrow em versalete → título display em duas linhas com pesos
 *   contrastantes (Light + Black, a última no accent) → LINHA DO MEL.
 *
 * A entrada é uma única subida suave (alpha + 18dp), respeitando
 * "reduzir movimento". Sem glow, sem pulso — o título é o elemento
 * de peso da tela.
 */
@Composable
fun AnimatedTitle(compact: Boolean, reduceMotion: Boolean) {
    val config = PortBranding.config
    val entrance = rememberEntrance(120, reduceMotion)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            alpha = entrance.value
            translationY = (1f - entrance.value) * 18f
        }
    ) {
        // ---- Eyebrow (versalete com o accent do port) ---------------------
        Text(
            text = config.portSubtitle.uppercase(),
            color = config.accent.copy(alpha = 0.85f),
            style = PortType.label,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

        // ---- Título display: duas palavras, pesos contrastantes ----------
        // portTitle ("NAUGHTY BEAR") é fatiado em palavras; a última recebe
        // peso Black + accent — a assinatura editorial do port.
        val words = config.portTitle.trim().split(' ').filter { it.isNotBlank() }
        val head = words.dropLast(1).joinToString(" ")
        val tail = words.lastOrNull() ?: ""
        val displaySize = if (compact) 36.sp else 46.sp

        if (head.isNotEmpty()) {
            Text(
                text = head,
                color = PortPalette.textPrimary,
                style = PortType.display.copy(fontSize = displaySize),
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = tail,
            color = config.accent,
            style = PortType.displayStrong.copy(fontSize = displaySize),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

        // ---- LINHA DO MEL: pincelada âmbar de 28dp x 2dp ------------------
        HoneyLine(color = config.accent)
    }
}

/** Filete curto no accent — divisor assinatura entre identidade e conteúdo. */
@Composable
fun HoneyLine(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .width(28.dp)
            .height(2.dp)
            .background(color)
    )
}

/** Sobe 0f → 1f (alpha puro) — para elementos discretos (chips, barras). */
@Composable
internal fun rememberFadeEntrance(delayMs: Int, reduceMotion: Boolean): Animatable<Float, *> {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            anim.snapTo(1f)
        } else {
            kotlinx.coroutines.delay(delayMs.toLong())
            anim.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        }
    }
    return anim
}
