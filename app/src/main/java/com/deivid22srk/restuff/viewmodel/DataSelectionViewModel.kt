package com.deivid22srk.restuff.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import android.widget.Toast
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
 *   SAF pasta (Activity) ──▶  onFolderPicked()  ──▶  resolução do caminho ──▶ Found
 *   referência persistida ──▶ restauração silenciosa ──▶ UiState
 *
 * FLUXO DE PASTA — SEM CÓPIA: o SAF (OpenDocumentTree) é usado apenas como
 * seletor; a árvore escolhida é RESOLVIDA para o caminho real no
 * armazenamento (/storage/emulated/0/...) e esse caminho é passado direto ao
 * motor como game_data_root. Nada é copiado para dentro do app — o motor lê
 * os arquivos onde estão (exige "Acesso a todos os arquivos", concedido pela
 * UI antes do seletor; mesmo modelo dos emuladores/ports Android).
 *
 * O ISO continua sendo lido via ParcelFileDescriptor (acesso aleatório
 * seekable) e o CONTEÚDO do disco é extraído UMA única vez para o
 * armazenamento privado do app — o arquivo original nunca é copiado nem
 * movido (um ISO não é montável pelo runtime: precisa da pasta extraída).
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
        // 1) Pasta real (fluxo sem cópia): válida se o xex ainda está lá.
        prefs.getString(KEY_GAME_ROOT_PATH, null)?.let { path ->
            val dir = File(path)
            val xex = if (dir.isDirectory) GamePaths.findXex(dir) else null
            if (xex != null) {
                _uiState.value = DataSelectionUiState(
                    DataPhase.Found(
                        folderUri = Uri.fromFile(dir).toString(),
                        fileName = xex.name
                    )
                )
                return
            }
            // Pasta removida/permissão perdida — limpa e cai para o fluxo ISO.
            GamePaths.setGameRoot(app, null)
        }
        // 2) Dados já extraídos (ISO) → pronto direto.
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
        // 3) ISO salvo mas ainda não extraído → re-extrai silenciosamente.
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
    // Fluxo pasta extraída (secundário) — SEM CÓPIA
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

        _uiState.value = DataSelectionUiState(DataPhase.Validating)
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) { resolveTreePath(treeUri) }
            if (dir == null || !dir.isDirectory) {
                _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                Toast.makeText(
                    getApplication(),
                    "Não foi possível acessar a pasta selecionada. Escolha uma pasta " +
                        "do armazenamento interno do aparelho.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val xex = withContext(Dispatchers.IO) { GamePaths.findXex(dir) }
            if (xex == null) {
                _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                Toast.makeText(
                    getApplication(),
                    "A pasta \"${dir.name}\" não contém o Default.xex na raiz. " +
                        "Selecione a pasta extraída do jogo (a que tem o Default.xex).",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // Válido: persiste o caminho REAL como game_data_root. Nada é
            // copiado — o motor lê os arquivos direto de onde estão.
            GamePaths.setGameRoot(app, dir.absolutePath)
            _uiState.value = DataSelectionUiState(
                DataPhase.Found(
                    folderUri = Uri.fromFile(dir).toString(),
                    fileName = xex.name
                )
            )
        }
    }

    /**
     * Resolve o caminho REAL de uma árvore SAF do provider de armazenamento
     * local (com.android.externalstorage.documents):
     *   content://…/tree/primary%3AGames%2FNaughtyBear
     *     → volume "primary" + caminho "Games/NaughtyBear"
     *     → /storage/emulated/0/Games/NaughtyBear
     * Volumes secundários (cartão SD, id tipo "XXXX-XXXX") mapeiam para
     * /storage/<id>. O retorno é nulo para providers que não expõem caminho
     * POSIX (downloads/cloud) — esses não servem ao motor.
     */
    private fun resolveTreePath(treeUri: Uri): File? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return null
        } ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1).replace('\\', '/').trim('/')
        val base = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory() ?: return null
            else -> File("/storage/$volume")
        }
        if (!base.isDirectory) return null
        return if (rel.isEmpty()) base else File(base, rel)
    }

    /** Revalida a pasta atualmente persistida (ex.: arquivo chegou depois). */
    fun rescan() {
        prefs.getString(KEY_GAME_ROOT_PATH, null)?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) {
                onFolderPathValidated(dir)
                return
            }
        }
        prefs.getString(KEY_FOLDER_URI, null)?.let { onFolderPicked(Uri.parse(it)) }
    }

    /** Persiste uma pasta já resolvida e válida (usado por rescan/restauração). */
    private fun onFolderPathValidated(dir: File) {
        viewModelScope.launch {
            val xex = withContext(Dispatchers.IO) { GamePaths.findXex(dir) }
            if (xex == null) {
                _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                return@launch
            }
            GamePaths.setGameRoot(getApplication(), dir.absolutePath)
            _uiState.value = DataSelectionUiState(
                DataPhase.Found(
                    folderUri = Uri.fromFile(dir).toString(),
                    fileName = xex.name
                )
            )
        }
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
        GamePaths.setGameRoot(app, null)
        GamePaths.wipeGameData(app)
        _uiState.value = DataSelectionUiState(DataPhase.Idle)
    }

    // ------------------------------------------------------------------
    // Lançamento
    // ------------------------------------------------------------------

    /** Clique no botão primário com dados prontos — entrega ao motor do port. */
    fun onStartGame() {
        val app = getApplication<Application>()
        val phase = _uiState.value.phase
        if (phase !is DataPhase.Found) return
        // Gate BARATO (listagem POSIX local, zero SAF, zero IPC): o motor só
        // lança com o entrypoint presente — boot sem Default.xex = tela preta.
        val root = GamePaths.gameRoot(app)
        if (GamePaths.findXex(root) == null) {
            if (root == GamePaths.gameDir(app) &&
                !GamePaths.extractMarker(app).exists()
            ) {
                // Fluxo ISO: a extração ainda não terminou.
                Toast.makeText(
                    app,
                    "Os dados do jogo ainda estão sendo preparados. Aguarde alguns " +
                        "instantes e toque em Iniciar Jogo de novo.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    app,
                    "Default.xex não encontrado em ${root.absolutePath}. " +
                        "Selecione a pasta do jogo novamente.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        onLaunchGame?.invoke(phase.folderUri, phase.fileName)
    }

    private companion object {
        const val PREFS_NAME = "port_screen_prefs"
        const val KEY_FOLDER_URI = "data_folder_uri"
        const val KEY_ISO_URI = "data_iso_uri"
        const val KEY_GAME_ROOT_PATH = "game_root_path"
        const val MIN_FREE_BYTES = 8L * 1_000_000_000L // ~8 GB
    }
}
