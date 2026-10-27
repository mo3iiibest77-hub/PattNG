package com.v2ray.ang.ui.qsn

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.qsn.domain.ConfigFilter
import com.v2ray.ang.ui.qsn.domain.ConfigFilterEngine
import com.v2ray.ang.ui.qsn.domain.ConfigGroup
import com.v2ray.ang.ui.qsn.domain.ConfigGrouping
import com.v2ray.ang.ui.qsn.domain.ConfigRow
import com.v2ray.ang.ui.qsn.domain.ConfigSource
import com.v2ray.ang.ui.qsn.domain.SubscriptionRow

/**
 * The Configurations tab.
 *
 * Four sections, top to bottom:
 *  1. the collapsible subscription panel (add / enable / update / delete),
 *  2. the filter chips, combined with AND,
 *  3. one collapsible section per subscription, holding only the
 *     Cloudflare-ready configurations of that subscription,
 *  4. the "Added by You" section for manual imports.
 *
 * The list is a view, not a delete tool: configurations that are not
 * Cloudflare-ready are hidden here but stay in storage and stay editable
 * through the normal editor, so no protocol support is lost.
 *
 * Every action is a callback into [QsViewModel]; this file holds no state that
 * outlives the screen except the text of the open dialogs.
 */
@Composable
fun QsConfigScreen(
    state: QsUiState,
    onToggleSubscriptions: () -> Unit,
    onAddSubscription: (String, String) -> Unit,
    onRenameSubscription: (String, String) -> Unit,
    onUpdateSubscription: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleSubscription: (String, Boolean) -> Unit,
    onActivateSubscription: (String) -> Unit,
    onRequestDeleteSubscription: (String) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onDismissDeleteSubscription: () -> Unit,
    onToggleFilter: (ConfigFilter) -> Unit,
    onClearFilters: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onSelectConfig: (String) -> Unit,
    onConfigAction: (String) -> Unit,
    onEditConfig: (String) -> Unit,
    onDuplicateConfig: (String) -> Unit,
    onRequestDeleteConfig: (String) -> Unit,
    onDeleteConfig: (String) -> Unit,
    onDismissDeleteConfig: () -> Unit,
    onOpenImport: () -> Unit,
    onCloseImport: () -> Unit,
    onImport: (String) -> Unit,
    onToggleLanguage: () -> Unit,
) {
    var addDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SubscriptionRow?>(null) }

    val groups = ConfigGrouping.groups(
        subscriptions = state.subscriptions,
        rows = state.configRows,
        manualTitle = stringResource(R.string.qs_group_added_by_you),
    )
    val readyCount = state.configRows.count { it.eligible }
    val shownCount = groups.sumOf { group ->
        ConfigFilterEngine.apply(state.filters, group.rows).size
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            QsScreenHeader(
                title = stringResource(R.string.qs_tab_configs),
                languageCode = state.languageCode,
                onToggleLanguage = onToggleLanguage,
                onAddConfig = onOpenImport,
            )
        }

        item(key = "subscriptions") {
            SubscriptionPanel(
                state = state,
                onToggle = onToggleSubscriptions,
                onUpdate = onUpdateSubscription,
                onUpdateAll = onUpdateAll,
                onToggleEnabled = onToggleSubscription,
                onActivate = onActivateSubscription,
                onRename = { renameTarget = it },
                onDelete = onRequestDeleteSubscription,
                onAdd = { addDialogOpen = true },
            )
        }

        if (state.status != QsStatus.Disconnected || state.message != null) {
            item(key = "connection-status") {
                ConnectionBanner(state = state)
            }
        }

        item(key = "filters") {
            FilterRow(
                selected = state.filters,
                onToggle = onToggleFilter,
                onClear = onClearFilters,
            )
        }

        item(key = "summary") {
            Text(
                text = stringResource(
                    R.string.qs_configs_summary,
                    shownCount,
                    state.configRows.size,
                    readyCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (groups.isEmpty()) {
            item(key = "empty") {
                EmptyConfigs(hasRows = state.configRows.isNotEmpty())
            }
        }

        groups.forEach { group ->
            val collapsed = state.collapsedGroups.contains(group.key)
            item(key = "group-" + group.key) {
                GroupHeader(
                    group = group,
                    collapsed = collapsed,
                    onToggle = { onToggleGroup(group.key) },
                    onImport = if (group.manual) onOpenImport else null,
                )
            }
            if (!collapsed) {
                val visible = ConfigFilterEngine.apply(state.filters, group.rows)
                if (visible.isEmpty()) {
                    item(key = "group-empty-" + group.key) {
                        Text(
                            text = if (group.manual) {
                                stringResource(R.string.qs_configs_filter_empty)
                            } else {
                                stringResource(R.string.qs_group_none_ready)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        )
                    }
                } else {
                    items(items = visible, key = { it.guid }) { row ->
                        ConfigCard(
                            row = row,
                            status = state.status,
                            connected = state.coreRunning && state.connectedGuid == row.guid,
                            busy = state.status == QsStatus.Connecting ||
                                state.status == QsStatus.Disconnecting,
                            onSelect = { onSelectConfig(row.guid) },
                            onAction = { onConfigAction(row.guid) },
                            onEdit = { onEditConfig(row.guid) },
                            onDuplicate = { onDuplicateConfig(row.guid) },
                            onDelete = { onRequestDeleteConfig(row.guid) },
                        )
                    }
                }
            }
        }
    }

    if (addDialogOpen) {
        AddSubscriptionDialog(
            onDismiss = { addDialogOpen = false },
            onConfirm = { remarks, url ->
                addDialogOpen = false
                onAddSubscription(remarks, url)
            },
        )
    }

    val target = renameTarget
    if (target != null) {
        RenameSubscriptionDialog(
            current = target.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                onRenameSubscription(target.guid, name)
            },
        )
    }

    if (state.importOpen) {
        QsAddConfigDialog(
            error = state.importError,
            onDismiss = onCloseImport,
            onConfirm = onImport,
        )
    }

    val pendingConfig = state.pendingDeleteGuid
    if (pendingConfig != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.qs_delete_config_title),
            body = stringResource(R.string.qs_delete_config_body),
            onDismiss = onDismissDeleteConfig,
            onConfirm = { onDeleteConfig(pendingConfig) },
        )
    }

    val pendingSubscription = state.pendingDeleteSubscription
    if (pendingSubscription != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.qs_delete_sub_title),
            body = stringResource(R.string.qs_delete_sub_body),
            onDismiss = onDismissDeleteSubscription,
            onConfirm = { onDeleteSubscription(pendingSubscription) },
        )
    }
}

