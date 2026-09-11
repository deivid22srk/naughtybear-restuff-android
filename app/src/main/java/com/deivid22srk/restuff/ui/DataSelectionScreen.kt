package com.deivid22srk.restuff.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.settings.PortSettingsViewModel
import com.deivid22srk.restuff.ui.background.AmbientParticles
import com.deivid22srk.restuff.ui.background.GrainOverlay
import com.deivid22srk.restuff.ui.background.ParallaxBackground
import com.deivid22srk.restuff.ui.background.rememberParallaxOffset
import com.deivid22srk.restuff.ui.components.AnimatedTitle
import com.deivid22srk.restuff.ui.components.CreditsButton
import com.deivid22srk.restuff.ui.components.CreditsDialog
import com.deivid22srk.restuff.ui.components.FolderButton
import com.deivid22srk.restuff.ui.components.PrimarySelectButton
import com.deivid22srk.restuff.ui.components.SettingsButton
import com.deivid22srk.restuff.ui.components.StatusArea
import com.deivid22srk.restuff.ui.components.TechStatusChip
import com.deivid22srk.restuff.viewmodel.DataPhase
import com.deivid22srk.restuff.viewmodel.DataSelectionUiState
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel

/**
 * Roteador da tela: conecta ViewModel, SAF (OpenDocumentTree), o ViewModel de
 * configurações e o estado de "reduzir movimento" (sistema OU override manual).
 */
@Composable
fun DataSelectionRoute(
    viewModel: DataSelectionViewModel = viewModel(),
    settingsViewModel: PortSettingsViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val extraction by viewModel.extraction.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ------------------------------------------------------------------
    // "Acesso a todos os arquivos" (fluxo de pasta SEM CÓPIA): o motor lê a
    // pasta do jogo direto do armazenamento — sem All Files Access o open()
    // POSIX falha (EACCES). Android 11+ usa o painel especial do sistema;
    // Android 9/10 usa READ_EXTERNAL_STORAGE em runtime. O estado é reavaliado
    // a cada ON_RESUME (o usuário volta do painel de permissões).
    // ------------------------------------------------------------------
    var hasAllFiles by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        )
    }
    var legacyStorageGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAllFiles
        )
    }
    val legacyPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> legacyStorageGranted = granted }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasAllFiles = Environment.isExternalStorageManager()
                    legacyStorageGranted = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openAllFilesSettings = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Exception) {
                // Alguns builds não têm o painel por-app: abre o geral.
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        Unit
    }

    val storageReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        hasAllFiles
    } else {
        legacyStorageGranted
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    // Picker primário: o .iso do jogo (SAF, URI persistido, sem cópia).
    val isoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.onIsoPicked(uri)
    }

    // Respeita a preferência de acessibilidade do sistema: escalas de
    // animação zeradas = "remover animações" ativo no Android.
    val systemReducedMotion = remember {
        val resolver = context.contentResolver
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }
    val reduceMotion = systemReducedMotion || settings.reduceMotionOverride

    DataSelectionScreen(
        state = state,
        reduceMotion = reduceMotion,
        particlesEnabled = settings.particlesEnabled,
        storageReady = storageReady,
        onGrantStorage = openAllFilesSettings,
        onSelectData = {
            if (state.phase is DataPhase.Found) viewModel.onStartGame() else isoPicker.launch(ISO_MIME)
        },
        onSelectFolder = {
            if (storageReady) folderPicker.launch(null) else openAllFilesSettings()
        },
        onOpenSettings = onOpenSettings
    )

    // Diálogo de progresso da extração do ISO.
    if (extraction.active || (extraction.done && extraction.error != null)) {
        ExtractionProgressDialog(
            state = extraction,
            onDismiss = { viewModel.dismissExtractionDialog() },
            onCancel = { viewModel.cancelExtraction() }
        )
    }
}

private val ISO_MIME = arrayOf("application/octet-stream", "application/x-iso9660-image")

/**
 * Composição da cena AAA:
 *
 *   [fundo parallax em camadas] → [partículas ambiente] → [conteúdo adaptativo]
 *   → [grain de filme por cima de tudo] → [diálogo de créditos]
 *
 * ADAPTATIVO (correção v1.1 — nada cortado com o celular deitado):
 *  - `WindowInsets.safeDrawing` cobre barras + notch lateral em landscape;
 *  - largura ≥ 560 dp (landscape/tablet) → layout em DUAS COLUNAS roláveis:
 *    título + chip técnico à esquerda, status + ações à direita;
 *  - portrait → coluna única centralizada, rolável quando necessário;
 *  - barra inferior (chip + engrenagem) sempre presa ao fundo com insets.
 */
