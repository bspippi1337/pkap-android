package com.bspippi.pkap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bspippi.pkap.model.CredType
import com.bspippi.pkap.model.Credential
import com.bspippi.pkap.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onStartVpn: () -> Unit,
    onStopAll: () -> Unit,
    onStartRootAuto: () -> Unit,
    onPickPcap: () -> Unit,
    onClear: () -> Unit,
    onFilter: (String) -> Unit,
    onExportCsv: () -> Unit,
    onOpenLogs: () -> Unit,
    onToggleReveal: () -> Unit,
    onBtScan: () -> Unit,
    onBtRefresh: () -> Unit,
    onBtSwitch: (String) -> Unit,
    onBtDisconnect: () -> Unit
) {
    val filtered = remember(state.credentials, state.selectedFilter) {
        if (state.selectedFilter == "ALL") state.credentials
        else state.credentials.filter { it.type.name == state.selectedFilter }
    }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PKAP // ", color = TextPrimary, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                            Text("BLCKSWAN", color = NeonGreen, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                            Text(" BT", color = NeonCyan, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                        }
                        Text(
                            when {
                                state.isRootMode -> "NODE42 · ROOT SENSOR · A2DP"
                                state.btBusy -> "NODE42 · BLUETOOTH ACTIVE"
                                state.isCapturing -> "NODE42 · VPN LAB"
                                else -> "RESTLESS · LOCAL ANALYSIS"
                            },
                            color = if (state.btBusy) NeonCyan else TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleReveal) {
                        Icon(if (state.revealSecrets) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = if (state.revealSecrets) NeonPink else NeonCyan)
                    }
                    IconButton(onClick = onExportCsv) { Icon(Icons.Default.TableChart, null, tint = NeonCyan) }
                    IconButton(onClick = onOpenLogs) { Icon(Icons.Default.Folder, null, tint = NeonCyan) }
                    IconButton(onClick = onClear) { Icon(Icons.Default.DeleteSweep, null, tint = NeonPink) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepBlack)
            )
        },
        bottomBar = { BottomBar(state, onStartVpn, onStopAll, onStartRootAuto, onPickPcap) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            GlitchRail()
            SecurityBanner(state.revealSecrets)
            StatusStrip(state)
            BluetoothPanel(state, onBtScan, onBtRefresh, onBtSwitch, onBtDisconnect)
            FilterRow(state.selectedFilter, onFilter)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (filtered.isEmpty()) {
                    EmptyState(state.isCapturing || state.isParsing, state.isRootMode)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(filtered, key = { it.id }) { CredentialCard(it, state.revealSecrets) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlitchRail() {
    Box(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF080810)).border(1.dp, NeonCyan.copy(alpha = .25f), RoundedCornerShape(8.dp))
    ) {
        Text("╔═ BLCKSWAN // BT // NODE42 ═╗", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(NeonPink.copy(alpha = .55f)))
    }
}

@Composable
private fun SecurityBanner(reveal: Boolean) {
    val c = if (reveal) NeonPink else NeonGreen
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(c.copy(alpha = .07f))
            .border(1.dp, c.copy(alpha = .3f), RoundedCornerShape(9.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (reveal) Icons.Default.Warning else Icons.Default.Shield, null, tint = c, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (reveal) "RAW VIEW · manual exports may include secrets" else "PRIVACY MODE · persistent logs are redacted", color = c, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusStrip(state: UiState) {
    val c = when {
        state.isRootMode -> NeonPink
        state.btBusy -> NeonCyan
        state.isCapturing -> NeonGreen
        else -> TextMuted
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg)
            .border(1.dp, c.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(c))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(state.status, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.packetCount > 0) Text("${state.packetCount} packets", color = TextMuted, fontSize = 9.sp)
        }
        Text("${state.credentials.size}", color = c, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
    }
}

@Composable
private fun BluetoothPanel(
    state: UiState,
    onScan: () -> Unit,
    onRefresh: () -> Unit,
    onSwitch: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF07151A)), shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("BLUETOOTH // A2DP", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Text(state.btMessage, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (state.btEnabled) "ON" else "OFF", color = if (state.btEnabled) NeonGreen else NeonPink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onScan, enabled = !state.btBusy, colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = DeepBlack), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Radar, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(if (state.btBusy) "SCAN…" else "SCAN", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onRefresh, enabled = !state.btBusy, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("PAIRED", fontSize = 9.sp)
                }
                OutlinedButton(onClick = onDisconnect, enabled = !state.btBusy, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(3.dp)); Text("DROP", fontSize = 9.sp)
                }
            }

            state.btDevices.take(4).forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(SurfaceVariant.copy(alpha = .65f)).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (d.audio) Icons.Default.Speaker else Icons.Default.Bluetooth, null, tint = if (d.connected) NeonGreen else TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.address} · ${if (d.bonded) "PAIRED" else "UNPAIRED"}${d.rssi?.let { " · ${it}dBm" } ?: ""}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                    }
                    if (d.connected) {
                        Text("LIVE", color = NeonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 9.sp)
                    } else if (d.bonded && d.audio) {
                        TextButton(onClick = { onSwitch(d.address) }, enabled = !state.btBusy, contentPadding = PaddingValues(4.dp)) {
                            Text("SWITCH", color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: String, onFilter: (String) -> Unit) {
    val filters = listOf("ALL", "NTLMv1", "NTLMv2", "HTTP_BASIC", "FTP", "SNMP", "HTTP_FORM", "CREDIT_CARD")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        filters.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onFilter(f) },
                label = { Text(f, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonGreen.copy(alpha = .18f), selectedLabelColor = NeonGreen, containerColor = SurfaceVariant, labelColor = TextMuted)
            )
        }
    }
}

