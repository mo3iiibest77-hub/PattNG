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
import androidx.compose.foundation.lazy.itemsIndexed
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
 * 3D Frosted Glass Servers Screen:
 * - Separate Label + Count Badge pills inside segmented filter (eliminates RTL/LTR parenthetical slop)
 * - Official Cloudflare / CloudFront / Direct logos on every server card
 * - Auto-ping indicator (`در حال تست…` / live ms latency / `آماده تست`) instead of static "عدم پاسخ"
 * - Rotating perimeter light beam on active/selected server card
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

        // Search + Add Config Bar
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
                color = QsColors.ElectricCyan.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.50f)),
                modifier = Modifier
                    .height(52.dp)
                    .clickable { showAddDialog = true },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_add_config_btn),
                        color = QsColors.ElectricCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Unified Segmented Pipeline Switcher with Separate Clean Count Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(QsColors.SurfaceInset)
                .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_all),
                count = allCount,
                selected = state.configBucket == ConfigListBucket.ALL,
                onClick = { onBucketChange(ConfigListBucket.ALL) },
                modifier = Modifier.weight(0.85f),
            )
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_cdn),
                count = cdnCount,
                selected = state.configBucket == ConfigListBucket.CDN,
                onClick = { onBucketChange(ConfigListBucket.CDN) },
                modifier = Modifier.weight(1.15f),
            )
            SegmentBucketTab(
                label = stringResource(R.string.novapulse_configs_tab_standard),
                count = stdCount,
                selected = state.configBucket == ConfigListBucket.STANDARD,
                onClick = { onBucketChange(ConfigListBucket.STANDARD) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (state.bulkSelectedGuids.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = QsColors.DangerCrimson.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.40f)),
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onClearBulkSelection) {
                            Text(stringResource(R.string.qsn3d_cancel), color = QsColors.TextSecondary, fontSize = 13.sp)
                        }
                        Button(
                            onClick = onDeleteBulkSelected,
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QsColors.DangerCrimson),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
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
                    label = if (state.isDelayTestingAll) {
                        "${stringResource(R.string.novapulse_ping_testing)} ${state.delayTestProgress}"
                    } else {
                        stringResource(R.string.qsn3d_test_all)
                    },
                    enabled = true,
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
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = QsColors.ElectricCyan,
                trackColor = QsColors.SurfaceInset,
            )
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (state.configBucket != ConfigListBucket.STANDARD && cdnRows.isNotEmpty()) {
                item(key = "hdr_cdn") {
                    Text(
                        text = stringResource(R.string.novapulse_configs_section_cdn),
                        color = QsColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(cdnRows, key = { _, cfg -> cfg.guid }) { idx, cfg ->
                    ServerNodeRow(
                        cfg = cfg,
                        rowIndex = idx + 1,
                        selected = cfg.guid == state.selectedGuid,
                        running = cfg.guid == state.selectedGuid && state.coreRunning,
                        isDelayTestingAll = state.isDelayTestingAll,
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(stdRows, key = { _, cfg -> cfg.guid }) { idx, cfg ->
                    ServerNodeRow(
                        cfg = cfg,
                        rowIndex = cdnRows.size + idx + 2,
                        selected = cfg.guid == state.selectedGuid,
                        running = cfg.guid == state.selectedGuid && state.coreRunning,
                        isDelayTestingAll = state.isDelayTestingAll,
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
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) QsColors.SurfaceElevated else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.45f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            Text(
                text = label,
                color = if (selected) QsColors.TextPrimary else QsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (selected) QsColors.ElectricCyan.copy(alpha = 0.18f)
                        else QsColors.BorderSubtle.copy(alpha = 0.65f)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = QsTextStyles.TabularStat,
                    color = if (selected) QsColors.ElectricCyan else QsColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = QsColors.SurfaceCard,
        border = BorderStroke(1.dp, QsColors.BorderSubtle),
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (enabled) QsColors.TextPrimary else QsColors.TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerNodeRow(
    cfg: ConfigUiRow,
    rowIndex: Int,
    selected: Boolean,
    running: Boolean,
    isDelayTestingAll: Boolean,
    inBulkMode: Boolean,
    bulkChecked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val activeGlow = when {
        running -> QsColors.ConnectedEmerald.copy(alpha = 0.78f)
        selected -> QsColors.ElectricCyan.copy(alpha = 0.64f)
        cfg.pipeline == ConfigPipeline.STANDARD -> QsColors.AuroraMint.copy(alpha = 0.28f)
        else -> QsColors.ElectricCyan.copy(alpha = 0.26f)
    }

    val rowSurface = when {
        running -> Color(0xB80D2722)
        selected -> QsColors.SurfaceElevated
        else -> QsColors.SurfaceCard
    }

    val delay = cfg.delayMillis
    val (delayText, delayColor) = when {
        delay > 0L && delay < 900L -> "${delay} ms" to QsColors.ConnectedEmerald
        delay >= 900L -> "${delay} ms" to QsColors.SolarGold
        isDelayTestingAll && delay == 0L -> stringResource(R.string.novapulse_ping_testing) to QsColors.ElectricCyan
        delay == 0L -> stringResource(R.string.novapulse_ping_untested) to QsColors.TextSecondary
        else -> stringResource(R.string.novapulse_timeout) to QsColors.DangerCrimson
    }

    val cardMod = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)

    Glass3dCard(
        modifier = cardMod,
        cornerRadius = 16.dp,
        glowColor = activeGlow,
        surfaceColor = rowSurface,
        contentPadding = 13.dp,
        beamSeed = rowIndex,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                            .size(10.dp)
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
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
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
                                shape = RoundedCornerShape(7.dp),
                                color = QsColors.SurfaceInset,
                                border = BorderStroke(1.dp, QsColors.BorderSubtle),
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_manual_badge),
                                    color = QsColors.TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }

                        if (running) {
                            Text(
                                text = stringResource(R.string.novapulse_connected_badge),
                                color = QsColors.ConnectedEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(9.dp),
                color = QsColors.SurfaceInset,
                border = BorderStroke(1.dp, delayColor.copy(alpha = 0.35f)),
            ) {
                Text(
                    text = delayText,
                    style = QsTextStyles.TabularStat,
                    color = delayColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
            glowColor = QsColors.ElectricCyan.copy(alpha = 0.45f),
            beamSeed = 99,
        ) {
            Text(
                text = stringResource(R.string.novapulse_add_config_title),
                color = QsColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(126.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.novapulse_add_config_hint),
                            color = QsColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = QsTextStyles.MonoCode.copy(color = QsColors.TextPrimary, fontSize = 13.sp),
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
                    Text(stringResource(R.string.novapulse_add_config_paste_btn), color = QsColors.TextPrimary, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(stringResource(R.string.novapulse_add_config_scan_qr_btn), color = QsColors.ElectricCyan, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.novapulse_add_config_cancel_btn), color = QsColors.TextSecondary, fontSize = 13.sp)
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
                    Text(stringResource(R.string.novapulse_add_config_save_btn), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
