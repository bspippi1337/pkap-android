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
    onOpenLogs: () -> Unit,
    onToggleReveal: () -> Unit
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
                            "PKAP // BLCKSWAN",
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = NeonGreen,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            when {
                                state.isRootMode -> "NODE42 · ROOT SENSOR"
                                state.isCapturing -> "NODE42 · VPN LAB"
                                else -> "RESTLESS · LOCAL ANALYSIS"
                            },
                            fontSize = 10.sp,
                            color = if (state.isRootMode) NeonPink else TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleReveal) {
                        Icon(
                            if (state.revealSecrets) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (state.revealSecrets) "Redact" else "Reveal",
                            tint = if (state.revealSecrets) NeonPink else NeonCyan
                        )
                    }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DeepBlack)
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
                .padding(horizontal = 14.dp)
        ) {
            SecurityBanner(state.revealSecrets)
            Spacer(Modifier.height(8.dp))
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
                        CredentialCard(cred, state.revealSecrets)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityBanner(revealSecrets: Boolean) {
    val color = if (revealSecrets) NeonPink else NeonGreen
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (revealSecrets) Icons.Default.Warning else Icons.Default.Shield,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (revealSecrets) "RAW VIEW · manual exports include secrets" else "PRIVACY MODE · persistence is redacted",
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
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
        tween(350), label = "statusBg"
    )
    val borderCol = when {
        state.isRootMode -> NeonPink.copy(alpha = 0.5f)
        state.isCapturing -> NeonGreen.copy(alpha = 0.4f)
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
                Text("${state.packetCount} packets", color = TextMuted, fontSize = 10.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${state.credentials.size}",
                color = if (state.isRootMode) NeonPink else NeonGreen,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace
            )
            Text("findings", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FilterRow(selected: String, onFilter: (String) -> Unit) {
    val filters = listOf("ALL", "NTLMv1", "NTLMv2", "HTTP_BASIC", "FTP", "SNMP", "HTTP_FORM", "CREDIT_CARD")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        filters.forEach { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onFilter(f) },
                label = { Text(f, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonGreen.copy(alpha = 0.2f),
                    selectedLabelColor = NeonGreen,
                    containerColor = SurfaceVariant,
                    labelColor = TextMuted
                )
            )
        }
    }
}

@Composable
private fun CredentialCard(cred: Credential, revealSecrets: Boolean) {
    val clipboard = LocalClipboardManager.current
    val accent = when (cred.type) {
        CredType.NTLMv1, CredType.NTLMv2 -> NeonPink
        CredType.HTTP_BASIC, CredType.HTTP_FORM -> NeonCyan
        CredType.FTP, CredType.SMTP, CredType.IMAP, CredType.POP3 -> NeonGreen
        CredType.SNMP -> Color(0xFFFFAA00)
        CredType.CREDIT_CARD -> Color(0xFFFF5555)
        else -> TextMuted
    }
    val shownSecret = if (revealSecrets) cred.shortSecret else cred.redactedSecret
    val copyText = if (revealSecrets) cred.hashcatLine else cred.safeSummary

    Card(
        modifier = Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(copyText)) },
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(30.dp)
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
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextMuted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                shownSecret,
                color = if (revealSecrets) TextPrimary else TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (cred.source.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    cred.source,
                    color = TextMuted.copy(alpha = 0.7f),
                    fontSize = 9.sp,
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
            Icon(
                if (rootMode) Icons.Default.Security else Icons.Default.Radar,
                contentDescription = null,
                tint = if (rootMode) NeonPink else NeonGreen,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    rootMode -> "ROOT SENSOR ACTIVE"
                    busy -> "ANALYZING…"
                    else -> "READY"
                },
                color = when {
                    rootMode -> NeonPink
                    busy -> NeonGreen
                    else -> TextMuted
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    rootMode -> "Local capture · redacted persistence"
                    busy -> "Packets flowing locally"
                    else -> "VPN LAB · ROOT SENSOR · PCAP"
                },
                color = TextMuted,
                fontSize = 12.sp
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
            modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (state.isCapturing && !state.isRootMode) onStopAll() else onStartVpn() },
                    enabled = !state.isParsing,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isCapturing && !state.isRootMode) NeonPink else NeonGreen,
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
                    Text(if (state.isCapturing && !state.isRootMode) "STOP" else "VPN", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                Button(
                    onClick = { if (state.isRootMode) onStopAll() else onStartRootAuto() },
                    enabled = state.rootAvailable && !state.isParsing,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRootMode) NeonPink else Color(0xFF3A0018),
                        contentColor = if (state.isRootMode) DeepBlack else NeonPink,
                        disabledContainerColor = SurfaceVariant,
                        disabledContentColor = TextMuted
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(if (state.isRootMode) Icons.Default.Stop else Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.isRootMode) "STOP" else "ROOT", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onPick,
                    enabled = !state.isCapturing && !state.isParsing,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PCAP", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            if (!state.rootAvailable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "ROOT SENSOR unavailable · PCAP/VPN still ready",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
