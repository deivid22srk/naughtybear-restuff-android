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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.settings.FpsLimitOption
import com.deivid22srk.restuff.settings.PortSettings
import com.deivid22srk.restuff.ui.theme.PortPalette
import kotlin.math.roundToInt

/**
 * Diálogos do jogo — design "Mel & Carvão", FLAT EDITORIAL.
 *
 * Espelho EM VIEWS DE PLATAFORMA dos mesmos primitivos da tela de
 * Configurações (SettingsScreen.kt / PortScreenTheme.kt): a mesma paleta
 * exata (carvão de superfície, texto off-white quente, um único acento
 * âmbar), a mesma tipografia (rótulos em versalete com letter-spacing,
 * labels 15sp medium, subs 12sp, chips 12.5sp semibold), a mesma GRAMÁTICA
 * visual — SEM caixas/cartões por linha: hairlines entre linhas, respiro
 * de 30dp entre seções, UM alvo de peso por diálogo (o CTA âmbar).
 *
 * Construído com Views de plataforma (sem Compose): a [GameActivity]
 * estende SDLActivity, uma Activity pura — ComposeView exigiria donos de
 * lifecycle manuais.
 *
 * - [QuickSettingsDialog]: painel de ajustes (aberto com 4 dedos em qualquer
 *   ponto da tela): TELA (tela cheia mobile-style, contador de FPS),
 *   CONTROLES (overlay, opacidade, tamanho, vibração) e DESEMPENHO (limite
 *   de FPS em chips — aplica ao vivo no cvar fps_cap do motor). Mudanças
 *   aplicam AO VIVO (o jogo continua rodando atrás) e persistem nas mesmas
 *   chaves da tela de Configurações (gravação única no fechamento —
 *   debounce).
 * - [ExitConfirmDialog]: confirmação do botão voltar — "voltar para a tela
 *   inicial?".
 *
 * ESTRUTURA do painel (lição da 1ª versão, que cortava os botões em
 * landscape): cabeçalho e rodapé de ações são FIXOS; só o miolo rola. O
 * conteúdo tem ~620dp para ~360-420dp úteis em landscape — um ScrollView
 * solto empurraria "Voltar ao jogo" para FORA da tela (ação mais frequente,
 * a menos acessível). Agora: header fixo + [ScrollView weight=1] + ações
 * fixas — o botão primário está SEMPRE visível, em qualquer tela.
 */
// ---------------------------------------------------------------------------
// Paleta "Mel & Carvão" — LIDA DA FONTE PARAMÉTRICA (PortPalette/
// PortBranding), como toda a UI do port: um rebrand de identidade troca um
// único objeto de config e o painel acompanha. Compose Color é value class
// pura — toArgb() funciona fora de composable. (Avaliação e1: deixa de ser
// literal hardcoded.)
// ---------------------------------------------------------------------------
private val COL_SURFACE = PortPalette.surface.toArgb()      // carvão de superfície (diálogo)
private val COL_TEXT = PortPalette.textPrimary.toArgb()     // texto primário (off-white quente)
private val COL_TEXT2 = PortPalette.textSecondary.toArgb()  // texto secundário (subs, hints)
private val COL_TEXT3 = PortPalette.textTertiary.toArgb()   // texto terciário (mono, metadados)
private val COL_HAIRLINE = PortPalette.hairline.toArgb()    // branco a 8%
private val COL_GHOST = PortPalette.ghostBorder.toArgb()    // contorno de chip não-selecionado
private val COL_ACCENT = PortBranding.config.accent.toArgb()  // âmbar pelúcia (único acento)
private val COL_ON_ACCENT = PortPalette.onAccent.toArgb()    // texto sobre âmbar
private val COL_TRACK_OFF = 0x20FFFFFF   // literal do ToggleRow Compose (uncheckedTrack)
private val COL_THUMB_OFF = PortPalette.textSecondary.toArgb()  // thumb do switch desligado

// Raio (PortPalette.radius*): chips/linhas Sm=10, CTA Md=12, diálogo Lg=18.
private const val RADIUS_SM = 10f
private const val RADIUS_MD = 12f
private const val RADIUS_LG = 18f

