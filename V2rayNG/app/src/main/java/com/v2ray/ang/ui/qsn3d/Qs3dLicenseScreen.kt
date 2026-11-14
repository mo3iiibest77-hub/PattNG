package com.v2ray.ang.ui.qsn3d

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.SubscriptionQuotaInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen NovaPulse License Onboarding Screen displayed on first launch when no license
 * is bound yet. Strictly accepts ONLY panel-issued license keys (keyboard or QR scan).
 */
@Composable
fun Qs3dLicenseGateScreen(
    activationInput: String,
    isRedeeming: Boolean,
    onInputChange: (String) -> Unit,
    onRedeemClick: () -> Unit,
    onScanLicenseQrClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeroBanner(
                imageRes = R.drawable.np_hero_license,
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.novapulse_license_gate_subtitle),
                accentColor = QsColors.ElectricCyan,
                height = 176.dp,
            )
        }

        item {
            Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.45f)) {
                Text(
                    text = stringResource(R.string.novapulse_license_card_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.novapulse_license_card_hint),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = activationInput,
                    onValueChange = onInputChange,
                    placeholder = {
                        Text(
                            text = "NP-XXXX-XXXX-XXXX",
                            style = TextStyle(fontFamily = FontFamily.Monospace)
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = QsColors.TextPrimary
                    ),
                    singleLine = true,
                    enabled = !isRedeeming,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onRedeemClick,
                    enabled = !isRedeeming && activationInput.trim().length >= 4,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QsColors.ElectricCyan)
                ) {
                    if (isRedeeming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF041322),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(R.string.novapulse_license_activate_btn),
                        color = Color(0xFF041322),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onScanLicenseQrClick,
                    enabled = !isRedeeming,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.novapulse_license_scan_qr))
                }
            }
        }
    }
}

/**
 * Dedicated "اشتراک من" (My Subscription) Dashboard with animated 3D traffic ring gauge,
 * remaining/used/total GB, start & expiry timestamps, countdown, device limit & license status.
 */
@Composable
fun Qs3dSubscriptionScreen(
    state: Qs3dUiState,
    onBack: () -> Unit,
    onRefreshQuota: () -> Unit,
) {
    val activeSub = state.subscriptions.firstOrNull { it.enabled } ?: state.subscriptions.firstOrNull()
    val quota: SubscriptionQuotaInfo? = activeSub?.quota
    val activeConfig = state.configs.firstOrNull { it.guid == state.selectedGuid }

    val totalB = quota?.totalBytes ?: 0L
    val usedB = quota?.usedBytes ?: 0L
    val remB = quota?.remainingBytes
    val remainingFraction = when {
        totalB > 0L && remB != null -> (remB.toFloat() / totalB.toFloat()).coerceIn(0f, 1f)
        else -> 1f
    }

    val centerBig = when {
        remB != null -> formatGbCompact(remB)
        totalB <= 0L -> stringResource(R.string.novapulse_unlimited)
        else -> "—"
    }
    val centerSub = stringResource(R.string.novapulse_remaining_traffic_label)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan)
                }
                Text(
                    text = stringResource(R.string.novapulse_my_subscription_title),
                    color = QsColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onRefreshQuota) {
                    Text(stringResource(R.string.qsn3d_sync_btn), color = QsColors.AuroraMint)
                }
            }
        }

        // 1. Hero 3D Animated Traffic Gauge Card
        item {
            Glass3dCard(glowColor = QsColors.ElectricCyan.copy(alpha = 0.5f)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = quota?.planName ?: activeSub?.remarks
                                    ?: stringResource(R.string.novapulse_sub_default_title),
                                color = QsColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            if (activeConfig != null) {
                                Text(
                                    text = activeConfig.remarks,
                                    color = QsColors.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        val activeStatus = quota?.licenseStatus != "revoked" && quota?.licenseStatus != "disabled"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (activeStatus) QsColors.ConnectedEmerald.copy(alpha = 0.16f)
                                    else QsColors.DangerCrimson.copy(alpha = 0.16f)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            StatusDot(active = activeStatus)
                            Text(
                                text = if (activeStatus) stringResource(R.string.novapulse_sub_status_active)
                                else stringResource(R.string.novapulse_sub_status_inactive),
                                color = if (activeStatus) QsColors.ConnectedEmerald else QsColors.DangerCrimson,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Animated3dQuotaChart(
                        remainingFraction = remainingFraction,
                        centerPrimary = centerBig,
                        centerSecondary = centerSub
                    )
                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricPill(
                            label = stringResource(R.string.novapulse_used_traffic_label),
                            value = formatGbCompact(usedB),
                            accent = QsColors.PlasmaViolet,
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            label = stringResource(R.string.novapulse_total_traffic_label),
                            value = if (totalB > 0L) formatGbCompact(totalB) else stringResource(R.string.novapulse_unlimited),
                            accent = QsColors.ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            label = stringResource(R.string.novapulse_devices_label),
                            value = "${quota?.usedDevices ?: 1} / ${quota?.maxDevices ?: 1}",
                            accent = QsColors.AuroraMint,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Validity Dates & Traffic Breakdown Card
        item {
            Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.35f)) {
                Text(
                    text = stringResource(R.string.novapulse_sub_dates_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))

                val startText = quota?.createdAtSeconds?.takeIf { it > 0L }?.let {
                    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it * 1000L))
                } ?: "—"

                val expRaw = quota?.expireAtSeconds ?: 0L
                val expiryText = when {
                    expRaw > 0L -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(Date(expRaw * 1000L))
                    expRaw < 0L -> stringResource(R.string.novapulse_starts_on_first_use)
                    else -> stringResource(R.string.novapulse_unlimited)
                }

                val remainingDaysText = when {
                    expRaw > 0L -> {
                        val days = (((expRaw * 1000L) - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
                        stringResource(R.string.novapulse_days_left_fmt, days)
                    }
                    expRaw < 0L -> {
                        val days = ((-expRaw) / 86_400L).coerceAtLeast(1L)
                        stringResource(R.string.novapulse_days_duration_fmt, days)
                    }
                    else -> stringResource(R.string.novapulse_unlimited)
                }

                InfoKeyValueRow(stringResource(R.string.novapulse_sub_start_date), startText)
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_expiry_date), expiryText)
                InfoKeyValueRow(stringResource(R.string.novapulse_sub_remaining_time), remainingDaysText)
                InfoKeyValueRow(
                    stringResource(R.string.novapulse_upload_download_breakdown),
                    "↑ ${formatGbCompact(quota?.uploadBytes ?: 0L)}  •  ↓ ${formatGbCompact(quota?.downloadBytes ?: 0L)}"
                )
            }
        }
    }
}

@Composable
private fun InfoKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = QsColors.TextSecondary, fontSize = 12.sp)
        Text(text = value, color = QsColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun formatGbCompact(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0 || bytes == 0L) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.US, "%.1f MB", mb)
    }
}
