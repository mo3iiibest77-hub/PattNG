package com.v2ray.ang.ui.qsn

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.qsn.domain.MetricFormat
import com.v2ray.ang.util.Utils

/**
 * The Connection tab.
 *
 * Everything rendered here comes from [QsUiState]; nothing is simulated. The
 * subscription form is collapsible and collapses itself only after the URL was
 * really persisted, and the speed block renders a measurement or "N/A" — never
 * a placeholder number.
 */
@Composable
fun QsConnectScreen(
    state: QsUiState,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
    onTogglePanel: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissCrash: () -> Unit,
    onToggleLanguage: () -> Unit,
    onOpenAddConfig: () -> Unit,
    onCloseAddConfig: () -> Unit,
    onAddConfig: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QsScreenHeader(
            title = stringResource(R.string.qs_tab_connection),
            languageCode = state.languageCode,
            onToggleLanguage = onToggleLanguage,
            onAddConfig = onOpenAddConfig,
        )

        QsStatusCard(state = state, onConnect = onConnect, onDisconnect = onDisconnect, onCancel = onCancel)

        // A previous process death is shown with its recorded cause instead of
        // being forgotten, so the next report can name it.
        val lastCrash = state.lastCrash
        if (lastCrash != null) {
            QsCrashCard(crash = lastCrash, onDismiss = onDismissCrash)
        }

        QsSubscriptionCard(
            state = state,
            onUrlChange = onUrlChange,
            onSave = onSave,
            onUpdate = onUpdate,
            onTogglePanel = onTogglePanel,
        )

        if (state.message != null) {
            QsMessageCard(
                message = state.message,
                isError = state.status == QsStatus.Error,
                onDismiss = onDismissMessage,
                onRetry = if (state.status == QsStatus.Error) onConnect else null,
            )
        }

        if (state.busy || state.status == QsStatus.Connected || state.status == QsStatus.Error) {
            QsProgressCard(state = state)
        }

        if (state.status == QsStatus.Connected) {
            QsDetailsCard(state = state)
        }
    }

    // Shared with the Configurations screen: one dialog, one import path.
    if (state.importOpen) {
        QsAddConfigDialog(
            error = state.importError,
            onDismiss = onCloseAddConfig,
            onConfirm = onAddConfig,
        )
    }
}

/**
 * Small header with the EN/FA control every Quiet Storm NG screen shares.
 *
 * [onAddConfig] adds the visible "add configuration" action. It is null on
 * screens that do not accept configurations, so the action can never appear
 * where it would do nothing.
 */
@Composable
internal fun QsScreenHeader(
    title: String,
    languageCode: String,
    onToggleLanguage: () -> Unit,
    onAddConfig: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (onAddConfig != null) {
            IconButton(onClick = onAddConfig) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24dp),
                    contentDescription = stringResource(R.string.qs_add_config),
                )
            }
        }
        AssistChip(
            onClick = onToggleLanguage,
            label = {
                Text(
                    text = if (languageCode == "fa") "FA / EN" else "EN / FA",
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
    }
}

@Composable
private fun QsStatusCard(
    state: QsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = when (state.status) {
        QsStatus.Connected -> MaterialTheme.colorScheme.tertiary
        QsStatus.Connecting, QsStatus.Disconnecting -> MaterialTheme.colorScheme.secondary
        QsStatus.Error -> MaterialTheme.colorScheme.error
        QsStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state.status) {
        QsStatus.Connected -> stringResource(R.string.qs_status_connected)
        QsStatus.Connecting -> stringResource(R.string.qs_status_connecting)
        QsStatus.Disconnecting -> stringResource(R.string.qs_status_disconnecting)
        QsStatus.Error -> stringResource(R.string.qs_status_error)
        QsStatus.Disconnected -> stringResource(R.string.qs_status_disconnected)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.status == QsStatus.Connected) {
                Spacer(Modifier.height(10.dp))
                QsTrafficBlock(state = state)
            }
            Spacer(Modifier.height(18.dp))
            when {
                state.connecting -> OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.qs_cancel))
                }

                state.coreRunning || state.status == QsStatus.Disconnecting -> Button(
                    onClick = onDisconnect,
                    enabled = state.status != QsStatus.Disconnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.qs_disconnect))
                }

                else -> Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.qs_connect))
                }
            }
        }
    }
}

/**
 * Receive/Send rates reported by the core.
 *
 * Three distinct states are kept apart on purpose: a real rate (including a
 * real zero), "measuring" before the first sample, and "N/A" when the core
 * reports no statistics at all.
 */
