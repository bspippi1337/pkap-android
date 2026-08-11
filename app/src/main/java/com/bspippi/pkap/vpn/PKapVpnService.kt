package com.bspippi.pkap.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bspippi.pkap.MainActivity
import com.bspippi.pkap.R
import com.bspippi.pkap.model.Credential

/**
 * VPN entry point kept for Android's VPN permission/service integration.
 *
 * IMPORTANT: a VpnService TUN interface is not a passive packet tap. If we add a
 * default route and only read packets, Android sends traffic into the TUN and it
 * never reaches the network. Until a real bidirectional forwarding/tunnel backend
 * is bundled, this service deliberately fails closed instead of blackholing the
 * device's connectivity.
 */
class PKapVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bspippi.pkap.START"
        const val ACTION_STOP = "com.bspippi.pkap.STOP"

        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "pkap_capture"

        @Volatile
        var isRunning: Boolean = false
            private set

        // Kept for API compatibility with the current UI/ViewModel. VPN capture
        // does not emit findings until a forwarding backend exists.
        var onCredentialFound: ((Credential) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            onStatus?.invoke("VPN stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startSafetyGuard()
        return START_NOT_STICKY
    }

    private fun startSafetyGuard() {
        createNotificationChannel()

        // Android 14+ requires a valid foreground-service type at runtime.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("VPN mode parked — use ROOT or PCAP"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        isRunning = false
        onStatus?.invoke(
            "VPN live is parked: no bidirectional forwarding backend is bundled. " +
                "Use ROOT AUTO or PCAP; network traffic will not be blackholed."
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        isRunning = false
        onStatus?.invoke("VPN permission revoked")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PKap Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PKap local capture status"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }
}
