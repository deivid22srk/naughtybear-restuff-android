package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.viewmodel.DataPhase

/**
 * Área de status com os estados do ciclo de dados (mais erro de permissão).
 * Cada estado tem ícone, cor e microanimação próprios, com transição cruzada:
 *
 *   Idle       → lupa suave: "nenhum dado selecionado" (estado inicial, sem busca)
 *   Validating → arco duplo girando/pulsando, cor = accent do port
 *   NotFound   → alerta vermelho suave + dica com o arquivo esperado
 *   Found      → check verde + nome do arquivo detectado em destaque
 */
@Composable
fun StatusArea(phase: DataPhase, compact: Boolean, reduceMotion: Boolean) {
    val config = PortBranding.config

    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (fadeIn(tween(360, delayMillis = 60)) +
                slideInVertically(tween(360, easing = FastOutSlowInEasing)) { it / 5 })
                .togetherWith(fadeOut(tween(180)))
        },
        label = "statusContent",
        modifier = Modifier.fillMaxWidth()
    ) { p ->
        when (p) {
            is DataPhase.Idle -> StatusRow(
                tint = Color(0xFF9DB2D6),
                title = config.labelIdle,
                description = config.labelIdleHint
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color(0xFF9DB2D6),
                    modifier = Modifier.size(22.dp)
                )
            }

            is DataPhase.Validating -> StatusRow(
                tint = config.accent,
                title = config.labelValidating,
                description = null
            ) {
                SearchingIndicator(tint = config.accent)
            }

            is DataPhase.NotFound -> StatusRow(
                tint = Color(0xFFFF6B6B),
                title = config.labelNotFound,
                description = config.labelNotFoundHint.format(
                    config.expectedDataFiles.firstOrNull() ?: "—"
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(22.dp)
                )
            }

            is DataPhase.Found -> StatusRow(
                tint = Color(0xFF4ADE80),
                title = config.labelFound,
                description = config.labelFoundFile.format(p.fileName)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4ADE80),
                    modifier = Modifier.size(22.dp)
                )
            }

            is DataPhase.PermissionError -> StatusRow(
                tint = Color(0xFFFF6B6B),
                title = config.labelPermissionError,
                description = config.labelNotFoundHint.format(
                    config.expectedDataFiles.firstOrNull() ?: "—"
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    tint: Color,
    title: String,
    description: String?,
    icon: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(tint.copy(alpha = 0.14f), CircleShape)
                .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) { icon() }

        Column {
            Text(
                text = title,
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2
                )
            }
        }
    }
}

/** Arco duplo girando com pulso de escala — indicador "validando". */
@Composable
private fun SearchingIndicator(tint: Color) {
    val transition = rememberInfiniteTransition(label = "searching")
    val rot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "rot"
    )
    val sc by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(1300, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "sc"
    )
    Canvas(
        Modifier
            .size(22.dp)
            .graphicsLayer {
                rotationZ = rot
                scaleX = sc
                scaleY = sc
            }
    ) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 96f,
            useCenter = false,
            style = stroke
        )
        drawArc(
            color = tint.copy(alpha = 0.35f),
            startAngle = 180f,
            sweepAngle = 96f,
            useCenter = false,
            style = stroke
        )
    }
}
