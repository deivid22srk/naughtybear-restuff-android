package com.deivid22srk.restuff.game

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.deivid22srk.restuff.settings.PortSettings
import kotlin.math.roundToInt

/**
 * Diálogos do jogo — design "Mel & Carvão" (a mesma identidade do launcher).
 *
 * Construídos com Views de plataforma (sem Compose): a [GameActivity] estende
 * SDLActivity, que é uma Activity pura — ComposeView exigiria donos de
 * lifecycle manuais. O visual vem da paleta do port: carvão translúcido,
 * âmbar do urso (#F2C14E), tipografia sans-serif-medium e cantos generosos.
 *
 * - [QuickSettingsDialog]: painel de ajustes do overlay (aberto com 4 dedos
 *   em qualquer ponto da tela): controles na tela, opacidade, tamanho,
 *   vibração, contador de FPS. Mudanças aplicam AO VIVO (o jogo continua
 *   rodando atrás) e persistem nas mesmas chaves da tela de Configurações.
 * - [ExitConfirmDialog]: confirmação do botão voltar — "voltar para a tela
 *   inicial?".
 */

// Paleta Mel & Carvão (espelha PortBranding sem depender de Compose).
private const val COL_BG = 0xF20B0B10.toInt()        // carvão 95%
private const val COL_ROW = 0x0D14131A                // linhas internas
private const val COL_AMBER = 0xFFF2C14E.toInt()     // amarelo pelúcia
private const val COL_AMBER_DIM = 0x66F2C14E.toInt()  // âmbar 40%
private const val COL_AMBER_DEEP = 0xFF8C5A18.toInt()
private const val COL_TEXT = 0xFFEDEFF4.toInt()
private const val COL_TEXT_DIM = 0xFF9AA0AD.toInt()
private const val COL_SECTION = 0xFF8A8F9E.toInt()
private const val COL_TRACK_OFF = 0xFF2A2C36.toInt()
private const val COL_THUMB_OFF = 0xFF6A6E7A.toInt()

private fun dp(ctx: Context, v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

// Nota: tamanhos de texto são passados como SP cru em `textSize = Xf`
// (TextView.setTextSize(Float) interpreta o valor como SP) — nunca como px
// convertido via applyDimension, que seria escalado DUAS vezes pelo sistema.

private fun cardBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(ctx, 26f).toFloat()
    setColor(COL_BG)
    setStroke(dp(ctx, 1f), COL_AMBER_DIM)
}

private fun pill(ctx: Context, fill: Int, stroke: Int? = null): RippleDrawable {
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, 14f).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(ctx, 1f), it) }
    }
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
}

private fun hairline(ctx: Context): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
    ).apply { topMargin = dp(ctx, 18f); bottomMargin = dp(ctx, 4f) }
    background = GradientDrawable().apply { setColor(0x1EFFFFFF) }
}

private fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    setTextColor(COL_SECTION)
    textSize = 11f
    letterSpacing = 0.14f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(ctx, 14f); bottomMargin = dp(ctx, 2f) }
}

// ---------------------------------------------------------------------------
// Diálogo de ajustes rápidos do overlay (4 dedos)
// ---------------------------------------------------------------------------

/**
 * @param initial  configurações atuais (carregadas na abertura)
 * @param onChange invocado a CADA mudança (ao vivo): quem hospeda aplica no
 *                 overlay/FPS e persiste
 */
