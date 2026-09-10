package com.deivid22srk.restuff.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.restuff.data.GamePaths
import com.deivid22srk.restuff.data.IsoExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fases do fluxo de dados do jogo. Cada fase tem visual, cor, ícone e
 * microanimação próprios na [com.deivid22srk.restuff.ui.components.StatusArea].
 *
 * O app abre SEMPRE em [DataPhase.Idle]: nada é procurado automaticamente.
 * A referência persistida de uma sessão anterior (ISO ou pasta) é revalidada
 * silenciosamente apenas para restaurar o estado "pronto".
 */
sealed interface DataPhase {

    /** Estado inicial: nenhum dado selecionado — apenas aguarda o usuário. */
    data object Idle : DataPhase

    /** Validação em andamento (pasta recém-escolhida ou restaurada). */
    data object Validating : DataPhase

    /** Nenhuma pasta válida / nenhum arquivo esperado encontrado. */
    data object NotFound : DataPhase

    /** Dados validados: [fileName] é o arquivo que deu match no config. */
    data class Found(
        val folderUri: String,
        val fileName: String
    ) : DataPhase

    /** A permissão persistida foi revogada ou a escolha falhou. */
    data object PermissionError : DataPhase
}

/**
 * Estado da extração do ISO (exibido em diálogo com barra de progresso).
 */
