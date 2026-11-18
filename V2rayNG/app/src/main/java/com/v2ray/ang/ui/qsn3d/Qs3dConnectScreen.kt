package com.v2ray.ang.ui.qsn3d

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.ConfigPipeline
import com.v2ray.ang.senpai.SmartStep
import com.v2ray.ang.util.Utils

/**
 * 3D Frosted Glass Home / Connect Screen:
 * - Animated Smart Mode Glass Pill (opposing perimeter beam)
 * - Volumetric 3D Gyroscope + Fibonacci Core Connect Trigger with 3D Emerald Shield Crest
 * - Windscribe-style Flowing Country Flag Backdrop when connected (across supported regions)
 * - Strictly masks origin domains/SNI (only ever displays verified numeric IPv4 addresses)
 * - Red disconnect callout & green live latency indicator
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

    val countryCode = normalizeCountryCode(
        rawCountry = state.connectedCountry,
        remarksFallback = selectedConfig?.remarks ?: flow.activeRemarks.orEmpty()
    )
    val isPersian = state.currentLanguageCode.startsWith("fa") || state.currentLanguageCode == "auto"
    val countryLabel = countryDisplayLabel(countryCode, isPersian)

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

    val pureBestIp = flow.bestIp?.takeIf { Utils.isPureIpAddress(it) }
    val tapInstruction = when {
        isBusy -> pureBestIp?.let { "IP: $it" } ?: stringResource(R.string.novapulse_connect_scanning)
        state.coreRunning -> stringResource(R.string.novapulse_connect_tap_stop)
        else -> stringResource(R.string.novapulse_connect_tap_start)
    }
    val tapInstructionColor = when {
        isBusy -> QsColors.ElectricCyan
        state.coreRunning -> Color(0xFFF87171) // Clear red disconnect callout
        else -> QsColors.TextSecondary
    }

    val motionActive = rememberMotionActive()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Animated Smart Mode Strip (with rotating perimeter beam & real routing emblem)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glass3dCard(
                modifier = Modifier.weight(1f, fill = false),
                cornerRadius = 13.dp,
                glowColor = if (state.coreRunning) QsColors.ConnectedEmerald.copy(alpha = 0.65f)
                else QsColors.ElectricCyan.copy(alpha = 0.48f),
                surfaceColor = QsColors.SurfaceElevated,
                contentPadding = 10.dp,
                beamSeed = 102,
                onClick = onOpenConfigsTab,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_np_smart_route),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    StatusDot(active = state.coreRunning)
                    Text(
                        text = if (state.isExplicitSelection && selectedConfig != null) {
                            stringResource(R.string.novapulse_mode_manual_pinned_fmt, selectedConfig.remarks)
                        } else {
                            stringResource(R.string.novapulse_mode_smart_auto)
                        },
                        color = QsColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.isExplicitSelection) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onSwitchToSmartAuto,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_switch_smart_auto_btn),
                        color = QsColors.ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Optional Failover Notice
        val banner = state.failoverBanner
        if (banner != null && state.coreRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = QsColors.SolarGold.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, QsColors.SolarGold.copy(alpha = 0.42f)),
            ) {
                Text(
                    text = stringResource(
                        R.string.novapulse_failover_switched_fmt,
                        banner.transportBadge.ifBlank { "Direct" },
                        banner.delayMs,
                    ),
                    style = QsTextStyles.TabularStat,
                    color = QsColors.SolarGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // 2. Hero 3D Connect Core Card + Windscribe-style Flowing Country Flag Backdrop
        val showFlagBackdrop = state.coreRunning && countryCode.isNotBlank()
        Glass3dCard(
            surfaceColor = QsColors.SurfaceCard,
            glowColor = when {
                state.coreRunning -> QsColors.ConnectedEmerald.copy(alpha = 0.72f)
                isBusy -> QsColors.SolarGold.copy(alpha = 0.65f)
                else -> QsColors.ElectricCyan.copy(alpha = 0.48f)
            },
            contentPadding = 18.dp,
            beamSeed = 1,
            backdropContent = {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showFlagBackdrop,
                    enter = fadeIn(animationSpec = tween(420)),
                    exit = fadeOut(animationSpec = tween(320)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WindscribeCountryFlagBackdrop(
                            countryCode = countryCode,
                            motionActive = motionActive,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.72f),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x5208101E),
                                            Color(0x3808101E),
                                            Color(0x9E08101E),
                                        )
                                    )
                                )
                        )
                    }
                }
            }
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
                    sizeDp = 206.dp,
                ) {
                    OrbCenterGlyph(mode = orbMode, sizeDp = 56.dp)
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = statusHeadline,
                    color = when {
                        state.coreRunning -> QsColors.AuroraMint
                        isBusy -> QsColors.ElectricCyan
                        else -> QsColors.TextPrimary
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = tapInstruction,
                    style = QsTextStyles.TabularStat,
                    color = tapInstructionColor,
                    fontSize = 13.sp,
                    fontWeight = if (state.coreRunning && !isBusy) FontWeight.Bold else FontWeight.Medium,
                )

                if (scanProgress != null) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { scanProgress },
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = QsColors.ElectricCyan,
                        trackColor = QsColors.SurfaceInset,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "${flow.scanDone} / ${flow.scanTotal}",
                        style = QsTextStyles.TabularStat,
                        color = QsColors.TextSecondary,
                        fontSize = 12.sp,
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
                        fontSize = 12.sp,
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
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.DangerCrimson),
                        ) {
                            Text(stringResource(R.string.qsn3d_cancel), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (activePipeline == ConfigPipeline.CDN) {
                        OutlinedButton(
                            onClick = onRescanIpAndConnect,
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, QsColors.BorderStrong),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = QsColors.SurfaceInset.copy(alpha = 0.72f),
                                contentColor = QsColors.ElectricCyan
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.novapulse_find_faster_ip_btn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (state.coreRunning) {
                        val ms = state.connectedLatencyMs?.takeIf { it > 0L }
                        val pingLabel = ms?.let { "${it} ms" } ?: stringResource(R.string.qsn3d_test_ping)
                        val pingColor = if (ms != null) QsColors.AuroraMint else QsColors.TextPrimary
                        OutlinedButton(
                            onClick = onTestConnectedPing,
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(
                                1.dp,
                                if (ms != null) QsColors.ConnectedEmerald.copy(alpha = 0.65f) else QsColors.BorderStrong
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = QsColors.SurfaceInset.copy(alpha = 0.78f),
                                contentColor = pingColor,
                            ),
                        ) {
                            Text(
                                text = pingLabel,
                                style = QsTextStyles.TabularStat,
                                color = pingColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // 3. 3D Illuminated Live Speed Telemetry Pair (beamSeed = 11 & 22 for opposite rotations)
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

        // 4. Active Server + Country Flag Medallion + Official Provider Logo + Subscription Strip
        val activeSub = state.subscriptions.firstOrNull()
        val quota = activeSub?.quota
        val gbUnit = stringResource(R.string.novapulse_unit_gb)
        val mbUnit = stringResource(R.string.novapulse_unit_mb)

        Glass3dCard(
            surfaceColor = QsColors.SurfaceElevated,
            glowColor = QsColors.AuroraMint.copy(alpha = 0.38f),
            contentPadding = 15.dp,
            beamSeed = 32,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenConfigsTab),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (countryCode.isNotBlank()) {
                        CountryFlagMedallion(
                            countryCode = countryCode,
                            size = 38.dp,
                            borderColor = if (state.coreRunning) QsColors.ConnectedEmerald else QsColors.ElectricCyan,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (countryLabel.isNotBlank() && state.coreRunning) {
                                "${stringResource(R.string.novapulse_location_label)} • $countryLabel"
                            } else {
                                stringResource(R.string.novapulse_location_label)
                            },
                            color = QsColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = selectedConfig?.remarks ?: flow.activeRemarks ?: stringResource(R.string.qsn3d_no_config_selected),
                            color = QsColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_np_sub),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.novapulse_my_subscription_short),
                                color = QsColors.TextMuted,
                                fontSize = 12.sp,
                            )
                            val remStr = quota?.remainingBytes?.let { formatTrafficLocalized(it, gbUnit, mbUnit) }
                                ?: if (quota != null && quota.totalBytes <= 0L) stringResource(R.string.novapulse_unlimited)
                                else "—"
                            Text(
                                text = stringResource(R.string.novapulse_settings_quota_summary_fmt, remStr),
                                style = QsTextStyles.TabularStat,
                                color = QsColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    TextButton(
                        onClick = onOpenSubscriptionDetails,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_view_details_chevron),
                            color = QsColors.ElectricCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
