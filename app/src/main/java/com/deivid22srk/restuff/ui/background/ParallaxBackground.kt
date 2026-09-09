/*
 * Fundo cinematográfico em camadas com parallax sutil:
 *
 *   camada 0 — gradiente base (sempre presente)
 *   camada 1 — arte do port (config.backgroundArtRes) com parallax fino
 *   camada 2 — scrims de legibilidade sobre a arte
 *   camada 3 — orbes de luz com parallax maior (profundidade) + vinheta
 *
 * O offset de parallax [-1..1]² vem de rememberParallaxOffset() (drift
 * senoidal automático). Quando o usuário pede "reduzir movimento", o offset
 * congela em zero e todas as camadas ficam estáticas.
 */
package com.deivid22srk.restuff.ui.background

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.deivid22srk.restuff.config.PortBranding
import kotlin.random.Random
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius

/** Offset de parallax [-1..1] em X e Y. Congela em [Offset.Zero] se reduzido. */
@Composable
fun rememberParallaxOffset(reduceMotion: Boolean): Offset {
    if (reduceMotion) return Offset.Zero
    val transition = rememberInfiniteTransition(label = "parallax")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "parallaxX"
    )
    val y by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse),
        label = "parallaxY"
    )
    return Offset(x, y)
}

@Composable
fun ParallaxBackground(parallax: Offset, compact: Boolean) {
    val config = PortBranding.config

    Box(Modifier.fillMaxSize()) {
        // ---- Camada 0: gradiente base -----------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF0B0B14),
                        0.5f to Color(0xFF12101C),
                        1f to Color(0xFF090910)
                    )
                )
        )

        // ---- Camada 1: arte do port com parallax fino -------------------
        val artRes = config.backgroundArtRes
        if (artRes != null) {
            Image(
                painter = painterResource(artRes),
                contentDescription = config.contentDescBackground,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Escala 1.14 dá folga para a arte deslizar sem mostrar bordas.
                        scaleX = 1.14f
                        scaleY = 1.14f
                        translationX = parallax.x * 26f
                        translationY = parallax.y * 18f
                    }
            )

            // ---- Camada 2: scrims para legibilidade ----------------------
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x660B0B14),
                            0.45f to Color(0x140B0B14),
                            1f to Color(0xE00B0B14)
                        )
                    )
            )
        }

        // ---- Camada 3: orbes de luz + vinheta ----------------------------
        val accent = config.accent
        val accentDeep = config.accentDeep
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // Orbe superior esquerdo (acento) — parallax mais largo.
                    val c1 = Offset(size.width * 0.18f, size.height * 0.16f) +
                        Offset(parallax.x * 60f, parallax.y * 40f)
                    val r1 = size.width * 0.55f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.13f), Color.Transparent),
                            center = c1,
                            radius = r1
                        ),
                        radius = r1,
                        center = c1
                    )

                    // Orbe inferior direito (acento profundo) — contra-parallax.
                    val c2 = Offset(size.width * 0.85f, size.height * 0.78f) -
                        Offset(parallax.x * 46f, parallax.y * 30f)
                    val r2 = size.width * 0.65f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accentDeep.copy(alpha = 0.20f), Color.Transparent),
                            center = c2,
                            radius = r2
                        ),
                        radius = r2,
                        center = c2
                    )

                    // Vinheta radial: foco no centro, bordas caem no preto.
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            center = Offset(size.width / 2f, size.height * 0.44f),
                            radius = size.width * 0.95f
                        )
                    )
                }
        )
    }
}

private const val GRAIN_SIZE = 128

/**
 * Grain de filme: bitmap de ruído 128px gerado uma única vez (seed fixa),
 * desenhado em tiling com qualidade nearest e deslocamento lento contínuo.
 * Custo por frame: ~160 quads GPU — imperceptível, sem alocações.
 */
@Composable
fun GrainOverlay() {
    val grain = remember { makeNoiseImageBitmap(GRAIN_SIZE) }
    val transition = rememberInfiniteTransition(label = "grain")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = GRAIN_SIZE.toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "grainOffset"
    )
    Canvas(Modifier.fillMaxSize()) {
        val tile = GRAIN_SIZE.toFloat()
        var y = -offset
        while (y < size.height) {
            var x = -offset
            while (x < size.width) {
                drawImage(
                    image = grain,
                    dstOffset = IntOffset(x.toInt(), y.toInt()),
                    dstSize = IntSize(GRAIN_SIZE, GRAIN_SIZE),
                    alpha = 0.06f,
                    filterQuality = FilterQuality.None
                )
                x += tile
            }
            y += tile
        }
    }
}

private fun makeNoiseImageBitmap(size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val pixels = IntArray(size * size)
    val rnd = Random(0xC0FFEEL)
    for (i in pixels.indices) {
        val v = 180 + rnd.nextInt(76)
        val a = if (rnd.nextBoolean()) rnd.nextInt(30) else 0
        pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}
