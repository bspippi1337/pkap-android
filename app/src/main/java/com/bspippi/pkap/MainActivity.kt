package com.bspippi.pkap

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bspippi.pkap.ui.MainScreen
import com.bspippi.pkap.ui.PKapViewModel
import com.bspippi.pkap.ui.theme.PKapTheme

class MainActivity : ComponentActivity() {

    private val vm: PKapViewModel by viewModels()
    private var pendingBtAction: (() -> Unit)? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.startVpn()
        else Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
    }

    private val pcapPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            vm.parsePcap(it)
        }
    }

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            pendingBtAction?.invoke()
            pendingBtAction = null
        } else {
            pendingBtAction = null
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        val missing = vm.btRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else {
            pendingBtAction = action
            btPermission.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { if (intent.action == Intent.ACTION_VIEW) vm.parsePcap(it) }

        setContent {
            PKapTheme {
                val state by vm.state.collectAsState()
                MainScreen(
                    state = state,
                    onStartVpn = {
                        val prepare = vm.prepareVpn()
                        if (prepare != null) vpnPermission.launch(prepare) else vm.startVpn()
                    },
                    onStopAll = { vm.stopAll() },
                    onStartRootAuto = { vm.startRootAuto() },
                    onPickPcap = {
                        pcapPicker.launch(arrayOf("application/vnd.tcpdump.pcap", "application/cap", "application/x-pcap", "application/octet-stream", "*/*"))
                    },
                    onClear = { vm.clearCredentials() },
                    onFilter = { vm.setFilter(it) },
                    onExportCsv = {
                        vm.exportCsv()?.let { Toast.makeText(this@MainActivity, "CSV: ${it.absolutePath}", Toast.LENGTH_LONG).show() }
                    },
                    onOpenLogs = {
                        Toast.makeText(this@MainActivity, "Logs: ${vm.getLogsDir().absolutePath}\nExports: ${vm.getExportsDir().absolutePath}", Toast.LENGTH_LONG).show()
                    },
                    onToggleReveal = { vm.toggleRevealSecrets() },
                    onBtScan = { withBluetoothPermissions { vm.scanBluetooth() } },
                    onBtRefresh = { withBluetoothPermissions { vm.refreshBluetooth() } },
                    onBtSwitch = { address -> withBluetoothPermissions { vm.switchBluetooth(address) } },
                    onBtDisconnect = { withBluetoothPermissions { vm.disconnectBluetooth() } }
                )
            }
        }
    }
}