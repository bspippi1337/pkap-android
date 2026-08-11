package com.bspippi.pkap.vpn

import android.content.Context
import android.util.Log
import com.bspippi.pkap.extractor.CredentialExtractor
import com.bspippi.pkap.model.Credential
import com.bspippi.pkap.parser.PcapParser
import com.bspippi.pkap.util.RootUtils
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local root capture for owned/authorized traffic.
 * Uses su + tcpdump when available, writes rotating PCAPs, and feeds the local parser.
 */
class RootCaptureManager(
    private val context: Context,
    private val onCredential: (Credential) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PKapRootCapture"
        private const val ROTATE_BYTES = 50L * 1024L * 1024L
        private const val POLL_MS = 1500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var captureJob: Job? = null

    @Volatile
    private var activePid: Int? = null

    private val extractor = CredentialExtractor(onCredential = onCredential, enableCc = true)
    private val pcapDir: File by lazy {
        File(context.cacheDir, "root_pcaps").also { it.mkdirs() }
    }

    fun start(autoMode: Boolean = true) {
        if (running.getAndSet(true)) return

        if (!RootUtils.isRootAvailable()) {
            onStatus("Root not available")
            running.set(false)
            return
        }

        val tcpdump = RootUtils.whichTcpdump()
        if (tcpdump == null) {
            onStatus("tcpdump not found · install a trusted local build")
            running.set(false)
            return
        }

        val ifaces = RootUtils.listInterfaces()
        onStatus("Root sensor · ${ifaces.joinToString()} · local only")

        captureJob = scope.launch {
            val pcapFile = File(pcapDir, "live_capture.pcap")
            if (pcapFile.exists()) pcapFile.delete()

            fun startTcpdump(): Int? {
                val cmd = "$tcpdump -i any -U -s 0 -w ${shellQuote(pcapFile.absolutePath)} >/dev/null 2>&1 & echo \$!"
                return RootUtils.su(cmd, timeoutSec = 5)
                    ?.trim()
                    ?.lines()
                    ?.lastOrNull()
                    ?.toIntOrNull()
                    ?.also { activePid = it }
            }

            fun stopTcpdump() {
                val pid = activePid ?: return
                RootUtils.su("kill $pid 2>/dev/null")
                activePid = null
            }

            var pid = startTcpdump()
            if (pid == null) {
                onStatus("Failed to start tcpdump as root")
                running.set(false)
                return@launch
            }

            onStatus("Root sensor active · pid $pid · redacted persistence")

            var lastSize = 0L
            val parser = PcapParser(extractor)

            try {
                while (isActive && running.get()) {
                    delay(POLL_MS)

                    try {
                        if (!pcapFile.exists()) continue
                        val size = pcapFile.length()

                        if (size > lastSize && size > 40) {
                            FileInputStream(pcapFile).use { fis ->
                                parser.parseStream(fis, "root-live")
                            }
                            lastSize = size
                            onStatus("Root sensor · ${size / 1024} KB · local analysis")
                        }

                        if (size > ROTATE_BYTES) {
                            stopTcpdump()

                            val rotated = File(
                                pcapDir,
                                "capture_${System.currentTimeMillis()}.pcap"
                            )
                            if (!pcapFile.renameTo(rotated)) {
                                onStatus("Rotation failed · keeping current capture")
                            }

                            pid = startTcpdump()
                            if (pid == null) {
                                onStatus("Rotation restart failed")
                                break
                            }

                            lastSize = 0L
                            onStatus("Rotated PCAP · new pid $pid")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Poll error: ${e.message}")
                    }
                }
            } finally {
                stopTcpdump()
                activePid = null
                running.set(false)
                onStatus("Root sensor stopped")
            }
        }
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel()
        captureJob = null

        // Kill only the tcpdump process created by this manager. Never pkill every tcpdump.
        activePid?.let { pid ->
            try {
                RootUtils.su("kill $pid 2>/dev/null")
            } catch (_: Exception) {}
        }
        activePid = null
        extractor.clearState()
        onStatus("Root sensor stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
