package com.bspippi.pkap.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bspippi.pkap.extractor.CredentialExtractor
import com.bspippi.pkap.model.Credential
import com.bspippi.pkap.parser.PcapParser
import com.bspippi.pkap.util.CsvExporter
import com.bspippi.pkap.util.LogWriter
import com.bspippi.pkap.util.RootUtils
import com.bspippi.pkap.vpn.PKapVpnService
import com.bspippi.pkap.vpn.RootCaptureManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val credentials: List<Credential> = emptyList(),
    val isCapturing: Boolean = false,
    val isRootMode: Boolean = false,
    val isAutoMode: Boolean = false,
    val isParsing: Boolean = false,
    val rootAvailable: Boolean = false,
    val revealSecrets: Boolean = false,
    val status: String = "Ready · local only · redacted",
    val packetCount: Int = 0,
    val selectedFilter: String = "ALL",
    val lastCsvPath: String? = null
)

class PKapViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val logWriter = LogWriter(app)
    private val liveCsvFile: File by lazy {
        File(app.getExternalFilesDir(null), "exports/pkap_live_redacted.csv")
    }

    private val extractor = CredentialExtractor(
        onCredential = { cred -> handleNewCredential(cred) }
    )

    private var rootCapture: RootCaptureManager? = null

    init {
        val hasRoot = RootUtils.isRootAvailable()
        _state.update {
            it.copy(
                rootAvailable = hasRoot,
                status = if (hasRoot) {
                    "Root ready · local only · redacted"
                } else {
                    "Ready · no root · PCAP available"
                }
            )
        }

        PKapVpnService.onCredentialFound = { cred -> handleNewCredential(cred) }
        PKapVpnService.onStatus = { msg ->
            _state.update { it.copy(status = msg, isCapturing = PKapVpnService.isRunning) }
        }
    }

    private fun handleNewCredential(cred: Credential) {
        // Background persistence is intentionally sanitized. Raw values stay memory-only
        // unless the user explicitly enables reveal and performs a manual export.
        logWriter.write(cred, includeSecrets = false)
        if (_state.value.isAutoMode || _state.value.isRootMode) {
            try {
                CsvExporter.appendLive(getApplication(), cred, liveCsvFile)
            } catch (_: Exception) {}
        }
        _state.update { s -> s.copy(credentials = listOf(cred) + s.credentials) }
    }

    fun prepareVpn(): Intent? = VpnService.prepare(getApplication())

    fun startVpn() {
        stopRoot()
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, PKapVpnService::class.java).apply {
            action = PKapVpnService.ACTION_START
        }
        ctx.startForegroundService(intent)
        _state.update {
            it.copy(
                isCapturing = true,
                isRootMode = false,
                isAutoMode = false,
                status = "VPN lab starting…"
            )
        }
    }

    fun stopVpn() {
        if (!PKapVpnService.isRunning) return
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, PKapVpnService::class.java).apply {
            action = PKapVpnService.ACTION_STOP
        }
        ctx.startService(intent)
        _state.update { it.copy(isCapturing = false, status = "Stopped · local data retained") }
    }

    fun startRootAuto() {
        if (!_state.value.rootAvailable) {
            _state.update { it.copy(status = "Root not available") }
            return
        }
        stopVpn()
        stopRoot()

        val manager = RootCaptureManager(
            context = getApplication(),
            onCredential = { handleNewCredential(it) },
            onStatus = { msg ->
                _state.update {
                    it.copy(
                        status = msg,
                        isCapturing = true,
                        isRootMode = true,
                        isAutoMode = true
                    )
                }
            }
        )
        rootCapture = manager
        manager.start(autoMode = true)
        _state.update {
            it.copy(
                isCapturing = true,
                isRootMode = true,
                isAutoMode = true,
                status = "Root capture starting · redacted persistence"
            )
        }
    }

    fun stopRoot() {
        rootCapture?.stop()
        rootCapture = null
        _state.update {
            it.copy(
                isCapturing = false,
                isRootMode = false,
                isAutoMode = false,
                status = "Root capture stopped"
            )
        }
    }

    fun stopAll() {
        stopVpn()
        stopRoot()
    }

    fun parsePcap(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isParsing = true, status = "Parsing PCAP locally…") }
            try {
                val ctx = getApplication<Application>()
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val parser = PcapParser(extractor) { current, _ ->
                        _state.update { it.copy(packetCount = current, status = "Parsed $current packets") }
                    }
                    parser.parseStream(input, uri.lastPathSegment ?: "pcap")
                }
                _state.update {
                    it.copy(
                        isParsing = false,
                        status = "Done · ${it.credentials.size} findings · local only"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isParsing = false, status = "PCAP error: ${e.message}") }
            }
        }
    }

    fun exportCsv(): File? {
        val creds = _state.value.credentials
        if (creds.isEmpty()) return null
        return try {
            val includeSecrets = _state.value.revealSecrets
            val file = CsvExporter.export(getApplication(), creds, includeSecrets = includeSecrets)
            _state.update {
                it.copy(
                    lastCsvPath = file.absolutePath,
                    status = if (includeSecrets) {
                        "Raw local CSV exported · protect this file"
                    } else {
                        "Redacted CSV exported"
                    }
                )
            }
            file
        } catch (e: Exception) {
            _state.update { it.copy(status = "CSV export failed: ${e.message}") }
            null
        }
    }

    fun toggleRevealSecrets() {
        _state.update {
            val reveal = !it.revealSecrets
            it.copy(
                revealSecrets = reveal,
                status = if (reveal) {
                    "Reveal enabled · memory only until manual export"
                } else {
                    "Redaction enabled · local only"
                }
            )
        }
    }

    fun clearCredentials() {
        _state.update {
            it.copy(
                credentials = emptyList(),
                packetCount = 0,
                status = "Cleared",
                lastCsvPath = null,
                revealSecrets = false
            )
        }
        extractor.clearState()
        logWriter.clearSession()
        try {
            if (liveCsvFile.exists()) liveCsvFile.delete()
        } catch (_: Exception) {}
    }

    fun setFilter(filter: String) {
        _state.update { it.copy(selectedFilter = filter) }
    }

    fun getLogsDir(): File = logWriter.getLogsDir()

    fun getExportsDir(): File =
        File(getApplication<Application>().getExternalFilesDir(null), "exports").also { it.mkdirs() }

    override fun onCleared() {
        stopAll()
        PKapVpnService.onCredentialFound = null
        PKapVpnService.onStatus = null
        super.onCleared()
    }
}