private fun dp(ctx: Context, v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

// Nota: tamanhos de texto são passados como SP cru em `textSize = Xf`
// (TextView.setTextSize(Float) interpreta o valor como SP) — nunca como px
// convertido via applyDimension, que seria escalado DUAS vezes pelo sistema.
// Letter-spacing de TextView é em EM (fração do tamanho): 2.5sp a 10.5sp ≈
// 0.24 — o mesmo rótulo em versalete do PortType.label.

/** Fundo do diálogo: superfície carvão, raio Lg, SEM moldura (flat). */
private fun dialogBg(ctx: Context): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(ctx, RADIUS_LG).toFloat()
    setColor(COL_SURFACE)
}

/**
 * Pílula clicável (chips, CTA, botão fantasma): ripple branco suave sobre o
 * fill pedido. O mesmo papel do portClickable + background da SettingsScreen.
 */
private fun pill(ctx: Context, fill: Int, stroke: Int? = null, radius: Float = RADIUS_SM): RippleDrawable {
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radius).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(ctx, 1f), it) }
    }
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
}

/** Hairline entre linhas de uma seção (branco a 8%, 1dp) — sem caixas. */
private fun hairline(ctx: Context): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
    )
    background = GradientDrawable().apply { setColor(COL_HAIRLINE) }
}

/** Respiro entre seções (30dp — mantém a hierarquia sem criar caixas). */
private fun sectionGap(ctx: Context): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 30f)
    )
}

/**
 * "LINHA DO MEL" — filete horizontal de 2dp no accent, ~28dp de largura
 * (gesto nº 1 do design system: o acento é uma pincelada, não uma moldura).
 * Separa a identidade (título) do conteúdo.
 */
private fun melLine(ctx: Context): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 2f)).apply {
        topMargin = dp(ctx, 12f)
    }
    background = GradientDrawable().apply { setColor(COL_ACCENT) }
}

/**
 * Cabeçalho de seção: ponto âmbar de 4dp + rótulo em versalete (10.5sp
 * semibold, letter-spacing 2.5sp, texto secundário) — idem SectionHeader.
 */
private fun sectionHeader(ctx: Context, text: String): LinearLayout {
    val label = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(COL_TEXT2)
        textSize = 10.5f
        letterSpacing = 0.24f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 4f) }
        addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 4f), dp(ctx, 4f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COL_ACCENT)
            }
        })
        addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 8f), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(label)
    }
}

/** Rótulo de configuração dentro da seção (versalete) — idem SettingLabel. */
private fun settingLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text.uppercase()
    setTextColor(COL_TEXT2)
    textSize = 10.5f
    letterSpacing = 0.24f
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

/** Texto de rodapé de bloco (nota terciária) — idem rowSub (12sp/16sp). */
private fun noteText(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    setTextColor(COL_TEXT3)
    textSize = 12f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lineHeight = dp(ctx, 16f)
    }
}

// ---------------------------------------------------------------------------
// Diálogo de ajustes rápidos do overlay (4 dedos)
// ---------------------------------------------------------------------------

