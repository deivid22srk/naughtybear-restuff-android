package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import com.deivid22srk.restuff.viewmodel.DataPhase

/**
 * Estado dos dados do jogo no design "Mel & Carvão": UMA linha de status —
 * ponto de cor (8dp) + frase — e, quando há detalhe, uma linha de apoio em
 * tipografia quieta. O ponto pulsa devagar apenas enquanto valida.
 * Sem painéis, sem ícones grandes: a cor do ponto carrega o semáforo.
 */
@Composable
fun StatusArea(phase: DataPhase, compact: Boolean, reduceMotion: Boolean) {
    val config = PortBranding.config

    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (fadeIn(tween(320)) +
                slideInVertically(tween(320)) { it / 6 })
                .togetherWith(fadeOut(tween(160)))
        },
        label = "statusContent",
        modifier = Modifier.fillMaxWidth()
    ) { p ->
        when (p) {
            is DataPhase.Idle -> StatusLine(
                dotColor = PortPalette.textSecondary,
                title = config.labelIdle,
                detail = config.labelIdleHint,
                pulsing = false,
                reduceMotion = reduceMotion
            )

            is DataPhase.Validating -> StatusLine(
                dotColor = config.accent,
                title = config.labelValidating,
                detail = null,
                pulsing = true,
                reduceMotion = reduceMotion
            )

            is DataPhase.Found -> StatusLine(
                dotColor = PortPalette.success,
                title = config.labelFound,
                detail = p.fileName,
                detailMono = true,
                pulsing = false,
                reduceMotion = reduceMotion
            )

            is DataPhase.NotFound -> StatusLine(
                dotColor = PortPalette.error,
                title = config.labelNotFound,
                detail = config.labelNotFoundHint.format(
                    config.expectedDataFiles.firstOrNull() ?: "—"
                ),
                pulsing = false,
                reduceMotion = reduceMotion
            )

            is DataPhase.PermissionError -> StatusLine(
                dotColor = PortPalette.error,
                title = config.labelPermissionError,
                detail = null,
                pulsing = false,
                reduceMotion = reduceMotion
            )
        }
    }
}

@Composable
private fun StatusLine(
    dotColor: Color,
    title: String,
    detail: String?,
    detailMono: Boolean = false,
    pulsing: Boolean,
    reduceMotion: Boolean,
) {
    val pulse: Float = if (pulsing && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val v by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(700),
                RepeatMode.Reverse
            ),
            label = "pulse"
        )
        v
    } else {
        1f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                color = PortPalette.textSecondary,
                style = if (detailMono) PortType.mono else PortType.rowSub,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
