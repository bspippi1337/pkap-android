package com.bspippi.pkap.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bspippi.pkap.util.RootUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * BLCKSWAN Bluetooth control for the BT variant.
 * Discovery is public-API based. A2DP switching is limited to already bonded devices.
 */
class BluetoothControl(private val context: Context) {

    data class Device(
        val name: String,
        val address: String,
        val bonded: Boolean,
        val audio: Boolean,
        val connected: Boolean,
        val rssi: Int? = null
    )

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var a2dp: BluetoothA2dp? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy as BluetoothA2dp
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
        }
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bind() {
        if (hasPermissions()) adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP)
    }

    @SuppressLint("MissingPermission")
    private fun info(device: BluetoothDevice, rssi: Int? = null): Device {
        val connected = try { a2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
        val cls = try { device.bluetoothClass } catch (_: Exception) { null }
        val audio = cls?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            cls?.hasService(BluetoothClass.Service.AUDIO) == true
        return Device(
            name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" },
            address = device.address ?: "",
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            audio = audio,
            connected = connected,
            rssi = rssi
        )
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Device> {
        val a = adapter ?: return emptyList()
        if (!hasPermissions()) return emptyList()
        bind()
        return try {
            a.bondedDevices.orEmpty().map { info(it) }
                .sortedWith(compareByDescending<Device> { it.connected }.thenByDescending { it.audio }.thenBy { it.name })
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(timeoutMs: Long = 10_000): List<Device> = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext emptyList()
        if (!hasPermissions() || !a.isEnabled) return@withContext emptyList()
        bind()

        val found = ConcurrentHashMap<String, Device>()
        bondedDevices().forEach { found[it.address] = it }
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val d: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val raw = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (d != null) found[d.address] = info(d, if (raw == Short.MIN_VALUE.toInt()) null else raw)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (!done.isCompleted) done.complete(Unit)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            a.cancelDiscovery()
            a.startDiscovery()
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            try { a.cancelDiscovery() } catch (_: Exception) {}
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }

        found.values.sortedWith(
            compareByDescending<Device> { it.connected }
                .thenByDescending { it.audio }
                .thenByDescending { it.rssi ?: -999 }
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun switchToBonded(address: String): String = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext "Bluetooth adapter missing"
        if (!hasPermissions()) return@withContext "Bluetooth permission missing"
        if (!a.isEnabled) return@withContext "Bluetooth is off"
        val target = try { a.getRemoteDevice(address) } catch (_: Exception) { return@withContext "Invalid Bluetooth address" }
        if (target.bondState != BluetoothDevice.BOND_BONDED) {
            return@withContext "Pair this device in Android first"
        }

        bind()
        repeat(20) {
            if (a2dp != null) return@repeat
            delay(100)
        }
        val proxy = a2dp ?: return@withContext "A2DP profile not ready"

        try {
            proxy.connectedDevices.orEmpty().filterNot { it.address.equals(address, true) }.forEach {
                reflect(proxy, "disconnect", it)
                delay(120)
            }
        } catch (_: Exception) {}

        val requested = reflect(proxy, "connect", target)
        delay(700)
        val connected = try { proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }

        if (!connected && RootUtils.isRootAvailable()) {
            // Root assist only refreshes Bluetooth manager state; it does not bypass pairing.
            RootUtils.su("cmd bluetooth_manager enable 2>/dev/null; true")
            delay(250)
            reflect(proxy, "connect", target)
            delay(500)
        }

        val finalConnected = try { proxy.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
        val label = try { target.name ?: target.address } catch (_: Exception) { target.address }
        when {
            finalConnected -> "A2DP switched → $label"
            requested -> "A2DP switch requested → $label"
            else -> "Android blocked direct A2DP switch → select $label in system Bluetooth"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectAudio(): String {
        val proxy = a2dp ?: return "A2DP profile not ready"
        if (!hasPermissions()) return "Bluetooth permission missing"
        val devices = try { proxy.connectedDevices.orEmpty() } catch (_: Exception) { emptyList() }
        devices.forEach { reflect(proxy, "disconnect", it) }
        return if (devices.isEmpty()) "No A2DP device connected" else "Disconnected ${devices.size} audio device(s)"
    }

    private fun reflect(proxy: BluetoothA2dp, method: String, device: BluetoothDevice): Boolean = try {
        val m: Method = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
        (m.invoke(proxy, device) as? Boolean) ?: true
    } catch (_: Exception) {
        false
    }

    fun close() {
        try { a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Exception) {}
        a2dp = null
    }
}