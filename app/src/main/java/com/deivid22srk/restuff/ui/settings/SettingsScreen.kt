/*
 * Tela de Configurações dedicada — exemplo completo de como um port estende
 * o template sem tocar no layout da tela inicial.
 *
 * Visual coerente com a cena principal (mesmo fundo em camadas, partículas e
 * grain), seções em painéis de vidro e controles com alvos de 48 dp. Tudo é
 * persistido por [PortSettingsViewModel]; os valores são templates prontos
 * para serem ligados ao motor do port.
 */
package com.deivid22srk.restuff.ui.settings

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.deivid22srk.restuff.settings.AspectRatioOption
import com.deivid22srk.restuff.settings.FpsLimitOption
import com.deivid22srk.restuff.settings.PortSettings
import com.deivid22srk.restuff.settings.PortSettingsViewModel
import com.deivid22srk.restuff.settings.RendererOption
import com.deivid22srk.restuff.settings.TextureFilterOption
import com.deivid22srk.restuff.ui.background.AmbientParticles
import com.deivid22srk.restuff.ui.background.GrainOverlay
import com.deivid22srk.restuff.ui.background.ParallaxBackground
import com.deivid22srk.restuff.viewmodel.DataPhase
import com.deivid22srk.restuff.viewmodel.DataSelectionUiState
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel
import java.util.Locale
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

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070C))
    ) {
        val compact = maxHeight < 560.dp

        ParallaxBackground(parallax = Offset.Zero, compact = compact)
        AmbientParticles(
            reducedMotion = reduceMotion,
            enabled = settings.particlesEnabled && config.particlesEnabled,
            compact = compact
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // ---- Cabeçalho -------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = config.contentDescBack,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = config.labelSettingsTitle,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = config.labelSettingsSubtitle,
                        color = Color.White.copy(alpha = 0.50f),
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // ---- Conteúdo rolável ------------------------------------------
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(10.dp))

                // ============================ VÍDEO ==========================
                SettingsSection(title = "Vídeo", icon = Icons.Filled.Tune, accent = accent) {
                    SettingLabel("Proporção da tela")
                    Spacer(Modifier.height(8.dp))
                    AspectRatioPreview(option = settings.aspectRatio, accent = accent)
                    Spacer(Modifier.height(12.dp))
                    ChoiceChipsRow(
                        options = AspectRatioOption.entries.toList(),
                        selected = settings.aspectRatio,
                        accent = accent
                    ) { option ->
                        onSettingsChange { it.copy(aspectRatio = option) }
                    }

                    Spacer(Modifier.height(18.dp))
                    SliderRow(
                        label = "Escala de resolução",
                        valueText = settings.resolutionScale.scaleLabel(),
                        value = settings.resolutionScale,
                        valueRange = 0.5f..3f,
                        steps = 9,
                        accent = accent
                    ) { value ->
                        onSettingsChange { it.copy(resolutionScale = value) }
                    }

                    Spacer(Modifier.height(16.dp))
                    SettingLabel("Filtro de textura")
                    Spacer(Modifier.height(10.dp))
                    ChoiceChipsRow(
                        options = TextureFilterOption.entries.toList(),
                        selected = settings.textureFilter,
                        accent = accent
                    ) { option ->
                        onSettingsChange { it.copy(textureFilter = option) }
                    }

                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        label = "VSync",
                        subtitle = "Sincroniza os quadros com a taxa de atualização",
                        checked = settings.vsync,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(vsync = checked) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ======================== DESEMPENHO =========================
                SettingsSection(title = "Desempenho", icon = Icons.Filled.Speed, accent = accent) {
                    SettingLabel("Renderizador")
                    Spacer(Modifier.height(10.dp))
                    ChoiceChipsRow(
                        options = RendererOption.entries.toList(),
                        selected = settings.renderer,
                        accent = accent
                    ) { option ->
                        onSettingsChange { it.copy(renderer = option) }
                    }

                    Spacer(Modifier.height(16.dp))
                    SettingLabel("Limite de FPS")
                    Spacer(Modifier.height(10.dp))
                    ChoiceChipsRow(
                        options = FpsLimitOption.entries.toList(),
                        selected = settings.fpsLimit,
                        accent = accent
                    ) { option ->
                        onSettingsChange { it.copy(fpsLimit = option) }
                    }

                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        label = "Frame skip",
                        subtitle = "Pula quadros para manter a fluidez em aparelhos fracos",
                        checked = settings.frameSkip,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(frameSkip = checked) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // =========================== ÁUDIO ===========================
                SettingsSection(title = "Áudio", icon = Icons.Filled.VolumeUp, accent = accent) {
                    SliderRow(
                        label = "Latência do áudio",
                        valueText = "${settings.audioLatencyMs} ms",
                        value = settings.audioLatencyMs.toFloat(),
                        valueRange = 20f..200f,
                        steps = 8,
                        accent = accent
                    ) { value ->
                        onSettingsChange { it.copy(audioLatencyMs = value.roundToInt()) }
                    }

                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = "Silenciar",
                        subtitle = "Desativa toda a saída de áudio do port",
                        checked = settings.audioMuted,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(audioMuted = checked) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ========================= CONTROLES =========================
                SettingsSection(title = "Controles", icon = Icons.Filled.Gamepad, accent = accent) {
                    ToggleRow(
                        label = "Overlay na tela",
                        subtitle = "Botões virtuais sobre o jogo",
                        checked = settings.showOverlayControls,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(showOverlayControls = checked) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SliderRow(
                        label = "Opacidade do overlay",
                        valueText = "${(settings.overlayOpacity * 100).roundToInt()}%",
                        value = settings.overlayOpacity,
                        valueRange = 0.2f..1f,
                        steps = 7,
                        accent = accent
                    ) { value ->
                        onSettingsChange { it.copy(overlayOpacity = value) }
                    }

                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = "Vibração",
                        subtitle = "Feedback tátil dos controles virtuais",
                        checked = settings.hapticFeedback,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(hapticFeedback = checked) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SliderRow(
                        label = "Tamanho dos controles",
                        valueText = "${(settings.overlayScale * 100).roundToInt()}%",
                        value = settings.overlayScale,
                        valueRange = 0.7f..1.6f,
                        steps = 8,
                        accent = accent
                    ) { value ->
                        onSettingsChange { it.copy(overlayScale = value) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ========================= MOTOR RESTUFF =====================
                SettingsSection(title = "Motor ReStuff", icon = Icons.Filled.Settings, accent = accent) {
                    ToggleRow(
                        label = "Desbloquear 60 FPS",
                        subtitle = "Reescreve o PresentationInterval do jogo (30 → 60). Lógica do jogo não verificada contra dobro de velocidade.",
                        checked = settings.unlock60Fps,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(unlock60Fps = checked) }
                    }

                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = "Unlock All (cheat)",
                        subtitle = "Força todos os trajes/conteúdos desbloqueados",
                        checked = settings.unlockAllCheat,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(unlockAllCheat = checked) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // =========================== EFEITOS =========================
                SettingsSection(title = "Efeitos da tela inicial", icon = Icons.Filled.AutoAwesome, accent = accent) {
                    ToggleRow(
                        label = config.labelToggleParticles,
                        subtitle = "Partículas ambiente da cena principal",
                        checked = settings.particlesEnabled,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(particlesEnabled = checked) }
                    }

                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = config.labelToggleMotion,
                        subtitle = "Desativa parallax, pulso e entradas animadas",
                        checked = settings.reduceMotionOverride,
                        accent = accent
                    ) { checked ->
                        onSettingsChange { it.copy(reduceMotionOverride = checked) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ======================== DADOS DO JOGO ======================
                SettingsSection(title = "Dados do jogo", icon = Icons.Filled.FolderOpen, accent = accent) {
                    SelectionSummary(selectionState, accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Arquivos esperados: " +
                            config.expectedDataFiles.joinToString(", ") +
                            config.acceptableExtensions.joinToString(", ", prefix = " · extensões: "),
                        color = Color.White.copy(alpha = 0.42f),
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(14.dp))
                    DangerButton(
                        label = config.labelClearSelection,
                        enabled = selectionState.phase !is DataPhase.Idle,
                        onClick = onClearSelection
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ================= DRIVERS GRÁFICOS (TURNIP) =================
                DriversSection(accent = accent)

                Spacer(Modifier.height(16.dp))
                Text(
                    text = config.labelSettingsFooter,
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(32.dp))
            }
        }

        GrainOverlay()
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
private fun DriversSection(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    SettingsSection(title = "Drivers gráficos (Turnip)", icon = Icons.Filled.Memory, accent = accent) {
        Text(
            text = "Driver Vulkan no padrão AdrenoTools (.zip com meta.json). " +
                "O driver selecionado substitui o driver do sistema ao iniciar o jogo; " +
                "se falhar, o app usa o driver do sistema automaticamente.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )

        Spacer(Modifier.height(12.dp))

        DriverOptionRow(
            title = "Padrão do sistema",
            subtitle = "Vulkan do fabricante do aparelho",
            selected = activeId == null,
            accent = accent,
            onSelect = {
                GpuDriverManager.clearActive(context)
                activeId = null
            },
            onDelete = null
        )

        drivers.forEach { driver ->
            DriverOptionRow(
                title = "${driver.name}  ${driver.version}",
                subtitle = listOf(driver.author, driver.vendor, driver.libName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                selected = activeId == driver.id,
                accent = accent,
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
        }

        Spacer(Modifier.height(14.dp))

        // Botão de importação (mesmo idioma visual dos botões da tela).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = if (importing) 0.05f else 0.12f))
                .border(1.dp, accent.copy(alpha = if (importing) 0.15f else 0.45f), RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !importing
                ) { zipPicker.launch(DRIVER_ZIP_MIME) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Text(
                        text = "Importando driver…",
                        color = accent.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Importar driver (.zip)",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        message?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = if (isError) Color(0xFFE0A0A0) else Color(0xFFA8D8A8),
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/** Linha (radio + título + subtítulo + lixeira) de um driver na lista. */
@Composable
private fun DriverOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f)
            )
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onSelect() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) accent else Color.White.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(10.dp).background(accent, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    maxLines = 1
                )
            }
        }
        onDelete?.let { del ->
            IconButton(onClick = del, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Remover driver",
                    tint = Color(0xFFFF6B6B).copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ======================================================================
// Componentes internos da tela
// ======================================================================

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp
    )
}

/** Linha de chips selecionáveis com scroll horizontal (nunca corta). */
@Composable
private fun <T> ChoiceChipsRow(
    options: List<T>,
    selected: T,
    accent: Color,
    label: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit,
) {
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isSelected) accent.copy(alpha = 0.95f)
                        else Color.White.copy(alpha = 0.07f)
                    )
                    .border(
                        1.dp,
                        if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.14f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) Color(0xFF12100B) else Color.White.copy(alpha = 0.72f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
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
    accent: Color,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.5.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color(0xFF0B0B12)
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
    accent: Color,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.5.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = accent,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
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
                inactiveTrackColor = Color.White.copy(alpha = 0.14f)
            )
        )
    }
}

/** Pré-visualização do frame do jogo na proporção selecionada. */
@Composable
private fun AspectRatioPreview(option: AspectRatioOption, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 6.dp.toPx()
            val availW = size.width - inset * 2
            val availH = size.height - inset * 2
            val ratio = option.ratio
            val frameW: Float
            val frameH: Float
            if (ratio == null || ratio < 0f) {
                frameW = availW
                frameH = availH
            } else {
                var w = availW
                var h = w / ratio
                if (h > availH) {
                    h = availH
                    w = h * ratio
                }
                frameW = w
                frameH = h
            }
            val left = (size.width - frameW) / 2f
            val top = (size.height - frameH) / 2f
            val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())

            // Moldura preenchida + contorno accent + guias de terços.
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(left, top),
                size = Size(frameW, frameH),
                cornerRadius = corner
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.95f),
                topLeft = Offset(left, top),
                size = Size(frameW, frameH),
                cornerRadius = corner,
                style = Stroke(width = 1.6.dp.toPx())
            )
            drawLine(
                color = accent.copy(alpha = 0.30f),
                start = Offset(left + frameW / 2f, top),
                end = Offset(left + frameW / 2f, top + frameH),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = accent.copy(alpha = 0.30f),
                start = Offset(left, top + frameH / 2f),
                end = Offset(left + frameW, top + frameH / 2f),
                strokeWidth = 1.dp.toPx()
            )
        }
        Text(
            text = option.label,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Resumo do estado atual da seleção de dados (ligado ao ViewModel real). */
@Composable
private fun SelectionSummary(state: DataSelectionUiState, accent: Color) {
    val config = PortBranding.config
    val (label, color) = when (val phase = state.phase) {
        is DataPhase.Found -> "Pasta pronta · ${phase.fileName}" to Color(0xFF4ADE80)
        is DataPhase.Validating -> "Validando…" to accent
        is DataPhase.NotFound -> "A seleção salva não contém os dados esperados." to Color(0xFFFF6B6B)
        is DataPhase.PermissionError -> "Permissão de leitura revogada." to Color(0xFFFF6B6B)
        is DataPhase.Idle -> "Nenhuma seleção salva." to Color.White.copy(alpha = 0.55f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            color = color,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val red = Color(0xFFFF6B6B)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(red.copy(alpha = if (enabled) 0.10f else 0.05f))
            .border(
                1.dp,
                red.copy(alpha = if (enabled) 0.35f else 0.15f),
                RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = null,
                tint = red.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                color = red.copy(alpha = if (enabled) 1f else 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun Float.scaleLabel(): String =
    if (this % 1f == 0f) "${toInt()}x" else String.format(Locale.US, "%.2fx", this)
