package com.deivid22srk.restuff.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Virtual gamepad minimalista do port (padrão Xbox 360, esperado pelo jogo).
 *
 * Filosofia visual: controles translúcidos e discretos — círculos de traço
 * fino com preenchimento quase invisível, letras ABXY em pontinhos coloridos
 * suaves. Nada de gamepad "desenhado": só o essencial para jogar sem pad.
 *
 * Layout (frações da tela, landscape):
 *   - direita-baixo : cluster ABXY
 *   - esquerda-baixo: analógico (thumbstick com mola)
 *   - topo          : LB/RB (pílulas) e LT/RT (gatilhos nas bordas)
 *   - centro        : Back/Start (círculos pequenos)
 *   - centro-baixo  : D-pad compacto (8 direções via ângulo)
 *
 * Multi-touch real: cada ponteiro é atribuído ao controle sob ele no
 * ACTION_DOWN/POINTER_DOWN e liberado no UP/POINTER_UP/POINTER_CANCEL.
 * O estado consolidado é enviado ao SDL virtual joystick (P1) via JNI
 * apenas quando muda (zero custo em frames parados).
 *
 * @param opacity opacidade base dos controles (0.2–1.0)
 * @param scale   escala de tamanho dos controles (0.75–1.5)
 * @param haptics vibração curta ao pressionar botões
 */
