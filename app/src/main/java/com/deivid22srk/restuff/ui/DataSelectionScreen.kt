package com.deivid22srk.restuff.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.settings.PortSettingsViewModel
import com.deivid22srk.restuff.ui.components.AnimatedTitle
import com.deivid22srk.restuff.ui.components.CreditsButton
import com.deivid22srk.restuff.ui.components.CreditsDialog
import com.deivid22srk.restuff.ui.components.FolderButton
import com.deivid22srk.restuff.ui.components.PrimarySelectButton
import com.deivid22srk.restuff.ui.components.SettingsButton
import com.deivid22srk.restuff.ui.components.StatusArea
import com.deivid22srk.restuff.ui.components.TechStatusChip
import com.deivid22srk.restuff.ui.components.portClickable
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType
import com.deivid22srk.restuff.viewmodel.DataPhase
import com.deivid22srk.restuff.viewmodel.DataSelectionUiState
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder

/**
 * Roteador da tela: conecta ViewModel, SAF (OpenDocument/OpenDocumentTree),
 * o ViewModel de configurações e o estado de "reduzir movimento" (sistema OU
 * override manual).
 */
@Composable
fun DataSelectionRoute(
    viewModel: DataSelectionViewModel = viewModel(),
    settingsViewModel: PortSettingsViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
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

    // Picker primário: o .iso do jogo (SAF, URI persistido). O disco é
    // montado IN-PLACE pelo motor — nada é copiado para dentro do app.
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
}

private val ISO_MIME = arrayOf("application/octet-stream", "application/x-iso9660-image")

/**
 * Tela inicial "Mel & Carvão": a arte cinematográfica do port esmaecida a
 * ~18% sob véu carvão (identidade sem ruído), UMA coluna centrada —
 * identidade → status → ação primária → convites secundários — e uma barra
 * inferior quieta (assinatura técnica mono + engrenagem).
 *
 * Centralização SEM o bug clássico do Compose (verticalScroll +
 * Arrangement.Center corta o topo quando o conteúdo estoura a tela):
 * um spacer superior calculado a partir da altura disponível aproxima o
 * centro quando sobra espaço e abre mão dele (24dp) quando estoura — o
 * scroll então alcança TUDO (banner + fontes grandes + landscape).
 */
@Composable
fun DataSelectionScreen(
    state: DataSelectionUiState,
    reduceMotion: Boolean,
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
            .background(PortPalette.background)
    ) {
        // Altura de conteúdo estimada (título + status + CTA + convites);
        // quando ela cabe, o spacer aproxima o centro ótico; quando não
        // cabe, o spacer colapsa e o scroll alcança o primeiro item.
        val topSpacer = ((maxHeight - 600.dp) / 2f).coerceIn(24.dp, 240.dp)
        val ready = state.phase is DataPhase.Found

        // ---- Fundo: arte do port esmaecida + véu + brilho âmbar -----------
        DimmedBackdrop(artRes = config.backgroundArtRes, accent = config.accent)

        // ---- Conteúdo -------------------------------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(topSpacer))

                AnimatedTitle(
                    compact = false,
                    reduceMotion = reduceMotion,
                    tailAccent = !ready
                )

                Spacer(Modifier.height(26.dp))

                StatusArea(state.phase, compact = false, reduceMotion = reduceMotion)

                if (!storageReady) {
                    Spacer(Modifier.height(16.dp))
                    StorageAccessBanner(onGrant = onGrantStorage)
                }

                Spacer(Modifier.height(28.dp))

                PrimarySelectButton(
                    phase = state.phase,
                    validating = state.phase is DataPhase.Validating,
                    compact = false,
                    reduceMotion = reduceMotion,
                    onClick = onSelectData,
                    modifier = Modifier.widthIn(max = 420.dp)
                )

                Spacer(Modifier.height(6.dp))

                FolderButton(
                    icon = Icons.Filled.Folder,
                    enabled = state.phase !is DataPhase.Found && state.phase !is DataPhase.Validating,
                    compact = false,
                    reduceMotion = reduceMotion,
                    onClick = onSelectFolder
                )

                CreditsButton(
                    reduceMotion = reduceMotion,
                    onClick = { creditsOpen = true }
                )
            }

            // ---- Barra inferior: assinatura técnica | engrenagem ------------
            BottomBar(
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (creditsOpen) {
            CreditsDialog(onDismiss = { creditsOpen = false })
        }
    }
}

/**
 * Fundo do design "Mel & Carvão": arte cinematográfica a 18% de opacidade
 * (ContentScale.Crop), véu vertical carvão para ancorar os extremos e um
 * brilho radial âmbar discreto (~6%) atrás do bloco de título. Sem
 * partículas, sem parallax, sem grain — presença sem ruído (e menos custo
 * de bateria/frame).
 */
@Composable
private fun DimmedBackdrop(artRes: Int?, accent: Color) {
    Box(Modifier.fillMaxSize()) {
        if (artRes != null) {
            Image(
                painter = painterResource(artRes),
                contentDescription = PortBranding.config.contentDescBackground,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Véu carvão: mais denso nas bordas superior/inferior, esvazia no
        // centro horizontal onde vive o conteúdo.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            PortPalette.background.copy(alpha = 0.74f),
                            PortPalette.background.copy(alpha = 0.42f),
                            PortPalette.background.copy(alpha = 0.60f),
                            PortPalette.background.copy(alpha = 0.86f)
                        )
                    )
                )
        )
        // Brilho âmbar atrás da identidade: a única "luz" da cena.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.07f),
                                Color.Transparent
                            ),
                            center = androidx.compose.ui.geometry.Offset(
                                size.width / 2f, size.height * 0.30f
                            ),
                            radius = size.minDimension * 0.7f
                        )
                    )
                }
        )
    }
}

// ======================================================================
// Barra inferior: assinatura técnica | engrenagem (sempre visível)
// ======================================================================

@Composable
private fun BottomBar(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val config = PortBranding.config

    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (config.showTechChip) {
            TechStatusChip()
        } else {
            Spacer(Modifier.size(44.dp))
        }
        SettingsButton(onClick = onOpenSettings)
    }
}

/**
 * Banner de permissão de armazenamento (fluxo de pasta SEM CÓPIA): cartão
 * carvão de borda âmbar sutil, texto curto e um ÚNICO alvo — o cartão
 * inteiro é clicável e a ação "Conceder →" em âmbar à direita indica o
 * desfecho. Some sozinho quando a permissão é concedida (reavaliada a cada
 * ON_RESUME). Micro-escala no pressed.
 *
 * Nota: serve apenas ao fluxo de PASTA. O fluxo de ISO (primário) roda o
 * disco onde está via grant do SAF — não exige esta permissão.
 */
@Composable
private fun StorageAccessBanner(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    val config = PortBranding.config

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .widthIn(max = 420.dp)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(PortPalette.radiusMd))
            .background(PortPalette.surface)
            .border(1.dp, config.accent.copy(alpha = 0.22f), RoundedCornerShape(PortPalette.radiusMd))
            .portClickable(haptic = true) { onGrant() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Para jogar direto da pasta, sem cópia, conceda o " +
                "\"Acesso a todos os arquivos\".",
            color = PortPalette.textSecondary,
            style = PortType.rowSub,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = "Conceder →",
            color = config.accent,
            style = PortType.caption
        )
    }
}
