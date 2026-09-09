package com.deivid22srk.restuff.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * Entrada orquestrada da cena: anima 0f → 1f com stagger via [delayMs].
 * Cada componente consome o valor em graphicsLayer { alpha; translationY },
 * de modo que a entrada roda na fase de renderização (sem recomposição).
 *
 * Com "reduzir movimento", salta direto para 1f (sem animação de entrada).
 */
@Composable
fun rememberEntrance(delayMs: Int, reduceMotion: Boolean): Animatable<Float, AnimationVector1D> {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            anim.snapTo(1f)
        } else {
            delay(delayMs.toLong())
            anim.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        }
    }
    return anim
}
