/*
 * Tela de Configurações do port — design "Mel & Carvão" (flat editorial).
 *
 * Sem painéis de vidro e sem ícones por seção: rótulos em versalete com
 * ponto âmbar, linhas separadas por hairline, UM alvo de peso (o controle
 * da linha). Tudo é persistido por [PortSettingsViewModel] e DE FATO
 * consumido: os campos do motor viram cvars do restuff.toml/argv (ver
 * GameActivity.getArguments()), os de controles alimentam o
 * VirtualGamepadView, e os de driver/diagnóstico operam o GpuDriverManager
 * e o log persistido. O contador de FPS (novo) lê os presents Vulkan reais
 * via JNI (nativeGetPresentCount).
 */
package com.deivid22srk.restuff.ui.settings

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.data.GpuDriverManager
import com.deivid22srk.restuff.settings.FpsLimitOption
import com.deivid22srk.restuff.settings.PortSettings
import com.deivid22srk.restuff.settings.PortSettingsViewModel
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import com.deivid22srk.restuff.ui.components.portClickable
import com.deivid22srk.restuff.viewmodel.DataPhase
import com.deivid22srk.restuff.viewmodel.DataSelectionUiState
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel
import kotlin.math.roundToInt

/**
 * Rota da tela de Configurações: conecta os dois ViewModels (preferências +
 * seleção de dados) e o estado de "reduzir movimento" do sistema.
 */
@Composable
fun SettingsRoute(
    settingsViewModel: PortSettingsViewModel,
    selectionViewModel: DataSelectionViewModel,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val selectionState by selectionViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val systemReducedMotion = remember {
        val resolver = context.contentResolver
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }

    SettingsScreen(
        settings = settings,
        selectionState = selectionState,
        systemReducedMotion = systemReducedMotion,
        onSettingsChange = settingsViewModel::update,
        onClearSelection = selectionViewModel::clearSavedSelection,
        onBack = onBack
    )
}

