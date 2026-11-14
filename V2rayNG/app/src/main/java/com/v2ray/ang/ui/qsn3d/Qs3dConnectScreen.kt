package com.v2ray.ang.ui.qsn3d

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.SmartStep
import com.v2ray.ang.senpai.SubscriptionQuotaInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Qs3dConnectScreen(
    state: Qs3dUiState,
    onSmartConnectOrDisconnect: () -> Unit,
    onDirectConnect: () -> Unit,
    onCancelFlow: () -> Unit,
    onActivationInputChange: (String) -> Unit,
    onRedeemActivation: () -> Unit,
    onRefreshSubs: () -> Unit,
    onDetectCdnPing: () -> Unit,
    onForcedCdnChange: (CdnType?) -> Unit,
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

    val orbSubtitle = when {
        state.coreRunning -> activeConfig?.server?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.qsn3d_tap_disconnect)
        flow.busy && flow.bestIp != null -> "${flow.bestIp} (${flow.bestUploadKBps} KB/s)"
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
        // 1. Hero 3D Holographic Orb
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Holographic3dConnectOrb(
                    mode = orbMode,
                    title = orbTitle,
                    subtitle = orbSubtitle,
                    cdnType = activeCdn,
                    onClick = {
                        if (flow.busy) onCancelFlow() else onSmartConnectOrDisconnect()
                    }
                )

                // Quick secondary controls under orb
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!state.coreRunning && !flow.busy && activeConfig != null) {
                        OutlinedButton(
                            onClick = onDirectConnect,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = stringResource(R.string.qsn3d_direct_connect),
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (state.coreRunning) {
                        OutlinedButton(
                            onClick = onTestConnectedPing,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = state.connectedTestMessage
                                    ?: stringResource(R.string.qsn3d_test_real_ping),
                                fontSize = 12.sp,
                                color = QsColors.AuroraMint
                            )
                        }
                    }
                }
            }
        }

        // 2. Scan / Smart Connect live step progress
        if (flow.busy || flow.step == SmartStep.Error) {
            item {
                Glass3dCard(
                    glowColor = if (flow.step == SmartStep.Error) QsColors.DangerCrimson.copy(alpha = 0.5f)
                    else QsColors.ElectricCyan.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (flow.step == SmartStep.Error) {
                                flow.errorMessage ?: stringResource(R.string.qsn3d_error_generic)
                            } else {
                                stepLabel(flow.step)
                            },
                            color = if (flow.step == SmartStep.Error) QsColors.DangerCrimson else QsColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (flow.busy) {
                            TextButton(onClick = onCancelFlow) {
                                Text(stringResource(R.string.qsn3d_cancel), color = QsColors.SolarGold)
                            }
                        }
                    }
                    if (flow.step == SmartStep.ScanningBestIp && flow.scanTotal > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (flow.scanDone.toFloat() / flow.scanTotal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = QsColors.ElectricCyan,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        if (flow.bestIp != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${stringResource(R.string.qsn3d_best_candidate)}: ${flow.bestIp} (${flow.bestUploadKBps} KB/s)",
                                color = QsColors.AuroraMint,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. Post-First-Connect Sequential Discovery Live Banner
        if (state.discoveryState.running) {
            item {
                val disc = state.discoveryState
                Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.55f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.qsn3d_post_connect_discovery_banner,
                                disc.activeProvider?.name ?: "CDN",
                                disc.done,
                                disc.total
                            ),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        if (disc.activeProvider != null) {
                            ProviderPillBadge(cdnType = disc.activeProvider)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (disc.total > 0) (disc.done.toFloat() / disc.total).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = QsColors.PlasmaViolet,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    if (disc.currentCidr.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = disc.currentCidr,
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 4. Domain Ping CDN Classification & Active Server Card
        item {
            Glass3dCard(glowColor = QsColors.CloudFrontSky.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeConfig?.remarks ?: stringResource(R.string.qsn3d_no_config_selected),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val origin = activeConfig?.originDomain.orEmpty()
                        if (origin.isNotBlank()) {
                            Text(
                                text = origin,
                                color = QsColors.TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    ProviderPillBadge(cdnType = activeCdn)
                }

                val pingInfo = state.lastCdnDetection
                if (pingInfo != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append(stringResource(R.string.qsn3d_ping_classify_result))
                            append(": ")
                            append(pingInfo.resolvedIp ?: "-")
                            if (pingInfo.pingRttMs != null) {
                                append(" (${pingInfo.pingRttMs} ms)")
                            }
                            append(" -> ")
                            append(pingInfo.cdnType.name)
                        },
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.forcedCdn == null,
                        onClick = { onForcedCdnChange(null) },
                        label = { Text(stringResource(R.string.qsn3d_cdn_auto), fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = state.forcedCdn == CdnType.CLOUDFLARE,
                        onClick = { onForcedCdnChange(CdnType.CLOUDFLARE) },
                        label = { Text("CF", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = state.forcedCdn == CdnType.CLOUDFRONT,
                        onClick = { onForcedCdnChange(CdnType.CLOUDFRONT) },
                        label = { Text("CloudFront", fontSize = 11.sp) }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDetectCdnPing,
                        enabled = !state.detectingCdn && activeConfig != null
                    ) {
                        Text(
                            text = if (state.detectingCdn) "..." else stringResource(R.string.qsn3d_ping_detect_btn),
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.qsn3d_configs_count_label, state.configs.size),
                        color = QsColors.TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = stringResource(R.string.qsn3d_manage_configs),
                        color = QsColors.ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onOpenConfigsTab)
                    )
                }
            }
        }

        // 5. Subscription Quota OR License / Subscription Activation Card
        if (activeSub != null) {
            item {
                SubscriptionQuotaCard(
                    sub = activeSub,
                    onRefresh = onRefreshSubs
                )
            }
        }

        item {
            Glass3dCard(glowColor = QsColors.SolarGold.copy(alpha = 0.25f)) {
                Text(
                    text = stringResource(R.string.qsn3d_activation_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.qsn3d_activation_subtitle),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.activationInput,
                    onValueChange = onActivationInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.qsn3d_activation_hint), fontSize = 12.sp)
                    }
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRedeemActivation,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QsColors.ElectricCyan,
                        contentColor = Color(0xFF031322)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.qsn3d_activate_import_btn),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionQuotaCard(
    sub: SubscriptionUiRow,
    onRefresh: () -> Unit,
) {
    Glass3dCard(glowColor = QsColors.AuroraMint.copy(alpha = 0.30f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.remarks,
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${sub.configCount} configs",
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.qsn3d_refresh_sub), fontSize = 12.sp)
            }
        }
        val q = sub.quota
        if (q != null && (q.totalBytes > 0L || q.expireAtSeconds > 0L)) {
            Spacer(Modifier.height(10.dp))
            QuotaBarContent(q)
        }
    }
}

@Composable
fun QuotaBarContent(quota: SubscriptionQuotaInfo) {
    val frac = quota.usageFraction
    if (frac != null) {
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (frac > 0.85f) QsColors.DangerCrimson else QsColors.AuroraMint,
            trackColor = Color.White.copy(alpha = 0.1f)
        )
        Spacer(Modifier.height(6.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val usedText = formatBytes(quota.usedBytes)
        val totalText = if (quota.totalBytes > 0L) formatBytes(quota.totalBytes) else "∞"
        Text(
            text = "$usedText / $totalText",
            color = QsColors.TextSecondary,
            fontSize = 11.sp
        )
        if (quota.expireAtSeconds > 0L) {
            val formattedDate = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(Date(quota.expireAtSeconds * 1000L))
            Text(
                text = formattedDate,
                color = QsColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun stepLabel(step: SmartStep): String = when (step) {
    SmartStep.Idle -> ""
    SmartStep.RedeemingAccess -> stringResource(R.string.qsn3d_step_redeeming)
    SmartStep.RefreshingSubscription -> stringResource(R.string.qsn3d_step_refresh_sub)
    SmartStep.SelectingProfile -> stringResource(R.string.qsn3d_step_selecting)
    SmartStep.DetectingCdnViaPing -> stringResource(R.string.qsn3d_step_detect_cdn)
    SmartStep.ScanningBestIp -> stringResource(R.string.qsn3d_step_scanning_ips)
    SmartStep.ApplyingBestIp -> stringResource(R.string.qsn3d_step_applying)
    SmartStep.StartingVpn -> stringResource(R.string.qsn3d_step_starting_vpn)
    SmartStep.Connected -> stringResource(R.string.qsn3d_orb_connected)
    SmartStep.Error -> stringResource(R.string.qsn3d_error_generic)
}