@Composable
fun DataSelectionScreen(
    state: DataSelectionUiState,
    reduceMotion: Boolean,
    particlesEnabled: Boolean,
    storageReady: Boolean,
    onGrantStorage: () -> Unit,
    onSelectData: () -> Unit,
    onSelectFolder: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val config = PortBranding.config
    var creditsOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070C))
    ) {
        val compact = maxHeight < 520.dp
        val wide = maxWidth >= 560.dp
        val parallax = rememberParallaxOffset(reduceMotion)

        ParallaxBackground(parallax = parallax, compact = compact)
        AmbientParticles(
            reducedMotion = reduceMotion,
            enabled = particlesEnabled && config.particlesEnabled,
            compact = compact
        )

        if (wide) {
            WideContent(
                state = state,
                reduceMotion = reduceMotion,
                compact = compact,
                storageReady = storageReady,
                onGrantStorage = onGrantStorage,
                onSelectData = onSelectData,
                onSelectFolder = onSelectFolder,
                onOpenCredits = { creditsOpen = true },
                onOpenSettings = onOpenSettings
            )
        } else {
            PortraitContent(
                state = state,
                reduceMotion = reduceMotion,
                compact = compact,
                storageReady = storageReady,
                onGrantStorage = onGrantStorage,
                onSelectData = onSelectData,
                onSelectFolder = onSelectFolder,
                onOpenCredits = { creditsOpen = true },
                onOpenSettings = onOpenSettings
            )
        }

        // Grain de filme acima de TODA a composição (inclusive conteúdo).
        GrainOverlay()

        if (creditsOpen) {
            CreditsDialog(onDismiss = { creditsOpen = false })
        }
    }
}

// ======================================================================
// Portrait: coluna única centralizada (rolável — nunca corta)
// ======================================================================

@Composable
private fun PortraitContent(
    state: DataSelectionUiState,
    reduceMotion: Boolean,
    compact: Boolean,
    storageReady: Boolean,
    onGrantStorage: () -> Unit,
    onSelectData: () -> Unit,
    onSelectFolder: () -> Unit,
    onOpenCredits: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedTitle(compact = compact, reduceMotion = reduceMotion)

            Spacer(Modifier.height(if (compact) 18.dp else 28.dp))

            StatusArea(state.phase, compact, reduceMotion)

            if (!storageReady) {
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                StorageAccessBanner(
                    compact = compact,
                    onGrant = onGrantStorage,
                    modifier = Modifier.widthIn(max = 420.dp)
                )
            }

            Spacer(Modifier.height(if (compact) 18.dp else 26.dp))

            PrimarySelectButton(
                phase = state.phase,
                validating = state.phase is DataPhase.Validating,
                compact = compact,
                reduceMotion = reduceMotion,
                onClick = onSelectData,
                modifier = Modifier.widthIn(max = 420.dp)
            )

            FolderButton(
                icon = Icons.Filled.Folder,
                enabled = state.phase !is DataPhase.Found && state.phase !is DataPhase.Validating,
                compact = compact,
                reduceMotion = reduceMotion,
                onClick = onSelectFolder
            )

            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))

            CreditsButton(
                reduceMotion = reduceMotion,
                onClick = onOpenCredits
            )
        }

        BottomBar(
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomCenter)
        )
    }
}

// ======================================================================
// Landscape / tablet (largura ≥ 560 dp): duas colunas roláveis
// ======================================================================

