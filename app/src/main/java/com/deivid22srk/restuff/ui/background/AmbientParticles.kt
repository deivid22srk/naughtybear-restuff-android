/*
 * Sistema de partículas ambiente parametrizável.
 *
 * Desempenho: as partículas vivem em um objeto [ParticleField] mutável que é
 * atualizado IN-PLACE por frame (zero alocação por frame). Um contador de
 * frame lido dentro do Canvas invalida apenas a fase de desenho — nunca há
 * recomposição da árvore de UI. Alvo: 60 fps com 50+ partículas.
 *
 * Acessibilidade: com "reduzir movimento" a simulação para de receber ticks
 * (o campo congela; nada se move).
 */
package com.deivid22srk.restuff.ui.background

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.deivid22srk.restuff.config.ParticleType
import com.deivid22srk.restuff.config.PortBranding
import kotlin.math.sin

class AmbientParticle(
    var x: Float,          // 0..1 (fração da largura)
    var y: Float,          // 0..1 (fração da altura)
    var vx: Float,         // fração/segundo
    var vy: Float,
    var sizeDp: Float,     // raio visual (dp) — MIST usa diâmetro maior
    var baseAlpha: Float,
    var phase: Float,      // desfasagem do twinkle/sway
    var speed: Float,      // velocidade do twinkle/sway
)

class ParticleField(val type: ParticleType, count: Int) {
    private val rnd = kotlin.random.Random(77L)
    val particles = ArrayList<AmbientParticle>(count)
    var time = 0f

    init {
        val n = if (type == ParticleType.MIST) minOf(count, 10) else count
        repeat(n) { particles.add(spawn()) }
    }

    private fun spawn(): AmbientParticle = when (type) {
        ParticleType.DUST -> AmbientParticle(
            rnd.nextFloat(), rnd.nextFloat(),
            (rnd.nextFloat() - 0.5f) * 0.005f,
            -0.005f - rnd.nextFloat() * 0.008f,
            1.2f + rnd.nextFloat() * 2.0f,
            0.10f + rnd.nextFloat() * 0.16f,
            rnd.nextFloat() * 6.2832f,
            0.5f + rnd.nextFloat() * 1.1f
        )

        ParticleType.EMBERS -> AmbientParticle(
            rnd.nextFloat(), rnd.nextFloat(),
            (rnd.nextFloat() - 0.5f) * 0.008f,
            -0.014f - rnd.nextFloat() * 0.022f,
            1.3f + rnd.nextFloat() * 2.4f,
            0.30f + rnd.nextFloat() * 0.50f,
            rnd.nextFloat() * 6.2832f,
            1.2f + rnd.nextFloat() * 2.2f
        )

        ParticleType.SPARKS -> AmbientParticle(
            rnd.nextFloat(), rnd.nextFloat(),
            (rnd.nextFloat() - 0.5f) * 0.05f,
            -0.03f - rnd.nextFloat() * 0.05f,
            0.9f + rnd.nextFloat() * 1.4f,
            0.45f + rnd.nextFloat() * 0.45f,
            rnd.nextFloat() * 6.2832f,
            2.5f + rnd.nextFloat() * 3.0f
        )

        ParticleType.MIST -> AmbientParticle(
            rnd.nextFloat(), rnd.nextFloat(),
            (rnd.nextFloat() - 0.5f) * 0.004f,
            -0.002f - rnd.nextFloat() * 0.003f,
            70f + rnd.nextFloat() * 130f,
            0.030f + rnd.nextFloat() * 0.035f,
            rnd.nextFloat() * 6.2832f,
            0.15f + rnd.nextFloat() * 0.35f
        )

        ParticleType.NONE -> AmbientParticle(0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f, 1f)
    }

    fun update(dt: Float) {
        time += dt
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            // Wrap nas bordas com margem (nada "salta" em câmera lenta).
            if (p.y < -0.06f) { p.y = 1.06f; p.x = rnd.nextFloat() }
            if (p.y > 1.06f) { p.y = -0.06f; p.x = rnd.nextFloat() }
            if (p.x < -0.06f) p.x = 1.06f
            if (p.x > 1.06f) p.x = -0.06f
        }
    }
}

@Composable
fun AmbientParticles(reducedMotion: Boolean, enabled: Boolean, compact: Boolean) {
    val config = PortBranding.config
    val type = config.particleType
    if (!enabled || type == ParticleType.NONE) return

    val particleColor = config.particleColor
    val accent = config.accent
    val count = if (compact) (config.particleCount * 0.6f).toInt() else config.particleCount
    val field = remember(type, count) { ParticleField(type, count) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = -1L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last >= 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    field.update(dt)
                }
                last = now
                tick++
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        tick // leitura invalida SOMENTE o draw (sem recomposição)

        val w = size.width
        val h = size.height
        when (type) {
            ParticleType.MIST -> {
                for (p in field.particles) {
                    val r = p.sizeDp.dp.toPx()
                    val cx = p.x * w + sin(field.time * p.speed + p.phase) * 30f
                    val cy = p.y * h
                    val brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = p.baseAlpha), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r
                    )
                    drawCircle(brush = brush, radius = r, center = Offset(cx, cy))
                }
            }

            ParticleType.SPARKS -> {
                for (p in field.particles) {
                    val cx = p.x * w
                    val cy = p.y * h
                    val tw = 0.55f + 0.45f * sin(field.time * p.speed + p.phase)
                    // Rastro na direção oposta ao movimento.
                    val tail = Offset(cx - p.vx * h * 0.10f, cy - p.vy * h * 0.10f)
                    drawLine(
                        color = particleColor.copy(alpha = p.baseAlpha * 0.35f * tw),
                        start = tail,
                        end = Offset(cx, cy),
                        strokeWidth = p.sizeDp.dp.toPx() * 0.6f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = particleColor,
                        radius = p.sizeDp.dp.toPx() * 0.5f,
                        center = Offset(cx, cy),
                        alpha = p.baseAlpha * tw
                    )
                }
            }

            else -> { // DUST e EMBERS
                val baseColor =
                    if (type == ParticleType.EMBERS) particleColor else Color(0xFFCFD6E4)
                for (p in field.particles) {
                    val tw = 0.55f + 0.45f * sin(field.time * p.speed + p.phase)
                    val cx = p.x * w + sin(field.time * p.speed * 0.7f + p.phase) * 18f
                    val cy = p.y * h
                    val r = p.sizeDp.dp.toPx() * 0.5f
                    if (type == ParticleType.EMBERS) {
                        // Halo difuso nas brasas (fake bloom barato).
                        drawCircle(
                            color = baseColor,
                            radius = r * 3f,
                            center = Offset(cx, cy),
                            alpha = p.baseAlpha * tw * 0.22f
                        )
                    }
                    drawCircle(
                        color = baseColor,
                        radius = r,
                        center = Offset(cx, cy),
                        alpha = p.baseAlpha * tw
                    )
                }
            }
        }
    }
}
