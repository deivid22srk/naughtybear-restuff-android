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
import kotlin.math.atan2
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
 *   - esquerda-baixo : analógico esquerdo (L — movimento)
 *   - direita-centro : analógico direito  (R — câmera)
 *   - direita-baixo  : cluster ABXY
 *   - topo           : LB/RB (pílulas) e LT/RT (gatilhos nas bordas)
 *   - centro         : Back/Start (círculos pequenos)
 *   - centro-baixo   : D-pad compacto, 8 direções via octantes do ângulo
 *
 * Multi-touch real: cada ponteiro é atribuído ao controle sob ele no
 * ACTION_DOWN/POINTER_DOWN e liberado no UP/POINTER_UP/POINTER_CANCEL. O
 * D-pad re-avalia a direção durante o arrasto (octante pelo ângulo), e dois
 * dedos no mesmo botão mantêm o botão pressionado (bits recomputados da
 * união das atribuições vivas).
 *
 * DECISÃO de input: a view consome TODOS os toques quando visível (retorna
 * true em onTouchEvent). O jogo é dirigido por gamepad — o contrário
 * (pass-through) entregaria o gesto multi-touch inteiro ao SDL ao declinar
 * um ACTION_DOWN, quebrando pressões simultâneas de botões. Toques no "vazio"
 * não fazem nada; o painel de ajustes abre com 4 dedos (nível Activity).
 *
 * O estado consolidado é enviado ao SDL virtual joystick (P1) via JNI apenas
 * quando muda (detecção explícita — zero custo em frames parados).
 *
 * @param opacity opacidade base dos controles (0.2–1.0)
 * @param scale   escala de tamanho dos controles (0.75–1.5)
 * @param haptics vibração curta ao pressionar controles
 */