@Composable
fun SettingsScreen(
    settings: PortSettings,
    selectionState: DataSelectionUiState,
    systemReducedMotion: Boolean,
    onSettingsChange: ((PortSettings) -> PortSettings) -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit,
) {
    val config = PortBranding.config
    val accent = config.accent
    val reduceMotion = systemReducedMotion || settings.reduceMotionOverride

    Column(
        Modifier
            .fillMaxSize()
            .background(PortPalette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ---- Cabeçalho: voltar + título, hairline abaixo -------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .portClickable(haptic = true) { onBack() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = config.contentDescBack,
                    tint = PortPalette.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = config.labelSettingsTitle,
                    color = PortPalette.textPrimary,
                    style = PortType.titleScreen
                )
                Text(
                    text = config.labelSettingsSubtitle,
                    color = PortPalette.textTertiary,
                    style = PortType.rowSub
                )
            }
        }

        // ---- Conteúdo rolável ----------------------------------------------
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // ======================== DESEMPENHO =========================
            // Aplicado de verdade: fps_cap e vblank_hz no restuff.toml
            // gerado pelo GameActivity antes do SDL_main. O contador de
            // FPS lê os presents Vulkan reais (JNI nativeGetPresentCount).
            SectionHeader("Desempenho")

            ToggleRow(
                label = "Contador de FPS",
                subtitle = "Quadros por segundo reais do motor, sobre o jogo",
                checked = settings.showFpsCounter,
                onChange = { checked ->
                    onSettingsChange { it.copy(showFpsCounter = checked) }
                }
            )
            Hairline()

            SettingLabel("Limite de FPS")
            Spacer(Modifier.height(10.dp))
            ChoiceChipsRow(
                options = FpsLimitOption.entries.toList(),
                selected = settings.fpsLimit,
                label = { it.label }
            ) { option ->
                onSettingsChange { it.copy(fpsLimit = option) }
            }

            Spacer(Modifier.height(18.dp))
            SliderRow(
                label = "Vblank sintético",
                valueText = "${settings.vblankHz} Hz",
                value = settings.vblankHz.toFloat(),
                valueRange = 30f..240f,
                steps = 20
            ) { value ->
                onSettingsChange { it.copy(vblankHz = value.roundToInt()) }
            }
            Text(
                text = "Relógio de vídeo do motor — ajuste fino para painéis " +
                    "de alta taxa de atualização.",
                color = PortPalette.textTertiary,
                style = PortType.rowSub
            )

            SectionGap()

            // ========================= CONTROLES =========================
            SectionHeader("Controles")

            ToggleRow(
                label = "Overlay na tela",
                subtitle = "Botões virtuais sobre o jogo",
                checked = settings.showOverlayControls,
                onChange = { checked ->
                    onSettingsChange { it.copy(showOverlayControls = checked) }
                }
            )
            Hairline()

            SliderRow(
                label = "Opacidade do overlay",
                valueText = "${(settings.overlayOpacity * 100).roundToInt()}%",
                value = settings.overlayOpacity,
                valueRange = 0.2f..1f,
                steps = 7
            ) { value ->
                onSettingsChange { it.copy(overlayOpacity = value) }
            }
            Hairline()

            ToggleRow(
                label = "Vibração",
                subtitle = "Feedback tátil dos controles virtuais",
                checked = settings.hapticFeedback,
                onChange = { checked ->
                    onSettingsChange { it.copy(hapticFeedback = checked) }
                }
            )
            Hairline()

            SliderRow(
                label = "Tamanho dos controles",
                valueText = "${(settings.overlayScale * 100).roundToInt()}%",
                value = settings.overlayScale,
                valueRange = 0.7f..1.6f,
                steps = 8
            ) { value ->
                onSettingsChange { it.copy(overlayScale = value) }
            }

            SectionGap()

            // ========================= MOTOR RESTUFF =====================
            SectionHeader("Motor ReStuff")

            ToggleRow(
                label = "Desbloquear 60 FPS",
                subtitle = "Força o jogo a rodar a 60 quadros por segundo",
                checked = settings.unlock60Fps,
                onChange = { checked ->
                    onSettingsChange { it.copy(unlock60Fps = checked) }
                }
            )
            Hairline()

            ToggleRow(
                label = "Unlock All (cheat)",
                subtitle = "Libera todos os trajes e conteúdos extras",
                checked = settings.unlockAllCheat,
                onChange = { checked ->
                    onSettingsChange { it.copy(unlockAllCheat = checked) }
                }
            )
            Hairline()

            ToggleRow(
                label = "Texture Mods (packs HD)",
                subtitle = "Usa packs de texturas em HD da pasta texture_mods",
                checked = settings.textureMods,
                onChange = { checked ->
                    onSettingsChange { it.copy(textureMods = checked) }
                }
            )

            SectionGap()

            // =========================== EFEITOS =========================
            SectionHeader("Tela inicial")

            ToggleRow(
                label = config.labelToggleMotion,
                subtitle = "Desativa as animações de entrada",
                checked = settings.reduceMotionOverride,
                onChange = { checked ->
                    onSettingsChange { it.copy(reduceMotionOverride = checked) }
                }
            )

            SectionGap()

            // ======================== DADOS DO JOGO ======================
            SectionHeader("Dados do jogo")

            SelectionSummary(selectionState)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Arquivos esperados: " +
                    config.expectedDataFiles.joinToString(", ") +
                    config.acceptableExtensions.joinToString(", ", prefix = " · extensões: "),
                color = PortPalette.textTertiary,
                style = PortType.mono
            )
            Spacer(Modifier.height(14.dp))
            DangerButton(
                label = config.labelClearSelection,
                enabled = selectionState.phase !is DataPhase.Idle,
                onClick = onClearSelection
            )

            SectionGap()

            // ================= DRIVERS GRÁFICOS (TURNIP) =================
            DriversSection()

            SectionGap()

            // ==================== DIAGNÓSTICO ============================
            DiagnosticsSection(
                detailedLogs = settings.detailedLogs,
                onDetailedLogsChange = { checked ->
                    onSettingsChange { it.copy(detailedLogs = checked) }
                }
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = config.labelSettingsFooter,
                color = PortPalette.textTertiary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

// ======================================================================
// Primitivos do layout flat
// ======================================================================

/** Cabeçalho de seção: ponto âmbar + rótulo em versalete. */
@Composable
private fun SectionHeader(title: String) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Box(
            Modifier
                .size(4.dp)
                .background(accent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            color = PortPalette.textSecondary,
            style = PortType.label
        )
    }
}

