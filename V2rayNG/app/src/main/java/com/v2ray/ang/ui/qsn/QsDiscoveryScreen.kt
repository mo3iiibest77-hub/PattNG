package com.v2ray.ang.ui.qsn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.RangeState

/**
 * The dedicated Discovery view.
 *
 * It is a viewer, not an owner: it reads [QsUiState.discovery], which the
 * scanner publishes from the real run, and it can only ask for a stop or a
 * resume. Leaving this screen does not cancel anything.
 */
@Composable
fun QsDiscoveryScreen(
    state: QsUiState,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
) {
    val status = state.discovery
    val rows = status.rows
    val running = rows.filter { it.state == RangeState.Running }
    val fraction = if (status.total > 0) {
        status.done.toFloat() / status.total.toFloat()
    } else {
        0f
    }
    val percent = if (status.total > 0) status.done * 100 / status.total else 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.qs_discovery_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.qs_discovery_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.qs_discovery_isp, status.ispName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = phaseLabel(status.phase),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        item(key = "progress") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.qs_isp_progress,
                            status.done,
                            status.total,
                            percent,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.qs_discovery_counts,
                            status.qualified,
                            status.failed,
                            status.errors,
                            status.done,
                            status.total,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.lastCidr?.let { cidr ->
                        Text(
                            text = stringResource(R.string.qs_discovery_last_result, cidr),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.phase == DiscoveryPhase.Running) {
                            OutlinedButton(
                                onClick = onStop,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.qs_discovery_stop))
                            }
                        } else {
                            OutlinedButton(
                                onClick = onResume,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.qs_discovery_resume))
                            }
                            OutlinedButton(
                                onClick = onRestart,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.qs_discovery_restart))
                            }
                        }
                    }
                }
            }
        }

        item(key = "measuring") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.qs_discovery_measuring_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (running.isEmpty()) {
                        Text(
                            text = stringResource(R.string.qs_discovery_none_measuring),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        running.forEach { row ->
                            val ips = status.measuring[row.cidr].orEmpty()
                            Text(
                                text = if (ips.isEmpty()) {
                                    row.cidr
                                } else {
                                    stringResource(
                                        R.string.qs_discovery_measuring_row,
                                        row.cidr,
                                        ips.joinToString(", "),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        item(key = "rows-header") {
            DiscoveryRowsHeader(rows)
        }

        items(items = rows, key = { row -> "discovery-" + row.cidr }) { row ->
            DiscoveryRangeRow(
                row = row,
                measuring = status.measuring[row.cidr].orEmpty(),
            )
        }
    }
}

/** Localised phase name, shared with the ISP card. */
@Composable
internal fun phaseLabel(phase: DiscoveryPhase): String = when (phase) {
    DiscoveryPhase.Idle -> stringResource(R.string.qs_isp_phase_idle)
    DiscoveryPhase.Running -> stringResource(R.string.qs_isp_phase_running)
    DiscoveryPhase.Paused -> stringResource(R.string.qs_isp_phase_paused)
    DiscoveryPhase.Completed -> stringResource(R.string.qs_isp_phase_completed)
    DiscoveryPhase.Interrupted -> stringResource(R.string.qs_isp_phase_interrupted)
    DiscoveryPhase.Cancelled -> stringResource(R.string.qs_isp_phase_cancelled)
    DiscoveryPhase.Failed -> stringResource(R.string.qs_isp_phase_failed)
}
