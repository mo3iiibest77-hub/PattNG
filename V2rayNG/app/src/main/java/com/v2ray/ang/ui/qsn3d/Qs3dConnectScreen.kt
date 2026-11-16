package com.v2ray.ang.ui.qsn3d

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.v2ray.ang.senpai.SmartSwitchReason

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
    val activeConfig = state.configs.firstOrNull { it.guid == state.selectedGuid }
    val activeSub = state.subscriptions.firstOrNull { it.enabled } ?: state.subscriptions.firstOrNull()

    val orbMode = when {
        state.coreRunning -> OrbVisualMode.CONNECTED
        flow.busy -> OrbVisualMode.PROCESSING
        flow.step == SmartStep.Error -> OrbVisualMode.ERROR
        else -> OrbVisualMode.DISCONNECTED
    }

    val orbTitle = when {
        state.coreRunning -> stringResource(R.string.qsn3d_orb_connected)
        flow.busy -> when (flow.step) {
            SmartStep.RedeemingAccess -> stringResource(R.string.qsn3d_step_redeeming)
            SmartStep.RefreshingSubscription -> stringResource(R.string.qsn3d_step_refresh_sub)
            SmartStep.DetectingCdnViaPing -> stringResource(R.string.qsn3d_step_detect_cdn)
            SmartStep.ScanningBestIp -> "${flow.scanDone}/${flow.scanTotal}"
            SmartStep.ApplyingBestIp,
            SmartStep.StartingVpn -> stringResource(R.string.qsn3d_step_starting_vpn)
            else -> stringResource(R.string.qsn3d_step_preparing)
        }
        flow.step == SmartStep.Error -> stringResource(R.string.qsn3d_orb_retry)
        else -> stringResource(R.string.qsn3d_orb_smart_connect)
    }

    val countryCode = normalizeCountryCode(state.connectedCountry, activeConfig?.remarks.orEmpty())
    val isPersian = state.currentLanguageCode.startsWith("fa")
    val countryLabel = countryDisplayLabel(countryCode, isPersian)

    val orbSubtitle = when {
        state.coreRunning -> countryLabel.takeIf { it.isNotBlank() }
            ?: state.connectedCountry.takeIf { it.isNotBlank() }
            ?: activeConfig?.remarks
            ?: stringResource(R.string.qsn3d_tap_disconnect)
        flow.busy && flow.bestIp != null -> "${flow.bestUploadKBps} KB/s"
        flow.busy -> stringResource(R.string.qsn3d_tap_cancel)
        else -> activeConfig?.remarks ?: stringResource(R.string.qsn3d_one_tap_ready)
    }

    val activeCdn = state.forcedCdn
        ?: flow.detectedCdn.takeIf { it != CdnType.UNKNOWN }
        ?: state.lastCdnDetection?.cdnType?.takeIf { it != CdnType.UNKNOWN }
        ?: activeConfig?.cdnHint
        ?: CdnType.UNKNOWN

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Hero 3D Orbital Sphere Card + Fresh IP Optimization Action
        item {
            val motionActive = rememberMotionActive()
            val showConnectCardFlag = state.coreRunning && countryCode.isNotBlank()
            Glass3dCard(
                beamSeed = 1,
                glowColor = when (orbMode) {
                    OrbVisualMode.CONNECTED -> QsColors.ConnectedEmerald.copy(alpha = 0.52f)
                    OrbVisualMode.PROCESSING -> QsColors.PlasmaViolet.copy(alpha = 0.52f)
                    OrbVisualMode.ERROR -> QsColors.DangerCrimson.copy(alpha = 0.52f)
                    OrbVisualMode.DISCONNECTED -> QsColors.ElectricCyan.copy(alpha = 0.36f)
                },
                backdropContent = {
                    AnimatedVisibility(
                        visible = showConnectCardFlag,
                        enter = fadeIn(animationSpec = tween(420)),
                        exit = fadeOut(animationSpec = tween(320)),
                        modifier = Modifier.matchParentSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WindscribeCountryFlagBackdrop(
                                countryCode = countryCode,
                                motionActive = motionActive,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.58f)
                            )
                            // Contrast scrim inside card so orb, flag badge & typography remain crisp
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0x90050B1C),
                                                Color(0x52060E24),
                                                Color(0xA4050B1C),
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
            ) {
                val activePipeline = activeConfig?.pipeline ?: flow.activePipeline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(active = state.coreRunning)
                        Text(
                            text = if (state.coreRunning) stringResource(R.string.novapulse_tunnel_protected)
                            else stringResource(R.string.qsn3d_status_ready),
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (activePipeline == ConfigPipeline.STANDARD) {
                        StandardPipelineBadge(
                            label = activeConfig?.transportBadge?.ifBlank { "Standard" }
                                ?: flow.activeTransportBadge
                                ?: "Standard",
                            accent = QsColors.PlasmaViolet,
                        )
                    } else {
                        CdnProviderBadge(cdnType = activeCdn)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modeText = if (state.isExplicitSelection) {
                        stringResource(
                            R.string.novapulse_mode_manual_pinned_fmt,
                            activeConfig?.let { it.transportBadge.ifBlank { it.remarks } } ?: "—",
                        )
                    } else {
                        stringResource(R.string.novapulse_mode_smart_auto)
                    }
                    Text(
                        text = modeText,
                        color = if (state.isExplicitSelection) QsColors.SolarGold else QsColors.AuroraMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.isExplicitSelection) {
                        TextButton(onClick = onSwitchToSmartAuto) {
                            Text(
                                text = stringResource(R.string.novapulse_switch_smart_auto_btn),
                                color = QsColors.ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                val banner = state.failoverBanner
                if (banner != null && banner.reason != SmartSwitchReason.NONE) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.novapulse_failover_switched_fmt,
                            banner.transportBadge.ifBlank { "Standard" },
                            banner.delayMs,
                        ),
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val progress = if (flow.busy && flow.step == SmartStep.ScanningBestIp && flow.scanTotal > 0) {
                        flow.scanDone.toFloat() / flow.scanTotal.toFloat()
                    } else null

                    ConnectionOrb3d(
                        mode = orbMode,
                        progressFraction = progress,
                        primaryText = orbTitle,
                        secondaryText = orbSubtitle,
                        connectedCountryCode = countryCode,
                        onClick = {
                            if (flow.busy) onCancelFlow() else onConnectOrDisconnect()
                        }
                    )
                }

                if (state.coreRunning && flow.busy &&
                    (flow.step == SmartStep.ScanningBestIp || flow.step == SmartStep.ApplyingBestIp || flow.step == SmartStep.StartingVpn)
                ) {
                    Spacer(Modifier.height(8.dp))
                    val isScanning = flow.step == SmartStep.ScanningBestIp && flow.scanTotal > 0
                    val optLabel = if (isScanning) {
                        stringResource(R.string.novapulse_bg_opt_scanning_fmt, flow.scanDone, flow.scanTotal)
                    } else {
                        stringResource(R.string.novapulse_bg_opt_switching)
                    }
                    Text(
                        text = optLabel,
                        color = QsColors.ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isScanning) {
                        LinearProgressIndicator(
                            progress = { (flow.scanDone.toFloat() / flow.scanTotal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                            color = QsColors.ElectricCyan,
                            trackColor = QsColors.GlassBorderTop,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                            color = QsColors.PlasmaViolet,
                            trackColor = QsColors.GlassBorderTop,
                        )
                    }
                }

                if (flow.step == SmartStep.Error && !flow.errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = flow.errorMessage,
                        color = QsColors.DangerCrimson,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (state.coreRunning) onTestConnectedPing() else onOpenConfigsTab() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (state.coreRunning) stringResource(R.string.qsn3d_test_ping)
                            else stringResource(R.string.qsn3d_select_config_btn),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                        val isStdActive = (activeConfig?.pipeline ?: flow.activePipeline) == ConfigPipeline.STANDARD
                        if (isStdActive && state.isExplicitSelection) {
                            OutlinedButton(
                                onClick = onSwitchToSmartAuto,
                                enabled = !flow.busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.ElectricCyan)
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_switch_smart_auto_btn),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onRescanIpAndConnect,
                                enabled = !flow.busy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.ElectricCyan)
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_find_faster_ip_btn),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                }
            }
        }

        // 2. Real-time Custom Speed HUD (Always enabled by default!)
        if (state.speedDisplayEnabled) {
            item {
                LiveSpeedHudRow(
                    downloadSpeedText = if (state.coreRunning) state.liveDownloadFormatted else "0 B/s",
                    uploadSpeedText = if (state.coreRunning) state.liveUploadFormatted else "0 B/s",
                    downloadLabel = stringResource(R.string.novapulse_download_speed),
                    uploadLabel = stringResource(R.string.novapulse_upload_speed),
                    active = state.coreRunning,
                    downloadBps = if (state.coreRunning) state.liveDownloadBps else 0L,
                    uploadBps = if (state.coreRunning) state.liveUploadBps else 0L,
                )
            }
        }

        // 3. Connected Node Location, Windscribe-Style Flag Medallion & Ping Telemetry Strip
        item {
            Glass3dCard(
                glowColor = QsColors.AuroraMint.copy(alpha = 0.28f),
                onClick = onOpenConfigsTab
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (countryCode.isNotBlank()) {
                        CountryFlagMedallion(
                            countryCode = countryCode,
                            size = 40.dp
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeConfig?.remarks ?: stringResource(R.string.novapulse_default_server_name),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (countryLabel.isNotBlank() || !activeConfig?.transportBadge.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            val subText = listOfNotNull(
                                activeConfig?.transportBadge?.takeIf { it.isNotBlank() },
                                countryLabel.takeIf { it.isNotBlank() }?.let { "${stringResource(R.string.novapulse_location_label)}: $it" },
                            ).joinToString(" • ")
                            Text(
                                text = subText,
                                color = QsColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = state.connectedLatencyMs?.let { "$it ms" }
                            ?: activeConfig?.delayMillis?.takeIf { it > 0L }?.let { "$it ms" }
                            ?: "—",
                        color = QsColors.ConnectedEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 4. Compact Subscription Card -> Tap opens full 3D Animated Subscription Details Screen!
        item {
            Glass3dCard(
                glowColor = QsColors.PlasmaViolet.copy(alpha = 0.36f),
                onClick = onOpenSubscriptionDetails
            ) {
                val quota = activeSub?.quota
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quota?.planName ?: activeSub?.remarks
                                ?: stringResource(R.string.novapulse_my_subscription_title),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        val exp = quota?.expireAtSeconds ?: 0L
                        val daysSummary = when {
                            exp > 0L -> {
                                val d = (((exp * 1000L) - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
                                stringResource(R.string.novapulse_days_left_fmt, d)
                            }
                            exp < 0L -> {
                                val d = ((-exp) / 86_400L).coerceAtLeast(1L)
                                stringResource(R.string.novapulse_days_duration_fmt, d)
                            }
                            else -> stringResource(R.string.novapulse_unlimited)
                        }
                        val remSummary = quota?.remainingBytes?.let { formatGbCompact(it) }
                            ?: stringResource(R.string.novapulse_unlimited)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$remSummary • $daysSummary",
                            color = QsColors.AuroraMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onRefreshSubs) {
                            Text(stringResource(R.string.qsn3d_sync_btn), fontSize = 12.sp)
                        }
                        Text(
                            text = stringResource(R.string.novapulse_view_details_chevron),
                            color = QsColors.ElectricCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (quota?.usageFraction != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (1f - quota.usageFraction!!).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = QsColors.AuroraMint,
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                }
            }
        }

        // 5. Post-Connect One-Time Discovery Live Banner (shows ONLY when running)
        if (state.discoveryState.running) {
            item {
                val disc = state.discoveryState
                val frac = if (disc.total > 0) (disc.done.toFloat() / disc.total).coerceIn(0f, 1f) else 0f
                Glass3dCard(glowColor = QsColors.AuroraMint.copy(alpha = 0.45f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.qsn3d_discovery_running_fmt,
                                disc.activeProvider?.name ?: "CDN"
                            ),
                            color = QsColors.AuroraMint,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${disc.done}/${disc.total}",
                            color = QsColors.TextPrimary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = QsColors.AuroraMint,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}
