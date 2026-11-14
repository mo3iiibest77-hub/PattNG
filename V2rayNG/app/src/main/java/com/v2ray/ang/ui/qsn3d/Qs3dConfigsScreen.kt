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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType

/**
 * Simplified, commercial-grade NovaPulse Server List:
 * - Zero raw protocol/domain/port/URL leaks
 * - Zero Copy / Share / Edit / Classic / Technical filter chips
 * - Keeps Search, Test All Delays, Sort by Ping, Bulk Select/Delete
 * - Always pins connected/active server at the top of the list
 */
@Composable
fun Qs3dConfigsScreen(
    state: Qs3dUiState,
    filteredConfigs: List<ConfigUiRow>,
    onSelectConfig: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleBulkSelect: (String) -> Unit,
    onClearBulkSelection: () -> Unit,
    onDeleteBulkSelected: () -> Unit,
    onTestAllDelays: () -> Unit,
    onSortByDelay: () -> Unit,
) {
    var bulkMode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 0. Illustrated Cloud Nodes Banner
        item {
            SectionHeroBanner(
                imageRes = R.drawable.np_hero_configs_nodes,
                title = stringResource(R.string.novapulse_hero_configs_title),
                subtitle = stringResource(R.string.novapulse_hero_configs_sub),
                accentColor = QsColors.ElectricCyan,
                height = 116.dp,
            )
        }

        // 1. Search Bar & Action Strip (Test All, Sort by Ping, Bulk Select)
        item {
            Glass3dCard(glowColor = QsColors.ElectricCyan.copy(alpha = 0.30f)) {
                OutlinedTextField(
                    value = state.configSearchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text(stringResource(R.string.novapulse_search_servers_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

        // 2. Clean Privacy-Protected Server Rows (Connected Pinned First!)
        items(filteredConfigs, key = { it.guid }) { row ->
            val isSelected = row.guid == state.selectedGuid
            val isConnectedHere = isSelected && state.coreRunning
            val isBulkChecked = row.guid in state.bulkSelectedGuids

            Glass3dCard(
                cornerRadius = 18.dp,
                glowColor = when {
                    isConnectedHere -> QsColors.ConnectedEmerald.copy(alpha = 0.65f)
                    isSelected -> QsColors.ElectricCyan.copy(alpha = 0.55f)
                    else -> QsColors.GlassBorderTop.copy(alpha = 0.25f)
                },
                onClick = {
                    if (bulkMode) onToggleBulkSelect(row.guid) else onSelectConfig(row.guid)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (bulkMode) {
                        Checkbox(
                            checked = isBulkChecked,
                            onCheckedChange = { onToggleBulkSelect(row.guid) }
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
                        if (isConnectedHere || isSelected || row.cdnHint != CdnType.UNKNOWN) {
                            Spacer(Modifier.height(4.dp))
                            Row(
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
                                if (row.cdnHint != CdnType.UNKNOWN) {
                                    CdnProviderBadge(cdnType = row.cdnHint)
                                }
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
    }
}
