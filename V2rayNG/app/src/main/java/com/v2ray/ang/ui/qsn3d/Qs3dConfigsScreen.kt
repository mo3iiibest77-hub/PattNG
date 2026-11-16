package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.ConfigListBucket
import com.v2ray.ang.senpai.ConfigPipeline
import com.v2ray.ang.util.Utils

/**
 * Privacy-hardened dual-pipeline NovaPulse Server List:
 * - Strictly separates CDN nodes (Cloudflare / CloudFront) from Standard v2rayNG protocols
 *   (XHTTP REALITY, TCP REALITY, Hysteria2, VMess, Trojan, SS, WireGuard, etc.)
 * - Zero raw IP / domain / port / credential leaks in the UI
 * - Allows importing custom Standard/REALITY configs (when licensed) alongside subscription nodes
 * - Keeps Search, Test All Delays, Sort by Ping, Bulk Select/Delete
 */
@Composable
fun Qs3dConfigsScreen(
    state: Qs3dUiState,
    filteredConfigs: List<ConfigUiRow>,
    onSelectConfig: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onBucketChange: (ConfigListBucket) -> Unit,
    onToggleBulkSelect: (String) -> Unit,
    onClearBulkSelection: () -> Unit,
    onDeleteBulkSelected: () -> Unit,
    onTestAllDelays: () -> Unit,
    onSortByDelay: () -> Unit,
    onImportManualConfig: (String) -> Unit,
    onScanConfigQr: () -> Unit,
) {
    val context = LocalContext.current
    var bulkMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var manualDraft by remember { mutableStateOf("") }

    val cdnCount = state.configs.count { it.pipeline == ConfigPipeline.CDN }
    val stdCount = state.configs.count { it.pipeline == ConfigPipeline.STANDARD }

    val cdnRows = filteredConfigs.filter { it.pipeline == ConfigPipeline.CDN }
    val stdRows = filteredConfigs.filter { it.pipeline == ConfigPipeline.STANDARD }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Search Bar, Pipeline Bucket Filter Chips & Action Strip
        item {
            Glass3dCard(glowColor = QsColors.ElectricCyan.copy(alpha = 0.30f)) {
                OutlinedTextField(
                    value = state.configSearchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text(stringResource(R.string.novapulse_search_servers_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.configBucket == ConfigListBucket.ALL,
                        onClick = { onBucketChange(ConfigListBucket.ALL) },
                        label = { Text(stringResource(R.string.novapulse_configs_tab_all, state.configs.size)) }
                    )
                    FilterChip(
                        selected = state.configBucket == ConfigListBucket.CDN,
                        onClick = { onBucketChange(ConfigListBucket.CDN) },
                        label = { Text(stringResource(R.string.novapulse_configs_tab_cdn, cdnCount)) }
                    )
                    FilterChip(
                        selected = state.configBucket == ConfigListBucket.STANDARD,
                        onClick = { onBucketChange(ConfigListBucket.STANDARD) },
                        label = { Text(stringResource(R.string.novapulse_configs_tab_standard, stdCount)) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        manualDraft = ""
                        showAddDialog = true
                    }) {
                        Text(
                            text = stringResource(R.string.novapulse_add_config_btn),
                            color = QsColors.ElectricCyan
                        )
                    }
                    OutlinedButton(onClick = onTestAllDelays) {
                        Text(
                            if (state.isDelayTestingAll) stringResource(R.string.qsn3d_stop_fmt, state.delayTestProgress)
                            else stringResource(R.string.qsn3d_test_all)
                        )
                    }
                    OutlinedButton(onClick = onSortByDelay) {
                        Text(stringResource(R.string.qsn3d_sort_ping))
                    }
                    OutlinedButton(
                        onClick = {
                            bulkMode = !bulkMode
                            if (!bulkMode) onClearBulkSelection()
                        }
                    ) {
                        Text(
                            if (bulkMode) stringResource(R.string.qsn3d_done_bulk)
                            else stringResource(R.string.qsn3d_bulk_select)
                        )
                    }
                    if (bulkMode && state.bulkSelectedGuids.isNotEmpty()) {
                        OutlinedButton(onClick = onDeleteBulkSelected) {
                            Text(
                                text = stringResource(R.string.qsn3d_delete_count_fmt, state.bulkSelectedGuids.size),
                                color = QsColors.DangerCrimson
                            )
                        }
                    }
                }
            }
        }

        // 2. CDN Optimized Section
        if (cdnRows.isNotEmpty()) {
            if (state.configBucket == ConfigListBucket.ALL) {
                item(key = "section_hdr_cdn") {
                    ConfigPipelineSectionHeader(
                        title = stringResource(R.string.novapulse_configs_section_cdn),
                        count = cdnRows.size,
                        accent = QsColors.CloudflareOrange,
                    )
                }
            }
            items(cdnRows, key = { "cdn_${it.guid}" }) { row ->
                ConfigRowCard(
                    row = row,
                    isSelected = row.guid == state.selectedGuid,
                    coreRunning = state.coreRunning,
                    bulkMode = bulkMode,
                    isBulkChecked = row.guid in state.bulkSelectedGuids,
                    onClick = {
                        if (bulkMode) onToggleBulkSelect(row.guid) else onSelectConfig(row.guid)
                    },
                    onToggleBulk = { onToggleBulkSelect(row.guid) },
                )
            }
        }

        // 3. Standard / Direct v2rayNG Protocols Section (REALITY, Hysteria2, VMess, Trojan, SS...)
        if (stdRows.isNotEmpty()) {
            if (state.configBucket == ConfigListBucket.ALL) {
                item(key = "section_hdr_std") {
                    ConfigPipelineSectionHeader(
                        title = stringResource(R.string.novapulse_configs_section_standard),
                        count = stdRows.size,
                        accent = QsColors.PlasmaViolet,
                    )
                }
            }
            items(stdRows, key = { "std_${it.guid}" }) { row ->
                ConfigRowCard(
                    row = row,
                    isSelected = row.guid == state.selectedGuid,
                    coreRunning = state.coreRunning,
                    bulkMode = bulkMode,
                    isBulkChecked = row.guid in state.bulkSelectedGuids,
                    onClick = {
                        if (bulkMode) onToggleBulkSelect(row.guid) else onSelectConfig(row.guid)
                    },
                    onToggleBulk = { onToggleBulkSelect(row.guid) },
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.novapulse_add_config_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualDraft,
                        onValueChange = { manualDraft = it },
                        placeholder = { Text(stringResource(R.string.novapulse_add_config_hint), fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clip = Utils.getClipboard(context).trim()
                            if (clip.isNotEmpty()) {
                                manualDraft = if (manualDraft.isBlank()) clip else "$manualDraft\n$clip"
                            }
                        }) {
                            Text(stringResource(R.string.novapulse_add_config_paste_btn))
                        }
                        OutlinedButton(onClick = {
                            showAddDialog = false
                            onScanConfigQr()
                        }) {
                            Text(stringResource(R.string.novapulse_add_config_scan_qr_btn))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = manualDraft.isNotBlank(),
                    onClick = {
                        val payload = manualDraft
                        showAddDialog = false
                        onImportManualConfig(payload)
                    }
                ) {
                    Text(stringResource(R.string.novapulse_add_config_save_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.novapulse_add_config_cancel_btn))
                }
            }
        )
    }
}

@Composable
private fun ConfigPipelineSectionHeader(
    title: String,
    count: Int,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            color = accent.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConfigRowCard(
    row: ConfigUiRow,
    isSelected: Boolean,
    coreRunning: Boolean,
    bulkMode: Boolean,
    isBulkChecked: Boolean,
    onClick: () -> Unit,
    onToggleBulk: () -> Unit,
) {
    val isConnectedHere = isSelected && coreRunning

    Glass3dCard(
        cornerRadius = 18.dp,
        glowColor = when {
            isConnectedHere -> QsColors.ConnectedEmerald.copy(alpha = 0.65f)
            isSelected -> QsColors.ElectricCyan.copy(alpha = 0.55f)
            row.pipeline == ConfigPipeline.STANDARD -> QsColors.PlasmaViolet.copy(alpha = 0.28f)
            else -> QsColors.GlassBorderTop.copy(alpha = 0.25f)
        },
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bulkMode) {
                Checkbox(
                    checked = isBulkChecked,
                    onCheckedChange = { onToggleBulk() }
                )
            }
            StatusDot(active = isConnectedHere || isSelected)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.remarks,
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isConnectedHere) {
                        Text(
                            text = stringResource(R.string.novapulse_connected_badge),
                            color = QsColors.ConnectedEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (isSelected) {
                        Text(
                            text = stringResource(R.string.novapulse_selected_badge),
                            color = QsColors.ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (row.pipeline == ConfigPipeline.CDN) {
                        if (row.cdnHint != CdnType.UNKNOWN) {
                            CdnProviderBadge(cdnType = row.cdnHint)
                        } else if (row.transportBadge.isNotBlank()) {
                            StandardPipelineBadge(label = row.transportBadge, accent = QsColors.ElectricCyan)
                        }
                    } else {
                        StandardPipelineBadge(
                            label = row.transportBadge.ifBlank { "Standard" },
                            accent = QsColors.PlasmaViolet,
                        )
                    }
                    if (row.isManual) {
                        StandardPipelineBadge(
                            label = stringResource(R.string.novapulse_manual_badge),
                            accent = QsColors.SolarGold,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val delayLabel = when {
                row.delayMillis > 0L -> "${row.delayMillis} ms"
                row.delayMillis == -1L -> "—"
                else -> stringResource(R.string.novapulse_timeout)
            }
            val delayColor = when {
                row.delayMillis in 1..450 -> QsColors.ConnectedEmerald
                row.delayMillis > 450 -> QsColors.SolarGold
                else -> QsColors.TextMuted
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(delayColor.copy(alpha = 0.14f))
                    .border(0.75.dp, delayColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = delayLabel,
                    color = delayColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}
