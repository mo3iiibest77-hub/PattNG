package com.v2ray.ang.ui.qsn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.qsn.domain.DiscoveryPhase
import com.v2ray.ang.ui.qsn.domain.IspProfile
import com.v2ray.ang.ui.qsn.domain.RangeOutcome
import com.v2ray.ang.ui.qsn.domain.RangeState
import com.v2ray.ang.ui.qsn.domain.ScanCandidate
import com.v2ray.ang.ui.qsn.domain.DiscoveryStatus
import com.v2ray.ang.ui.qsn.domain.MetricFormat
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangesSummary
import com.v2ray.ang.ui.qsn.domain.ScanRange
import com.v2ray.ang.ui.qsn.domain.ScanRangeParser
import com.v2ray.ang.ui.qsn.domain.ScanRangePlanner

/**
 * The IP Ranges tab.
 *
 * The ranges are the only input the scanner gets, so the limits are shown up
 * front instead of failing silently: the smallest accepted prefix, how many
 * addresses are sampled per range, and the hard ceiling on candidates.
 */
@Composable
fun QsRangesScreen(
    state: QsUiState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String) -> Unit,
    onScanNow: () -> Unit,
    onRescanNext: () -> Unit,
    onDismissMessage: () -> Unit,
    onToggleLanguage: () -> Unit,
    onSelectIsp: (String) -> Unit,
    onStartDiscovery: (String) -> Unit,
    onResumeDiscovery: () -> Unit,
    onRestartDiscovery: (String) -> Unit,
    onStopDiscovery: () -> Unit,
    onUseCandidate: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val enabled = RangesSummary.enabled(state.ranges)
    val total = RangesSummary.total(state.ranges)
    val estimated = minOf(
        ScanRangeParser.MAX_CANDIDATES,
        ScanRangePlanner.dedupe(state.ranges).size * ScanRangeParser.DEFAULT_PER_RANGE,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            QsScreenHeader(
                title = stringResource(R.string.qs_tab_ranges),
                languageCode = state.languageCode,
                onToggleLanguage = onToggleLanguage,
            )
        }

        item(key = "intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.qs_ranges_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.qs_ranges_limits,
                            ScanRangeParser.MIN_PREFIX,
                            ScanRangeParser.DEFAULT_PER_RANGE,
                            ScanRangeParser.MAX_CANDIDATES,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.qs_range_count,
                            total,
                            enabled,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.qs_ranges_summary,
                            enabled,
                            estimated,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.scanningNow) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.qs_range_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (state.lastScanAt > 0L) {
                            stringResource(R.string.qs_last_scan, formatTimestamp(state.lastScanAt))
                        } else {
                            stringResource(R.string.qs_never_scanned)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "isp-discovery") {
            IspDiscoveryCard(
                state = state,
                onSelectIsp = onSelectIsp,
                onStartDiscovery = onStartDiscovery,
                onResumeDiscovery = onResumeDiscovery,
                onRestartDiscovery = onRestartDiscovery,
                onStopDiscovery = onStopDiscovery,
            )
        }

        item(key = "discovered") {
            DiscoveredRangesCard(
                ranges = state.qualifiedRanges,
                discovery = state.discovery,
            )
        }

        item(key = "input") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = state.rangeError != null,
                        label = { Text(stringResource(R.string.qs_range_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (state.rangeError != null) {
                        Text(
                            text = state.rangeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onAdd(input)
                                input = ""
                            },
                            enabled = input.isNotBlank() && !state.scanningNow,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add_24dp),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.qs_range_add))
                        }
                        OutlinedButton(
                            onClick = onScanNow,
                            // Stage 2 measures the ISP's stored ranges, so a
                            // user who never added a manual range must still be
                            // able to scan after discovery qualified some.
                            enabled = !state.scanningNow &&
                                (enabled > 0 || state.discovery.qualified > 0),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (state.scanningNow) {
                                    stringResource(R.string.qs_scanning)
                                } else if (state.discovery.qualified > 0) {
                                    stringResource(R.string.qs_isp_use)
                                } else {
                                    stringResource(R.string.qs_scan_now)
                                },
                            )
                        }
                    }
                    if (state.scanningNow) {
                        Spacer(Modifier.height(10.dp))
                        val fraction = if (state.scanTotal > 0) {
                            state.scanDone.toFloat() / state.scanTotal.toFloat()
                        } else {
                            null
                        }
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.qs_scan_progress,
                                state.scanDone,
                                state.scanTotal,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.message != null) {
            item(key = "message") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = onDismissMessage) {
                            Text(stringResource(R.string.qs_dismiss))
                        }
                    }
                }
            }
        }

        if (state.ranges.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.qs_ranges_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        } else {
            items(items = state.ranges, key = { it.cidr }) { range ->
                RangeRow(
                    range = range,
                    editable = !state.scanningNow,
                    onToggle = { onToggle(range.cidr) },
                    onRemove = { onRemove(range.cidr) },
                )
            }
        }

        if (state.discovery.rows.isNotEmpty()) {
            item(key = "discovery-rows-header") {
                DiscoveryRowsHeader(state.discovery.rows)
            }
            items(
                items = state.discovery.rows,
                key = { row -> "disc-" + row.cidr },
            ) { row ->
                DiscoveryRangeRow(row)
            }
        }

        if (state.scanCandidates.isNotEmpty()) {
            item(key = "stage2-header") {
                Stage2Header(state.scanCandidates.size)
            }
            items(
                items = state.scanCandidates,
                key = { candidate -> "ip-" + candidate.ip },
            ) { candidate ->
                Stage2ResultRow(
                    candidate = candidate,
                    best = candidate.ip == state.scanCandidates.firstOrNull()?.ip,
                    onUse = { onUseCandidate(candidate.ip) },
                )
            }
        }

        item(key = "rescan") {
            TextButton(onClick = onRescanNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.qs_range_rescan))
            }
        }
    }
}

