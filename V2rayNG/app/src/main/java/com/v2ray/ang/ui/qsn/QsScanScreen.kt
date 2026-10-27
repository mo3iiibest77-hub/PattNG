package com.v2ray.ang.ui.qsn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.qsn.domain.ConfigChoice
import com.v2ray.ang.ui.qsn.domain.MetricFormat
import com.v2ray.ang.ui.qsn.domain.ScanCandidate

/**
 * Full-screen step of the connection flow: pick the configuration, watch the
 * scan, confirm the IP.
 *
 * It is a real step, not decoration: while it is shown the orchestrator is
 * parked and does nothing until the user answers, and Cancel really stops the
 * work. Every number comes from a measurement; a missing one renders as "N/A".
 */
@Composable
fun QsScanScreen(
    state: QsUiState,
    onChooseConfig: (String) -> Unit,
    onSelectIp: (String) -> Unit,
    onConfirmIp: () -> Unit,
    onCancel: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScanHeader(state = state, onCancel = onCancel)
        }

        if (state.step == QsStep.ChoosingConfig) {
            item(key = "choose-hint") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.qs_choose_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.qs_choose_config_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.configChoices.isEmpty()) {
                item(key = "choose-empty") {
                    ScanNotice(stringResource(R.string.qs_choose_config_empty))
                }
            } else {
                items(items = state.configChoices, key = { it.guid }) { choice ->
                    ConfigChoiceCard(
                        choice = choice,
                        selected = choice.guid == state.chosenConfigGuid,
                        onClick = { onChooseConfig(choice.guid) },
                    )
                }
                item(key = "choose-action") {
                    val chosen = state.chosenConfigGuid
                    Button(
                        onClick = { chosen?.let(onChooseConfig) },
                        enabled = chosen != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text(stringResource(R.string.qs_choose_config_action))
                    }
                }
            }
        } else {
            item(key = "results-title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.qs_scan_results_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.qs_scan_totals,
                            state.scanDone,
                            state.scanTotal,
                            state.scanCandidates.count { it.answered },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.scanCandidates.isEmpty()) {
                item(key = "results-empty") {
                    ScanNotice(
                        if (state.step == QsStep.ChoosingIp) {
                            stringResource(R.string.qs_scan_empty)
                        } else {
                            stringResource(R.string.qs_scan_waiting)
                        },
                    )
                }
            } else {
                items(items = state.scanCandidates, key = { it.ip }) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        suggested = candidate.ip == state.suggestedIp,
                        selected = candidate.ip == state.pendingIp,
                        selectable = state.step == QsStep.ChoosingIp && candidate.answered,
                        onClick = { onSelectIp(candidate.ip) },
                    )
                }
            }

            if (state.step == QsStep.ChoosingIp) {
                item(key = "confirm") {
                    Button(
                        onClick = onConfirmIp,
                        enabled = state.pendingIp != null &&
                            state.scanCandidates.any { it.ip == state.pendingIp && it.answered },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text(stringResource(R.string.qs_scan_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanHeader(state: QsUiState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.qs_scan_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when (state.step) {
                            QsStep.ChoosingIp -> stringResource(R.string.qs_scan_phase_choosing)
                            QsStep.ChoosingConfig -> stringResource(R.string.qs_step_choosing_config)
                            else -> stringResource(R.string.qs_scan_phase_scanning)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.qs_cancel))
                }
            }
            val fraction = state.scanFraction
            if (state.step == QsStep.ScanningIps) {
                Spacer(Modifier.height(10.dp))
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
            }
        }
    }
}

@Composable
private fun ScanNotice(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ConfigChoiceCard(
    choice: ConfigChoice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = choice.remarks,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        text = stringResource(R.string.qs_choose_config_selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = choice.protocol + " · " + choice.network + " · " + choice.security +
                    " · " + choice.port,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (choice.sniOrHost.isNotBlank()) {
                Text(
                    text = stringResource(R.string.qs_field_sni) + ": " + choice.sniOrHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (choice.subscriptionName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.qs_field_source) + ": " + choice.subscriptionName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: ScanCandidate,
    suggested: Boolean,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (candidate.answered) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = candidate.ip,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (suggested) {
                    Text(
                        text = stringResource(R.string.qs_scan_suggested),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.qs_scan_col_ping) + " " +
                    MetricFormat.latency(candidate.pingMs) + "  ·  " +
                    stringResource(R.string.qs_scan_col_jitter) + " " +
                    MetricFormat.jitter(candidate.jitterMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.qs_scan_col_down) + " " +
                    MetricFormat.speed(candidate.downloadKBps) + "  ·  " +
                    stringResource(R.string.qs_scan_col_up) + " " +
                    MetricFormat.speed(candidate.uploadKBps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val statusText = if (candidate.answered) {
                stringResource(R.string.qs_scan_status_ok)
            } else {
                stringResource(R.string.qs_scan_status_failed)
            }
            Text(
                text = stringResource(R.string.qs_scan_col_stability) + " " +
                    MetricFormat.percent(candidate.stabilityPercent) + "  ·  " + statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.failures > 0 || candidate.timeouts > 0) {
                Text(
                    text = stringResource(
                        R.string.qs_scan_errors,
                        candidate.failures,
                        candidate.timeouts,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