/**
 * @param initial  configurações atuais (carregadas na abertura)
 * @param onChange invocado a CADA mudança (ao vivo): quem hospeda aplica no
 *                 overlay/FPS/limite de FPS/tela cheia e decide quando persistir
 * @param onExitRequested chamado pelo atalho "Sair para a tela inicial"
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
        // Altura explícita: o miolo é um ScrollView weight=1 (cabeçalho e
        // ações fixos) — com WRAP_CONTENT a janela mediria o ScrollView como
        // 0dp e o painel colapsaria. 92% da tela, no máximo 720dp (tablets).
        val h = (metrics.heightPixels * 0.92f).toInt().coerceAtMost(dp(context, 720f))
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
            setLayout(w, h)
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
            setPadding(dp(c, 22f), dp(c, 20f), dp(c, 22f), dp(c, 16f))
            background = dialogBg(c)
        }

        // ---- Cabeçalho (fixo): título + linha do mel ----------------------
        root.addView(TextView(c).apply {
            text = "Ajustes rápidos"
            setTextColor(COL_TEXT)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(c).apply {
            text = "Mudanças aplicam na hora, o jogo segue rodando"
            setTextColor(COL_TEXT2)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 2f) }
        })
        root.addView(melLine(c))

        // ---- Miolo rolável ---------------------------------------------------
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 18f)
            )
        })

        // ============================ TELA =============================
        body.addView(sectionHeader(c, "TELA"))
        body.addView(toggleRow(
            c, "Tela cheia",
            "Preenche toda a tela do aparelho (estica além de 16:9), como " +
                "um jogo mobile — sem as barras pretas",
            current.fullscreenStretch
        ) { checked -> mutate(current.copy(fullscreenStretch = checked)) })
        body.addView(hairline(c))
        body.addView(toggleRow(
            c, "Contador de FPS",
            "Quadros por segundo reais do motor, sobre o jogo",
            current.showFpsCounter
        ) { checked -> mutate(current.copy(showFpsCounter = checked)) })

        body.addView(sectionGap(c))

        // ========================== CONTROLES ==========================
        body.addView(sectionHeader(c, "CONTROLES"))
        body.addView(toggleRow(
            c, "Controles na tela", "Botões virtuais sobre o jogo",
            current.showOverlayControls
        ) { checked -> mutate(current.copy(showOverlayControls = checked)) })
        body.addView(hairline(c))
        body.addView(sliderRow(
            c, "Opacidade do overlay",
            (current.overlayOpacity * 100).roundToInt().coerceIn(20, 100),
            20, 100
        ) { pct -> mutate(current.copy(overlayOpacity = pct / 100f)) })
        body.addView(hairline(c))
        body.addView(sliderRow(
            c, "Tamanho dos controles",
            (current.overlayScale * 100).roundToInt().coerceIn(70, 160),
            70, 160
        ) { pct -> mutate(current.copy(overlayScale = pct / 100f)) })
        body.addView(hairline(c))
        body.addView(toggleRow(
            c, "Vibração", "Feedback tátil dos controles virtuais",
            current.hapticFeedback
        ) { checked -> mutate(current.copy(hapticFeedback = checked)) })

        body.addView(sectionGap(c))

        // ========================= DESEMPENHO ==========================
        body.addView(sectionHeader(c, "DESEMPENHO"))
        body.addView(settingLabel(c, "Limite de FPS"))
        body.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 10f)
            )
        })
        body.addView(fpsLimitRow(c))
        body.addView(noteText(
            c, "Aplica na hora — também vale nas Configurações do app"
        ))
        body.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 18f)
            )
        })
        // M4.39: resolução/aniso exigem reiniciar (SceneScale resolve uma vez
        // no boot) — mostra o valor ativo e aponta para as Configurações em
        // vez de oferecer um controle que não aplicaria ao vivo.
        body.addView(noteText(
            c, "Resolução interna: ${current.resScale.short} · Aniso ${current.aniso.label} — " +
                "para trocar, volte à tela inicial → Configurações → Desempenho " +
                "(vale no próximo início do jogo)."
        ))

        root.addView(ScrollView(c).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        // ---- Rodapé de ações (fixo — sempre visível) ------------------------
        // ALVO ÚNICO de peso do diálogo (gesto nº 3 do design system): o CTA
        // âmbar sólido "Voltar ao jogo".
        root.addView(TextView(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48f)
            ).apply { topMargin = dp(c, 14f) }
            text = "Voltar ao jogo"
            gravity = Gravity.CENTER
            setTextColor(COL_ON_ACCENT)
            textSize = 14.5f
            letterSpacing = 0.03f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = pill(c, COL_ACCENT, radius = RADIUS_MD)
            setOnClickListener { dismiss() }
        })
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 10f) }
            addView(TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                text = "4 dedos em qualquer lugar reabrem este painel"
                setTextColor(COL_TEXT3)
                textSize = 10.5f
                maxLines = 2
            })
            addView(TextView(c).apply {
                text = "Sair para a tela inicial"
                setTextColor(COL_TEXT2)
                textSize = 12.5f
                letterSpacing = 0.03f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                minHeight = dp(c, 48f)
                gravity = Gravity.CENTER
                setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
                background = pill(c, Color.TRANSPARENT, COL_GHOST, radius = RADIUS_MD)
                setOnClickListener {
                    dismiss()
                    onExitRequested()
                }
            })
        })

        setContentView(root)
    }

    // ---- Linhas reutilizáveis (espelham ToggleRow/SliderRow da
    // SettingsScreen — linha inteira clicável, hairline separa, sem caixa) ----

    private fun toggleRow(
        c: Context,
        label: String,
        subtitle: String,
        checked: Boolean,
        onUpdate: (Boolean) -> Unit,
    ): LinearLayout {
        val switch = Switch(c).apply {
            id = View.generateViewId()
            isChecked = checked
            // Cores do ToggleRow do Compose: track âmbar quando ligado (com
            // thumb "on accent"), track branco 12% + thumb secundário quando
            // desligado.
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COL_ON_ACCENT, COL_THUMB_OFF)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COL_ACCENT, COL_TRACK_OFF)
            )
            // TalkBack: o Switch é o NÓ ÚNICO da linha (equivalente do
            // merge + Role.Switch do Compose) — label e subtítulo fundem no
            // contentDescription e o ESTADO ligado/desligado é anunciado
            // pelo próprio widget.
            contentDescription = "$label — $subtitle"
            setOnCheckedChangeListener { _, value -> onUpdate(value) }
            // Haptic também no tap DIRETO no widget (tap na linha vibra no
            // listener da row; tap no switch não passa por ele). O
            // performClick do CompoundButton faz o toggle E chama este
            // listener — ordem segura, sem duplo haptic em nenhum caminho.
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(c, 56f)
            setPadding(0, dp(c, 8f), 0, dp(c, 8f))
            // Linha INTEIRA clicável (idem portClickable do ToggleRow): alvo
            // de toque confortável em qualquer densidade. Haptic idem
            // (HapticFeedbackType.LongPress do portClickable).
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                switch.toggle()
            }
            // A travessia de acessibilidade passa SÓ pelo switch: sem isto o
            // TalkBack leria a linha ("toque duplo para ativar", SEM estado)
            // E o switch separado — dois nós, nenhum com o estado certo.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(c).apply {
                    text = label
                    setTextColor(COL_TEXT)
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    // Fundido no contentDescription do switch (acima).
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(TextView(c).apply {
                    text = subtitle
                    setTextColor(COL_TEXT2)
                    textSize = 12f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(c, 2f) }
                })
            })
            addView(switch)
        }
        return row
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
            setTextColor(COL_ACCENT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            // O valor muda a cada tick do arrasto: fora da travessia do
            // TalkBack (o SeekBar abaixo anuncia o progresso por conta).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(c).apply {
                text = label
                setTextColor(COL_TEXT)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                // Fundido no contentDescription do SeekBar abaixo (e2: sem
                // isto o TalkBack anunciava o label DUAS vezes — nó texto +
                // nó slider).
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueView)
        }
        val seek = SeekBar(c).apply {
            min = minPct
            max = maxPct
            progress = initialPct
            progressTintList = ColorStateList.valueOf(COL_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(COL_TRACK_OFF)
            thumbTintList = ColorStateList.valueOf(COL_ACCENT)
            contentDescription = label
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
            setPadding(0, dp(c, 12f), 0, dp(c, 10f))
            addView(header)
            addView(seek)
        }
    }

    /**
     * Limite de FPS — chips de seleção única (idem ChoiceChipsRow:
     * rolagem horizontal, nunca corta; alvo de 48dp; selecionado = âmbar
     * sólido + texto "on accent", não-selecionado = contorno fantasma).
     * A escolha aplica AO VIVO via cvar fps_cap (JNI): o limiter do
     * guest e o pacing do present thread leem o valor por frame — nada de
     * reiniciar o jogo. Persiste junto com o resto do painel (mesma chave
     * das Configurações, gravada no fechamento → restuff.toml no próximo
     * boot).
     */
    private fun fpsLimitRow(c: Context): HorizontalScrollView {
        val chips = ArrayList<Pair<TextView, FpsLimitOption>>()

        fun repaint() {
            for ((chip, opt) in chips) {
                val selected = opt == current.fpsLimit
                (chip.background as? RippleDrawable)?.let { ripple ->
                    (ripple.getDrawable(0) as? GradientDrawable)?.apply {
                        setColor(if (selected) COL_ACCENT else Color.TRANSPARENT)
                        setStroke(
                            dp(c, 1f),
                            if (selected) Color.TRANSPARENT else COL_GHOST
                        )
                    }
                }
                chip.setTextColor(if (selected) COL_ON_ACCENT else COL_TEXT2)
                // SemiBold em ambos os estados (idem PortType.chip): quem
                // distingue seleção é o fundo âmbar + cor do texto.
                chip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                chip.contentDescription = if (opt == FpsLimitOption.UNLIMITED) {
                    "Limite de FPS ilimitado"
                } else {
                    "Limite de FPS em ${opt.label} quadros por segundo"
                }
                // Estado selecionado para TalkBack (focus/a11y) além do
                // contentDescription.
                chip.isSelected = selected
            }
        }

        val chipsRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (opt in FpsLimitOption.entries) {
            val chip = TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 48f)
                ).apply { marginEnd = dp(c, 8f) }
                text = if (opt == FpsLimitOption.UNLIMITED) "∞" else opt.label
                gravity = Gravity.CENTER
                textSize = 12.5f
                letterSpacing = 0.03f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(c, 18f), 0, dp(c, 18f), 0)
                background = pill(c, Color.TRANSPARENT, COL_GHOST, radius = RADIUS_SM)
                setOnClickListener {
                    if (current.fpsLimit != opt) {
                        mutate(current.copy(fpsLimit = opt))
                        repaint()
                    }
                }
            }
            chips.add(chip to opt)
            chipsRow.addView(chip)
        }
        repaint()

        return HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(chipsRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }
}