class VirtualGamepadView(
    context: Context,
    private var opacity: Float,
    private var scale: Float,
    private val haptics: Boolean,
) : View(context) {

    // ------------------------------------------------------------------
    // Modelo de controles
    // ------------------------------------------------------------------

    private enum class Kind { BUTTON, STICK, DPAD, TRIGGER }

    private class Pad(
        val id: String,
        val kind: Kind,
        val cx: Float, // fração da largura (0..1)
        val cy: Float, // fração da altura (0..1)
        val radius: Float, // fração da menor dimensão
        val bit: Int, // bit no bitmask (BUTTON/TRIGGER: rt usa bit 15)
    )

    private companion object {
        // Bits compatíveis com android_main.cpp / SDL gamepad button order.
        const val BIT_A = 0; const val BIT_B = 1; const val BIT_X = 2; const val BIT_Y = 3
        const val BIT_BACK = 4; const val BIT_GUIDE = 5; const val BIT_START = 6
        const val BIT_LB = 7; const val BIT_RB = 8
        const val BIT_LS = 9; const val BIT_RS = 10
        const val BIT_DUP = 11; const val BIT_DDOWN = 12
        const val BIT_DLEFT = 13; const val BIT_DRIGHT = 14
        const val BIT_LT = 15; const val BIT_RT = 16

        const val MIN_OPACITY = 0.15f
        const val MAX_OPACITY = 1.0f
    }

    private fun controls(width: Int, height: Int): List<Pad> {
        val s = scale.coerceIn(0.7f, 1.6f)
        val small = min(width, height).toFloat()
        val r = { f: Float -> f * small * s }
        // Coordenadas normalizadas pensadas para landscape (wide).
        return listOf(
            Pad("A", Kind.BUTTON, 0.885f, 0.80f, r(0.075f), BIT_A),
            Pad("B", Kind.BUTTON, 0.940f, 0.68f, r(0.075f), BIT_B),
            Pad("X", Kind.BUTTON, 0.830f, 0.68f, r(0.075f), BIT_X),
            Pad("Y", Kind.BUTTON, 0.885f, 0.56f, r(0.075f), BIT_Y),
            Pad("LB", Kind.BUTTON, 0.115f, 0.10f, r(0.065f), BIT_LB),
            Pad("RB", Kind.BUTTON, 0.885f, 0.10f, r(0.065f), BIT_RB),
            Pad("LT", Kind.TRIGGER, 0.045f, 0.28f, r(0.065f), BIT_LT),
            Pad("RT", Kind.TRIGGER, 0.955f, 0.28f, r(0.065f), BIT_RT),
            Pad("BACK", Kind.BUTTON, 0.425f, 0.86f, r(0.048f), BIT_BACK),
            Pad("START", Kind.BUTTON, 0.575f, 0.86f, r(0.048f), BIT_START),
            Pad("STICK", Kind.STICK, 0.135f, 0.66f, r(0.145f), BIT_LS),
            Pad("DPAD", Kind.DPAD, 0.385f, 0.62f, r(0.105f), BIT_DUP),
        )
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private var controlsCache: List<Pad> = emptyList()
    private var viewW = 1f
    private var viewH = 1f

    private val buttons = IntArray(32) // 0/1 por bit
    private var lt = 0 // 0..32767
    private var rt = 0
    private var lx = 0 // -32768..32767
    private var ly = 0
    private var rx = 0
    private var ry = 0

    private var stickPointer = -1
    private var stickThumbX = 0f // px, offset do centro
    private var stickThumbY = 0f
    private var stickCenterX = 0f
    private var stickCenterY = 0f
    private var stickRadiusPx = 1f

    private val pointerButton = SparseIntArrayCompat() // pointerId -> bit/controle

    // ------------------------------------------------------------------
    // Pintura
    // ------------------------------------------------------------------

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val faceColors = mapOf(
        "A" to Color.argb(255, 0x7A, 0xC7, 0x6E), // verde suave
        "B" to Color.argb(255, 0xE0, 0x6C, 0x62), // vermelho suave
        "X" to Color.argb(255, 0x5A, 0x9E, 0xD6), // azul suave
        "Y" to Color.argb(255, 0xE8, 0xC8, 0x5A), // amarelo suave
    )
    private val dpadRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w.coerceAtLeast(1).toFloat()
        viewH = h.coerceAtLeast(1).toFloat()
        controlsCache = controls(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)
        for (c in controlsCache) {
            val cx = c.cx * viewW
            val cy = c.cy * viewH
            val r = c.radius
            when (c.kind) {
                Kind.STICK -> drawStick(canvas, cx, cy, r, base)
                Kind.DPAD -> drawDpad(canvas, cx, cy, r, base)
                else -> drawRoundButton(canvas, cx, cy, r, c, base)
            }
        }
    }

    private fun drawRoundButton(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Pad, base: Float) {
        val pressed = when (c.id) {
            "LT" -> lt > 0; "RT" -> rt > 0
            else -> buttons[c.bit] != 0
        }
        val a = if (pressed) base.coerceAtLeast(0.35f) else base * 0.55f

        if (c.id == "LT" || c.id == "RT") {
            // Gatilho: pílula vertical nas bordas — quanto mais apertado, mais cheio.
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (a * 255 * 0.30f).toInt()
            val frac = (if (c.id == "LT") lt else rt) / 32767f
            val top = cy + r - 2 * r * frac
            canvas.drawRoundRect(RectF(cx - r, top, cx + r, cy + r), r, r, fillPaint)
            strokePaint.alpha = (a * 255).toInt()
            canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), r, r, strokePaint)
            return
        }

        val tint = faceColors[c.id]
        fillPaint.color = tint ?: Color.WHITE
        fillPaint.alpha = if (pressed) (a * 255 * 0.55f).toInt() else (a * 255 * 0.16f).toInt()
        canvas.drawCircle(cx, cy, r, fillPaint)
        strokePaint.color = tint ?: Color.WHITE
        strokePaint.alpha = (a * 255).toInt()
        canvas.drawCircle(cx, cy, r, strokePaint)

        val label = when (c.id) {
            "LB" -> "LB"; "RB" -> "RB"
            "BACK" -> "≡"; "START" -> "▶"
            else -> c.id
        }
        textPaint.color = tint ?: Color.WHITE
        textPaint.alpha = (base * 235).toInt()
        val ts = r * (if (c.id.length > 1) 0.62f else 0.95f)
        textPaint.textSize = ts
        canvas.drawText(label, cx, cy + ts * 0.35f, textPaint)
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, base: Float) {
        // Zona externa: anel discreto.
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (base * 150).toInt()
        canvas.drawCircle(cx, cy, r, strokePaint)
        strokePaint.alpha = (base * 60).toInt()
        canvas.drawCircle(cx, cy, r * 0.62f, strokePaint)
        // Polegar.
        fillPaint.color = Color.WHITE
        fillPaint.alpha = (base * 90).toInt()
        canvas.drawCircle(stickCenterX + stickThumbX, stickCenterY + stickThumbY, r * 0.42f, fillPaint)
        strokePaint.alpha = (base * 200).toInt()
        canvas.drawCircle(stickCenterX + stickThumbX, stickCenterY + stickThumbY, r * 0.42f, strokePaint)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float, base: Float) {
        strokePaint.color = Color.WHITE
        val a = base * 170
        val arm = r
        val armW = r * 0.34f
        dpadRect.set(cx - armW, cy - arm, cx + armW, cy - arm * 0.15f)
        drawDir(canvas, dpadRect, buttons[BIT_DUP] != 0, a)
        dpadRect.set(cx - armW, cy + arm * 0.15f, cx + armW, cy + arm)
        drawDir(canvas, dpadRect, buttons[BIT_DDOWN] != 0, a)
        dpadRect.set(cx - arm, cy - armW, cx - arm * 0.15f, cy + armW)
        drawDir(canvas, dpadRect, buttons[BIT_DLEFT] != 0, a)
        dpadRect.set(cx + arm * 0.15f, cy - armW, cx + arm, cy + armW)
        drawDir(canvas, dpadRect, buttons[BIT_DRIGHT] != 0, a)
    }

    private fun drawDir(canvas: Canvas, rect: RectF, pressed: Boolean, alpha: Float) {
        fillPaint.color = Color.WHITE
        fillPaint.alpha = (if (pressed) alpha * 0.8f else alpha * 0.22f).toInt()
        canvas.drawRoundRect(rect, rect.width() / 2.2f, rect.height() / 2.2f, fillPaint)
    }

    // ------------------------------------------------------------------
    // Toque
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                assignPointer(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    movePointer(id, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                releasePointer(id)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun assignPointer(pointerId: Int, x: Float, y: Float) {
        // Stick tem prioridade (zona maior).
        for (c in controlsCache) {
            if (c.kind == Kind.STICK) {
                val cx = c.cx * viewW; val cy = c.cy * viewH
                if (hypot(x - cx, y - cy) <= c.radius * 1.25f) {
                    stickPointer = pointerId
                    stickCenterX = cx; stickCenterY = cy
                    stickRadiusPx = c.radius * 0.62f
                    movePointer(pointerId, x, y)
                    return
                }
            }
        }
        // Botão mais próximo dentro do raio.
        var best: Pad? = null; var bestD = Float.MAX_VALUE
        for (c in controlsCache) {
            if (c.kind == Kind.STICK) continue
            val cx = c.cx * viewW; val cy = c.cy * viewH
            val d = hypot(x - cx, y - cy)
            val hit = c.radius * (if (c.kind == Kind.DPAD) 1.35f else 1.45f)
            if (d <= hit && d < bestD) { best = c; bestD = d }
        }
        if (best != null) {
            val bit = if (best.kind == Kind.DPAD) {
                dpadBitAt(best, x, y)
            } else {
                best.bit
            }
            pointerButton.put(pointerId, bit)
            if (best.kind == Kind.TRIGGER) {
                if (bit == BIT_LT) lt = 32767 else rt = 32767
            } else {
                setButtonBit(bit, true)
            }
            hapticTap()
            fire()
            invalidate()
        }
    }

    /** Direção do D-pad pelo ângulo do toque em torno do centro. */
    private fun dpadBitAt(pad: Pad, x: Float, y: Float): Int {
        val cx = pad.cx * viewW
        val cy = pad.cy * viewH
        val dx = x - cx
        val dy = y - cy
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) BIT_DRIGHT else BIT_DLEFT
        } else {
            if (dy > 0) BIT_DDOWN else BIT_DUP
        }
    }

    private fun movePointer(pointerId: Int, x: Float, y: Float) {
        if (pointerId == stickPointer) {
            var dx = x - stickCenterX
            var dy = y - stickCenterY
            val len = hypot(dx, dy)
            if (len > stickRadiusPx) {
                dx = dx / len * stickRadiusPx
                dy = dy / len * stickRadiusPx
            }
            stickThumbX = dx; stickThumbY = dy
            lx = (dx / stickRadiusPx * 32767f).toInt().coerceIn(-32768, 32767)
            ly = (dy / stickRadiusPx * 32767f).toInt().coerceIn(-32768, 32767)
            // Pressionar o stick (L3) só com "clique" não é detectável em touch —
            // LS fica ativo enquanto o polegar está deslocado (padrão em ports).
            setButtonBit(BIT_LS, abs(lx) > 8000 || abs(ly) > 8000)
            fire()
            invalidate()
        }
    }

    private fun releasePointer(pointerId: Int) {
        if (pointerId == stickPointer) {
            stickPointer = -1
            stickThumbX = 0f; stickThumbY = 0f
            lx = 0; ly = 0
            setButtonBit(BIT_LS, false)
            fire()
            invalidate()
            return
        }
        val bit = pointerButton.get(pointerId, -1)
        if (bit >= 0) {
            pointerButton.delete(pointerId)
            when (bit) {
                BIT_LT -> lt = 0
                BIT_RT -> rt = 0
                else -> setButtonBit(bit, false)
            }
            fire()
            invalidate()
        }
    }

    private fun releaseAll() {
        stickPointer = -1
        stickThumbX = 0f; stickThumbY = 0f
        lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        for (i in buttons.indices) buttons[i] = 0
        pointerButton.clear()
        fire()
        invalidate()
    }

    private fun setButtonBit(bit: Int, pressed: Boolean) {
        buttons[bit] = if (pressed) 1 else 0
    }

    /** Estado consolidado → SDL (P1 virtual) via JNI. */
    private fun fire() {
        if (!NativeBridge.loaded) return
        var mask = 0
        for (i in 0 until 15) {
            if (buttons[i] != 0) mask = mask or (1 shl i)
        }
        // Direções do D-pad derivadas do stick? Não: D-pad é botão dedicado.
        NativeBridge.nativeSetVirtualPadState(mask, lx, ly, rx, ry, lt, rt)
    }

    private fun hapticTap() {
        if (!haptics) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    ?.vibrate(VibrationEffect.createOneShot(12L, 60))
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Config dinâmica
    // ------------------------------------------------------------------

    fun setOpacity(v: Float) {
        opacity = v.coerceIn(MIN_OPACITY, MAX_OPACITY)
        invalidate()
    }

    fun setScale(v: Float) {
        scale = v.coerceIn(0.7f, 1.6f)
        controlsCache = controls(viewW.toInt(), viewH.toInt())
        invalidate()
    }

    fun shutdown() {
        releaseAll()
    }

    /** Compat mínima (evita androidx.collection em um arquivo só do overlay). */
    private class SparseIntArrayCompat {
        private val map = HashMap<Int, Int>()
        fun put(k: Int, v: Int) { map[k] = v }
        fun get(k: Int, fallback: Int): Int = map[k] ?: fallback
        fun delete(k: Int) { map.remove(k) }
        fun clear() = map.clear()
    }
}