class QuickSettingsDialog(
    context: Context,
    initial: PortSettings,
    private val onChange: (PortSettings) -> Unit,
    private val onExitRequested: () -> Unit,
) : Dialog(context) {

    private var current = initial

    init {
        setCanceledOnTouchOutside(true)
        buildContent()
    }

    override fun show() {
        super.show()
        val metrics = context.resources.displayMetrics
        val w = (metrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(context, 560f))
        window?.apply {
            // Mesmas flags immersive da janela do jogo (SDL): sem isto, as
            // barras de sistema reaparecem por cima do jogo enquanto o
            // painel estiver aberto.
            decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun mutate(next: PortSettings) {
        current = next
        onChange(current)
    }

    private fun buildContent() {
        val c = context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 24f), dp(c, 22f), dp(c, 24f), dp(c, 18f))
            background = cardBg(c)
        }

        // ---- Cabeçalho ----------------------------------------------------
        root.addView(TextView(c).apply {
            text = "AJUSTES RÁPIDOS"
            setTextColor(COL_AMBER)
            textSize = 15f
            letterSpacing = 0.10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        root.addView(TextView(c).apply {
            text = "Toque com 4 dedos em qualquer lugar para abrir"
            setTextColor(COL_TEXT_DIM)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 4f) }
        })

        root.addView(hairline(c))

        // ---- Seção: Controles ---------------------------------------------
        root.addView(sectionLabel(c, "CONTROLES"))

        root.addView(switchRow(
            c, "Controles na tela", "Botões virtuais sobre o jogo",
            current.showOverlayControls
        ) { checked -> mutate(current.copy(showOverlayControls = checked)) })

        root.addView(sliderRow(
            c, "Opacidade do overlay",
            (current.overlayOpacity * 100).roundToInt().coerceIn(20, 100),
            20, 100
        ) { pct -> mutate(current.copy(overlayOpacity = pct / 100f)) })

        root.addView(sliderRow(
            c, "Tamanho dos controles",
            (current.overlayScale * 100).roundToInt().coerceIn(70, 160),
            70, 160
        ) { pct -> mutate(current.copy(overlayScale = pct / 100f)) })

        root.addView(switchRow(
            c, "Vibração", "Feedback tátil dos controles virtuais",
            current.hapticFeedback
        ) { checked -> mutate(current.copy(hapticFeedback = checked)) })

        root.addView(hairline(c))

        // ---- Seção: Diagnóstico -------------------------------------------
        root.addView(sectionLabel(c, "DIAGNÓSTICO"))

        root.addView(switchRow(
            c, "Contador de FPS", "Quadros por segundo reais do motor, sobre o jogo",
            current.showFpsCounter
        ) { checked -> mutate(current.copy(showFpsCounter = checked)) })

        // ---- Ações --------------------------------------------------------
        root.addView(TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52f)
            ).apply { topMargin = dp(c, 20f) }
            text = "Voltar ao jogo"
            gravity = Gravity.CENTER
            setTextColor(0xFF141006.toInt())
            textSize = 14f
            letterSpacing = 0.02f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pill(c, COL_AMBER)
            setOnClickListener { dismiss() }
        })

        root.addView(TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(c, 12f)
            }
            text = "Sair para a tela inicial"
            setTextColor(COL_TEXT_DIM)
            textSize = 12f
            minHeight = dp(c, 48f)
            gravity = Gravity.CENTER
            setPadding(dp(c, 10f), dp(c, 14f), dp(c, 10f), dp(c, 14f))
            setOnClickListener {
                dismiss()
                onExitRequested()
            }
        })

        setContentView(root)
    }

    // ---- Linhas reutilizáveis ---------------------------------------------

    private fun switchRow(
        c: Context,
        label: String,
        subtitle: String,
        checked: Boolean,
        onUpdate: (Boolean) -> Unit,
    ): LinearLayout {
        val labelView = TextView(c).apply {
            text = label
            setTextColor(COL_TEXT)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subtitleView = TextView(c).apply {
            text = subtitle
            setTextColor(COL_TEXT_DIM)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 2f) }
        }
        val switch = Switch(c).apply {
            id = View.generateViewId()
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COL_AMBER, COL_THUMB_OFF)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COL_AMBER_DIM, COL_TRACK_OFF)
            )
            setOnCheckedChangeListener { _, value -> onUpdate(value) }
        }
        labelView.labelFor = switch.id
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 14f), dp(c, 10f), dp(c, 14f), dp(c, 10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(c, 14f).toFloat()
                setColor(COL_ROW)
            }
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(labelView)
                addView(subtitleView)
            })
            addView(switch)
        }
    }

    private fun sliderRow(
        c: Context,
        label: String,
        initialPct: Int,
        minPct: Int,
        maxPct: Int,
        onUpdate: (Int) -> Unit,
    ): LinearLayout {
        val valueView = TextView(c).apply {
            text = "$initialPct%"
            setTextColor(COL_AMBER)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(c).apply {
                text = label
                setTextColor(COL_TEXT)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView)
        }
        val seek = SeekBar(c).apply {
            min = minPct
            max = maxPct
            progress = initialPct
            progressTintList = ColorStateList.valueOf(COL_AMBER)
            progressBackgroundTintList = ColorStateList.valueOf(COL_TRACK_OFF)
            thumbTintList = ColorStateList.valueOf(COL_AMBER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        valueView.text = "$value%"
                        onUpdate(value)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(c, 14f).toFloat()
                setColor(COL_ROW)
            }
            addView(header)
            addView(seek)
        }
    }
}

// ---------------------------------------------------------------------------
// Diálogo de confirmação do botão voltar
// ---------------------------------------------------------------------------

/**
 * "Deseja voltar para a tela inicial?" — o jogo é encerrado (Activity
 * finalizada) e o usuário retorna ao launcher do port.
 */
class ExitConfirmDialog(
    context: Context,
    private val onExit: () -> Unit,
) : Dialog(context) {

    init {
        setCanceledOnTouchOutside(true)
        buildContent()
    }

    override fun show() {
        super.show()
        val metrics = context.resources.displayMetrics
        val w = (metrics.widthPixels * 0.80f).toInt().coerceAtMost(dp(context, 480f))
        window?.apply {
            // Mesmas flags immersive da janela do jogo (SDL) — ver QuickSettingsDialog.
            decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun buildContent() {
        val c = context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 24f), dp(c, 22f), dp(c, 24f), dp(c, 20f))
            background = cardBg(c)
        }

        root.addView(TextView(c).apply {
            text = "Voltar para a tela inicial?"
            setTextColor(COL_TEXT)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        root.addView(TextView(c).apply {
            text = "O jogo será encerrado e você voltará ao menu do port. O progresso não salvo será perdido."
            setTextColor(COL_TEXT_DIM)
            textSize = 13f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = dp(c, 20f)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 10f) }
        })

        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 22f) }
            // Continuar jogando (contorno âmbar) — fecha e segue.
            addView(TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(c, 48f), 1f).apply {
                    marginEnd = dp(c, 10f)
                }
                text = "Continuar jogando"
                gravity = Gravity.CENTER
                setTextColor(COL_AMBER)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pill(c, Color.TRANSPARENT, COL_AMBER_DIM)
                setOnClickListener { dismiss() }
            })
            // Voltar (âmbar sólido) — sai para o launcher.
            addView(TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(c, 48f), 1f)
                text = "Voltar"
                gravity = Gravity.CENTER
                setTextColor(0xFF141006.toInt())
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pill(c, COL_AMBER)
                setOnClickListener {
                    dismiss()
                    onExit()
                }
            })
        })

        setContentView(root)
    }
}
