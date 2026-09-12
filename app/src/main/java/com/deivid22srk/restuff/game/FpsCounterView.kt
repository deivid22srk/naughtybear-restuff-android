package com.deivid22srk.restuff.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.deivid22srk.restuff.config.PortBranding

/**
 * Pill minimalista de FPS sobre o jogo — design "Mel & Carvão":
 * mono pequeno no accent do branding sobre carvão translúcido, canto
 * superior direito (com respeito ao cutout/notch).
 *
 * A taxa é calculada sobre o CONTADOR REAL de presents Vulkan exposto pelo
 * motor via [NativeBridge.nativeGetPresentCount] (trampolim no
 * vulkan_device.cpp) — portanto mede o frame rate do MOTOR, não o vsync do
 * painel (Choreographer/FrameMetrics mediriam 60/120 do display, não o jogo).
 *
 * Detalhes de acabamento:
 *  - minWidth fixo + gravity CENTER: "58 FPS · 17.2 ms" e "120 FPS · 8.3 ms"
 *    ocupam a MESMA largura (sem "pulsar" da pill a cada poll);
 *  - as cores vêm do [PortBranding.config] (a pill segue a identidade do
 *    port, nada hardcoded);
 *  - respeita displayCutout/safe insets no posicionamento;
 *  - sem JNI disponível (lib não carregada) mostra "…" e não lança;
 *  - NÃO consome toques: TextView não-clicável deixa o evento passar para
 *    a superfície SDL embaixo (overlays ImGui do jogo continuam funcionando).
 */
@SuppressLint("ViewConstructor")
class FpsCounterView(context: Context) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastCount = -1L
    private var lastNanos = 0L
    private var lastFps = 0.0

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        val accent = PortBranding.config.accent
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.045f
        setTextColor(accent.toArgb())
        text = DASH
        gravity = Gravity.CENTER
        minWidth = dp(110f).toInt()
        isClickable = false
        isFocusable = false
        contentDescription = "Contador de quadros por segundo do motor"
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f)
            setColor(0xB30A0A0E.toInt())
            setStroke(dp(1f).toInt(), accent.copy(alpha = 0.20f).toArgb())
        }
        val hPad = dp(9f).toInt()
        val vPad = dp(4f).toInt()
        setPadding(hPad, vPad, hPad, vPad)
    }

    fun start() {
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun update() {
        val count: Long = try {
            NativeBridge.nativeGetPresentCount()
        } catch (_: UnsatisfiedLinkError) {
            text = DASH
            return
        } catch (_: Throwable) {
            text = DASH
            return
        }
        val now = System.nanoTime()
        if (lastCount >= 0) {
            val dFrames = (count - lastCount).coerceAtLeast(0)
            val dSec = (now - lastNanos) / 1_000_000_000.0
            if (dSec > 0) {
                lastFps = dFrames / dSec
            }
        }
        lastCount = count
        lastNanos = now

        text = if (lastFps > 0.5) {
            val frameMs = 1000.0 / lastFps
            String.format(java.util.Locale.US, "%.0f FPS · %.1f ms", lastFps, frameMs)
        } else {
            DASH
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val POLL_MS = 500L
        private const val DASH = "…"

        /**
         * Adiciona a pill no canto superior direito do layout do SDL,
         * respeitando o cutout (notch) quando houver. Retorna null se o
         * content view ainda não estiver disponível.
         */
        fun addTo(activity: GameActivity): FpsCounterView? {
            val layout = org.libsdl.app.SDLActivity.getContentView() as? android.view.ViewGroup
                ?: return null
            val pill = FpsCounterView(activity)
            val params = FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dpStatic(activity, 14f).toInt()
                marginEnd = dpStatic(activity, 14f).toInt()
            }
            layout.addView(pill, params)

            // Cutout/notch: soma os insets seguros do display às margens.
            pill.setOnApplyWindowInsetsListener { v, insets ->
                val cutout = insets.displayCutout
                val top = maxOf(
                    insets.systemWindowInsetTop,
                    cutout?.safeInsetTop ?: 0
                )
                val right = maxOf(
                    insets.systemWindowInsetRight,
                    cutout?.safeInsetRight ?: 0
                )
                (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.topMargin = dpStatic(v.context, 14f).toInt() + top
                    lp.rightMargin = dpStatic(v.context, 14f).toInt() + right
                    v.layoutParams = lp
                }
                insets
            }
            pill.requestApplyInsets()
            pill.start()
            return pill
        }

        private fun dpStatic(context: Context, v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
            )
    }
}
