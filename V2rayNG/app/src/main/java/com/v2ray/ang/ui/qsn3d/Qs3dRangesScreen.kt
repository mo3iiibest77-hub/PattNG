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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.RangeOrigin
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncluttered CDN Ranges & Discovery screen:
 * - Strictly separates Cloudflare and Amazon CloudFront pools and Discovery progress
 * - Removes individual CIDR toggle switches and per-row delete controls
 * - Launches dedicated full-page [Qs3dRangesExplorerScreen] for browsing and Copy All
 */
@Composable
fun Qs3dRangesScreen(
    state: Qs3dUiState,
    onSelectProvider: (CdnType) -> Unit,
    onStartDiscovery: (CdnType) -> Unit,
    onStopDiscovery: () -> Unit,
    onSyncAws: () -> Unit,
    onOpenExplorer: () -> Unit,
    onCopyAll: () -> Unit,
) {
    val provider = state.activeRangeProvider
    val discoveredCount = state.rangeEntries.count { it.origin == RangeOrigin.DISCOVERED }
    val seedCount = state.rangeEntries.count { it.origin == RangeOrigin.SEED }
    val fetchedCount = state.rangeEntries.count { it.origin == RangeOrigin.FETCHED }

    val providerDiscoveryRunning = state.isManualDiscoveryRunning ||
        (state.discoveryState.running && state.discoveryState.activeProvider == provider)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Independent Provider Switcher
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(QsColors.GlassSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProviderSegmentButton(
                    label = "Cloudflare",
                    selected = provider == CdnType.CLOUDFLARE,
                    accent = QsColors.CloudflareOrange,
                    onClick = { onSelectProvider(CdnType.CLOUDFLARE) },
                    modifier = Modifier.weight(1f)
                )
                ProviderSegmentButton(
                    label = "Amazon CloudFront",
                    selected = provider == CdnType.CLOUDFRONT,
                    accent = QsColors.CloudFrontSky,
                    onClick = { onSelectProvider(CdnType.CLOUDFRONT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Clean Provider Summary & Actions Card
        item {
            val accent = if (provider == CdnType.CLOUDFLARE) QsColors.CloudflareOrange else QsColors.CloudFrontSky
            Glass3dCard(glowColor = accent.copy(alpha = 0.42f)) {
                Text(
                    text = if (provider == CdnType.CLOUDFLARE) {
                        stringResource(R.string.qsn3d_cf_pool_title)
                    } else {
                        stringResource(R.string.qsn3d_cfront_pool_title)
                    },
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricPill(
                        label = stringResource(R.string.novapulse_ranges_discovered_label),
                        value = "$discoveredCount",
                        accent = QsColors.AuroraMint,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = if (provider == CdnType.CLOUDFLARE) {
                            stringResource(R.string.novapulse_ranges_seed_label)
                        } else {
                            stringResource(R.string.novapulse_ranges_aws_label)
                        },
                        value = if (provider == CdnType.CLOUDFLARE) "$seedCount" else "$fetchedCount",
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = stringResource(R.string.novapulse_ranges_total_label),
                        value = "${state.rangeEntries.size}",
                        accent = QsColors.ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (provider == CdnType.CLOUDFRONT && state.cloudFrontLastSyncedAt > 0L) {
                    val syncedStr = DateFormat.getDateTimeInstance(
                        DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()
                    ).format(Date(state.cloudFrontLastSyncedAt))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.novapulse_last_aws_sync_fmt, syncedStr),
                        color = QsColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }

                if (providerDiscoveryRunning) {
                    val done = if (state.discoveryState.running && state.discoveryState.activeProvider == provider) {
                        state.discoveryState.done
                    } else state.manualDiscoveryDone
                    val total = if (state.discoveryState.running && state.discoveryState.activeProvider == provider) {
                        state.discoveryState.total
                    } else state.manualDiscoveryTotal
                    val cur = if (state.discoveryState.running && state.discoveryState.activeProvider == provider) {
                        state.discoveryState.currentCidr
                    } else state.manualDiscoveryCidr
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = accent,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$done / $total • $cur",
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (providerDiscoveryRunning) {
                        OutlinedButton(onClick = onStopDiscovery) {
                            Text(stringResource(R.string.qsn3d_stop_discovery), color = QsColors.DangerCrimson)
                        }
                    } else {
                        Button(
                            onClick = { onStartDiscovery(provider) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text(
                                text = stringResource(R.string.qsn3d_run_discovery),
                                color = Color(0xFF041322),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (provider == CdnType.CLOUDFRONT) {
                        OutlinedButton(onClick = onSyncAws, enabled = !state.isSyncingAws) {
                            Text(
                                if (state.isSyncingAws) stringResource(R.string.qsn3d_syncing)
                                else stringResource(R.string.qsn3d_sync_aws)
                            )
                        }
                    }
                }
            }
        }

        // 3. Dedicated Range & Discovered IP Viewer Launcher Card
        item {
            Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.35f)) {
                Text(
                    text = stringResource(R.string.novapulse_range_viewer_card_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.novapulse_range_viewer_card_desc),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenExplorer,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.novapulse_open_range_viewer_btn))
                    }
                    OutlinedButton(
                        onClick = onCopyAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.qsn3d_copy_all_btn))
                    }
                }
            }
        }
    }
}

/**
 * Independent scrollable viewer for Discovered & Active CIDR Ranges with Search & Copy All.
 */
@Composable
fun Qs3dRangesExplorerScreen(
    state: Qs3dUiState,
    onSearchChange: (String) -> Unit,
    onCopyAll: () -> Unit,
    onBack: () -> Unit,
) {
    val provider = state.activeRangeProvider
    val q = state.rangeSearchQuery.trim().lowercase()
    val filtered = state.rangeEntries.filter {
        q.isEmpty() || it.cidr.lowercase().contains(q)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan)
            }
            Text(
                text = if (provider == CdnType.CLOUDFLARE) "Cloudflare (${filtered.size})"
                else "CloudFront (${filtered.size})",
                color = QsColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            OutlinedButton(onClick = onCopyAll) {
                Text(stringResource(R.string.qsn3d_copy_all_btn), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.rangeSearchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(stringResource(R.string.qsn3d_search_cidr_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { "${it.provider.name}_${it.cidr}" }) { entry ->
                val badgeColor = when (entry.origin) {
                    RangeOrigin.DISCOVERED -> QsColors.AuroraMint
                    RangeOrigin.SEED -> QsColors.CloudflareOrange
                    RangeOrigin.FETCHED -> QsColors.CloudFrontSky
                    RangeOrigin.MANUAL -> QsColors.PlasmaViolet
                }
                val originLabel = when (entry.origin) {
                    RangeOrigin.DISCOVERED -> stringResource(R.string.novapulse_origin_discovered)
                    RangeOrigin.SEED -> stringResource(R.string.novapulse_origin_seed)
                    RangeOrigin.FETCHED -> stringResource(R.string.novapulse_origin_fetched)
                    RangeOrigin.MANUAL -> stringResource(R.string.novapulse_origin_manual)
                }
                Glass3dCard(
                    cornerRadius = 14.dp,
                    glowColor = badgeColor.copy(alpha = 0.25f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.cidr,
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(text = originLabel, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderSegmentButton(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) accent.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accent else QsColors.TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