/** Hairline entre linhas de uma seção. */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PortPalette.hairline)
    )
}

/** Respiro entre seções (mantém a hierarquia sem criar caixas). */
@Composable
private fun SectionGap() {
    Spacer(Modifier.height(30.dp))
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = PortPalette.textSecondary,
        style = PortType.label
    )
}

/** Linha de chips selecionáveis com scroll horizontal (nunca corta). */
@Composable
private fun <T> ChoiceChipsRow(
    options: List<T>,
    selected: T,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
    val accent = PortBranding.config.accent
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(PortPalette.radiusSm))
                    .background(if (isSelected) accent else Color.Transparent)
                    .border(
                        1.dp,
                        if (isSelected) Color.Transparent else PortPalette.ghostBorder,
                        RoundedCornerShape(PortPalette.radiusSm)
                    )
                    .portClickable { onSelect(option) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) PortPalette.onAccent else PortPalette.textSecondary,
                    style = PortType.chip
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .portClickable(haptic = true, role = androidx.compose.ui.semantics.Role.Switch) {
                onChange(!checked)
            }
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = PortPalette.textSecondary,
                    style = PortType.rowSub
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = PortPalette.onAccent,
                uncheckedTrackColor = Color(0x20FFFFFF),
                uncheckedThumbColor = PortPalette.textSecondary
            )
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val accent = PortBranding.config.accent
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = accent,
                style = PortType.mono.copy(fontSize = 12.sp)
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color(0x20FFFFFF)
            )
        )
    }
}

/** Resumo do estado atual da seleção de dados (ligado ao ViewModel real). */
@Composable
private fun SelectionSummary(state: DataSelectionUiState) {
    val accent = PortBranding.config.accent
    val (label, color) = when (val phase = state.phase) {
        is DataPhase.Found -> "Pronto · ${phase.fileName}" to PortPalette.success
        is DataPhase.Validating -> "Validando…" to accent
        is DataPhase.NotFound -> "A seleção salva não contém os dados esperados." to PortPalette.error
        is DataPhase.PermissionError -> "Permissão de leitura revogada." to PortPalette.error
        is DataPhase.Idle -> "Nenhuma seleção salva." to PortPalette.textSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            color = color,
            style = PortType.rowLabel
        )
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val red = PortPalette.error
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(PortPalette.radiusSm))
            .border(
                1.dp,
                red.copy(alpha = if (enabled) 0.35f else 0.12f),
                RoundedCornerShape(PortPalette.radiusSm)
            )
            .portClickable(enabled = enabled, haptic = true) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = red.copy(alpha = if (enabled) 1f else 0.4f),
            style = PortType.caption
        )
    }
}

// ======================================================================
// Drivers gráficos (padrão AdrenoTools — Turnip/Mesa)
// ======================================================================

private val DRIVER_ZIP_MIME = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed",
)

/**
 * Seção de drivers Vulkan customizados no padrão AdrenoTools: importa .zip
 * (meta.json + .so), lista, seleciona o ativo e remove. O driver ativo é
 * carregado DE VERDADE pelo motor no próximo boot do jogo (dlopen no lugar
 * do libvulkan.so do sistema — ver vulkan_instance.cpp do SDK).
 */
