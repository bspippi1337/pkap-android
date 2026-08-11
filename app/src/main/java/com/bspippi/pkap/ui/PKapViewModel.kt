package com.bspippi.pkap.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
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
    val vpnAvailable: Boolean = false,
    val status: String = "Ready",
    val packetCount: Int = 0,
    val selectedFilter: String = "ALL",
    val lastCsvPath: String? = null
)

class PKapViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val logWriter = LogWriter(app)
    private val liveCsvFile: File by lazy {
        File(app.getExternalFilesDir(null), "exports/pkap_live.csv")
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
                vpnAvailable = false,
                status = if (hasRoot) {
                    "Root ready · ROOT + PCAP available"
                } else {
                    "Ready · PCAP available"
                }
            )
        }

        PKapVpnService.onCredentialFound = { cred -> handleNewCredential(cred) }
        PKapVpnService.onStatus = { msg ->
            _state.update {
                it.copy(
                    status = msg,
                    isCapturing = PKapVpnService.isRunning,
                    isRootMode = false,
                    isAutoMode = false
                )
            }
        }
    }

    private fun handleNewCredential(cred: Credential) {
        logWriter.write(cred)

        if (_state.value.isAutoMode || _state.value.isRootMode) {
            try {
                CsvExporter.appendLive(getApplication(), cred, liveCsvFile)
            } catch (_: Exception) {
            }
        }

        _state.update { state ->
            state.copy(credentials = listOf(cred) + state.credentials)
        }
    }

    /**
     * VPN mode is intentionally parked until a real bidirectional forwarding
     * backend exists. Returning null avoids asking the user for VPN permission
     * for a mode that cannot safely forward packets yet.
     */
    fun prepareVpn(): Intent? = null

    fun startVpn() {
        _state.update {
            it.copy(
                isCapturing = false,
                isRootMode = false,
                isAutoMode = false,
                status = "VPN live is parked — use ROOT or PCAP (no traffic blackhole)"
            )
        }
    }

    fun stopVpn() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, PKapVpnService::class.java))
        _state.update {
            it.copy(
                isCapturing = false,
                status = if (it.isRootMode) it.status else "Stopped"
            )
        }
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
                val active = rootCapture?.isRunning() == true
                _state.update {
                    it.copy(
                        status = msg,
                        isCapturing = active,
                        isRootMode = active,
                        isAutoMode = active
                    )
                }
            }
        )

        rootCapture = manager
        manager.start()

        _state.update {
            it.copy(
                isCapturing = manager.isRunning(),
                isRootMode = manager.isRunning(),
                isAutoMode = manager.isRunning(),
                status = "Root capture starting…"
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
            _state.update { it.copy(isParsing = true, status = "Parsing PCAP…") }

            try {
                val context = getApplication<Application>()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val parser = PcapParser(extractor) { current, _ ->
                        _state.update {
                            it.copy(
                                packetCount = current,
                                status = "Parsed $current packets"
                            )
                        }
                    }
                    parser.parseStream(input, uri.lastPathSegment ?: "pcap")
                }

                _state.update {
                    it.copy(
                        isParsing = false,
                        status = "Done — ${it.credentials.size} findings"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isParsing = false,
                        status = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun exportCsv(): File? {
        val creds = _state.value.credentials
        if (creds.isEmpty()) return null

        return try {
            val file = CsvExporter.export(getApplication(), creds)
            _state.update {
                it.copy(
                    lastCsvPath = file.absolutePath,
                    status = "CSV exported: ${file.name}"
                )
            }
            file
        } catch (e: Exception) {
            _state.update { it.copy(status = "CSV export failed: ${e.message}") }
            null
        }
    }

    fun clearCredentials() {
        _state.update {
            it.copy(
                credentials = emptyList(),
                packetCount = 0,
                status = "Cleared",
                lastCsvPath = null
            )
        }
        extractor.clearState()
        logWriter.clearSession()
        try {
            if (liveCsvFile.exists()) liveCsvFile.delete()
        } catch (_: Exception) {
        }
    }

    fun setFilter(filter: String) {
        _state.update { it.copy(selectedFilter = filter) }
    }

    fun getLogsDir(): File = logWriter.getLogsDir()

    fun getExportsDir(): File =
        File(getApplication<Application>().getExternalFilesDir(null), "exports").also {
            it.mkdirs()
        }

    override fun onCleared() {
        stopAll()
        PKapVpnService.onCredentialFound = null
        PKapVpnService.onStatus = null
        super.onCleared()
    }
}
