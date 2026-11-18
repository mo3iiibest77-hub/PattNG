package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.ConfigPipeline
import com.v2ray.ang.senpai.SmartStep

/**
 * Clean, calm, high-precision Home / Connect Screen.
 * Prioritizes immediate clarity: Smart Mode Pill -> Magnetic Core Trigger -> Live Speeds -> Active Node & Quota.
 */
@Composable
fun Qs3dConnectScreen(
    state: Qs3dUiState,
    onConnectOrDisconnect: () -> Unit,
    onRescanIpAndConnect: () -> Unit,
    onSwitchToSmartAuto: () -> Unit,
    onCancelFlow: () -> Unit,
    onOpenSubscriptionDetails: () -> Unit,
    onRefreshSubs: () -> Unit,
    onTestConnectedPing: () -> Unit,
    onOpenConfigsTab: () -> Unit,
) {
    val flow = state.smartFlow
    val isBusy = flow.busy || flow.step == SmartStep.ScanningBestIp || flow.step == SmartStep.ApplyingBestIp
    val orbMode = when {
        state.coreRunning && isBusy -> OrbVisualMode.SCANNING
        state.coreRunning -> OrbVisualMode.CONNECTED
        isBusy -> OrbVisualMode.CONNECTING
        else -> OrbVisualMode.IDLE
    }

    val scanProgress: Float? = if (
        flow.step == SmartStep.ScanningBestIp && flow.scanTotal > 0
    ) {
        (flow.scanDone.toFloat() / flow.scanTotal.toFloat()).coerceIn(0f, 1f)
    } else null

    val selectedConfig = state.configs.firstOrNull { it.guid == state.selectedGuid }
        ?: state.configs.firstOrNull { it.guid == flow.activeGuid }
    val activePipeline = selectedConfig?.pipeline ?: flow.activePipeline

    val statusHeadline = when {
        flow.step == SmartStep.RedeemingAccess -> stringResource(R.string.qsn3d_step_redeeming)
        flow.step == SmartStep.RefreshingSubscription -> stringResource(R.string.qsn3d_step_refresh_sub)
        flow.step == SmartStep.DetectingCdnViaPing -> stringResource(R.string.qsn3d_step_detect_cdn)
        flow.step == SmartStep.ScanningBestIp -> stringResource(R.string.novapulse_connect_scanning)
        flow.step == SmartStep.ApplyingBestIp -> stringResource(R.string.qsn3d_step_applying)
        flow.step == SmartStep.StartingVpn -> stringResource(R.string.qsn3d_step_starting_vpn)
        state.coreRunning -> stringResource(R.string.novapulse_connect_active_tunnel)
        else -> stringResource(R.string.novapulse_connect_standby)
    }

    val tapInstruction = when {
        isBusy -> flow.bestIp?.let { "IP: $it" } ?: stringResource(R.string.novapulse_connect_scanning)
        state.coreRunning -> stringResource(R.string.novapulse_connect_tap_stop)
        else -> stringResource(R.string.novapulse_connect_tap_start)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Smart Mode & Failover Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = QsColors.SurfaceElevated,
                border = BorderStroke(1.dp, QsColors.BorderSubtle),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(onClick = onOpenConfigsTab),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    StatusDot(active = state.coreRunning)
                    Text(
                        text = if (state.isExplicitSelection && selectedConfig != null) {
                            stringResource(R.string.novapulse_mode_manual_pinned_fmt, selectedConfig.remarks)
                        } else {
                            stringResource(R.string.novapulse_mode_smart_auto)
                        },
                        color = QsColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.isExplicitSelection) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSwitchToSmartAuto,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_switch_smart_auto_btn),
                        color = QsColors.ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Optional Failover / Background Discovery Banners
        val banner = state.failoverBanner
        if (banner != null && state.coreRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = QsColors.SolarGold.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, QsColors.SolarGold.copy(alpha = 0.35f)),
            ) {
                Text(
                    text = stringResource(
                        R.string.novapulse_failover_switched_fmt,
                        banner.transportBadge.ifBlank { "Standard" },
                        banner.delayMs,
                    ),
                    style = QsTextStyles.TabularStat,
                    color = QsColors.SolarGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // 2. Hero Connect Trigger Zone
        Glass3dCard(
            surfaceColor = QsColors.SurfaceCard,
            glowColor = when {
                state.coreRunning -> QsColors.ConnectedEmerald.copy(alpha = 0.36f)
                isBusy -> QsColors.ElectricCyan.copy(alpha = 0.36f)
                else -> QsColors.BorderSubtle
            },
            contentPadding = 18.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ConnectionOrb3d(
                    isRunning = state.coreRunning,
                    isBusy = isBusy,
                    mode = orbMode,
                    scanProgressFraction = scanProgress,
                    onClick = onConnectOrDisconnect,
                    sizeDp = 188.dp,
                ) {
                    OrbCenterGlyph(mode = orbMode, sizeDp = 42.dp)
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = statusHeadline,
                    color = when {
                        state.coreRunning -> QsColors.ConnectedEmerald
                        isBusy -> QsColors.ElectricCyan
                        else -> QsColors.TextPrimary
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = tapInstruction,
                    style = QsTextStyles.TabularStat,
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                )

                if (scanProgress != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = QsColors.ElectricCyan,
                        trackColor = QsColors.SurfaceInset,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${flow.scanDone} / ${flow.scanTotal}",
                        style = QsTextStyles.TabularStat,
                        color = QsColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }

                if (state.discoveryState.running) {
                    val disc = state.discoveryState
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.qsn3d_post_connect_discovery_banner,
                            disc.activeProvider?.name ?: "CDN",
                            disc.done,
                            disc.total.coerceAtLeast(1),
                        ),
                        style = QsTextStyles.TabularStat,
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    if (isBusy) {
                        OutlinedButton(
                            onClick = onCancelFlow,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.DangerCrimson),
                        ) {
                            Text(stringResource(R.string.qsn3d_cancel), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (activePipeline == ConfigPipeline.CDN) {
                        OutlinedButton(
                            onClick = onRescanIpAndConnect,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, QsColors.BorderStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.ElectricCyan),
                        ) {
                            Text(
                                text = stringResource(R.string.novapulse_find_faster_ip_btn),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (state.coreRunning) {
                        OutlinedButton(
                            onClick = onTestConnectedPing,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, QsColors.BorderStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.TextPrimary),
                        ) {
                            val pingLabel = state.connectedLatencyMs?.takeIf { it > 0L }?.let { "${it} ms" }
                                ?: stringResource(R.string.qsn3d_test_ping)
                            Text(
                                text = pingLabel,
                                style = QsTextStyles.TabularStat,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // 3. Live Speed Telemetry
        if (state.speedDisplayEnabled) {
            LiveSpeedHudRow(
                downloadLabel = stringResource(R.string.novapulse_download_speed),
                downloadFormatted = state.liveDownloadFormatted,
                downloadBps = state.liveDownloadBps,
                uploadLabel = stringResource(R.string.novapulse_upload_speed),
                uploadFormatted = state.liveUploadFormatted,
                uploadBps = state.liveUploadBps,
                isRunning = state.coreRunning,
            )
        }

        // 4. Active Server & Subscription Compact Strip
        val activeSub = state.subscriptions.firstOrNull()
        val quota = activeSub?.quota
        val gbUnit = stringResource(R.string.novapulse_unit_gb)
        val mbUnit = stringResource(R.string.novapulse_unit_mb)

        Glass3dCard(
            surfaceColor = QsColors.SurfaceElevated,
            contentPadding = 14.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenConfigsTab),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.novapulse_location_label),
                        color = QsColors.TextMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = selectedConfig?.remarks ?: flow.activeRemarks ?: stringResource(R.string.qsn3d_no_config_selected),
                        color = QsColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (activePipeline == ConfigPipeline.STANDARD) {
                    StandardPipelineBadge(label = selectedConfig?.transportBadge ?: flow.activeTransportBadge.orEmpty())
                } else {
                    val cdnBadge = selectedConfig?.cdnHint?.takeIf { it != CdnType.UNKNOWN }
                        ?: flow.detectedCdn.takeIf { it != CdnType.UNKNOWN }
                        ?: CdnType.CLOUDFLARE
                    CdnProviderBadge(cdnType = cdnBadge)
                }
            }

            if (activeSub != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = QsColors.BorderSubtle.copy(alpha = 0.7f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSubscriptionDetails),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.novapulse_my_subscription_short),
                            color = QsColors.TextMuted,
                            fontSize = 11.sp,
                        )
                        val remStr = quota?.remainingBytes?.let { formatTrafficLocalized(it, gbUnit, mbUnit) }
                            ?: if (quota != null && quota.totalBytes <= 0L) stringResource(R.string.novapulse_unlimited)
                            else "—"
                        Text(
                            text = stringResource(R.string.novapulse_settings_quota_summary_fmt, remStr),
                            style = QsTextStyles.TabularStat,
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(
                        onClick = onOpenSubscriptionDetails,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_view_details_chevron),
                            color = QsColors.ElectricCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
