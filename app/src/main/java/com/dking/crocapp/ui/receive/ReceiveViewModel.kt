package com.dking.crocapp.ui.receive

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dking.crocapp.CrocApp
import com.dking.crocapp.croc.CodePhraseGenerator
import com.dking.crocapp.croc.CrocBinaryManager
import com.dking.crocapp.croc.CrocEngine
import com.dking.crocapp.croc.CrocProcess
import com.dking.crocapp.croc.CrocTransferState
import com.dking.crocapp.data.db.TransferHistory
import com.dking.crocapp.data.db.TransferStatus
import com.dking.crocapp.data.db.TransferType
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import com.dking.crocapp.util.QrCodeParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ReceivedFile(
    val name: String,
    val savedLocation: String,
    val uri: Uri,
    val mimeType: String
)

data class ReceiveUiState(
    val codePhrase: String = "",
    val transferState: CrocTransferState = CrocTransferState.Idle,
    val activeEngine: CrocEngine = CrocEngine.CURRENT,
    val defaultCodePhrase: String = "",
    val savedCodePhrases: List<String> = emptyList(),
    val receivedFiles: List<ReceivedFile> = emptyList(),
    val sessionOverrideUri: Uri? = null,
    val receiveLocationLabel: String = "Downloads/croc-received",
    // ── Panel mode ──
    val panelMode: Boolean = false,
    /** When the current panel code was issued (ms), for the "new code in N min" hint. */
    val panelCodeIssuedAt: Long = 0L
)

/** How often a waiting panel swaps to a fresh code (and QR). */
const val PANEL_CODE_LIFETIME_MS = 10 * 60 * 1000L

class ReceiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CrocApp
    private val prefsRepo = UserPreferencesRepository(application)
    private val binaryManager = CrocBinaryManager(application)
    private val crocProcess = CrocProcess(application, binaryManager, prefsRepo)
    private var currentOutputDir: File? = null

    // The coroutine running the current croc receive, so it can be stopped
    // cleanly before a new one starts (panel code refresh).
    private var receiveJob: Job? = null

    // Panel-mode session state
    private var panelActive = false
    private var panelWatcher: Job? = null
    private var stateSince = System.currentTimeMillis()
    private var panelFailures = 0
    private var panelTriedLegacy = false

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            crocProcess.state.collect { state ->
                if (state::class != _uiState.value.transferState::class) {
                    stateSince = System.currentTimeMillis()
                }
                _uiState.update { it.copy(transferState = state) }
                if (state is CrocTransferState.Completed) {
                    val prefs = prefsRepo.preferencesFlow.first()
                    // Session override takes priority, then settings, then default
                    val customUri = _uiState.value.sessionOverrideUri
                        ?: prefs.receiveLocationUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    val receivedFiles = publishReceivedFiles(customUri, prefs.receiveConflictStrategy)
                    _uiState.update { it.copy(receivedFiles = receivedFiles) }
                    // Use actual file names for history, not truncated parser names
                    val actualNames = receivedFiles.map { it.name }
                    saveToHistory(
                        state.copy(
                            fileNames = actualNames,
                            totalFileCount = receivedFiles.size
                        ),
                        receivedFiles
                    )
                    currentOutputDir?.deleteRecursively()
                    currentOutputDir = null
                }
            }
        }

        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                val label = resolveLocationLabel(
                    _uiState.value.sessionOverrideUri,
                    prefs.receiveLocationUri
                )
                _uiState.update { state ->
                    state.copy(
                        codePhrase = if (state.codePhrase.isBlank()) prefs.defaultCodePhrase else state.codePhrase,
                        defaultCodePhrase = prefs.defaultCodePhrase,
                        savedCodePhrases = prefs.savedCodePhrases,
                        receiveLocationLabel = label,
                        panelMode = prefs.panelMode
                    )
                }
            }
        }
    }

    fun updateCodePhrase(code: String) {
        val parsed = QrCodeParser.parseCode(code)
        if (parsed != _uiState.value.codePhrase) {
            currentOutputDir?.deleteRecursively()
            currentOutputDir = null
        }
        _uiState.update { it.copy(codePhrase = parsed) }
    }

    fun setCodeFromQr(code: String) {
        val parsed = QrCodeParser.parseCode(code)
        if (parsed != _uiState.value.codePhrase) {
            currentOutputDir?.deleteRecursively()
            currentOutputDir = null
        }
        _uiState.update { it.copy(codePhrase = parsed) }
    }

    fun startReceiveWithCode(code: String, saveAsDefault: Boolean = false, engine: CrocEngine? = null) {
        val parsed = QrCodeParser.parseCode(code).trim()
        if (parsed.isBlank()) return
        updateCodePhrase(parsed)
        if (saveAsDefault) {
            viewModelScope.launch {
                prefsRepo.updateDefaultCodePhrase(parsed)
            }
        }
        startReceive(engine)
    }

    fun saveCurrentCode() {
        viewModelScope.launch {
            prefsRepo.saveCodePhrase(_uiState.value.codePhrase)
        }
    }

    fun toggleEngine() {
        val next = if (_uiState.value.activeEngine == CrocEngine.CURRENT) CrocEngine.LEGACY else CrocEngine.CURRENT
        _uiState.update { it.copy(activeEngine = next) }
    }

    fun setEngine(engine: CrocEngine) {
        _uiState.update { it.copy(activeEngine = engine) }
    }

    fun switchToLegacyForNextReceive() {
        crocProcess.reset()
        currentOutputDir?.deleteRecursively()
        currentOutputDir = null
        _uiState.update {
            it.copy(
                activeEngine = CrocEngine.LEGACY,
                codePhrase = "",
                transferState = CrocTransferState.Idle
            )
        }
    }

    fun startReceive(engine: CrocEngine? = null) {
        val code = _uiState.value.codePhrase.trim()
        if (code.isBlank()) return

        val previousJob = receiveJob
        receiveJob = viewModelScope.launch {
            // Make sure an older receive has fully stopped before starting
            // a new one, so it can't overwrite the new transfer's state.
            if (previousJob?.isActive == true) {
                crocProcess.cancel()
                previousJob.cancelAndJoin()
            }
            val targetEngine = engine ?: _uiState.value.activeEngine.takeIf { it == CrocEngine.LEGACY }
                ?: if (prefsRepo.preferencesFlow.first().tryLegacyFirst) {
                    CrocEngine.LEGACY
                } else {
                    CrocEngine.CURRENT
                }

            _uiState.update { it.copy(activeEngine = targetEngine) }

            // Always reset before starting a new transfer
            crocProcess.reset()
            kotlinx.coroutines.delay(50)

            // Reuse existing outputDir if retrying an uncompleted transfer so croc can resume partial files
            val existingDir = currentOutputDir?.takeIf { it.exists() && it.walkTopDown().any { f -> f.isFile } }
            val outputDir = existingDir ?: File(
                getApplication<CrocApp>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "croc-received/${System.currentTimeMillis()}"
            ).apply { mkdirs() }
            currentOutputDir = outputDir

            // On a panel, keep showing what arrived last until something new does.
            if (!panelActive) {
                _uiState.update { it.copy(receivedFiles = emptyList()) }
            }
            crocProcess.receive(code, outputDir, engine = targetEngine)
        }
    }

    // ───────────────────────── Panel mode ─────────────────────────
    //
    // The panel waits as the RECEIVER with a code it generated itself, shown
    // as a QR. A phone scans it and sends. The code is swapped for a fresh
    // one every 10 minutes, after every transfer, and whenever the
    // connection drops, so the QR on screen always works and an old photo
    // of it soon stops working.

    fun startPanelSession() {
        if (panelActive) return
        panelActive = true
        panelFailures = 0
        issueNewPanelCode()
        panelWatcher?.cancel()
        panelWatcher = viewModelScope.launch {
            while (true) {
                delay(1_000)
                panelTick()
            }
        }
    }

    fun stopPanelSession() {
        if (!panelActive) return
        panelActive = false
        panelWatcher?.cancel()
        panelWatcher = null
        receiveJob?.cancel()
        crocProcess.reset()
        currentOutputDir?.deleteRecursively()
        currentOutputDir = null
        // Don't leave the panel's throwaway code in the normal Receive form
        // (e.g. after switching back to Meko).
        _uiState.update {
            it.copy(codePhrase = it.defaultCodePhrase, activeEngine = CrocEngine.CURRENT)
        }
    }

    /** Manual "New code" button on the panel. */
    fun refreshPanelCode() {
        if (panelActive) issueNewPanelCode()
    }

    private fun issueNewPanelCode() {
        // A refresh never resumes a half-finished download from an old code.
        if (crocProcess.state.value !is CrocTransferState.Transferring) {
            currentOutputDir?.deleteRecursively()
            currentOutputDir = null
        }
        panelTriedLegacy = false
        val code = CodePhraseGenerator.generate()
        _uiState.update {
            it.copy(
                codePhrase = code,
                activeEngine = CrocEngine.CURRENT,
                panelCodeIssuedAt = System.currentTimeMillis()
            )
        }
        startReceive(CrocEngine.CURRENT)
    }

    private fun panelTick() {
        if (!panelActive) return
        val now = System.currentTimeMillis()
        val inState = now - stateSince
        val codeAge = now - _uiState.value.panelCodeIssuedAt
        when (crocProcess.state.value) {
            is CrocTransferState.Preparing,
            is CrocTransferState.WaitingForPeer -> {
                // Waiting steadily means the relay is reachable again.
                if (inState >= 30_000) panelFailures = 0
                if (codeAge >= PANEL_CODE_LIFETIME_MS) issueNewPanelCode()
            }
            is CrocTransferState.Transferring -> Unit // never interrupt a transfer
            is CrocTransferState.Completed -> {
                panelFailures = 0
                // Leave "Received!" on screen briefly, then wait for the next sender.
                if (inState >= 6_000) issueNewPanelCode()
            }
            is CrocTransferState.LegacyFallbackAvailable -> {
                // The phone is on an older croc: retry the same code with the
                // legacy engine once, otherwise move on to a fresh code.
                if (!panelTriedLegacy) {
                    panelTriedLegacy = true
                    _uiState.update { it.copy(activeEngine = CrocEngine.LEGACY) }
                    startReceive(engine = CrocEngine.LEGACY)
                } else if (inState >= 3_000) {
                    issueNewPanelCode()
                }
            }
            is CrocTransferState.Error,
            is CrocTransferState.Cancelled,
            is CrocTransferState.Idle,
            is CrocTransferState.StoreCompleted -> {
                // Connection dropped / no internet: back off 3s, 6s, 12s… up to 60s
                // so an offline panel doesn't hammer the relay.
                val backoff = (3_000L shl panelFailures.coerceAtMost(5)).coerceAtMost(60_000L)
                if (inState >= backoff) {
                    panelFailures++
                    issueNewPanelCode()
                }
            }
        }
    }

    override fun onCleared() {
        panelActive = false
        crocProcess.cancel()
        super.onCleared()
    }

    fun retryWithLegacy() {
        _uiState.update { it.copy(activeEngine = CrocEngine.LEGACY) }
        startReceive(engine = CrocEngine.LEGACY)
    }

    fun cancelTransfer() {
        crocProcess.cancel()
        currentOutputDir?.deleteRecursively()
        currentOutputDir = null
    }

    fun dismissTransferResult() {
        crocProcess.reset()
        currentOutputDir?.deleteRecursively()
        currentOutputDir = null
    }

    fun resetTransfer() {
        crocProcess.reset()
        currentOutputDir?.deleteRecursively()
        currentOutputDir = null
        _uiState.update {
            it.copy(
                codePhrase = it.defaultCodePhrase,
                receivedFiles = emptyList(),
                sessionOverrideUri = null
            )
        }
        // Recalculate label since override was cleared
        viewModelScope.launch {
            val prefs = prefsRepo.preferencesFlow.first()
            val label = resolveLocationLabel(null, prefs.receiveLocationUri)
            _uiState.update { it.copy(receiveLocationLabel = label) }
        }
    }

    fun setSessionOverrideLocation(uri: Uri) {
        val label = try {
            uri.lastPathSegment?.replace(":", "/") ?: "Custom folder"
        } catch (_: Exception) { "Custom folder" }
        _uiState.update {
            it.copy(
                sessionOverrideUri = uri,
                receiveLocationLabel = label
            )
        }
    }

    fun clearSessionOverrideLocation() {
        _uiState.update { it.copy(sessionOverrideUri = null) }
        viewModelScope.launch {
            val prefs = prefsRepo.preferencesFlow.first()
            val label = resolveLocationLabel(null, prefs.receiveLocationUri)
            _uiState.update { it.copy(receiveLocationLabel = label) }
        }
    }

    private fun resolveLocationLabel(overrideUri: Uri?, settingsUri: String): String {
        val uri = overrideUri ?: settingsUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        return if (uri != null) {
            try {
                uri.lastPathSegment?.replace(":", "/") ?: "Custom folder"
            } catch (_: Exception) { "Custom folder" }
        } else {
            "Downloads/croc-received"
        }
    }

    private suspend fun saveToHistory(
        state: CrocTransferState.Completed,
        receivedFiles: List<ReceivedFile>
    ) {
        val code = _uiState.value.codePhrase
        val isStore = crocProcess.isStoredToken(code)
        if (state.isTextTransfer || receivedFiles.isEmpty()) {
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.RECEIVE,
                    fileName = state.fileNames.firstOrNull() ?: "text",
                    fileSize = state.totalBytes,
                    isStored = isStore,
                    status = TransferStatus.COMPLETED
                )
            )
            return
        }

        receivedFiles.forEach { file ->
            app.database.transferHistoryDao().insert(
                TransferHistory(
                    code = code,
                    type = TransferType.RECEIVE,
                    fileName = file.name,
                    fileSize = state.totalBytes,
                    fileUri = file.uri.toString(),
                    mimeType = file.mimeType,
                    savedLocation = file.savedLocation,
                    isStored = isStore,
                    status = TransferStatus.COMPLETED
                )
            )
        }
    }

    /**
     * Scan the output directory for all files and publish them.
     * We don't rely on [fileNames] from the parser because croc truncates
     * long names in its progress output (e.g. "4200-funn...").
     */
    private fun publishReceivedFiles(
        customTreeUri: Uri? = null,
        conflictStrategy: String = "rename"
    ): List<ReceivedFile> {
        val outputDir = currentOutputDir ?: return emptyList()
        return ReceivedFilePublisher.publish(getApplication(), outputDir, customTreeUri, conflictStrategy)
    }

    private fun normalizeCodePhrase(code: String): String {
        return QrCodeParser.parseCode(code)
    }
}
