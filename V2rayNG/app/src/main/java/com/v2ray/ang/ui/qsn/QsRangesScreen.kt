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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
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
                            enabled = !state.scanningNow && enabled > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (state.scanningNow) {
                                    stringResource(R.string.qs_scanning)
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

        item(key = "rescan") {
            TextButton(onClick = onRescanNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.qs_range_rescan))
            }
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