class VirtualGamepadView(
    context: Context,
    private var opacity: Float,
    private var scale: Float,
    private var haptics: Boolean,
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
        val bit: Int, // bit no bitmask (BUTTON/TRIGGER)
    )

    /** Toque ativo em um analógico (polegar preso ao anel, com mola). */
    private class StickTouch(
        val pointerId: Int,
        var centerX: Float,
        var centerY: Float,
        var radiusPx: Float,
    ) {
        var thumbX = 0f // px, offset do centro
        var thumbY = 0f
    }

    /** Botão/D-pad atribuído a um ponteiro (máscara viva p/ diagonais). */
    private class Assignment(
        val pad: Pad,
        var mask: Int, // bits de botão atualmente pressionados por ESTE ponteiro
        val trigger: Int, // 0 (não-gatilho), BIT_LT ou BIT_RT
    )

    private companion object {
        // Bits compatíveis com android_main.cpp — ORDEM CANÔNICA do enum
        // SDL_GAMEPAD_BUTTON_* (o joystick virtual mapeia botão i → botão
        // padrão i): A, B, X, Y, Back, Guide, Start, LS, RS, LB, RB,
        // DUp, DDown, DLeft, DRight. LT/RT vão pelos eixos analógicos.
        // ⚠️ LB/RB (9/10) vêm DEPOIS dos stick-clicks (7/8) — inverter aqui
        // troca ombros por clique-de-stick dentro do jogo.
        const val BIT_A = 0; const val BIT_B = 1; const val BIT_X = 2; const val BIT_Y = 3
        const val BIT_BACK = 4; const val BIT_GUIDE = 5; const val BIT_START = 6
        const val BIT_LS = 7; const val BIT_RS = 8
        const val BIT_LB = 9; const val BIT_RB = 10
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
        // Coordenadas normalizadas pensadas para landscape (wide). O stick
        // direito (câmera) fica entre o D-pad e o cluster ABXY: 4 agrupamentos
        // equilibrados (0.135 / 0.385 / 0.615 / 0.885) — o polegar direito
        // alterna entre R e ABXY como num pad físico.
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
            Pad("RSTICK", Kind.STICK, 0.615f, 0.60f, r(0.145f), BIT_RS),
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

    private var stickL: StickTouch? = null
    private var stickR: StickTouch? = null

    private val assignments = HashMap<Int, Assignment>() // pointerId → controle

    // Último estado enviado ao SDL (fire só transmite quando muda).
    private var lastMask = -1
    private var lastLx = 0; private var lastLy = 0
    private var lastRx = 0; private var lastRy = 0
    private var lastLt = 0; private var lastRt = 0

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
            when (c.kind) {
                Kind.STICK -> {
                    val st = if (c.id == "STICK") stickL else stickR
                    drawStick(canvas, c, st, base)
                }
                Kind.DPAD -> drawDpad(canvas, c.cx * viewW, c.cy * viewH, c.radius, base)
                else -> drawRoundButton(canvas, c.cx * viewW, c.cy * viewH, c.radius, c, base)
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
            // Rótulo do gatilho (bug reportado: LT/RT não desenhavam texto).
            textPaint.color = Color.WHITE
            textPaint.alpha = (base * 235).toInt()
            val ts = r * 0.62f
            textPaint.textSize = ts
            canvas.drawText(c.id, cx, cy + ts * 0.35f, textPaint)
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

    private fun drawStick(canvas: Canvas, pad: Pad, st: StickTouch?, base: Float) {
        val cx = pad.cx * viewW
        val cy = pad.cy * viewH
        val r = pad.radius
        // Zona externa: anel discreto.
        strokePaint.color = Color.WHITE
        strokePaint.alpha = (base * 150).toInt()
        canvas.drawCircle(cx, cy, r, strokePaint)
        strokePaint.alpha = (base * 60).toInt()
        canvas.drawCircle(cx, cy, r * 0.62f, strokePaint)
        // Marca discreta L/R no centro do anel: identifica qual analógico é.
        textPaint.color = Color.WHITE
        textPaint.alpha = (base * 110).toInt()
        textPaint.textSize = r * 0.34f
        canvas.drawText(if (pad.id == "STICK") "L" else "R", cx, cy + r * 0.12f, textPaint)
        // Polegar (mola: fica no centro quando sem toque).
        fillPaint.color = Color.WHITE
        fillPaint.alpha = (base * 90).toInt()
        val tx = cx + (st?.thumbX ?: 0f)
        val ty = cy + (st?.thumbY ?: 0f)
        canvas.drawCircle(tx, ty, r * 0.42f, fillPaint)
        strokePaint.alpha = (base * 200).toInt()
        canvas.drawCircle(tx, ty, r * 0.42f, strokePaint)
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
        // Analógicos têm prioridade (zonas maiores).
        for (c in controlsCache) {
            if (c.kind != Kind.STICK) continue
            val occupied = if (c.id == "STICK") stickL != null else stickR != null
            if (occupied) continue
            val cx = c.cx * viewW; val cy = c.cy * viewH
            if (hypot(x - cx, y - cy) <= c.radius * 1.25f) {
                val st = StickTouch(pointerId, cx, cy, c.radius * 0.62f)
                if (c.id == "STICK") stickL = st else stickR = st
                hapticTap()
                movePointer(pointerId, x, y)
                return
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
            val mask = if (best.kind == Kind.DPAD) {
                dpadMaskAt(best, x, y)
            } else {
                1 shl best.bit
            }
            val trigger = if (best.kind == Kind.TRIGGER) best.bit else 0
            assignments[pointerId] = Assignment(best, mask, trigger)
            if (trigger == BIT_LT) lt = 32767
            if (trigger == BIT_RT) rt = 32767
            syncButtonBitsFromAssignments()
            hapticTap()
            fire()
            invalidate()
        }
    }

    /**
     * D-pad 8 direções: octante do ângulo do toque em torno do centro
     * (0° = leste, -90° = norte; setores de 45°). Diagonais acionam dois
     * bits — o joystick virtual interpreta a combinação como diagonal.
     */
    private fun dpadMaskAt(pad: Pad, x: Float, y: Float): Int {
        val cx = pad.cx * viewW
        val cy = pad.cy * viewH
        val angle = Math.toDegrees(atan2(y - cy, x - cx))
        return when {
            angle >= 157.5 || angle < -157.5 -> BIT_DLEFT
            angle >= 112.5 -> BIT_DLEFT or BIT_DDOWN
            angle >= 67.5 -> BIT_DDOWN
            angle >= 22.5 -> BIT_DDOWN or BIT_DRIGHT
            angle >= -22.5 -> BIT_DRIGHT
            angle >= -67.5 -> BIT_DRIGHT or BIT_DUP
            angle >= -112.5 -> BIT_DUP
            else -> BIT_DUP or BIT_DLEFT
        }
    }

    private fun movePointer(pointerId: Int, x: Float, y: Float) {
        val st = stickL?.takeIf { it.pointerId == pointerId }
            ?: stickR?.takeIf { it.pointerId == pointerId }
        if (st != null) {
            var dx = x - st.centerX
            var dy = y - st.centerY
            val len = hypot(dx, dy)
            if (len > st.radiusPx) {
                dx = dx / len * st.radiusPx
                dy = dy / len * st.radiusPx
            }
            st.thumbX = dx; st.thumbY = dy
            if (stickL === st) {
                lx = (dx / st.radiusPx * 32767f).toInt().coerceIn(-32768, 32767)
                ly = (dy / st.radiusPx * 32767f).toInt().coerceIn(-32768, 32767)
                // Pressionar o stick (L3) só com "clique" não é detectável em touch —
                // LS fica ativo enquanto o polegar está deslocado (padrão em ports).
                setButtonBit(BIT_LS, abs(lx) > 8000 || abs(ly) > 8000)
            } else {
                rx = (dx / st.radiusPx * 32767f).toInt().coerceIn(-32768, 32767)
                ry = (dy / st.radiusPx * 32767f).toInt().coerceIn(-32768, 32767)
                // RS (R3) NÃO é auto-ativado: em console o clique do analógico
                // direito costuma ser "reset de câmera" — dispará-lo a cada pan
                // lutaria contra o jogador. Sem análogo touch para clique real.
            }
            fire()
            invalidate()
            return
        }
        // D-pad vivo: direção re-avaliada durante o arrasto.
        val asg = assignments[pointerId] ?: return
        if (asg.pad.kind != Kind.DPAD) return
        val newMask = dpadMaskAt(asg.pad, x, y)
        if (newMask != asg.mask) {
            asg.mask = newMask
            syncButtonBitsFromAssignments()
            fire()
            invalidate()
        }
    }

    private fun releasePointer(pointerId: Int) {
        stickL?.let {
            if (it.pointerId == pointerId) {
                stickL = null
                lx = 0; ly = 0
                setButtonBit(BIT_LS, false)
                fire()
                invalidate()
                return
            }
        }
        stickR?.let {
            if (it.pointerId == pointerId) {
                stickR = null
                rx = 0; ry = 0
                fire()
                invalidate()
                return
            }
        }
        val asg = assignments.remove(pointerId) ?: return
        // Gatilho só libera se NENHUM outro dedo o segura.
        if (asg.trigger == BIT_LT && assignments.values.none { it.trigger == BIT_LT }) lt = 0
        if (asg.trigger == BIT_RT && assignments.values.none { it.trigger == BIT_RT }) rt = 0
        syncButtonBitsFromAssignments()
        fire()
        invalidate()
    }

    private fun releaseAll() {
        stickL = null
        stickR = null
        assignments.clear()
        lx = 0; ly = 0; rx = 0; ry = 0; lt = 0; rt = 0
        for (i in buttons.indices) buttons[i] = 0
        fire(force = true)
        invalidate()
    }

    /**
     * Bits de botão = união das máscaras vivas (dois dedos no mesmo botão
     * mantêm o botão pressionado; soltar um não derruba o outro). Bits de
     * stick-click (LS/RS) ficam de fora — são da lógica dos analógicos.
     */
    private fun syncButtonBitsFromAssignments() {
        var union = 0
        for (a in assignments.values) union = union or a.mask
        for (b in 0 until 15) {
            if (b == BIT_LS || b == BIT_RS) continue
            setButtonBit(b, union and (1 shl b) != 0)
        }
    }

    private fun setButtonBit(bit: Int, pressed: Boolean) {
        buttons[bit] = if (pressed) 1 else 0
    }

    /**
     * Estado consolidado → SDL (P1 virtual) via JNI. Só transmite quando o
     * estado muda de fato (detecção explícita) — MOVE sem variação não paga
     * JNI+mutex. [force] garante o envio do estado zero (releaseAll).
     */
    private fun fire(force: Boolean = false) {
        if (!NativeBridge.loaded) return
        var mask = 0
        for (i in 0 until 15) {
            if (buttons[i] != 0) mask = mask or (1 shl i)
        }
        if (!force && mask == lastMask && lx == lastLx && ly == lastLy &&
            rx == lastRx && ry == lastRy && lt == lastLt && rt == lastRt
        ) return
        lastMask = mask
        lastLx = lx; lastLy = ly
        lastRx = rx; lastRy = ry
        lastLt = lt; lastRt = rt
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
    // Config dinâmica (aplicada ao vivo pelo diálogo de ajustes rápidos)
    // ------------------------------------------------------------------

    fun setOpacity(v: Float) {
        opacity = v.coerceIn(MIN_OPACITY, MAX_OPACITY)
        invalidate()
    }

    fun setScale(v: Float) {
        scale = v.coerceIn(0.7f, 1.6f)
        controlsCache = controls(viewW.toInt(), viewH.toInt())
        // Re-ancora toques ativos nos novos centros/raios (o diálogo pode
        // mudar a escala com um stick preso por outro dedo).
        for (pad in controlsCache) {
            if (pad.kind != Kind.STICK) continue
            val st = (if (pad.id == "STICK") stickL else stickR) ?: continue
            st.centerX = pad.cx * viewW
            st.centerY = pad.cy * viewH
            st.radiusPx = pad.radius * 0.62f
            // Re-clampa o polegar e re-deriva os eixos a partir da posição atual.
            movePointer(st.pointerId, st.centerX + st.thumbX, st.centerY + st.thumbY)
        }
        invalidate()
    }

    fun setHaptics(v: Boolean) {
        haptics = v
    }

    fun shutdown() {
        releaseAll()
    }
}
