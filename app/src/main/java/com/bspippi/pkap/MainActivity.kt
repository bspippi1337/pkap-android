package com.bspippi.pkap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bspippi.pkap.ui.MainScreen
import com.bspippi.pkap.ui.PKapViewModel
import com.bspippi.pkap.ui.theme.PKapTheme

class MainActivity : ComponentActivity() {

    private val vm: PKapViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.startVpn()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val pcapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            vm.parsePcap(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.data?.let { uri ->
            if (intent.action == Intent.ACTION_VIEW) {
                vm.parsePcap(uri)
            }
        }

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
                        pcapPicker.launch(
                            arrayOf(
                                "application/vnd.tcpdump.pcap",
                                "application/cap",
                                "application/x-pcap",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onClear = { vm.clearCredentials() },
                    onFilter = { vm.setFilter(it) },
                    onExportCsv = {
                        val file = vm.exportCsv()
                        if (file != null) {
                            Toast.makeText(this@MainActivity, "CSV: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                        }
                    },
                    onOpenLogs = {
                        Toast.makeText(
                            this@MainActivity,
                            "Logs: ${vm.getLogsDir().absolutePath}\nExports: ${vm.getExportsDir().absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onToggleReveal = { vm.toggleRevealSecrets() }
                )
            }
        }
    }
}