@Composable
private fun CredentialCard(cred: Credential, reveal: Boolean) {
    val clipboard = LocalClipboardManager.current
    val accent = when (cred.type) {
        CredType.NTLMv1, CredType.NTLMv2 -> NeonPink
        CredType.HTTP_BASIC, CredType.HTTP_FORM -> NeonCyan
        CredType.FTP, CredType.SMTP, CredType.IMAP, CredType.POP3 -> NeonGreen
        CredType.SNMP -> Color(0xFFFFAA00)
        CredType.CREDIT_CARD -> Color(0xFFFF5555)
        else -> TextMuted
    }
    val shown = if (reveal) cred.shortSecret else cred.redactedSecret
    val copied = if (reveal) cred.hashcatLine else cred.safeSummary

    Card(
        Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(copied)) },
        colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(28.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(cred.displayTitle.ifBlank { cred.type.name }, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${cred.type} · ${cred.protocol}", color = accent, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
                Icon(Icons.Default.ContentCopy, null, tint = TextMuted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.height(5.dp))
            Text(shown, color = if (reveal) TextPrimary else TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(busy: Boolean, root: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (root) Icons.Default.Security else Icons.Default.Radar, null, tint = if (root) NeonPink else NeonGreen, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(8.dp))
            Text(if (root) "ROOT SENSOR ACTIVE" else if (busy) "ANALYZING…" else "READY", color = if (root) NeonPink else if (busy) NeonGreen else TextMuted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

@Composable
private fun BottomBar(state: UiState, onStartVpn: () -> Unit, onStopAll: () -> Unit, onRoot: () -> Unit, onPick: () -> Unit) {
    Surface(color = CardBg, tonalElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(
                onClick = { if (state.isCapturing && !state.isRootMode) onStopAll() else onStartVpn() },
                enabled = !state.isParsing,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.isCapturing && !state.isRootMode) NeonPink else NeonGreen, contentColor = DeepBlack),
                shape = RoundedCornerShape(10.dp)
            ) { Text(if (state.isCapturing && !state.isRootMode) "STOP" else "VPN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }

            Button(
                onClick = { if (state.isRootMode) onStopAll() else onRoot() },
                enabled = state.rootAvailable && !state.isParsing,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.isRootMode) NeonPink else Color(0xFF3A0018), contentColor = if (state.isRootMode) DeepBlack else NeonPink),
                shape = RoundedCornerShape(10.dp)
            ) { Text(if (state.isRootMode) "STOP" else "ROOT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }

            OutlinedButton(onClick = onPick, enabled = !state.isCapturing && !state.isParsing, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp)) {
                Text("PCAP", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
    }
}