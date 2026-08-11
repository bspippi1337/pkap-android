package com.bspippi.pkap.vpn

import android.content.Context
import android.util.Log
import com.bspippi.pkap.extractor.CredentialExtractor
import com.bspippi.pkap.model.Credential
import com.bspippi.pkap.parser.PcapParser
import com.bspippi.pkap.util.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root capture mode for explicitly started, local diagnostics.
 *
 * A single tcpdump PID is tracked for the lifetime of this manager. We never
 * use a global `pkill -f tcpdump`, because that can terminate unrelated packet
 * captures owned by the user or another tool.
 */
class RootCaptureManager(
    private val context: Context,
    private val onCredential: (Credential) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PKapRootCapture"
        private const val MAX_CAPTURE_BYTES = 50L * 1024L * 1024L
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

    fun start() {
        if (running.getAndSet(true)) return

        if (!RootUtils.isRootAvailable()) {
            onStatus("Root not available")
            running.set(false)
            return
        }

        val tcpdump = RootUtils.whichTcpdump()
        if (tcpdump == null) {
            onStatus("tcpdump not found — install it explicitly before using ROOT mode")
            running.set(false)
            return
        }

        val ifaces = RootUtils.listInterfaces()
        onStatus("Root ready · ifaces: ${ifaces.joinToString()} · $tcpdump")

        captureJob = scope.launch {
            val pcapFile = File(pcapDir, "live_capture.pcap")
            if (pcapFile.exists() && !pcapFile.delete()) {
                onStatus("Could not reset previous live capture")
                running.set(false)
                return@launch
            }

            try {
                val firstPid = startTcpdump(tcpdump, pcapFile)
                if (firstPid == null) {
                    onStatus("Failed to start tcpdump as root")
                    running.set(false)
                    return@launch
                }

                activePid = firstPid
                onStatus("Root capture running · pid $firstPid")

                var lastSize = 0L
                val parser = PcapParser(extractor) { _, _ -> }

                while (isActive && running.get()) {
                    delay(1500)

                    try {
                        if (!pcapFile.exists()) continue

                        val size = pcapFile.length()
                        if (size > lastSize && size > 40) {
                            FileInputStream(pcapFile).use { input ->
                                parser.parseStream(input, "root-live")
                            }
                            lastSize = size
                            onStatus("Root capture · ${size / 1024} KB · analyzing")
                        }

                        if (size > MAX_CAPTURE_BYTES) {
                            val oldPid = activePid
                            stopTrackedTcpdump()

                            val rotated = File(
                                pcapDir,
                                "capture_${System.currentTimeMillis()}.pcap"
                            )

                            if (!pcapFile.renameTo(rotated)) {
                                onStatus("PCAP rotation failed; capture stopped safely")
                                running.set(false)
                                break
                            }

                            val newPid = startTcpdump(tcpdump, pcapFile)
                            if (newPid == null) {
                                onStatus("Rotation complete, but tcpdump restart failed")
                                running.set(false)
                                break
                            }

                            activePid = newPid
                            lastSize = 0L
                            onStatus(
                                "Rotated ${rotated.name} · pid ${oldPid ?: "?"} → $newPid"
                            )
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.w(TAG, "Capture poll error", e)
                            onStatus("Root capture warning: ${e.message ?: "poll error"}")
                        }
                    }
                }
            } finally {
                stopTrackedTcpdump()
                running.set(false)
                onStatus("Root capture stopped")
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        captureJob?.cancel()
        captureJob = null
        extractor.clearState()
        onStatus("Stopping root capture…")

        // Do the root shell call off the UI thread. The capture job's finally
        // block also calls this; stopTrackedTcpdump() is idempotent.
        scope.launch {
            stopTrackedTcpdump()
        }
    }

    fun isRunning(): Boolean = running.get()

    fun close() {
        stop()
        scope.cancel()
    }

    private fun startTcpdump(tcpdump: String, pcapFile: File): Int? {
        val out = RootUtils.su(
            "$tcpdump -i any -U -s 0 -w ${shellQuote(pcapFile.absolutePath)} " +
                ">/dev/null 2>&1 & echo \$!",
            timeoutSec = 5
        ) ?: return null

        return out.lineSequence()
            .map { it.trim() }
            .lastOrNull { line -> line.isNotEmpty() && line.all(Char::isDigit) }
            ?.toIntOrNull()
    }

    @Synchronized
    private fun stopTrackedTcpdump() {
        val pid = activePid ?: return
        activePid = null
        RootUtils.su("kill $pid 2>/dev/null || true", timeoutSec = 4)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