data class ExtractionUiState(
    val active: Boolean = false,
    val phase: IsoExtractor.Phase? = null,
    val currentFile: String = "",
    val bytesCopied: Long = 0L,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * Estado imutável observado pela UI. Toda a lógica vive no ViewModel —
 * a tela é uma função pura deste estado.
 */
data class DataSelectionUiState(
    val phase: DataPhase = DataPhase.Idle,
)

/**
 * ViewModel da tela de seleção de dados — arquitetura estado/UI separada:
 *
 *   SAF ISO (Activity)  ──▶  onIsoPicked()      ──▶  extração GDFX em IO ──▶ Found
 *   SAF pasta (Activity) ──▶  onFolderPicked()  ──▶  validação em IO      ──▶ Found
 *   referência persistida ──▶ restauração silenciosa ──▶ UiState
 *
 * O ISO é lido via ParcelFileDescriptor (acesso aleatório seekable) e apenas
 * o CONTEÚDO do disco é extraído para o armazenamento privado do app — o
 * arquivo original nunca é copiado nem movido.
 */
class DataSelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(DataSelectionUiState())
    val uiState: StateFlow<DataSelectionUiState> = _uiState.asStateFlow()

    private val _extraction = MutableStateFlow(ExtractionUiState())
    val extraction: StateFlow<ExtractionUiState> = _extraction.asStateFlow()

    private var extractionJob: Job? = null
    private var currentExtractor: IsoExtractor? = null

    /**
     * Ponto de integração do MOTOR do port. O host (MainActivity) registra um
     * callback aqui e recebe (gameRoot, fileName) quando o usuário toca em
     * "Iniciar Jogo" com os dados prontos.
     */
    var onLaunchGame: ((folderUri: String, fileName: String) -> Unit)? = null

    init {
        restoreSavedSelection()
    }

    // ------------------------------------------------------------------
    // Restauração silenciosa
    // ------------------------------------------------------------------

    private fun restoreSavedSelection() {
        val app = getApplication<Application>()
        // 1) Dados já extraídos (ou Default.xex de pasta) → pronto direto.
        if (GamePaths.isGameDataReady(app) || GamePaths.hasRawXex(app)) {
            val isoUri = prefs.getString(KEY_ISO_URI, null)
            _uiState.value = DataSelectionUiState(
                DataPhase.Found(
                    folderUri = isoUri ?: GamePaths.gameDir(app).toURI().toString(),
                    fileName = GamePaths.XEX_NAME
                )
            )
            return
        }
        // 2) ISO salvo mas ainda não extraído → re-extrai silenciosamente.
        val savedIso = prefs.getString(KEY_ISO_URI, null)
        if (savedIso != null) {
            startExtraction(Uri.parse(savedIso))
        }
    }

    // ------------------------------------------------------------------
    // Fluxo ISO (primário)
    // ------------------------------------------------------------------

    /** Chamado pela Activity quando o SAF devolve o documento .iso escolhido. */
    fun onIsoPicked(isoUri: Uri) {
        val app = getApplication<Application>()
        // Persistir o grant para sobreviver a reboots (SAF padrão de ports).
        try {
            app.contentResolver.takePersistableUriPermission(
                isoUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            _uiState.value = DataSelectionUiState(DataPhase.PermissionError)
            return
        }
        prefs.edit().putString(KEY_ISO_URI, isoUri.toString()).apply()
        startExtraction(isoUri)
    }

    private fun startExtraction(isoUri: Uri) {
        if (extractionJob?.isActive == true) return // já extraindo

        // Checagem de espaço: o jogo ocupa vários GB no armazenamento interno.
        val free = GamePaths.freeSpaceBytes(getApplication())
        if (free < MIN_FREE_BYTES) {
            _uiState.value = DataSelectionUiState(DataPhase.NotFound)
            Toast.makeText(
                getApplication(),
                "Espaço insuficiente: ${(free / 1_000_000_000L)} GB livres " +
                    "(o jogo precisa de ~8 GB)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        _uiState.value = DataSelectionUiState(DataPhase.Validating)
        _extraction.value = ExtractionUiState(active = true)

        extractionJob = viewModelScope.launch {
            val extractor = IsoExtractor(getApplication())
            currentExtractor = extractor
            val result = withContext(Dispatchers.IO) {
                extractor.extract(isoUri) { p ->
                    _extraction.value = ExtractionUiState(
                        active = true,
                        phase = p.phase,
                        currentFile = p.currentFile,
                        bytesCopied = p.totalBytesCopied,
                        done = p.phase == IsoExtractor.Phase.DONE,
                        error = if (p.phase == IsoExtractor.Phase.ERROR) p.currentFile else null,
                    )
                }
            }

            when (result.phase) {
                IsoExtractor.Phase.DONE -> {
                    _uiState.value = DataSelectionUiState(
                        DataPhase.Found(
                            folderUri = isoUri.toString(),
                            fileName = GamePaths.XEX_NAME
                        )
                    )
                }
                IsoExtractor.Phase.CANCELLED -> {
                    _uiState.value = DataSelectionUiState(DataPhase.Idle)
                    prefs.edit().remove(KEY_ISO_URI).apply()
                }
                else -> {
                    _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                }
            }
            // O diálogo lê o estado final (erro/ok/cancelado) antes de fechar;
            // mantém `active` até a UI confirmar em dismissExtractionDialog().
            _extraction.value = _extraction.value.copy(active = false, done = true)
            currentExtractor = null
        }
    }

    /** Fecha o diálogo de extração na UI. */
    fun dismissExtractionDialog() {
        _extraction.value = ExtractionUiState()
    }

    /** Cancela a extração em andamento (o usuário pode re-selecionar depois). */
    fun cancelExtraction() {
        currentExtractor?.cancel()
        extractionJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Fluxo pasta extraída (secundário)
    // ------------------------------------------------------------------

    /** Chamado pela Activity quando o SAF devolve a árvore escolhida. */
    fun onFolderPicked(treeUri: Uri) {
        val app = getApplication<Application>()
        try {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            _uiState.value = DataSelectionUiState(DataPhase.PermissionError)
            return
        }
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
        validateFolder(treeUri)
    }

    /** Revalida a pasta atualmente persistida (ex.: arquivo chegou depois). */
    fun rescan() {
        prefs.getString(KEY_FOLDER_URI, null)?.let { validateFolder(Uri.parse(it)) }
    }

    /**
     * TODO(validação): a checagem da pasta via SAF (GameDataScanner
     * .findExpectedFile + DocumentFile.listFiles) foi REMOVIDA TEMPORARIAMENTE
     * por performance — cada listFiles() do DocumentFile é uma rajada de IPCs
     * de binder que trava a seleção em pastas grandes. O fluxo continua 100%
     * funcional: a pasta é marcada como válida na hora, o conteúdo é copiado
     * em background e o botão "Iniciar Jogo" só lança o motor quando o
     * marcador local (.extract_ok) confirmar a cópia completa (verificação
     * BARATA, em arquivo local, sem SAF). Reintroduzir a validação depois de
     * forma otimizada (cache de listagem + checagem em background).
     */
    private fun validateFolder(uri: Uri) {
        _uiState.value = DataSelectionUiState(
            DataPhase.Found(folderUri = uri.toString(), fileName = "pasta extraída")
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                copySafFolderToGameDir(uri)
            }
        }
    }

    private fun copySafFolderToGameDir(treeUri: Uri) {
        val app = getApplication<Application>()
        val root = GamePaths.gameDir(app)
        root.mkdirs()
        val folder = DocumentFile.fromTreeUri(app, treeUri) ?: return
        fun walk(src: DocumentFile, dst: File) {
            for (child in src.listFiles()) {
                if (child.isDirectory) {
                    val sub = File(dst, child.name ?: continue)
                    sub.mkdirs()
                    walk(child, sub)
                } else {
                    val name = child.name ?: continue
                    val target = File(dst, name)
                    if (target.isFile && target.length() == child.length()) continue
                    app.contentResolver.openInputStream(child.uri)?.use { input ->
                        // Buffer de 1 MiB: o default do copyTo (8 KiB) tornava a
                        // cópia via SAF dolorosamente lenta em pastas de jogo.
                        target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                    }
                }
            }
        }
        walk(folder, root)
        GamePaths.extractMarker(app).writeText("folder=$treeUri\n")
    }

    // ------------------------------------------------------------------
    // Limpeza
    // ------------------------------------------------------------------

    /**
     * Apaga a seleção persistida (usado pela tela de Configurações): libera
     * as permissões, remove o conteúdo extraído e volta ao estado inicial.
     */
    fun clearSavedSelection() {
        val app = getApplication<Application>()
        for (key in listOf(KEY_ISO_URI, KEY_FOLDER_URI)) {
            prefs.getString(key, null)?.let { saved ->
                try {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(saved),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Permissão já revogada pelo sistema — nada a fazer.
                }
            }
        }
        prefs.edit().remove(KEY_ISO_URI).remove(KEY_FOLDER_URI).apply()
        GamePaths.wipeGameData(app)
        _uiState.value = DataSelectionUiState(DataPhase.Idle)
    }

    // ------------------------------------------------------------------
    // Lançamento
    // ------------------------------------------------------------------

    /** Clique no botão primário com dados prontos — entrega ao motor do port. */
    fun onStartGame() {
        val phase = _uiState.value.phase
        if (phase !is DataPhase.Found) return
        // Gate de integridade BARATO (arquivo local, zero SAF): só lança o
        // motor com a extração/cópia 100% concluída — boot com dados
        // incompletos = tela preta. Sem bloquear a UI com validação lenta.
        if (!GamePaths.extractMarker(getApplication()).exists()) {
            Toast.makeText(
                getApplication(),
                "Os dados do jogo ainda estão sendo preparados. Aguarde alguns " +
                    "instantes e toque em Iniciar Jogo de novo.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        onLaunchGame?.invoke(phase.folderUri, phase.fileName)
    }

    private companion object {
        const val PREFS_NAME = "port_screen_prefs"
        const val KEY_FOLDER_URI = "data_folder_uri"
        const val KEY_ISO_URI = "data_iso_uri"
        const val MIN_FREE_BYTES = 8L * 1_000_000_000L // ~8 GB
    }
}
