package com.v2ray.ang.ui.qsn

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/**
 * Minimal home screen: one status, one button, one subscription field, one honest progress list.
 *
 * Everything rendered here comes from [QsUiState]; nothing is simulated. When a value is not
 * available (traffic counters are not exposed to the UI process by the core) it is not shown as
 * a fake number.
 */
@Composable
fun QsConnectScreen(
    state: QsUiState,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QsStatusCard(state = state, onConnect = onConnect, onDisconnect = onDisconnect, onCancel = onCancel)

        QsSubscriptionCard(
            state = state,
            onUrlChange = onUrlChange,
            onSave = onSave,
            onUpdate = onUpdate,
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

@Composable
private fun QsSubscriptionCard(
    state: QsUiState,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qs_subscription_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            if (state.selectedRemarks != null) {
                DetailRow(stringResource(R.string.qs_detail_config), state.selectedRemarks)
            }
            if (state.selectedServer != null) {
                DetailRow(stringResource(R.string.qs_detail_server), state.selectedServer)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.qs_speed_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.width(78.dp),
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
private fun stepLabel(step: QsStep): String = when (step) {
    QsStep.Preparing -> stringResource(R.string.qs_step_preparing)
    QsStep.FetchingSubscription -> stringResource(R.string.qs_step_fetching)
    QsStep.CheckingConfigs -> stringResource(R.string.qs_step_checking)
    QsStep.ScanningIps -> stringResource(R.string.qs_step_scanning)
    QsStep.SelectingIp -> stringResource(R.string.qs_step_selecting)
    QsStep.StartingVpn -> stringResource(R.string.qs_step_starting)
    QsStep.Connected -> stringResource(R.string.qs_step_connected)
    QsStep.Idle -> stringResource(R.string.qs_step_preparing)
}

