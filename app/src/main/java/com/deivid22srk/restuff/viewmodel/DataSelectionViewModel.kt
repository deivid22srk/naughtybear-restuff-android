package com.deivid22srk.restuff.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.restuff.data.GameIso
import com.deivid22srk.restuff.data.GamePaths
import java.io.File
import kotlinx.coroutines.Dispatchers
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

    /** Validação em andamento (ISO/pasta recém-escolhidos ou restaurados). */
    data object Validating : DataPhase

    /** Nenhuma fonte válida / nenhum arquivo esperado encontrado. */
    data object NotFound : DataPhase

    /** Dados validados: [fileName] é a fonte que deu match (ISO ou XEX). */
    data class Found(
        val folderUri: String,
        val fileName: String
    )

    /** A permissão persistida foi revogada ou a escolha falhou. */
    data object PermissionError : DataPhase
}

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
 *   SAF ISO (Activity)  ──▶  onIsoPicked()      ──▶  validação GDFX ──▶ Found
 *   SAF pasta (Activity) ──▶  onFolderPicked()  ──▶  resolução do caminho ──▶ Found
 *   referência persistida ──▶ restauração silenciosa ──▶ UiState
 *
 * FLUXO DE ISO — BOOT IN-PLACE (modelo XenDroid): o .iso selecionado é
 * apenas MAPEADO pelo motor, onde ele já está. A fonte pode ser um caminho
 * real (quando traduzível e legível — [GameIso.resolve] cascata) ou o
 * próprio content:// URI do SAF (mmap via FileDescriptor do ContentResolver
 * no nativo). NADA é copiado para dentro do app: sem os ~8 GB de extração,
 * sem checagem de espaço livre, sem diálogo de progresso. O runtime monta a
 * imagem GDFX como \Device\Harddisk0\Partition1 (DiscImageDevice).
 *
 * FLUXO DE PASTA — SEM CÓPIA (inalterado): o SAF (OpenDocumentTree) é usado
 * apenas como seletor; a árvore escolhida é RESOLVIDA para o caminho real no
 * armazenamento (/storage/emulated/0/...) e esse caminho é passado direto ao
 * motor como game_data_root (exige "Acesso a todos os arquivos").
 *
 * O extraído legado (<files>/game de versões antigas do app) continua
 * bootável (última prioridade na restauração) para não quebrar instalações
 * existentes; selecionar um ISO novo o substitui e LIBERA o espaço.
 */
class DataSelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(DataSelectionUiState())
    val uiState: StateFlow<DataSelectionUiState> = _uiState.asStateFlow()

    /**
     * Ponto de integração do MOTOR do port. O host (MainActivity) registra um
     * callback aqui e recebe (fonte, fileName) quando o usuário toca em
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

        // 1) ISO in-place: valida acesso rápido e restaura o estado pronto.
        GamePaths.isoSource(app)?.let { source ->
            val iso = GameIso.parseSource(source)
            viewModelScope.launch {
                val accessible = withContext(Dispatchers.IO) { GameIso.isAccessible(app, iso) }
                if (accessible) {
                    _uiState.value = DataSelectionUiState(
                        DataPhase.Found(folderUri = iso.value, fileName = iso.displayName)
                    )
                } else {
                    // Fonte removida OU permissão SAF revogada: limpa e cai
                    // para os fluxos seguintes (pasta/extraído legado).
                    withContext(Dispatchers.IO) { GamePaths.setIsoSource(app, null) }
                    fallbackRestore(app)
                }
            }
            return
        }

        // 2) Pasta real (fluxo sem cópia) e 3) extraído legado.
        fallbackRestore(app)
    }

    /** Prioridade 2/3 da restauração: pasta real → dados extraídos legados. */
    private fun fallbackRestore(app: Application) {
        // Pasta real (fluxo sem cópia): válida se o xex ainda está lá.
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
            // Pasta removida/permissão perdida — limpa e cai para o extraído.
            GamePaths.setGameRoot(app, null)
        }
        // Dados extraídos (legado) → pronto direto.
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
        _uiState.value = DataSelectionUiState(DataPhase.Idle)
    }

    // ------------------------------------------------------------------
    // Fluxo ISO — boot IN-PLACE (primário)
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

        _uiState.value = DataSelectionUiState(DataPhase.Validating)
        viewModelScope.launch {
            // Tri-estado: válida (non-null) / abriu mas não é disco Xbox
            // (notXboxDisc) / não abriu (ambos null).
            var notXboxDisc: GameIso.Source? = null
            val valid = withContext(Dispatchers.IO) {
                val source = GameIso.resolve(app, isoUri) ?: return@withContext null
                if (GameIso.validateXboxDisc(app, source)) source
                else {
                    notXboxDisc = source
                    null
                }
            }

            when {
                valid != null -> {
                    // Boot in-place: o motor monta a imagem onde ela está.
                    withContext(Dispatchers.IO) {
                        GamePaths.setIsoSource(app, valid.value)
                        // O ISO é a fonte agora: limpa o fluxo de pasta e o
                        // extraído legado (~8 GB de instalações antigas).
                        GamePaths.setGameRoot(app, null)
                        GamePaths.wipeGameData(app)
                    }
                    _uiState.value = DataSelectionUiState(
                        DataPhase.Found(
                            folderUri = valid.value,
                            fileName = valid.displayName
                        )
                    )
                }
                notXboxDisc != null -> {
                    _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                    toast(
                        "\"${notXboxDisc.displayName}\" não é uma imagem de disco Xbox 360 " +
                            "(GDFX/XDVDFS). Selecione o .iso do jogo."
                    )
                }
                else -> {
                    _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                    toast("Não foi possível abrir o arquivo selecionado.")
                }
            }
        }
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
                toast(
                    "Não foi possível acessar a pasta selecionada. Escolha uma pasta " +
                        "do armazenamento interno do aparelho."
                )
                return@launch
            }
            val xex = withContext(Dispatchers.IO) { GamePaths.findXex(dir) }
            if (xex == null) {
                _uiState.value = DataSelectionUiState(DataPhase.NotFound)
                toast(
                    "A pasta \"${dir.name}\" não contém o Default.xex na raiz. " +
                        "Selecione a pasta extraída do jogo (a que tem o Default.xex)."
                )
                return@launch
            }
            // Válido: persiste o caminho REAL como game_data_root. Nada é
            // copiado — o motor lê os arquivos direto de onde estão.
            withContext(Dispatchers.IO) {
                GamePaths.setGameRoot(app, dir.absolutePath)
                // A pasta passa a ser a fonte: limpa um ISO in-place anterior.
                GamePaths.setIsoSource(app, null)
            }
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
            "primary" -> android.os.Environment.getExternalStorageDirectory() ?: return null
            else -> File("/storage/$volume")
        }
        if (!base.isDirectory) return null
        return if (rel.isEmpty()) base else File(base, rel)
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
        GamePaths.setIsoSource(app, null)
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

        // Boot in-place: gate = a fonte ainda é acessível (caminho legível ou
        // grant SAF vivo). O resto (GDFX válido, Default.xex presente) o motor
        // confere ao montar e reporta com caixa de erro própria.
        GamePaths.isoSource(app)?.let { source ->
            val iso = GameIso.parseSource(source)
            viewModelScope.launch {
                val accessible = withContext(Dispatchers.IO) { GameIso.isAccessible(app, iso) }
                if (accessible) {
                    onLaunchGame?.invoke(phase.folderUri, phase.fileName)
                } else {
                    withContext(Dispatchers.IO) { GamePaths.setIsoSource(app, null) }
                    _uiState.value = DataSelectionUiState(DataPhase.Idle)
                    toast(
                        "O ISO não está mais acessível (arquivo movido ou permissão " +
                            "revogada). Selecione o arquivo novamente."
                    )
                }
            }
            return
        }

        // Gate BARATO (listagem POSIX local, zero SAF, zero IPC): o motor só
        // lança com o entrypoint presente — boot sem Default.xex = tela preta.
        val root = GamePaths.gameRoot(app)
        if (GamePaths.findXex(root) == null) {
            if (root == GamePaths.gameDir(app) &&
                !GamePaths.extractMarker(app).exists()
            ) {
                toast(
                    "Os dados do jogo ainda estão sendo preparados. Aguarde alguns " +
                        "instantes e toque em Iniciar Jogo de novo."
                )
            } else {
                toast(
                    "Default.xex não encontrado em ${root.absolutePath}. " +
                        "Selecione a pasta do jogo novamente."
                )
            }
            return
        }
        onLaunchGame?.invoke(phase.folderUri, phase.fileName)
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val PREFS_NAME = "port_screen_prefs"
        const val KEY_FOLDER_URI = "data_folder_uri"
        const val KEY_ISO_URI = "data_iso_uri"
        const val KEY_GAME_ROOT_PATH = "game_root_path"
    }
}
