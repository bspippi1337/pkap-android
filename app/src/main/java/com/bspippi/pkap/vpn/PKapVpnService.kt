package com.bspippi.pkap.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bspippi.pkap.MainActivity
import com.bspippi.pkap.R
import com.bspippi.pkap.extractor.CredentialExtractor
import com.bspippi.pkap.model.Credential
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PKapVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bspippi.pkap.START"
        const val ACTION_STOP = "com.bspippi.pkap.STOP"
        private const val TAG = "PKapVpn"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "pkap_capture"

        @Volatile
        var isRunning = false
            private set

        // simple callback for UI
        var onCredentialFound: ((Credential) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    private val extractor = CredentialExtractor(
        onCredential = { cred ->
            onCredentialFound?.invoke(cred)
        },
        enableCc = true
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (running.getAndSet(true)) return
        isRunning = true

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))

        val builder = Builder()
            .setSession("PKap")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setBlocking(true)

        // Optional: allow apps to bypass if needed
        // builder.addDisallowedApplication(packageName)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                onStatus?.invoke("VPN establish failed (permission?)")
                stopCapture()
                return
            }
            onStatus?.invoke("Live capture active")
            updateNotification("Capturing traffic…")

            captureJob = scope.launch {
                val fd = vpnInterface!!.fileDescriptor
                val input = FileInputStream(fd)
                val buffer = ByteArray(32767)

                while (isActive && running.get()) {
                    try {
                        val length = input.read(buffer)
                        if (length > 0) {
                            processIpPacket(buffer, length)
                        }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "Read error: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start failed", e)
            onStatus?.invoke("Error: ${e.message}")
            stopCapture()
        }
    }

    private fun processIpPacket(data: ByteArray, length: Int) {
        if (length < 20) return
        val version = (data[0].toInt() and 0xF0) ushr 4
        if (version != 4) return // IPv4 only for now

        val ihl = (data[0].toInt() and 0x0F) * 4
        if (ihl + 8 > length) return

        val protocol = data[9].toInt() and 0xFF
        val srcIp = (0..3).joinToString(".") { (data[12 + it].toInt() and 0xFF).toString() }
        val dstIp = (0..3).joinToString(".") { (data[16 + it].toInt() and 0xFF).toString() }

        when (protocol) {
            6 -> { // TCP
                val tcpOff = ihl
                if (tcpOff + 20 > length) return
                val srcPort = ((data[tcpOff].toInt() and 0xFF) shl 8) or (data[tcpOff + 1].toInt() and 0xFF)
                val dstPort = ((data[tcpOff + 2].toInt() and 0xFF) shl 8) or (data[tcpOff + 3].toInt() and 0xFF)
                val dataOff = ((data[tcpOff + 12].toInt() and 0xF0) ushr 4) * 4
                val payloadOff = tcpOff + dataOff
                if (payloadOff < length) {
                    val payload = data.copyOfRange(payloadOff, length)
                    extractor.processPayload(payload, srcIp, dstIp, srcPort, dstPort, true)
                }
            }
            17 -> { // UDP
                val udpOff = ihl
                if (udpOff + 8 > length) return
                val srcPort = ((data[udpOff].toInt() and 0xFF) shl 8) or (data[udpOff + 1].toInt() and 0xFF)
                val dstPort = ((data[udpOff + 2].toInt() and 0xFF) shl 8) or (data[udpOff + 3].toInt() and 0xFF)
                val payloadOff = udpOff + 8
                if (payloadOff < length) {
                    val payload = data.copyOfRange(payloadOff, length)
                    extractor.processPayload(payload, srcIp, dstIp, srcPort, dstPort, false)
                }
            }
        }
    }

    private fun stopCapture() {
        running.set(false)
        isRunning = false
        captureJob?.cancel()
        captureJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        extractor.clearState()
        onStatus?.invoke("Capture stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PKap Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live credential extraction"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