/**
 * The measured range database, with the live state of the discovery run.
 *
 * Collapsed by default: the list can hold the whole qualified set and the user came to
 * this screen for their own ranges. The count and the run state stay visible
 * either way, so a paused or background run is never mistaken for a finished
 * one, and nothing spins when no run is active.
 */
@Composable
private fun DiscoveredRangesCard(
    ranges: List<QualifiedRange>,
    discovery: DiscoveryStatus,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.qs_discovered_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.qs_discovered_count, ranges.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24dp),
                        contentDescription = stringResource(R.string.qs_discovered_title),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (expanded) 180f else 0f),
                    )
                }
            }
            DiscoveryStatusLine(discovery)
            if (expanded) {
                if (ranges.isEmpty()) {
                    Text(
                        text = stringResource(R.string.qs_discovered_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                    )
                } else {
                    ranges.forEach { range ->
                        QualifiedRangeRow(range)
                    }
                }
            }
        }
    }
}

@Composable
private fun QualifiedRangeRow(range: QualifiedRange) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = range.cidr,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(
                    R.string.qs_discovered_meta,
                    MetricFormat.speed(range.uploadKBps),
                    range.latencyMs,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (range.validatedAt > 0L) {
            Text(
                text = stringResource(
                    R.string.qs_discovered_validated,
                    formatTimestamp(range.validatedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One truthful line about the run: running, paused, finished, cancelled or
 * failed. Idle shows nothing, so the screen never implies a scan that is not
 * happening.
 */
@Composable
private fun DiscoveryStatusLine(status: DiscoveryStatus) {
    val text: String? = when (status.phase) {
        DiscoveryPhase.Running -> if (status.background) {
            stringResource(R.string.qs_discovery_background, status.done, status.total)
        } else {
            stringResource(R.string.qs_discovery_running, status.done, status.total, status.qualified)
        }

        DiscoveryPhase.Paused -> stringResource(
            R.string.qs_discovery_paused_counts,
            status.qualified,
            status.done,
            status.total,
        )
        DiscoveryPhase.Interrupted -> stringResource(R.string.qs_isp_phase_interrupted)
        DiscoveryPhase.Completed -> stringResource(R.string.qs_discovery_completed, status.qualified)
        DiscoveryPhase.Cancelled -> stringResource(R.string.qs_discovery_cancelled)
        DiscoveryPhase.Failed -> stringResource(R.string.qs_discovery_failed)
        DiscoveryPhase.Idle -> null
    }
    if (text != null) {
        Spacer(Modifier.height(6.dp))
        if (status.phase == DiscoveryPhase.Running && status.total > 0) {
            LinearProgressIndicator(
                progress = { (status.done.toFloat() / status.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.phase == DiscoveryPhase.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(end = 8.dp),
        )
        val last = status.lastCidr
        if (status.phase == DiscoveryPhase.Running && !last.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.qs_discovery_last, last),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun RangeRow(
    range: ScanRange,
    editable: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = range.cidr,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = range.enabled,
                onCheckedChange = { onToggle() },
                enabled = editable,
            )
            IconButton(onClick = onRemove, enabled = editable) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = stringResource(R.string.qs_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}


/**
 * Discovery controls for one ISP.
 *
 * The card is the honest view of the run: the phase, how many ranges have a
 * final result, how many qualified, and the three-way split between good,
 * failed and uncertain. Stop is always available while the run is active, and
 * Resume continues from the persisted per-range results instead of starting
 * over.
 */
@Composable
private fun IspDiscoveryCard(
    state: QsUiState,
    onSelectIsp: (String) -> Unit,
    onStartDiscovery: (String) -> Unit,
    onResumeDiscovery: () -> Unit,
    onRestartDiscovery: (String) -> Unit,
    onStopDiscovery: () -> Unit,
) {
    var ispInput by rememberSaveable { mutableStateOf(state.selectedIsp) }
    // Selecting a saved ISP updates the field; typing a new name is not
    // overwritten by progress updates because this key does not change.
    LaunchedEffect(state.selectedIsp) { ispInput = state.selectedIsp }
    val status = state.discovery
    val active = status.isActive
    val phaseLabel = when (status.phase) {
        DiscoveryPhase.Idle -> stringResource(R.string.qs_isp_phase_idle)
        DiscoveryPhase.Running -> stringResource(R.string.qs_isp_phase_running)
        DiscoveryPhase.Paused -> stringResource(R.string.qs_isp_phase_paused)
        DiscoveryPhase.Completed -> stringResource(R.string.qs_isp_phase_completed)
        DiscoveryPhase.Interrupted -> stringResource(R.string.qs_isp_phase_interrupted)
        DiscoveryPhase.Cancelled -> stringResource(R.string.qs_isp_phase_cancelled)
        DiscoveryPhase.Failed -> stringResource(R.string.qs_isp_phase_failed)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.qs_isp_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.qs_isp_status, phaseLabel) +
                    if (status.ispName.isNotBlank()) " · " + status.ispName else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (status.phase == DiscoveryPhase.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (status.total > 0 || active) {
                Spacer(Modifier.height(8.dp))
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.qs_isp_progress,
                        status.done,
                        status.total,
                        status.percent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.qs_isp_counts,
                        status.qualified,
                        status.failed,
                        status.errors,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ispInput,
                onValueChange = { ispInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !active,
                label = { Text(stringResource(R.string.qs_isp_hint)) },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    OutlinedButton(
                        onClick = onStopDiscovery,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.qs_isp_stop))
                    }
                } else {
                    Button(
                        onClick = {
                            when {
                                status.phase == DiscoveryPhase.Completed ->
                                    onRestartDiscovery(ispInput)
                                status.isResumable -> onResumeDiscovery()
                                else -> onStartDiscovery(ispInput)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    status.phase == DiscoveryPhase.Completed ->
                                        R.string.qs_isp_restart
                                    status.isResumable -> R.string.qs_isp_resume
                                    else -> R.string.qs_isp_start
                                },
                            ),
                        )
                    }
                    if (status.isResumable) {
                        TextButton(onClick = { onRestartDiscovery(ispInput) }) {
                            Text(stringResource(R.string.qs_isp_restart))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.qs_isp_saved),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.ispProfiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.qs_isp_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.ispProfiles.forEach { profile ->
                    SavedIspRow(
                        profile = profile,
                        selected = profile.name.equals(state.selectedIsp, ignoreCase = true),
                        onSelect = { onSelectIsp(profile.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedIspRow(profile: IspProfile, selected: Boolean, onSelect: () -> Unit) {
    val phase = when (profile.phase) {
        DiscoveryPhase.Idle -> stringResource(R.string.qs_isp_phase_idle)
        DiscoveryPhase.Running -> stringResource(R.string.qs_isp_phase_running)
        DiscoveryPhase.Paused -> stringResource(R.string.qs_isp_phase_paused)
        DiscoveryPhase.Completed -> stringResource(R.string.qs_isp_phase_completed)
        DiscoveryPhase.Interrupted -> stringResource(R.string.qs_isp_phase_interrupted)
        DiscoveryPhase.Cancelled -> stringResource(R.string.qs_isp_phase_cancelled)
        DiscoveryPhase.Failed -> stringResource(R.string.qs_isp_phase_failed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = phase + " · " + profile.scanned + "/" + profile.total +
                    " · " + profile.goodRanges.size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text(
                text = stringResource(R.string.qs_isp_select),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DiscoveryRowsHeader(rows: List<RangeOutcome>) {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.qs_isp_rows_title, rows.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.qs_isp_rows_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One range result.
 *
 * The label is the persisted verdict, not a spinner: a range that was measured
 * keeps its Good/Failed/Uncertain result even while the rest of the run is
 * still going, which is what makes the list trustworthy on resume.
 */
@Composable
private fun DiscoveryRangeRow(row: RangeOutcome) {
    val label = when (row.state) {
        RangeState.Pending -> stringResource(R.string.qs_isp_range_pending)
        RangeState.Running -> stringResource(R.string.qs_isp_range_running)
        RangeState.Good -> stringResource(R.string.qs_isp_range_good)
        RangeState.Failed -> stringResource(R.string.qs_isp_range_failed)
        RangeState.Error -> stringResource(R.string.qs_isp_range_error)
    }
    val color = when (row.state) {
        RangeState.Good -> MaterialTheme.colorScheme.primary
        RangeState.Failed -> MaterialTheme.colorScheme.error
        RangeState.Error -> MaterialTheme.colorScheme.tertiary
        RangeState.Running -> MaterialTheme.colorScheme.secondary
        RangeState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.cidr,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            val upload = row.uploadKBps
            val latency = row.latencyMs
            if (upload != null || latency != null) {
                Text(
                    text = stringResource(
                        R.string.qs_isp_range_metrics,
                        MetricFormat.speed(upload),
                        MetricFormat.speed(row.downloadKBps),
                        MetricFormat.latency(latency),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}


@Composable
private fun Stage2Header(count: Int) {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.qs_stage2_title, count),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.qs_stage2_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One Stage 2 candidate.
 *
 * Every number is a measurement; a missing one is "N/A". The first row is the
 * ranker's best score, not simply the fastest ping.
 */
@Composable
private fun Stage2ResultRow(
    candidate: ScanCandidate,
    best: Boolean,
    onUse: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.ip,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Text(
                text = stringResource(
                    R.string.qs_stage2_metrics,
                    MetricFormat.latency(candidate.pingMs),
                    MetricFormat.speed(candidate.downloadKBps),
                    MetricFormat.speed(candidate.uploadKBps),
                    candidate.score.toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (best) {
            Text(
                text = stringResource(R.string.qs_scan_suggested),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        if (candidate.answered) {
            TextButton(onClick = onUse) {
                Text(stringResource(R.string.qs_use_ip))
            }
        }
    }
}