/** Stable identity for a collapsible section. */
private val ConfigGroup.key: String
    get() = if (manual) "manual" else "sub-" + subscriptionId

@Composable
private fun GroupHeader(
    group: ConfigGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onImport: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            // Translucent so the animated network field stays visible behind
            // the list without hurting the contrast of the text.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (group.manual) {
                        stringResource(R.string.qs_group_manual_hint)
                    } else {
                        stringResource(
                            R.string.qs_group_ready,
                            group.rows.size,
                            group.totalConfigs,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onImport != null) {
                TextButton(onClick = onImport) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.qs_import_manual))
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = stringResource(
                        if (collapsed) R.string.qs_expand else R.string.qs_collapse,
                    ),
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (collapsed) 0f else 180f),
                )
            }
        }
    }
}

@Composable
private fun SubscriptionPanel(
    state: QsUiState,
    onToggle: () -> Unit,
    onUpdate: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onActivate: (String) -> Unit,
    onRename: (SubscriptionRow) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.qs_subscriptions_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.qs_subscriptions_summary,
                            state.subscriptions.size,
                            state.subscriptions.count { it.enabled },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24dp),
                        contentDescription = stringResource(
                            if (state.subscriptionsExpanded) {
                                R.string.qs_collapse
                            } else {
                                R.string.qs_expand
                            },
                        ),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            if (!state.subscriptionsExpanded) return@Column

            Spacer(Modifier.height(6.dp))
            state.subscriptions.forEach { row ->
                SubscriptionItemRow(
                    row = row,
                    busy = state.refreshing,
                    onUpdate = { onUpdate(row.guid) },
                    onToggleEnabled = { onToggleEnabled(row.guid, it) },
                    onActivate = { onActivate(row.guid) },
                    onRename = { onRename(row) },
                    onDelete = { onDelete(row.guid) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAdd) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.qs_sub_add))
                }
                TextButton(
                    onClick = onUpdateAll,
                    enabled = !state.refreshing && state.subscriptions.any { it.enabled },
                ) {
                    Text(
                        text = if (state.refreshing) {
                            stringResource(R.string.qs_updating)
                        } else {
                            stringResource(R.string.qs_update_all)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionItemRow(
    row: SubscriptionRow,
    busy: Boolean,
    onUpdate: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (row.active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggleEnabled,
            )
        }
        Text(
            text = row.redactedUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.qs_sub_meta,
                row.configCount,
                formatTimestamp(row.lastUpdated),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onActivate, enabled = row.enabled && !row.active) {
                Text(stringResource(R.string.qs_sub_activate))
            }
            TextButton(onClick = onUpdate, enabled = row.enabled && !busy) {
                Text(stringResource(R.string.qs_update))
            }
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.qs_rename))
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.qs_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Curated chips.
 *
 * "Other transport", "Not eligible", "Manual" and "Subscription" are gone as
 * top-level categories: the sections already say where a config came from, and
 * a config that is not Cloudflare-ready is simply not listed under a
 * subscription.
 */
@Composable
private fun FilterRow(
    selected: Set<ConfigFilter>,
    onToggle: (ConfigFilter) -> Unit,
    onClear: () -> Unit,
) {
    val options = listOf(
        ConfigFilter.All to R.string.qs_filter_all,
        ConfigFilter.CloudflareEligible to R.string.qs_filter_eligible,
        ConfigFilter.WebSocketTls to R.string.qs_filter_ws_tls,
        ConfigFilter.XhttpTls to R.string.qs_filter_xhttp_tls,
        ConfigFilter.Active to R.string.qs_filter_active,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (filter, label) ->
            FilterChip(
                selected = if (filter == ConfigFilter.All) selected.isEmpty() else selected.contains(filter),
                onClick = {
                    if (filter == ConfigFilter.All) onClear() else onToggle(filter)
                },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/**
 * Compact connection state for this screen.
 *
 * The full progress lives on the Connection tab; this is the one line that
 * makes the state visible without leaving Configurations, so a Connect pressed
 * here never looks like it did nothing.
 */
@Composable
private fun ConnectionBanner(state: QsUiState) {
    val isError = state.status == QsStatus.Error
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                qsCardSurface()
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = when (state.status) {
                    QsStatus.Connecting -> stringResource(R.string.qs_status_connecting)
                    QsStatus.Connected -> stringResource(R.string.qs_status_connected)
                    QsStatus.Disconnecting -> stringResource(R.string.qs_status_disconnecting)
                    QsStatus.Error -> stringResource(R.string.qs_status_error)
                    QsStatus.Disconnected -> stringResource(R.string.qs_status_disconnected)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            val message = state.message
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    row: ConfigRow,
    status: QsStatus,
    connected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.active) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f)
            } else {
                qsCardSurface()
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // An explicit control, not a hidden tap target: which configuration
            // will be connected is never ambiguous.
            RadioButton(
                selected = row.active,
                onClick = onSelect,
                enabled = !busy,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.remarks,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.server,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.transportLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    EligibilityBadge(row)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit_24dp),
                    contentDescription = stringResource(R.string.qs_edit),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.qs_duplicate_manual),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = stringResource(R.string.qs_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Only the selected configuration offers the action, so the button can
        // never be read as belonging to a neighbouring row.
        if (row.active) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        connected -> stringResource(R.string.qs_status_connected)
                        status == QsStatus.Connecting ->
                            stringResource(R.string.qs_status_connecting)
                        status == QsStatus.Disconnecting ->
                            stringResource(R.string.qs_status_disconnecting)
                        status == QsStatus.Error -> stringResource(R.string.qs_status_error)
                        else -> stringResource(R.string.qs_choose_config_selected)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onAction,
                    enabled = !busy,
                ) {
                    Text(
                        text = if (connected) {
                            stringResource(R.string.qs_disconnect)
                        } else {
                            stringResource(R.string.qs_connect)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EligibilityBadge(row: ConfigRow) {
    val (text, color) = if (row.eligible) {
        stringResource(R.string.qs_badge_eligible) to MaterialTheme.colorScheme.primary
    } else {
        stringResource(R.string.qs_badge_not_eligible) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
    if (row.source == ConfigSource.Manual) {
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.qs_badge_manual),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyConfigs(hasRows: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = qsCardSurface()),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (hasRows) {
                    stringResource(R.string.qs_configs_filter_empty)
                } else {
                    stringResource(R.string.qs_configs_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var remarks by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_sub_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.qs_sub_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.qs_subscription_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(remarks, url) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.qs_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qs_cancel))
            }
        },
    )
}

@Composable
private fun RenameSubscriptionDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.qs_sub_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.qs_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qs_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.qs_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qs_cancel))
            }
        },
    )
}