@Composable
private fun WideContent(
    state: DataSelectionUiState,
    reduceMotion: Boolean,
    compact: Boolean,
    storageReady: Boolean,
    onGrantStorage: () -> Unit,
    onSelectData: () -> Unit,
    onSelectFolder: () -> Unit,
    onOpenCredits: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val config = PortBranding.config

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp)
                .padding(bottom = 92.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- Coluna esquerda: identidade ------------------------------
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedTitle(compact = compact, reduceMotion = reduceMotion)
                Spacer(Modifier.height(20.dp))
                if (config.showTechChip) {
                    TechStatusChip()
                }
                Spacer(Modifier.height(14.dp))
                CreditsButton(
                    reduceMotion = reduceMotion,
                    onClick = onOpenCredits
                )
            }

            // ---- Coluna direita: status + ações ---------------------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                StatusArea(state.phase, compact, reduceMotion)

                if (!storageReady) {
                    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    StorageAccessBanner(
                        compact = compact,
                        onGrant = onGrantStorage,
                        modifier = Modifier.widthIn(max = 420.dp)
                    )
                }

                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))

                PrimarySelectButton(
                    phase = state.phase,
                    validating = state.phase is DataPhase.Validating,
                    compact = compact,
                    reduceMotion = reduceMotion,
                    onClick = onSelectData,
                    modifier = Modifier.widthIn(max = 420.dp)
                )

                FolderButton(
                    icon = Icons.Filled.Folder,
                    enabled = state.phase !is DataPhase.Found && state.phase !is DataPhase.Validating,
                    compact = compact,
                    reduceMotion = reduceMotion,
                    onClick = onSelectFolder
                )
            }
        }

        BottomBar(
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ======================================================================
// Barra inferior: chip técnico | engrenagem (sempre visível, com insets)
// ======================================================================

@Composable
private fun BottomBar(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val config = PortBranding.config

    Row(
        modifier = modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (config.showTechChip) {
            TechStatusChip()
        } else {
            Spacer(Modifier.size(48.dp))
        }
        SettingsButton(onClick = onOpenSettings)
    }
}

/**
 * Banner compacto de permissão de armazenamento (fluxo de pasta SEM CÓPIA):
 * explica por que o app precisa do "Acesso a todos os arquivos" e abre o
 * painel do sistema quando tocado. Some sozinho quando a permissão é
 * concedida (reavaliada a cada ON_RESUME).
 */
@Composable
private fun StorageAccessBanner(
    compact: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = PortBranding.config.accent
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color(0x14FFFFFF),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (compact) {
                    "Conceda o acesso a todos os arquivos para jogar direto da pasta, sem cópia."
                } else {
                    "Para usar a pasta do jogo SEM copiar nada para dentro do app, " +
                        "conceda o \"Acesso a todos os arquivos\" e toque em Selecionar Pasta."
                },
                color = Color(0xFFC9CBD6),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(
                onClick = onGrant,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = 4.dp
                )
            ) {
                Text("Conceder", color = accent)
            }
        }
    }
}

/**
 * Diálogo de progresso da extração do ISO — mostra o arquivo corrente, os
 * bytes já despejados e um botão de cancelar. Mesmo idioma visual do port
 * (painéis de vidro, acento do branding).
 */
@Composable
private fun ExtractionProgressDialog(
    state: com.deivid22srk.restuff.viewmodel.ExtractionUiState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = PortBranding.config.accent
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (state.done) onDismiss()
        },
        title = {
            Text(
                text = when {
                    state.error != null -> "Falha na extração"
                    state.phase == com.deivid22srk.restuff.data.IsoExtractor.Phase.CANCELLED ->
                        "Extração cancelada"
                    state.done -> "Dados prontos"
                    else -> "Extraindo dados do ISO…"
                },
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.error != null) {
                    Text(state.error, color = Color(0xFFE0A0A0))
                } else if (!state.done) {
                    val mb = state.bytesCopied / (1024f * 1024f)
                    Text(
                        text = state.currentFile.ifEmpty { "Lendo a imagem de disco…" },
                        color = Color(0xFFC9CBD6),
                        maxLines = 1
                    )
                    LinearProgressIndicator(
                        progress = {
                            // Sem tamanho total confiável (SAF): progresso infinito suave.
                            0.35f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent,
                        trackColor = Color(0x22FFFFFF)
                    )
                    Text(
                        text = String.format("%.0f MB despejados", mb),
                        color = Color(0xFF9DA0AC),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "O conteúdo do disco foi extraído para o armazenamento do app. O arquivo ISO original permanece no local onde você o deixou.",
                        color = Color(0xFFC9CBD6)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (state.done) onDismiss else onCancel
            ) {
                Text(if (state.done) "Fechar" else "Cancelar")
            }
        },
        containerColor = Color(0xFF14141C),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFC9CBD6)
    )
}
