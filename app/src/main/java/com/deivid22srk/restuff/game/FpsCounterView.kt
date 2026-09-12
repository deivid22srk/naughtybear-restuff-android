package com.deivid22srk.restuff.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Pill minimalista de FPS sobre o jogo — design "Mel & Carvão":
 * mono pequeno âmbar sobre carvão translúcido, canto superior direito.
 *
 * A taxa é calculada sobre o CONTADOR REAL de presents Vulkan exposto pelo
 * motor via [NativeBridge.nativeGetPresentCount] (trampoline no
 * vulkan_device.cpp) — portanto mede o frame rate do MOTOR, não o vsync do
 * painel (Choreographer/FrameMetrics mediriam 60/120 do display, não o jogo).
 *
 * Sem JNI disponível (ex.: lib não carregada), mostra "—".
 * A view NÃO consome toques: TextView não-clicável deixa o evento passar
 * para a superfície SDL embaixo (overlays ImGui do jogo continuam funcionando).
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
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.parseColor("#F2C14E"))
        text = DASH
        isClickable = false
        isFocusable = false
        contentDescription = "Contador de quadros por segundo do motor"
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f)
            setColor(Color.parseColor("#B30A0A0E"))
            setStroke(dp(1f).toInt(), Color.parseColor("#33F2C14E"))
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
            val frameMs = if (lastFps > 0.5) 1000.0 / lastFps else 0.0
            String.format(java.util.Locale.US, "%.0f FPS · %.1f ms", lastFps, frameMs)
        } else {
            DASH
        }
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val POLL_MS = 500L
        private const val DASH = "— FPS"

        /** Adiciona a pill no canto superior direito do layout do SDL. */
        fun addTo(activity: GameActivity) {
            val layout = org.libsdl.app.SDLActivity.getContentView() as? android.view.ViewGroup
                ?: return
            val pill = FpsCounterView(activity)
            val params = FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dpStatic(activity, 14f).toInt()
                rightMargin = dpStatic(activity, 14f).toInt()
            }
            layout.addView(pill, params)
            pill.start()
        }

        private fun dpStatic(context: Context, v: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
            )
    }
}
