package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * First-launch onboarding & hardware-bound license gate.
 * Presents core architecture clearly without AI-slop neon clutter.
 */
@Composable
fun Qs3dLicenseGateScreen(
    activationInput: String,
    isRedeeming: Boolean,
    onInputChange: (String) -> Unit,
    onRedeemClick: () -> Unit,
    onScanLicenseQrClick: () -> Unit,
    onOpenAboutPromo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(6.dp))

        // Crisp Brand Header
        Surface(
            shape = CircleShape,
            color = QsColors.SurfaceElevated,
            border = BorderStroke(1.dp, QsColors.BorderStrong),
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_stat_name),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = QsColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = QsColors.ElectricCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = QsTextStyles.MonoCode,
                        color = QsColors.ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.novapulse_gate_tagline),
                color = QsColors.TextSecondary,
                fontSize = 13.sp,
            )
        }

        // Core Capability Summary (crisp flat spec rows)
        Glass3dCard(
            surfaceColor = QsColors.SurfaceElevated,
            contentPadding = 14.dp,
        ) {
            GateSpecItem(
                accent = QsColors.ElectricCyan,
                text = stringResource(R.string.novapulse_gate_feature_1),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = QsColors.BorderSubtle.copy(alpha = 0.7f),
            )
            GateSpecItem(
                accent = QsColors.ConnectedEmerald,
                text = stringResource(R.string.novapulse_gate_feature_2),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = QsColors.BorderSubtle.copy(alpha = 0.7f),
            )
            GateSpecItem(
                accent = QsColors.PlasmaViolet,
                text = stringResource(R.string.novapulse_gate_feature_3),
            )
        }

        // License Activation Input Card
        Glass3dCard(
            glowColor = QsColors.ElectricCyan.copy(alpha = 0.35f),
            contentPadding = 16.dp,
        ) {
            Text(
                text = stringResource(R.string.novapulse_license_card_title),
                color = QsColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.novapulse_license_card_hint),
                color = QsColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = activationInput,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRedeeming,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "NP-XXXX-XXXX-XXXX",
                            color = QsColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        )
                    },
                    textStyle = QsTextStyles.MonoCode.copy(
                        color = QsColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (activationInput.isNotBlank() && !isRedeeming) onRedeemClick()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = QsColors.ElectricCyan,
                        unfocusedBorderColor = QsColors.BorderStrong,
                        focusedContainerColor = QsColors.SurfaceInset,
                        unfocusedContainerColor = QsColors.SurfaceInset,
                        focusedTextColor = QsColors.TextPrimary,
                        unfocusedTextColor = QsColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onRedeemClick,
                enabled = !isRedeeming && activationInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = QsColors.ElectricCyan,
                    contentColor = Color(0xFF04151E),
                    disabledContainerColor = QsColors.BorderSubtle,
                    disabledContentColor = QsColors.TextMuted,
                )
            ) {
                if (isRedeeming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF04151E),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.novapulse_license_activate_btn),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onScanLicenseQrClick,
                enabled = !isRedeeming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, QsColors.BorderStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.TextPrimary)
            ) {
                Text(
                    text = stringResource(R.string.novapulse_license_scan_qr),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }

        // Secondary Guide & Purchase Support Card
        Glass3dCard(
            surfaceColor = QsColors.SurfaceElevated,
            contentPadding = 14.dp,
        ) {
            Text(
                text = stringResource(R.string.novapulse_gate_need_license_title),
                color = QsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.novapulse_gate_need_license_desc),
                color = QsColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenAboutPromo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, QsColors.AuroraMint.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.AuroraMint),
            ) {
                Text(
                    text = stringResource(R.string.novapulse_open_promo_guide_btn),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GateSpecItem(accent: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Text(
            text = text,
            color = QsColors.TextPrimary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/**
 * Rich, accurate "My Subscription" dashboard:
 * - Localized GB/MB precision (1024^3 binary GiB matching 3x-ui & NovaPulse server)
 * - Exact Remaining Days + Hours countdown
 * - Separate Upload / Download / Used / Remaining / Total traffic breakdown
 * - Device slot counters, Start & Expiry timestamps, License Status & Last Sync timestamp
 */
@Composable
fun Qs3dSubscriptionScreen(
    state: Qs3dUiState,
    onBack: () -> Unit,
    onRefreshQuota: () -> Unit,
) {
    val activeSub = state.subscriptions.firstOrNull()
    val quota = activeSub?.quota
    val gbUnit = stringResource(R.string.novapulse_unit_gb)
    val mbUnit = stringResource(R.string.novapulse_unit_mb)

    val usedB = quota?.usedBytes ?: 0L
    val totalB = quota?.totalBytes ?: 0L
    val remB = quota?.remainingBytes
    val uploadB = quota?.uploadBytes ?: 0L
    val downloadB = quota?.downloadBytes ?: 0L
    val usedPct = quota?.usagePercent ?: 0
    val remPct = quota?.remainingPercent ?: 100
    val usedFrac = quota?.usageFraction ?: 0f

    val isActiveStatus = (quota?.licenseStatus ?: "active").lowercase(Locale.US) == "active"

    val remainingText = when {
        quota == null -> "—"
        totalB <= 0L -> stringResource(R.string.novapulse_unlimited)
        remB != null -> formatTrafficLocalized(remB, gbUnit, mbUnit)
        else -> "—"
    }

    val expRaw = quota?.expireAtSeconds ?: 0L
    val startText = quota?.createdAtSeconds?.takeIf { it > 0L }?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it * 1000L))
    } ?: "—"

    val expiryText = when {
        expRaw > 0L -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(expRaw * 1000L))
        expRaw < 0L -> stringResource(R.string.novapulse_starts_on_first_use)
        else -> stringResource(R.string.novapulse_unlimited)
    }

    val context = LocalContext.current
    val remainingTimeText = formatRemainingTimeText(
        expRawSeconds = expRaw,
        nowMs = System.currentTimeMillis(),
        unlimitedLabel = context.getString(R.string.novapulse_unlimited),
        startsOnFirstUseLabel = context.getString(R.string.novapulse_starts_on_first_use),
        expiredLabel = context.getString(R.string.novapulse_expired_label),
        daysHoursFmt = { d, h -> context.getString(R.string.novapulse_days_hours_left_fmt, d, h) },
        hoursLeftFmt = { h -> context.getString(R.string.novapulse_hours_left_fmt, h) },
        durationDaysFmt = { d -> context.getString(R.string.novapulse_days_duration_fmt, d) },
    )

    val maxDev = quota?.maxDevices ?: 1
    val usedDev = quota?.usedDevices ?: 1
    val devicesText = if (maxDev <= 0) {
        stringResource(R.string.novapulse_sub_devices_unlimited_fmt, usedDev)
    } else {
        stringResource(R.string.novapulse_sub_devices_fmt, usedDev, maxDev)
    }

    val syncedAt = quota?.updatedAtMs?.takeIf { it > 0L } ?: activeSub?.lastUpdated?.takeIf { it > 0L }
    val syncedText = syncedAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(it))
    } ?: "—"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = stringResource(R.string.novapulse_my_subscription_title),
                    color = QsColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onRefreshQuota,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_sub_refresh_btn),
                        color = QsColors.ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Plan Banner + Status + Donut Ring
        item {
            Glass3dCard(
                surfaceColor = QsColors.SurfaceElevated,
                glowColor = if (isActiveStatus) QsColors.ConnectedEmerald.copy(alpha = 0.30f)
                else QsColors.DangerCrimson.copy(alpha = 0.45f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.novapulse_sub_plan_label),
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = quota?.planName?.takeIf { it.isNotBlank() }
                                ?: activeSub?.remarks?.takeIf { it.isNotBlank() }
                                ?: "NovaPulse",
                            color = QsColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isActiveStatus) QsColors.ConnectedEmerald.copy(alpha = 0.16f)
                        else QsColors.DangerCrimson.copy(alpha = 0.16f),
                        border = BorderStroke(
                            1.dp,
                            if (isActiveStatus) QsColors.ConnectedEmerald.copy(alpha = 0.40f)
                            else QsColors.DangerCrimson.copy(alpha = 0.40f)
                        ),
                    ) {
                        Text(
                            text = if (isActiveStatus) stringResource(R.string.novapulse_sub_status_active)
                            else stringResource(R.string.novapulse_sub_status_inactive),
                            color = if (isActiveStatus) QsColors.ConnectedEmerald else QsColors.DangerCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Animated3dQuotaChart(
                        usedFraction = if (totalB > 0L) usedFrac else 0f,
                        usedPercentText = if (totalB > 0L) "$remPct%" else "∞",
                        caption = stringResource(R.string.novapulse_remaining_traffic_label),
                        accentColor = when {
                            totalB <= 0L -> QsColors.ConnectedEmerald
                            usedFrac > 0.88f -> QsColors.DangerCrimson
                            usedFrac > 0.70f -> QsColors.SolarGold
                            else -> QsColors.ElectricCyan
                        },
                        sizeDp = 122.dp,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.novapulse_remaining_traffic_label),
                                color = QsColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                            Text(
                                text = remainingText,
                                style = QsTextStyles.TabularStat,
                                color = QsColors.TextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        if (totalB > 0L) {
                            Text(
                                text = "${stringResource(R.string.novapulse_used_pct_fmt, usedPct)}  •  ${stringResource(R.string.novapulse_remaining_pct_fmt, remPct)}",
                                style = QsTextStyles.TabularStat,
                                color = QsColors.ElectricCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = QsColors.SurfaceInset,
                            border = BorderStroke(1.dp, QsColors.BorderSubtle),
                        ) {
                            Text(
                                text = remainingTimeText,
                                style = QsTextStyles.TabularStat,
                                color = QsColors.AuroraMint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Traffic Grid: Used / Total / Upload / Download
        item {
            Glass3dCard(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricPill(
                        label = stringResource(R.string.novapulse_used_traffic_label),
                        value = formatTrafficLocalized(usedB, gbUnit, mbUnit),
                        accent = QsColors.ElectricCyan,
                        modifier = Modifier.weight(1f),
                    )
                    MetricPill(
                        label = stringResource(R.string.novapulse_total_traffic_label),
                        value = if (totalB > 0L) formatTrafficLocalized(totalB, gbUnit, mbUnit)
                        else stringResource(R.string.novapulse_unlimited),
                        accent = QsColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetricPill(
                        label = stringResource(R.string.novapulse_sub_download_label),
                        value = "↓ ${formatTrafficLocalized(downloadB, gbUnit, mbUnit)}",
                        accent = QsColors.ConnectedEmerald,
                        modifier = Modifier.weight(1f),
                    )
                    MetricPill(
                        label = stringResource(R.string.novapulse_sub_upload_label),
                        value = "↑ ${formatTrafficLocalized(uploadB, gbUnit, mbUnit)}",
                        accent = QsColors.PlasmaViolet,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Schedule, Devices & Sync Audit Details
        item {
            Glass3dCard {
                Text(
                    text = stringResource(R.string.novapulse_sub_dates_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))

                InfoKeyValueRow(stringResource(R.string.novapulse_devices_label), devicesText)
                HorizontalDivider(color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_start_date), startText)
                HorizontalDivider(color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_expiry_date), expiryText)
                HorizontalDivider(color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_remaining_time), remainingTimeText)
                HorizontalDivider(color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_last_synced), syncedText)
            }
        }
    }
}

@Composable
private fun InfoKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = QsColors.TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            style = QsTextStyles.TabularStat,
            color = QsColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Formats byte count using exact binary GiB (`1024^3`, matching Xray/3x-ui/NovaPulse quotas)
 * with localized unit labels (`گیگابایت` / `مگابایت` or `GB` / `MB`).
 */
internal fun formatTrafficLocalized(bytes: Long, gbLabel: String, mbLabel: String): String {
    val cleanBytes = bytes.coerceAtLeast(0L)
    if (cleanBytes == 0L) return "0.00 $gbLabel"
    val gb = cleanBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f %s", gb, gbLabel)
    } else {
        val mb = cleanBytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.US, "%.1f %s", mb, mbLabel)
    }
}

internal fun formatGbCompact(bytes: Long): String {
    return formatTrafficLocalized(bytes, "GB", "MB")
}

internal fun formatRemainingTimeText(
    expRawSeconds: Long,
    nowMs: Long,
    unlimitedLabel: String,
    startsOnFirstUseLabel: String,
    expiredLabel: String,
    daysHoursFmt: (Long, Long) -> String,
    hoursLeftFmt: (Long) -> String,
    durationDaysFmt: (Long) -> String,
): String {
    return when {
        expRawSeconds > 0L -> {
            val diffMs = (expRawSeconds * 1000L) - nowMs
            if (diffMs <= 0L) {
                expiredLabel
            } else {
                val totalHours = diffMs / 3_600_000L
                val days = totalHours / 24L
                val hours = totalHours % 24L
                if (days >= 1L) daysHoursFmt(days, hours) else hoursLeftFmt(totalHours.coerceAtLeast(1L))
            }
        }
        expRawSeconds < 0L -> {
            val days = ((-expRawSeconds) / 86_400L).coerceAtLeast(1L)
            durationDaysFmt(days)
        }
        else -> unlimitedLabel
    }
}
