package com.v2ray.ang.ui.qsn3d

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.ProviderRangeEntry
import com.v2ray.ang.senpai.RangeOrigin
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
    val entries = state.rangeEntries
    val discoveredCount = entries.count { it.origin == RangeOrigin.DISCOVERED }
    val seedCount = entries.count { it.origin == RangeOrigin.SEED }
    val fetchedCount = entries.count { it.origin == RangeOrigin.FETCHED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider Segmented Switcher with Official Cloudflare / CloudFront Logos
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(QsColors.SurfaceInset)
                    .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(13.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProviderSegmentTab(
                    label = "Cloudflare",
                    iconRes = R.drawable.ic_logo_cloudflare,
                    selected = provider == CdnType.CLOUDFLARE,
                    accent = QsColors.CloudflareOrange,
                    onClick = { onSelectProvider(CdnType.CLOUDFLARE) },
                    modifier = Modifier.weight(1f),
                )
                ProviderSegmentTab(
                    label = "CloudFront",
                    iconRes = R.drawable.ic_logo_cloudfront,
                    selected = provider == CdnType.CLOUDFRONT,
                    accent = QsColors.CloudFrontSky,
                    onClick = { onSelectProvider(CdnType.CLOUDFRONT) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Overview Stats Card
        item {
            val providerAccent = if (provider == CdnType.CLOUDFLARE) QsColors.CloudflareOrange else QsColors.CloudFrontSky
            Glass3dCard(
                surfaceColor = QsColors.SurfaceElevated,
                glowColor = providerAccent.copy(alpha = 0.42f),
                beamSeed = 17,
            ) {
                Text(
                    text = if (provider == CdnType.CLOUDFLARE) {
                        stringResource(R.string.qsn3d_cf_pool_title)
                    } else {
                        stringResource(R.string.qsn3d_cfront_pool_title)
                    },
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricPill(
                        label = stringResource(R.string.novapulse_ranges_discovered_label),
                        value = discoveredCount.toString(),
                        accent = QsColors.ConnectedEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = if (provider == CdnType.CLOUDFLARE) {
                            stringResource(R.string.novapulse_ranges_seed_label)
                        } else {
                            stringResource(R.string.novapulse_ranges_aws_label)
                        },
                        value = (if (provider == CdnType.CLOUDFLARE) seedCount else fetchedCount).toString(),
                        accent = QsColors.ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricPill(
                        label = stringResource(R.string.novapulse_ranges_total_label),
                        value = entries.size.toString(),
                        accent = QsColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (provider == CdnType.CLOUDFRONT && state.cloudFrontLastSyncedAt > 0L) {
                    Spacer(Modifier.height(8.dp))
                    val syncedStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(state.cloudFrontLastSyncedAt))
                    Text(
                        text = stringResource(R.string.novapulse_last_aws_sync_fmt, syncedStr),
                        style = QsTextStyles.TabularStat,
                        color = QsColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Discovery & AWS Controls
        item {
            Glass3dCard(
                glowColor = QsColors.ElectricCyan.copy(alpha = 0.36f),
                beamSeed = 21,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (state.isManualDiscoveryRunning) onStopDiscovery()
                            else onStartDiscovery(provider)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isManualDiscoveryRunning) QsColors.DangerCrimson else QsColors.ElectricCyan,
                            contentColor = if (state.isManualDiscoveryRunning) Color.White else Color(0xFF04151E),
                        )
                    ) {
                        Text(
                            text = if (state.isManualDiscoveryRunning) stringResource(R.string.qsn3d_stop_discovery)
                            else stringResource(R.string.qsn3d_run_discovery),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    if (provider == CdnType.CLOUDFRONT) {
                        OutlinedButton(
                            onClick = onSyncAws,
                            enabled = !state.isSyncingAws,
                            modifier = Modifier.weight(0.85f),
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, QsColors.BorderStrong),
                        ) {
                            Text(
                                text = if (state.isSyncingAws) stringResource(R.string.qsn3d_syncing)
                                else stringResource(R.string.qsn3d_sync_aws),
                                color = QsColors.ElectricCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (state.isManualDiscoveryRunning && state.manualDiscoveryTotal > 0) {
                    val progress = (state.manualDiscoveryDone.toFloat() / state.manualDiscoveryTotal.toFloat()).coerceIn(0f, 1f)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = QsColors.ElectricCyan,
                        trackColor = QsColors.SurfaceInset,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${state.manualDiscoveryDone} / ${state.manualDiscoveryTotal}  •  ${state.manualDiscoveryCidr}",
                        style = QsTextStyles.MonoCode,
                        color = QsColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Read-Only Explorer Launcher
        item {
            Glass3dCard(
                glowColor = QsColors.AuroraMint.copy(alpha = 0.28f),
                beamSeed = 29,
            ) {
                Text(
                    text = stringResource(R.string.novapulse_range_viewer_card_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.novapulse_range_viewer_card_desc),
                    color = QsColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(11.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenExplorer,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, QsColors.BorderStrong),
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_open_range_viewer_btn),
                            color = QsColors.ElectricCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = onCopyAll,
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, QsColors.BorderSubtle),
                    ) {
                        Text(
                            text = stringResource(R.string.qsn3d_copy_all_btn),
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Qs3dRangesExplorerScreen(
    state: Qs3dUiState,
    onSearchChange: (String) -> Unit,
    onCopyAll: () -> Unit,
    onBack: () -> Unit,
) {
    val q = state.rangeSearchQuery.trim()
    val filtered = state.rangeEntries.filter { q.isEmpty() || it.cidr.contains(q, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "${state.activeRangeProvider.name} (${filtered.size})",
                    style = QsTextStyles.TabularStat,
                    color = QsColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = onCopyAll,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.qsn3d_copy_all_btn), color = QsColors.ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.rangeSearchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.qsn3d_search_cidr_hint), color = QsColors.TextMuted, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QsColors.ElectricCyan,
                    unfocusedBorderColor = QsColors.BorderSubtle,
                    focusedContainerColor = QsColors.SurfaceInset,
                    unfocusedContainerColor = QsColors.SurfaceInset,
                    focusedTextColor = QsColors.TextPrimary,
                    unfocusedTextColor = QsColors.TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }

        items(filtered, key = { "${it.cidr}_${it.origin.name}" }) { entry ->
            RangeEntryRow(entry)
        }
    }
}

@Composable
private fun ProviderSegmentTab(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) QsColors.SurfaceElevated else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.52f)) else null,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = if (selected) accent else QsColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RangeEntryRow(entry: ProviderRangeEntry) {
    val (originLabel, originColor) = when (entry.origin) {
        RangeOrigin.DISCOVERED -> stringResource(R.string.novapulse_origin_discovered) to QsColors.ConnectedEmerald
        RangeOrigin.SEED -> stringResource(R.string.novapulse_origin_seed) to QsColors.ElectricCyan
        RangeOrigin.FETCHED -> stringResource(R.string.novapulse_origin_fetched) to QsColors.CloudFrontSky
        RangeOrigin.MANUAL -> stringResource(R.string.novapulse_origin_manual) to QsColors.SolarGold
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = QsColors.SurfaceCard,
        border = BorderStroke(1.dp, QsColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = entry.cidr,
                    style = QsTextStyles.MonoCode,
                    color = QsColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = originColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, originColor.copy(alpha = 0.38f)),
            ) {
                Text(
                    text = originLabel,
                    color = originColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}
