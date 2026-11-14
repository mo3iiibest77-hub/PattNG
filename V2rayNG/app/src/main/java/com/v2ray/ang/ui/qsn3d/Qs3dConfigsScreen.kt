package com.v2ray.ang.ui.qsn3d

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType

@Composable
fun Qs3dConfigsScreen(
    state: Qs3dUiState,
    filteredConfigs: List<ConfigUiRow>,
    onSelectConfig: (String) -> Unit,
    onSmartConnectConfig: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleFilter: (ConfigFilterChip) -> Unit,
    onImportClipboard: () -> Unit,
    onLaunchQrScanner: () -> Unit,
    onLaunchAddManualConfig: () -> Unit,
    onEditConfig: (ConfigUiRow) -> Unit,
    onCopyConfig: (String) -> Unit,
    onGetQrBitmap: (String) -> Bitmap?,
    onDeleteConfig: (String) -> Unit,
    onToggleBulkSelect: (String) -> Unit,
    onClearBulkSelection: () -> Unit,
    onDeleteBulkSelected: () -> Unit,
    onTestAllDelays: () -> Unit,
    onSortByDelay: () -> Unit,
    onRemoveInvalid: () -> Unit,
    onAddSubscription: (String, String) -> Unit,
    onToggleSubscription: (String, Boolean) -> Unit,
    onRenameSubscription: (String, String) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onRefreshAllSubs: () -> Unit,
) {
    var subsExpanded by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var renameSubTarget by remember { mutableStateOf<SubscriptionUiRow?>(null) }
    var qrPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bulkMode by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Subscriptions Collapsible Section
        item {
            Glass3dCard(glowColor = QsColors.AuroraMint.copy(alpha = 0.28f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { subsExpanded = !subsExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${stringResource(R.string.qsn3d_subscriptions)} (${state.subscriptions.size})",
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (subsExpanded) stringResource(R.string.qsn3d_tap_collapse)
                            else stringResource(R.string.qsn3d_tap_manage_subs),
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = { showAddSubDialog = true }) {
                        Text("+ ${stringResource(R.string.qsn3d_add)}")
                    }
                    TextButton(onClick = onRefreshAllSubs) {
                        Text(stringResource(R.string.qsn3d_refresh))
                    }
                }

                if (subsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    state.subscriptions.forEach { sub ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.remarks,
                                        color = QsColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "${sub.configCount} configs",
                                        color = QsColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                IconButton(onClick = { renameSubTarget = sub }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit_24dp),
                                        contentDescription = "Rename",
                                        tint = QsColors.TextSecondary
                                    )
                                }
                                IconButton(onClick = { onDeleteSubscription(sub.guid) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete_24dp),
                                        contentDescription = "Delete",
                                        tint = QsColors.DangerCrimson
                                    )
                                }
                                Switch(
                                    checked = sub.enabled,
                                    onCheckedChange = { onToggleSubscription(sub.guid, it) }
                                )
                            }
                            sub.quota?.let { q ->
                                Spacer(Modifier.height(6.dp))
                                QuotaBarContent(q)
                            }
                        }
                    }
                }
            }
        }

        // 2. Import & Test Toolbar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onImportClipboard, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.qsn3d_import_clipboard), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onLaunchQrScanner, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.qsn3d_scan_qr), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onLaunchAddManualConfig, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.qsn3d_add_manual), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onTestAllDelays, shape = RoundedCornerShape(50)) {
                    Text(
                        text = if (state.isDelayTestingAll) {
                            "${stringResource(R.string.qsn3d_stop)} (${state.delayTestProgress})"
                        } else {
                            stringResource(R.string.qsn3d_test_delays)
                        },
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(onClick = onSortByDelay, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.qsn3d_sort_delay), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRemoveInvalid, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.qsn3d_clean_invalid), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        bulkMode = !bulkMode
                        if (!bulkMode) onClearBulkSelection()
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text(stringResource(R.string.qsn3d_bulk_select), fontSize = 12.sp)
                }
            }
        }

        if (bulkMode && state.bulkSelectedGuids.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(QsColors.DangerCrimson.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.bulkSelectedGuids.size} ${stringResource(R.string.qsn3d_selected)}",
                        color = QsColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Button(onClick = {
                        onDeleteBulkSelected()
                        bulkMode = false
                    }) {
                        Text(stringResource(R.string.qsn3d_delete_selected))
                    }
                }
            }
        }

        // 3. Search & Filter Chips
        item {
            OutlinedTextField(
                value = state.configSearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.qsn3d_search_configs), fontSize = 12.sp) }
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConfigFilterChip.entries.forEach { chip ->
                    FilterChip(
                        selected = chip in state.activeFilters,
                        onClick = { onToggleFilter(chip) },
                        label = { Text(chip.name, fontSize = 11.sp) }
                    )
                }
            }
        }

        // 4. Config Rows
        items(filteredConfigs, key = { it.guid }) { row ->
            val selected = row.guid == state.selectedGuid
            Glass3dCard(
                glowColor = if (selected) QsColors.ConnectedEmerald.copy(alpha = 0.65f)
                else QsColors.ElectricCyan.copy(alpha = 0.2f),
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
                            checked = row.guid in state.bulkSelectedGuids,
                            onCheckedChange = { onToggleBulkSelect(row.guid) }
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.remarks,
                            color = if (selected) QsColors.AuroraMint else QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        val meta = buildString {
                            append(row.protocol)
                            if (row.network.isNotBlank()) append(" • ${row.network}")
                            if (row.security.isNotBlank()) append("+${row.security}")
                            append(" • ${row.originDomain.ifBlank { row.server }}:${row.port}")
                        }
                        Text(
                            text = meta,
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (row.cdnHint != CdnType.UNKNOWN) {
                        Spacer(Modifier.width(6.dp))
                        ProviderPillBadge(cdnType = row.cdnHint)
                    }

                    if (row.delayMillis > 0L) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${row.delayMillis}ms",
                            color = if (row.delayMillis < 800) QsColors.ConnectedEmerald else QsColors.SolarGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else if (row.delayMillis == -2L || row.delayMillis == 0L) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "-",
                            color = QsColors.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSmartConnectConfig(row.guid) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.qsn3d_scan_and_connect),
                            color = QsColors.ElectricCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row {
                        IconButton(
                            onClick = { qrPreviewBitmap = onGetQrBitmap(row.guid) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share_24dp),
                                contentDescription = "QR",
                                tint = QsColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onCopyConfig(row.guid) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = "Copy",
                                tint = QsColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onEditConfig(row) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit_24dp),
                                contentDescription = "Edit",
                                tint = QsColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (!selected) {
                            IconButton(
                                onClick = { onDeleteConfig(row.guid) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_24dp),
                                    contentDescription = "Delete",
                                    tint = QsColors.DangerCrimson.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSubDialog) {
        var remarks by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSubDialog = false },
            title = { Text(stringResource(R.string.qsn3d_add_subscription)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = remarks,
                        onValueChange = { remarks = it },
                        label = { Text(stringResource(R.string.qsn3d_sub_name)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.qsn3d_sub_url)) },
                        singleLine = false
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAddSubscription(remarks, url)
                    showAddSubDialog = false
                }) { Text(stringResource(R.string.qsn3d_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddSubDialog = false }) {
                    Text(stringResource(R.string.qsn3d_cancel))
                }
            }
        )
    }

    renameSubTarget?.let { target ->
        var text by remember(target.guid) { mutableStateOf(target.remarks) }
        AlertDialog(
            onDismissRequest = { renameSubTarget = null },
            title = { Text(stringResource(R.string.qsn3d_rename_subscription)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameSubscription(target.guid, text)
                    renameSubTarget = null
                }) { Text(stringResource(R.string.qsn3d_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameSubTarget = null }) {
                    Text(stringResource(R.string.qsn3d_cancel))
                }
            }
        )
    }

    qrPreviewBitmap?.let { bmp ->
        AlertDialog(
            onDismissRequest = { qrPreviewBitmap = null },
            title = { Text("QR") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { qrPreviewBitmap = null }) {
                    Text(stringResource(R.string.qsn3d_close))
                }
            }
        )
    }
}
