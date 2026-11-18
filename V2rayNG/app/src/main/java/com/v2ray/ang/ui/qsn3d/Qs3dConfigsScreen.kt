package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.R
import com.v2ray.ang.senpai.ConfigListBucket
import com.v2ray.ang.senpai.ConfigPipeline

/**
 * Redesigned Servers / Configs Screen:
 * - Clean single-track segmented switcher (`All | CDN | Direct`) instead of generic boxy chips
 * - Dedicated top Search + Add Config bar without visual clutter
 * - Crisp server rows with left/right active indicator bar, pipeline badge & tabular delay badge
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
    var showAddDialog by remember { mutableStateOf(false) }

    val allCount = state.configs.size
    val cdnCount = state.configs.count { it.pipeline == ConfigPipeline.CDN }
    val stdCount = state.configs.count { it.pipeline == ConfigPipeline.STANDARD }

    val cdnRows = filteredConfigs.filter { it.pipeline == ConfigPipeline.CDN }
    val stdRows = filteredConfigs.filter { it.pipeline == ConfigPipeline.STANDARD }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        // Search + Dedicated Add Config Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.configSearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.novapulse_search_servers_hint),
                        color = QsColors.TextMuted,
                        fontSize = 13.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QsColors.ElectricCyan,
                    unfocusedBorderColor = QsColors.BorderSubtle,
                    focusedContainerColor = QsColors.SurfaceInset,
                    unfocusedContainerColor = QsColors.SurfaceInset,
                    focusedTextColor = QsColors.TextPrimary,
                    unfocusedTextColor = QsColors.TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = QsColors.ElectricCyan.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.45f)),
                modifier = Modifier
                    .height(50.dp)
                    .clickable { showAddDialog = true },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_add_config_btn),
                        color = QsColors.ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Unified Segmented Pipeline Switcher (Anti-Slop single track)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(QsColors.SurfaceInset)
                .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_all, allCount),
                selected = state.configBucket == ConfigListBucket.ALL,
                onClick = { onBucketChange(ConfigListBucket.ALL) },
                modifier = Modifier.weight(1f),
            )
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_cdn, cdnCount),
                selected = state.configBucket == ConfigListBucket.CDN,
                onClick = { onBucketChange(ConfigListBucket.CDN) },
                modifier = Modifier.weight(1f),
            )
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_standard, stdCount),
                selected = state.configBucket == ConfigListBucket.STANDARD,
                onClick = { onBucketChange(ConfigListBucket.STANDARD) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        // Compact Utilities Strip OR Bulk Selection Action Bar
        if (state.bulkSelectedGuids.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = QsColors.DangerCrimson.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.bulkSelectedGuids.size} ${stringResource(R.string.qsn3d_selected)}",
                        style = QsTextStyles.TabularStat,
                        color = QsColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onClearBulkSelection) {
                            Text(stringResource(R.string.qsn3d_cancel), color = QsColors.TextSecondary, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onDeleteBulkSelected,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QsColors.DangerCrimson),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.qsn3d_delete_count_fmt, state.bulkSelectedGuids.size),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UtilityActionButton(
                    label = if (state.isDelayTestingAll) state.delayTestProgress.ifBlank { "…" }
                    else stringResource(R.string.qsn3d_test_all),
                    enabled = !state.isDelayTestingAll,
                    onClick = onTestAllDelays,
                    modifier = Modifier.weight(1f),
                )
                UtilityActionButton(
                    label = stringResource(R.string.qsn3d_sort_delay),
                    enabled = true,
                    onClick = onSortByDelay,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.isDelayTestingAll) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = QsColors.ElectricCyan,
                trackColor = QsColors.SurfaceInset,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Server List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.configBucket != ConfigListBucket.STANDARD && cdnRows.isNotEmpty()) {
                item(key = "hdr_cdn") {
                    Text(
                        text = stringResource(R.string.novapulse_configs_section_cdn),
                        color = QsColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(cdnRows, key = { it.guid }) { cfg ->
                    ServerNodeRow(
                        cfg = cfg,
                        selected = cfg.guid == state.selectedGuid,
                        running = cfg.guid == state.selectedGuid && state.coreRunning,
                        inBulkMode = state.bulkSelectedGuids.isNotEmpty(),
                        bulkChecked = cfg.guid in state.bulkSelectedGuids,
                        onClick = {
                            if (state.bulkSelectedGuids.isNotEmpty()) onToggleBulkSelect(cfg.guid)
                            else onSelectConfig(cfg.guid)
                        },
                        onLongClick = { onToggleBulkSelect(cfg.guid) },
                    )
                }
            }

            if (state.configBucket != ConfigListBucket.CDN && stdRows.isNotEmpty()) {
                item(key = "hdr_std") {
                    Text(
                        text = stringResource(R.string.novapulse_configs_section_standard),
                        color = QsColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(stdRows, key = { it.guid }) { cfg ->
                    ServerNodeRow(
                        cfg = cfg,
                        selected = cfg.guid == state.selectedGuid,
                        running = cfg.guid == state.selectedGuid && state.coreRunning,
                        inBulkMode = state.bulkSelectedGuids.isNotEmpty(),
                        bulkChecked = cfg.guid in state.bulkSelectedGuids,
                        onClick = {
                            if (state.bulkSelectedGuids.isNotEmpty()) onToggleBulkSelect(cfg.guid)
                            else onSelectConfig(cfg.guid)
                        },
                        onLongClick = { onToggleBulkSelect(cfg.guid) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddManualConfigDialog(
            onDismiss = { showAddDialog = false },
            onImport = { raw ->
                onImportManualConfig(raw)
                showAddDialog = false
            },
            onScanQr = {
                showAddDialog = false
                onScanConfigQr()
            },
        )
    }
}

@Composable
private fun SegmentBucketTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) QsColors.SurfaceElevated else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, QsColors.BorderStrong) else null,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = QsTextStyles.TabularStat,
                color = if (selected) QsColors.ElectricCyan else QsColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UtilityActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = QsColors.SurfaceCard,
        border = BorderStroke(1.dp, QsColors.BorderSubtle),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (enabled) QsColors.TextPrimary else QsColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerNodeRow(
    cfg: ConfigUiRow,
    selected: Boolean,
    running: Boolean,
    inBulkMode: Boolean,
    bulkChecked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accentBorder = when {
        running -> QsColors.ConnectedEmerald.copy(alpha = 0.55f)
        selected -> QsColors.ElectricCyan.copy(alpha = 0.55f)
        else -> QsColors.BorderSubtle
    }
    val rowBg = when {
        running -> QsColors.ConnectedEmerald.copy(alpha = 0.08f)
        selected -> QsColors.SurfaceElevated
        else -> QsColors.SurfaceCard
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(14.dp),
        color = rowBg,
        border = BorderStroke(1.dp, accentBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (inBulkMode) {
                    Checkbox(
                        checked = bulkChecked,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(checkedColor = QsColors.ElectricCyan),
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    running -> QsColors.ConnectedEmerald
                                    selected -> QsColors.ElectricCyan
                                    else -> QsColors.BorderStrong
                                }
                            )
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cfg.remarks.ifBlank { stringResource(R.string.novapulse_default_server_name) },
                        color = QsColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (cfg.pipeline == ConfigPipeline.STANDARD) {
                            StandardPipelineBadge(label = cfg.transportBadge)
                        } else {
                            CdnProviderBadge(cdnType = cfg.cdnHint)
                        }

                        if (cfg.isManual) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = QsColors.SurfaceInset,
                                border = BorderStroke(1.dp, QsColors.BorderSubtle),
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_manual_badge),
                                    color = QsColors.TextSecondary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }

                        if (running) {
                            Text(
                                text = stringResource(R.string.novapulse_connected_badge),
                                color = QsColors.ConnectedEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Latency Pill
            val delay = cfg.delayMillis
            val (delayText, delayColor) = when {
                delay > 0L && delay < 900L -> "${delay} ms" to QsColors.ConnectedEmerald
                delay >= 900L -> "${delay} ms" to QsColors.SolarGold
                delay < 0L -> stringResource(R.string.novapulse_timeout) to QsColors.DangerCrimson
                else -> "—" to QsColors.TextMuted
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = QsColors.SurfaceInset,
                border = BorderStroke(1.dp, QsColors.BorderSubtle),
            ) {
                Text(
                    text = delayText,
                    style = QsTextStyles.TabularStat,
                    color = delayColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun AddManualConfigDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onScanQr: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Dialog(onDismissRequest = onDismiss) {
        Glass3dCard(
            surfaceColor = QsColors.SurfaceElevated,
            glowColor = QsColors.ElectricCyan.copy(alpha = 0.40f),
        ) {
            Text(
                text = stringResource(R.string.novapulse_add_config_title),
                color = QsColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.novapulse_add_config_hint),
                            color = QsColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = QsTextStyles.MonoCode.copy(color = QsColors.TextPrimary, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QsColors.ElectricCyan,
                        unfocusedBorderColor = QsColors.BorderStrong,
                        focusedContainerColor = QsColors.SurfaceInset,
                        unfocusedContainerColor = QsColors.SurfaceInset,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) inputText = pasted
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(stringResource(R.string.novapulse_add_config_paste_btn), color = QsColors.TextPrimary, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(stringResource(R.string.novapulse_add_config_scan_qr_btn), color = QsColors.ElectricCyan, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.novapulse_add_config_cancel_btn), color = QsColors.TextSecondary)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (inputText.isNotBlank()) onImport(inputText) },
                    enabled = inputText.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QsColors.ElectricCyan,
                        contentColor = Color(0xFF04151E),
                    ),
                ) {
                    Text(stringResource(R.string.novapulse_add_config_save_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
