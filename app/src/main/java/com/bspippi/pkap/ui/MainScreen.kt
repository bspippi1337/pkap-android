package com.bspippi.pkap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
    onOpenLogs: () -> Unit
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
                        Text(
                            "PKap",
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            color = NeonGreen,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            when {
                                state.isRootMode -> "root · auto crawl"
                                state.isCapturing -> "vpn · live"
                                else -> "native · blckswan"
                            },
                            fontSize = 11.sp,
                            color = if (state.isRootMode) NeonPink else TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onExportCsv) {
                        Icon(Icons.Default.TableChart, contentDescription = "Export CSV", tint = NeonCyan)
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Folder, contentDescription = "Logs", tint = NeonCyan)
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = NeonPink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DeepBlack,
                    titleContentColor = NeonGreen
                )
            )
        },
        bottomBar = {
            BottomBar(
                state = state,
                onStartVpn = onStartVpn,
                onStopAll = onStopAll,
                onStartRootAuto = onStartRootAuto,
                onPick = onPickPcap
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            StatusStrip(state)
            Spacer(Modifier.height(8.dp))
            FilterRow(state.selectedFilter, onFilter)
            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                EmptyState(state.isCapturing || state.isParsing, state.isRootMode)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filtered, key = { it.id }) { cred ->
                        CredentialCard(cred)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStrip(state: UiState) {
    val bg by animateColorAsState(
        when {
            state.isRootMode -> Color(0xFF2A0015)
            state.isCapturing -> Color(0xFF003322)
            else -> CardBg
        },
        tween(400), label = "statusBg"
    )
    val borderCol = when {
        state.isRootMode -> NeonPink.copy(0.5f)
        state.isCapturing -> NeonGreen.copy(0.4f)
        else -> Color(0xFF2A2A3A)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    when {
                        state.isRootMode -> NeonPink
                        state.isCapturing -> NeonGreen
                        state.isParsing -> NeonCyan
                        else -> TextMuted
                    }
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.status,
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.packetCount > 0) {
                Text("${state.packetCount} packets", color = TextMuted, fontSize = 11.sp)
            }
            if (state.lastCsvPath != null) {
                Text("CSV ready", color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            "${state.credentials.size}",
            color = if (state.isRootMode) NeonPink else NeonGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FilterRow(selected: String, onFilter: (String) -> Unit) {
    val filters = listOf("ALL", "NTLMv1", "NTLMv2", "HTTP_BASIC", "FTP", "SNMP", "HTTP_FORM", "CREDIT_CARD")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        filters.forEach { f ->
            val active = f == selected
            FilterChip(
                selected = active,
                onClick = { onFilter(f) },
                label = { Text(f, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonGreen.copy(0.2f),
                    selectedLabelColor = NeonGreen,
                    containerColor = SurfaceVariant,
                    labelColor = TextMuted
                )
            )
        }
    }
}

@Composable
private fun CredentialCard(cred: Credential) {
    val clipboard = LocalClipboardManager.current
    val accent = when (cred.type) {
        CredType.NTLMv1, CredType.NTLMv2 -> NeonPink
        CredType.HTTP_BASIC, CredType.HTTP_FORM -> NeonCyan
        CredType.FTP, CredType.SMTP, CredType.IMAP, CredType.POP3 -> NeonGreen
        CredType.SNMP -> Color(0xFFFFAA00)
        CredType.CREDIT_CARD -> Color(0xFFFF5555)
        else -> TextMuted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { clipboard.setText(AnnotatedString(cred.hashcatLine)) },
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cred.displayTitle.ifBlank { cred.type.name },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${cred.type} · ${cred.protocol}",
                        color = accent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                cred.shortSecret,
                color = TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (cred.source.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    cred.source,
                    color = TextMuted.copy(0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyState(busy: Boolean, rootMode: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    rootMode -> "ROOT AUTO CRAWL"
                    busy -> "HUNTING…"
                    else -> "NO CREDENTIALS YET"
                },
                color = when {
                    rootMode -> NeonPink
                    busy -> NeonGreen
                    else -> TextMuted
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    rootMode -> "Scanning all interfaces · writing live CSV"
                    busy -> "Packets flowing"
                    else -> "LIVE · ROOT AUTO · or open a PCAP"
                },
                color = TextMuted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: UiState,
    onStartVpn: () -> Unit,
    onStopAll: () -> Unit,
    onStartRootAuto: () -> Unit,
    onPick: () -> Unit
) {
    Surface(color = CardBg, tonalElevation = 8.dp, shadowElevation = 12.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // VPN Live
                Button(
                    onClick = {
                        if (state.isCapturing) onStopAll() else onStartVpn()
                    },
                    enabled = !state.isParsing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            state.isCapturing && !state.isRootMode -> NeonPink
                            else -> NeonGreen
                        },
                        contentColor = DeepBlack
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (state.isCapturing && !state.isRootMode) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.isCapturing && !state.isRootMode) "STOP" else "VPN",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                // Root Auto
                Button(
                    onClick = {
                        if (state.isRootMode) onStopAll() else onStartRootAuto()
                    },
                    enabled = state.rootAvailable && !state.isParsing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRootMode) NeonPink else Color(0xFF3A0018),
                        contentColor = if (state.isRootMode) DeepBlack else NeonPink,
                        disabledContainerColor = SurfaceVariant,
                        disabledContentColor = TextMuted
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (state.isRootMode) Icons.Default.Stop else Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.isRootMode) "STOP" else "ROOT",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }

                // PCAP
                OutlinedButton(
                    onClick = onPick,
                    enabled = !state.isCapturing && !state.isParsing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PCAP", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }

            if (!state.rootAvailable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Root not detected – ROOT AUTO disabled",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