// ---------------------------------------------------------------------------
// Diálogo de confirmação do botão voltar
// ---------------------------------------------------------------------------

/**
 * "Deseja voltar para a tela inicial?" — o jogo é encerrado (Activity
 * finalizada) e o usuário retorna ao launcher do port. Mesma gramática flat
 * do painel de ajustes: superfície carvão raio Lg, linha do mel, CTA âmbar
 * como alvo único (a ação destrutiva fica no sólido; continuar é fantasma).
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
            background = dialogBg(c)
        }

        root.addView(TextView(c).apply {
            text = "Voltar para a tela inicial?"
            setTextColor(COL_TEXT)
            // Mesmo token do título do painel (PortType.titleScreen: 22sp
            // Medium) — avaliação e1: fora da escala 18sp Bold.
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(melLine(c))
        root.addView(TextView(c).apply {
            text = "O jogo será encerrado e você voltará ao menu do port. O progresso não salvo será perdido."
            setTextColor(COL_TEXT2)
            textSize = 13f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = dp(c, 18f)  // PortType.caption: 13sp/18sp
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 12f) }
        })

        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(c, 22f) }
            // Continuar jogando (fantasma) — fecha e segue.
            addView(TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(c, 48f), 1f).apply {
                    marginEnd = dp(c, 10f)
                }
                text = "Continuar jogando"
                gravity = Gravity.CENTER
                setTextColor(COL_TEXT2)
                textSize = 12.5f
                letterSpacing = 0.03f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pill(c, Color.TRANSPARENT, COL_GHOST, radius = RADIUS_MD)
                setOnClickListener { dismiss() }
            })
            // Voltar (âmbar sólido — alvo único) — sai para o launcher.
            addView(TextView(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(c, 48f), 1f)
                text = "Voltar"
                gravity = Gravity.CENTER
                setTextColor(COL_ON_ACCENT)
                textSize = 12.5f
                letterSpacing = 0.03f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = pill(c, COL_ACCENT, radius = RADIUS_MD)
                setOnClickListener {
                    dismiss()
                    onExit()
                }
            })
        })

        setContentView(root)
    }
}