@Composable
private fun DriversSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = PortBranding.config.accent

    var drivers by remember { mutableStateOf(GpuDriverManager.list(context)) }
    var activeId by remember { mutableStateOf(GpuDriverManager.activeId(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // Recarrega a lista ao voltar para esta tela (ex.: driver removido
    // manualmente pelo sistema / outra instância).
    LaunchedEffect(Unit) {
        drivers = GpuDriverManager.list(context)
        activeId = GpuDriverManager.activeId(context)
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        scope.launch {
            try {
                val driver = withContext(Dispatchers.IO) {
                    GpuDriverManager.importFromZip(context, uri)
                }
                withContext(Dispatchers.IO) { GpuDriverManager.setActive(context, driver.id) }
                drivers = GpuDriverManager.list(context)
                activeId = driver.id
                message = "Driver “${driver.name}” v${driver.version} importado e ativado — " +
                    "será usado no próximo início do jogo."
                isError = false
            } catch (e: GpuDriverManager.DriverImportException) {
                message = e.message
                isError = true
            } catch (e: Exception) {
                message = "Falha ao importar driver: ${e.message ?: "erro desconhecido"}"
                isError = true
            }
            importing = false
        }
    }

    Column {
        SectionHeader("Drivers gráficos (Turnip / Vortek)")

        Text(
            text = "Importe um driver Vulkan (.zip) para melhorar a " +
                "compatibilidade e o desempenho em GPUs Adreno. Para GPUs " +
                "não-Adreno (Mali e outras), experimente a camada Vortek — " +
                "executa o jogo sobre o driver do sistema com correções de " +
                "compatibilidade. O driver é usado no próximo início do jogo; " +
                "se falhar, o motor volta ao driver do sistema e o motivo " +
                "aparece no Diagnóstico.",
            color = PortPalette.textSecondary,
            style = PortType.rowSub
        )

        Spacer(Modifier.height(14.dp))

        DriverOptionRow(
            title = "Padrão do sistema",
            subtitle = "Vulkan do fabricante do aparelho",
            selected = activeId == null,
            onSelect = {
                GpuDriverManager.clearActive(context)
                activeId = null
            },
            onDelete = null
        )
        Hairline()

        // Vortek — camada de compatibilidade Vulkan embutida (experimental).
        // Créditos: Vortek © brunodev85 (Winlator), LGPL-2.1 — ver README.
        if (GpuDriverManager.isVortekAvailable(context)) {
            DriverOptionRow(
                title = "Vortek (experimental)",
                subtitle = "Camada de compatibilidade sobre o driver do " +
                    "sistema — pensada p/ Mali e demais GPUs · © brunodev85 " +
                    "(Winlator, LGPL-2.1)",
                selected = activeId == GpuDriverManager.VORTEK_DRIVER_ID,
                onSelect = {
                    runCatching { GpuDriverManager.setActiveVortek(context) }
                        .onSuccess {
                            activeId = GpuDriverManager.VORTEK_DRIVER_ID
                            message = "Vortek ativado — camada de compatibilidade " +
                                "sobre o driver do sistema. Recomendado em GPUs " +
                                "Mali e outras não-Adreno; em Adreno, o Turnip " +
                                "direto costuma ser mais rápido. Vale no " +
                                "próximo início do jogo."
                            isError = false
                        }
                        .onFailure { vortekErr ->
                            message = vortekErr.message
                            isError = true
                        }
                },
                onDelete = null
            )
            Hairline()
        }

        drivers.forEach { driver ->
            DriverOptionRow(
                title = "${driver.name}  ${driver.version}",
                subtitle = listOf(driver.author, driver.vendor, driver.libName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                selected = activeId == driver.id,
                onSelect = {
                    runCatching { GpuDriverManager.setActive(context, driver.id) }
                        .onSuccess {
                            activeId = driver.id
                            message = "Driver “${driver.name}” será usado no próximo início do jogo."
                            isError = false
                        }
                        .onFailure { importMsg ->
                            message = importMsg.message
                            isError = true
                        }
                },
                onDelete = {
                    GpuDriverManager.remove(context, driver.id)
                    drivers = GpuDriverManager.list(context)
                    activeId = GpuDriverManager.activeId(context)
                    message = "Driver removido."
                    isError = false
                }
            )
            Hairline()
        }

        Spacer(Modifier.height(14.dp))

        // Importação: único botão de destaque da seção (contorno âmbar).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(PortPalette.radiusSm))
                .border(
                    1.dp,
                    accent.copy(alpha = if (importing) 0.2f else 0.55f),
                    RoundedCornerShape(PortPalette.radiusSm)
                )
                .portClickable(enabled = !importing) { zipPicker.launch(DRIVER_ZIP_MIME) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Text(
                        text = "Importando driver…",
                        color = accent.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Importar driver (.zip)",
                        color = accent,
                        style = PortType.caption
                    )
                }
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = if (isError) PortPalette.error else PortPalette.success,
                style = PortType.rowSub
            )
        }
    }
}

/** Linha (indicador + título + subtítulo + lixeira) de um driver da lista. */
@Composable
private fun DriverOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val accent = PortBranding.config.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .portClickable { onSelect() }
            .padding(vertical = 8.dp)
    ) {
        // Indicador: ponto cheio no accent (selecionado) ou anel hairline.
        Box(
            modifier = Modifier
                .size(18.dp)
                .let {
                    if (selected) {
                        it.background(accent, CircleShape)
                    } else {
                        it.border(1.5.dp, PortPalette.textTertiary, CircleShape)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (!selected) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(Color.Transparent, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = PortPalette.textSecondary,
                    style = PortType.rowSub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        onDelete?.let { del ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .portClickable { del() }
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Remover driver",
                    tint = PortPalette.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ======================================================================
// Diagnóstico (log detalhado persistido + desfecho do último boot)
// ======================================================================

/**
 * Seção de diagnóstico: toggle do log detalhado (debug), local do log da
 * última sessão (storage público com fallback privado) e desfecho do
 * carregamento do driver Vulkan no último boot (lido de
 * files/drivers/last_boot.txt, escrito por vulkan_instance.cpp).
 */
@Composable
private fun DiagnosticsSection(
    detailedLogs: Boolean,
    onDetailedLogsChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    Column {
        SectionHeader("Diagnóstico")

        ToggleRow(
            label = "Log detalhado (debug)",
            subtitle = "Grava eventos internos do motor no log da sessão",
            checked = detailedLogs,
            onChange = onDetailedLogsChange
        )
        Hairline()

        // Local do log da última sessão.
        val logLocation = remember { GpuDriverManager.lastLogLocation(context) }
        Text(
            text = when {
                logLocation == null ->
                    "Log: ainda não iniciado."
                logLocation == "privado" ->
                    "Log: storage PRIVADO do app (conceda \"Todos os arquivos\" " +
                        "para usar /storage/emulated/0/Naughty Bear ReStuff/logs)."
                else -> "Log: " + logLocation.removePrefix("publico:")
            },
            color = PortPalette.textTertiary,
            style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
        )

        Spacer(Modifier.height(8.dp))

        // Desfecho do driver no último boot.
        val outcome = remember { GpuDriverManager.lastBootOutcome(context) }
        if (outcome != null) {
            val label: String
            val labelColor: Color
            when (outcome.status) {
                "custom_ok" -> {
                    label = "Último boot: driver CUSTOMIZADO carregado (AdrenoTools)"
                    labelColor = PortPalette.success
                }
                "custom_failed" -> {
                    label = "Último boot: driver customizado FALHOU — usado o do sistema"
                    labelColor = PortPalette.error
                }
                else -> {
                    label = "Último boot: driver do sistema"
                    labelColor = PortPalette.textTertiary
                }
            }
            Text(
                text = label,
                color = labelColor,
                style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
            )
            if (outcome.status == "custom_failed" && outcome.error != "-") {
                Text(
                    text = "  motivo: " + outcome.error.take(120),
                    color = PortPalette.textTertiary,
                    style = PortType.mono.copy(fontSize = 10.5.sp)
                )
            }
        } else {
            Text(
                text = "Driver: rode o jogo uma vez para ver o desfecho do boot.",
                color = PortPalette.textTertiary,
                style = PortType.mono.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Crashes nativos gravam backtrace no log da sessão e em " +
                "files/last_crash.txt (visível só via adb/backup).",
            color = PortPalette.textTertiary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
    }
}
