package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.ProviderRangeEntry
import com.v2ray.ang.senpai.RangeOrigin

@Composable
fun Qs3dRangesScreen(
    state: Qs3dUiState,
    onSelectProvider: (CdnType) -> Unit,
    onSearchChange: (String) -> Unit,
    onAddManualCidrs: (String) -> Unit,
    onToggleEntry: (ProviderRangeEntry) -> Unit,
    onRemoveEntry: (ProviderRangeEntry) -> Unit,
    onResetCfSeeds: () -> Unit,
    onClearDiscovered: (CdnType) -> Unit,
    onCopyAllVisible: () -> Unit,
    onSyncAwsCloudFront: () -> Unit,
    onStartDiscovery: (CdnType) -> Unit,
    onStartSequentialDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    val provider = state.activeRangeProvider
    val q = state.rangeSearchQuery.trim().lowercase()
    val filteredEntries = remember(state.rangeEntries, q) {
        if (q.isEmpty()) state.rangeEntries
        else state.rangeEntries.filter {
            it.cidr.lowercase().contains(q) || it.origin.name.lowercase().contains(q)
        }
    }

    val discoveredCount = state.rangeEntries.count { it.origin == RangeOrigin.DISCOVERED }
    val seedCount = state.rangeEntries.count { it.origin == RangeOrigin.SEED }
    val fetchedCount = state.rangeEntries.count { it.origin == RangeOrigin.FETCHED }
    val manualCount = state.rangeEntries.count { it.origin == RangeOrigin.MANUAL }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Cloudflare vs CloudFront Segmented Switcher
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(QsColors.GlassSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProviderSegmentButton(
                    label = "Cloudflare",
                    selected = provider == CdnType.CLOUDFLARE,
                    accent = QsColors.CloudflareOrange,
                    onClick = { onSelectProvider(CdnType.CLOUDFLARE) },
                    modifier = Modifier.weight(1f)
                )
                ProviderSegmentButton(
                    label = "Amazon CloudFront",
                    selected = provider == CdnType.CLOUDFRONT,
                    accent = QsColors.CloudFrontSky,
                    onClick = { onSelectProvider(CdnType.CLOUDFRONT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Pool Summary + Discovery Actions Card
        item {
            Glass3dCard(
                glowColor = if (provider == CdnType.CLOUDFLARE) QsColors.CloudflareOrange.copy(alpha = 0.4f)
                else QsColors.CloudFrontSky.copy(alpha = 0.4f)
            ) {
                Text(
                    text = if (provider == CdnType.CLOUDFLARE) {
                        stringResource(R.string.qsn3d_cf_pool_title)
                    } else {
                        stringResource(R.string.qsn3d_cfront_pool_title)
                    },
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (provider == CdnType.CLOUDFLARE) {
                        "Seeds: $seedCount • Discovered: $discoveredCount • Manual: $manualCount • Total: ${state.rangeEntries.size}"
                    } else {
                        "AWS Prefixes: $fetchedCount • Discovered: $discoveredCount • Manual: $manualCount • Total: ${state.rangeEntries.size}"
                    },
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )

                val anyDiscoveryRunning = state.isManualDiscoveryRunning || state.discoveryState.running
                if (anyDiscoveryRunning) {
                    val done = if (state.discoveryState.running) state.discoveryState.done else state.manualDiscoveryDone
                    val total = if (state.discoveryState.running) state.discoveryState.total else state.manualDiscoveryTotal
                    val cur = if (state.discoveryState.running) state.discoveryState.currentCidr else state.manualDiscoveryCidr
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = QsColors.ElectricCyan,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$done / $total • $cur",
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (anyDiscoveryRunning) {
                        Button(onClick = onStopDiscovery) {
                            Text(stringResource(R.string.qsn3d_stop_discovery), fontSize = 12.sp)
                        }
                    } else {
                        Button(onClick = { onStartDiscovery(provider) }) {
                            Text(
                                text = if (provider == CdnType.CLOUDFLARE) {
                                    stringResource(R.string.qsn3d_run_cf_discovery)
                                } else {
                                    stringResource(R.string.qsn3d_run_cfront_discovery)
                                },
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(onClick = onStartSequentialDiscovery) {
                            Text(stringResource(R.string.qsn3d_run_seq_discovery), fontSize = 12.sp)
                        }
                    }

                    if (provider == CdnType.CLOUDFRONT) {
                        OutlinedButton(
                            onClick = onSyncAwsCloudFront,
                            enabled = !state.isSyncingAws
                        ) {
                            Text(
                                text = if (state.isSyncingAws) "..."
                                else stringResource(R.string.qsn3d_sync_aws_ranges),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        OutlinedButton(onClick = onResetCfSeeds) {
                            Text(stringResource(R.string.qsn3d_reset_46_seeds), fontSize = 12.sp)
                        }
                    }

                    if (discoveredCount > 0) {
                        OutlinedButton(onClick = { onClearDiscovered(provider) }) {
                            Text(stringResource(R.string.qsn3d_clear_discovered), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. Uncapped Search, Add, Copy-All Bar
        item {
            OutlinedTextField(
                value = state.rangeSearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.qsn3d_search_ranges), fontSize = 12.sp) }
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.qsn3d_showing_ranges_count, filteredEntries.size),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyAllVisible, shape = RoundedCornerShape(50)) {
                        Text(stringResource(R.string.qsn3d_copy_all), fontSize = 12.sp)
                    }
                    Button(onClick = { showAddDialog = true }, shape = RoundedCornerShape(50)) {
                        Text("+ ${stringResource(R.string.qsn3d_add_cidr)}", fontSize = 12.sp)
                    }
                }
            }
        }

        // 4. Full Uncapped CIDR List
        items(filteredEntries, key = { "${it.provider.name}_${it.cidr}" }) { entry ->
            val originColor = when (entry.origin) {
                RangeOrigin.SEED -> QsColors.SolarGold
                RangeOrigin.DISCOVERED -> QsColors.ConnectedEmerald
                RangeOrigin.FETCHED -> QsColors.CloudFrontSky
                RangeOrigin.MANUAL -> QsColors.PlasmaViolet
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(QsColors.GlassSurface)
                    .border(
                        1.dp,
                        if (entry.enabled) originColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.cidr,
                        color = if (entry.enabled) QsColors.TextPrimary else QsColors.TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.origin.name,
                        color = originColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = { onToggleEntry(entry) }
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { onRemoveEntry(entry) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = "Delete CIDR",
                        tint = QsColors.DangerCrimson.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("${stringResource(R.string.qsn3d_add_cidr)} (${provider.name})") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("104.16.0.0/13\n8.34.146.0/24") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onAddManualCidrs(text)
                    showAddDialog = false
                }) { Text(stringResource(R.string.qsn3d_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.qsn3d_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProviderSegmentButton(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else QsColors.TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}