@Composable
private fun QsTrafficBlock(state: QsUiState) {
    val traffic = state.traffic
    val receive = when {
        traffic == null -> stringResource(R.string.qs_speed_measuring)
        !traffic.available -> MetricFormat.NOT_AVAILABLE
        else -> MetricFormat.rate(traffic.downBytesPerSecond)
    }
    val send = when {
        traffic == null -> stringResource(R.string.qs_speed_measuring)
        !traffic.available -> MetricFormat.NOT_AVAILABLE
        else -> MetricFormat.rate(traffic.upBytesPerSecond)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TrafficValue(stringResource(R.string.qs_speed_receive), receive)
        TrafficValue(stringResource(R.string.qs_speed_send), send)
    }
    if (traffic != null && traffic.available) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.qs_speed_total,
                MetricFormat.bytes(traffic.totalDownBytes),
                MetricFormat.bytes(traffic.totalUpBytes),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrafficValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The subscription form.
 *
 * Collapsed by default once a subscription is stored: the main screen stays
 * readable and the URL field is one tap away. It only closes itself when the
 * save actually succeeded, and a rejected URL reopens it with the error.
 */
@Composable
private fun QsSubscriptionCard(
    state: QsUiState,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
    onTogglePanel: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (state.subscriptionPanelExpanded) 180f else 0f,
        label = "subscriptionPanelArrow",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.qs_subscription_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.subscriptionPanelExpanded && state.hasSubscription) {
                        // The panel collapses on save, so the download it
                        // started has to stay visible from here.
                        Text(
                            text = if (state.refreshing) {
                                stringResource(R.string.qs_updating)
                            } else {
                                stringResource(R.string.qs_configs, state.configCount)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onTogglePanel) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24dp),
                        contentDescription = stringResource(
                            if (state.subscriptionPanelExpanded) {
                                R.string.qs_sub_panel_close
                            } else {
                                R.string.qs_sub_panel_open
                            },
                        ),
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(rotation),
                    )
                }
            }

            if (state.refreshing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }

            if (!state.subscriptionPanelExpanded) return@Column

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.subscriptionUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.inputError != null,
                placeholder = { Text(stringResource(R.string.qs_subscription_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            if (state.inputError != null) {
                Text(
                    text = state.inputError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !state.refreshing,
                ) {
                    Text(stringResource(R.string.qs_save))
                }
                OutlinedButton(
                    onClick = onUpdate,
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy && !state.refreshing && state.hasSubscription,
                ) {
                    Text(
                        text = if (state.refreshing) {
                            stringResource(R.string.qs_updating)
                        } else {
                            stringResource(R.string.qs_update)
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.qs_configs, state.configCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (state.lastUpdated > 0L) {
                    stringResource(R.string.qs_last_update, formatTimestamp(state.lastUpdated))
                } else {
                    stringResource(R.string.qs_never_updated)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QsMessageCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.qs_retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.qs_dismiss))
                }
            }
        }
    }
}

/**
 * The recorded cause of the previous process death.
 *
 * It is a diagnosis, not a decoration: the text is the exception line plus the
 * first stack frame, and it can be copied so it can be sent back verbatim.
 */
@Composable
private fun QsCrashCard(crash: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qs_crash_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = crash,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qs_crash_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { Utils.setClipboard(context, crash) }) {
                    Text(stringResource(R.string.qs_copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.qs_dismiss))
                }
            }
        }
    }
}

@Composable
private fun QsProgressCard(state: QsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qs_steps_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val fraction = state.scanFraction
            if (state.busy) {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            val currentIndex = QsStep.progressSteps.indexOf(state.step)
            QsStep.progressSteps.forEachIndexed { index, step ->
                val isCurrent = index == currentIndex
                val isDone = state.step == QsStep.Connected || (currentIndex >= 0 && index < currentIndex)
                val isFailed = isCurrent && state.status == QsStatus.Error
                val dotColor = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    isDone -> MaterialTheme.colorScheme.primary
                    isCurrent -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stepLabel(step),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            isFailed -> MaterialTheme.colorScheme.error
                            isDone || isCurrent -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (isCurrent && state.step == QsStep.ScanningIps && state.scanTotal > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.qs_scan_progress, state.scanDone, state.scanTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QsDetailsCard(state: QsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qs_details_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            DetailRow(
                label = stringResource(R.string.qs_detail_config),
                value = state.selectedRemarks ?: stringResource(R.string.qs_detail_unknown),
            )
            // The origin server, never the Cloudflare edge: the edge has its
            // own row below so the two can never be confused.
            DetailRow(
                label = stringResource(R.string.qs_detail_server),
                value = state.selectedServer ?: stringResource(R.string.qs_detail_unknown),
            )
            DetailRow(
                label = stringResource(R.string.qs_detail_edge),
                value = state.selectedEdge ?: MetricFormat.NOT_AVAILABLE,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun stepLabel(step: QsStep): String = when (step) {
    QsStep.Preparing -> stringResource(R.string.qs_step_preparing)
    QsStep.FetchingSubscription -> stringResource(R.string.qs_step_fetching)
    QsStep.CheckingConfigs -> stringResource(R.string.qs_step_checking)
    QsStep.ChoosingConfig -> stringResource(R.string.qs_step_choosing_config)
    QsStep.Discovering -> stringResource(R.string.qs_step_discovering)
    QsStep.ScanningIps -> stringResource(R.string.qs_step_scanning)
    QsStep.ChoosingIp -> stringResource(R.string.qs_step_choosing_ip)
    QsStep.SelectingIp -> stringResource(R.string.qs_step_selecting)
    QsStep.ApplyingEdge -> stringResource(R.string.qs_step_applying)
    QsStep.StartingVpn -> stringResource(R.string.qs_step_starting)
    QsStep.Connected -> stringResource(R.string.qs_step_connected)
    QsStep.Idle -> stringResource(R.string.qs_step_preparing)
}
