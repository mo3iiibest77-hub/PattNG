package com.v2ray.ang.ui.qsn3d

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.compose.AppListItem
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Qs3dSettingsScreen(
    state: Qs3dUiState,
    apps: List<AppInfo>,
    appsLoading: Boolean,
    selectedApps: Set<String>,
    perAppEnabled: Boolean,
    perAppBypass: Boolean,
    onPerAppEnabledChange: (Boolean) -> Unit,
    onPerAppBypassChange: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onSearchApps: (String) -> Unit,
    onAutoDiscoveryChange: (Boolean) -> Unit,
    onAuto2hOptimizeChange: (Boolean) -> Unit,
    onAutoSmartFailoverChange: (Boolean) -> Unit,
    onSpeedDisplayChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onRedeemNewLicense: (String) -> Unit,
    onScanLicenseQr: () -> Unit,
    onOpenMySubscription: () -> Unit,
    onRefreshSubscription: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAboutPromo: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenAvailableUpdate: () -> Unit,
    onLogoutLicense: () -> Unit,
) {
    var appQuery by remember { mutableStateOf("") }
    var showPerAppList by remember { mutableStateOf(false) }
    var showChangeLicenseDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var licenseDraft by remember { mutableStateOf("") }

    val gbUnit = stringResource(R.string.novapulse_unit_gb)
    val mbUnit = stringResource(R.string.novapulse_unit_mb)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Account & Subscription Card (never prints raw license key)
        item {
            Glass3dCard(
                surfaceColor = QsColors.SurfaceElevated,
                glowColor = QsColors.ConnectedEmerald.copy(alpha = 0.45f),
                beamSeed = 2,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsCardHeader(
                        iconRes = R.drawable.ic_np_sub,
                        title = stringResource(R.string.novapulse_license_section_title),
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = QsColors.ConnectedEmerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, QsColors.ConnectedEmerald.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_license_active_status),
                            color = QsColors.ConnectedEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                val activeSub = state.subscriptions.firstOrNull()
                val quota = activeSub?.quota
                if (activeSub != null) {
                    Spacer(Modifier.height(6.dp))
                    val remText = when {
                        quota == null -> "—"
                        quota.totalBytes <= 0L -> stringResource(R.string.novapulse_unlimited)
                        else -> formatTrafficLocalized(quota.remainingBytes ?: 0L, gbUnit, mbUnit)
                    }
                    Text(
                        text = "${activeSub.remarks}  •  ${stringResource(R.string.novapulse_settings_quota_summary_fmt, remText)}",
                        style = QsTextStyles.TabularStat,
                        color = QsColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onOpenMySubscription,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = QsColors.ElectricCyan,
                            contentColor = Color(0xFF04151E),
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_my_subscription_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }

                    OutlinedButton(
                        onClick = onRefreshSubscription,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, QsColors.BorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QsColors.ElectricCyan)
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_sync_sub_btn),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showChangeLicenseDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, QsColors.BorderSubtle),
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_change_license_btn),
                            color = QsColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                    OutlinedButton(
                        onClick = onScanLicenseQr,
                        modifier = Modifier.weight(0.75f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, QsColors.BorderSubtle),
                    ) {
                        Text(
                            text = stringResource(R.string.qsn3d_scan_qr),
                            color = QsColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showLogoutConfirmDialog = true },
                    enabled = !state.isLoggingOut,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.45f)),
                ) {
                    if (state.isLoggingOut) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = QsColors.DangerCrimson
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.novapulse_logout_license_btn),
                        color = QsColors.DangerCrimson,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        // 2. Smart Routing & Telemetry Controls
        item {
            Glass3dCard(
                glowColor = QsColors.ElectricCyan.copy(alpha = 0.38f),
                beamSeed = 3,
            ) {
                SettingsCardHeader(
                    iconRes = R.drawable.ic_np_smart_route,
                    title = stringResource(R.string.qsn3d_smart_automation_title),
                )
                Spacer(Modifier.height(10.dp))

                ToggleSettingRow(
                    title = stringResource(R.string.novapulse_auto_failover_label),
                    description = stringResource(R.string.novapulse_auto_failover_desc),
                    checked = state.autoSmartFailover,
                    onCheckedChange = onAutoSmartFailoverChange
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 9.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                ToggleSettingRow(
                    title = stringResource(R.string.qsn3d_auto_discovery_label),
                    description = stringResource(R.string.qsn3d_auto_discovery_desc),
                    checked = state.autoPostConnectDiscovery,
                    onCheckedChange = onAutoDiscoveryChange
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 9.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                ToggleSettingRow(
                    title = stringResource(R.string.qsn3d_auto_2h_optimize_label),
                    description = stringResource(R.string.qsn3d_auto_2h_optimize_desc),
                    checked = state.auto2hOptimize,
                    onCheckedChange = onAuto2hOptimizeChange
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 9.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                ToggleSettingRow(
                    title = stringResource(R.string.novapulse_speed_display_label),
                    description = stringResource(R.string.novapulse_speed_display_desc),
                    checked = state.speedDisplayEnabled,
                    onCheckedChange = onSpeedDisplayChange
                )

                if (state.lastOptimizeAt > 0L) {
                    val dt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(state.lastOptimizeAt))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.novapulse_last_opt_fmt, dt, "${state.lastOptimizeUploadKBps} KB/s"),
                        style = QsTextStyles.TabularStat,
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Per-App Split Tunneling (with dedicated Expand / Collapse button right beside it)
        item {
            Glass3dCard(
                glowColor = QsColors.CloudFrontSky.copy(alpha = 0.35f),
                beamSeed = 6,
            ) {
                SettingsCardHeader(
                    iconRes = R.drawable.ic_per_apps_24dp,
                    title = stringResource(R.string.title_pref_per_app_proxy),
                    tint = QsColors.CloudFrontSky,
                )
                Spacer(Modifier.height(8.dp))
                ToggleSettingRow(
                    title = stringResource(R.string.per_app_proxy_settings_enable),
                    description = stringResource(R.string.qsn3d_per_app_desc),
                    checked = perAppEnabled,
                    onCheckedChange = { enabled ->
                        onPerAppEnabledChange(enabled)
                        if (!enabled) showPerAppList = false
                    }
                )

                if (perAppEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 9.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                    ToggleSettingRow(
                        title = stringResource(R.string.switch_bypass_apps_mode),
                        description = if (perAppBypass) {
                            stringResource(R.string.novapulse_per_app_summary_bypass_fmt, selectedApps.size)
                        } else {
                            stringResource(R.string.novapulse_per_app_summary_proxy_fmt, selectedApps.size)
                        },
                        checked = perAppBypass,
                        onCheckedChange = onPerAppBypassChange
                    )

                    Spacer(Modifier.height(10.dp))

                    // Explicit Collapse / Expand Toggle beside Search
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { showPerAppList = !showPerAppList },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showPerAppList) QsColors.SurfaceInset else QsColors.ElectricCyan.copy(alpha = 0.16f),
                                contentColor = QsColors.ElectricCyan,
                            ),
                            border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.40f)),
                        ) {
                            Text(
                                text = if (showPerAppList) {
                                    stringResource(R.string.novapulse_per_app_hide_list)
                                } else {
                                    stringResource(R.string.novapulse_per_app_show_list_fmt, selectedApps.size)
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (showPerAppList) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = appQuery,
                            onValueChange = {
                                appQuery = it
                                onSearchApps(it)
                            },
                            placeholder = {
                                Text(stringResource(R.string.qsn3d_search_apps_hint), color = QsColors.TextMuted, fontSize = 12.sp)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = QsColors.ElectricCyan,
                                unfocusedBorderColor = QsColors.BorderSubtle,
                                focusedContainerColor = QsColors.SurfaceInset,
                                unfocusedContainerColor = QsColors.SurfaceInset,
                                focusedTextColor = QsColors.TextPrimary,
                                unfocusedTextColor = QsColors.TextPrimary,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }
        }

        if (perAppEnabled && showPerAppList) {
            if (appsLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = QsColors.ElectricCyan)
                    }
                }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    AppListItem(
                        appName = app.appName,
                        packageName = app.packageName,
                        icon = null,
                        checked = selectedApps.contains(app.packageName),
                        onCheckedChange = { onToggleApp(app.packageName) }
                    )
                }
            }
        }

        // 4. Diagnostics & Live Redacted Logs
        item {
            Glass3dCard(
                glowColor = QsColors.PlasmaViolet.copy(alpha = 0.34f),
                beamSeed = 7,
            ) {
                SettingsCardHeader(
                    iconRes = R.drawable.ic_np_logs,
                    title = stringResource(R.string.novapulse_logs_section_title),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.novapulse_logs_section_desc),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_logs_open_btn),
                        color = QsColors.ElectricCyan,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // 5. Language Selector
        item {
            Glass3dCard(beamSeed = 10) {
                Text(
                    text = stringResource(R.string.qsn3d_language_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(QsColors.SurfaceInset)
                        .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val isFa = state.currentLanguageCode == "fa" || state.currentLanguageCode == "auto"
                    LanguageSegmentOption(
                        label = "فارسی",
                        selected = isFa,
                        onClick = { onLanguageSelected("fa") },
                        modifier = Modifier.weight(1f),
                    )
                    LanguageSegmentOption(
                        label = "English",
                        selected = state.currentLanguageCode == "en",
                        onClick = { onLanguageSelected("en") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 6. App Version & Self-Update
        item {
            val upd = state.availableUpdate
            val hasUpd = upd?.hasUpdate == true && !upd.latestVersion.isNullOrBlank()
            val abiLabel = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

            Glass3dCard(
                glowColor = if (hasUpd) QsColors.AuroraMint.copy(alpha = 0.42f) else QsColors.BorderSubtle,
                beamSeed = 11,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsCardHeader(
                            iconRes = R.drawable.ic_np_update,
                            title = stringResource(R.string.novapulse_update_card_title),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.novapulse_update_current_ver_fmt, BuildConfig.VERSION_NAME, abiLabel),
                            style = QsTextStyles.MonoCode,
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }

                    val pillColor = if (hasUpd) QsColors.AuroraMint else QsColors.ElectricCyan
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = pillColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.40f)),
                    ) {
                        Text(
                            text = if (hasUpd) {
                                "${stringResource(R.string.novapulse_update_badge_new)} v${upd?.latestVersion}"
                            } else {
                                stringResource(R.string.novapulse_update_badge_up_to_date)
                            },
                            color = pillColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (hasUpd) {
                    Button(
                        onClick = onOpenAvailableUpdate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = QsColors.AuroraMint,
                            contentColor = Color(0xFF04151E),
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_update_available_title_fmt, upd?.latestVersion.orEmpty()),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                OutlinedButton(
                    onClick = onCheckUpdates,
                    enabled = !state.isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    if (state.isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = QsColors.ElectricCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.novapulse_update_checking), color = QsColors.ElectricCyan, fontSize = 12.sp)
                    } else {
                        Text(stringResource(R.string.novapulse_update_check_btn), color = QsColors.ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 7. Guide, Official Channel & Telegram Support
        item {
            Glass3dCard(
                glowColor = QsColors.AuroraMint.copy(alpha = 0.34f),
                beamSeed = 14,
            ) {
                SettingsCardHeader(
                    iconRes = R.drawable.ic_np_guide,
                    title = stringResource(R.string.novapulse_promo_card_title),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.novapulse_promo_card_desc),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenAboutPromo,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.AuroraMint.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_open_promo_guide_btn),
                        color = QsColors.AuroraMint,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (showChangeLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showChangeLicenseDialog = false },
            containerColor = QsColors.SurfaceElevated,
            titleContentColor = QsColors.TextPrimary,
            textContentColor = QsColors.TextPrimary,
            title = { Text(stringResource(R.string.novapulse_change_license_btn)) },
            text = {
                OutlinedTextField(
                    value = licenseDraft,
                    onValueChange = { licenseDraft = it },
                    placeholder = { Text("NP-XXXX-XXXX-XXXX", color = QsColors.TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val code = licenseDraft.trim()
                    showChangeLicenseDialog = false
                    if (code.isNotEmpty()) onRedeemNewLicense(code)
                }) {
                    Text(stringResource(R.string.novapulse_license_activate_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangeLicenseDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            containerColor = QsColors.SurfaceElevated,
            titleContentColor = QsColors.TextPrimary,
            textContentColor = QsColors.TextSecondary,
            title = { Text(stringResource(R.string.novapulse_logout_confirm_title)) },
            text = { Text(stringResource(R.string.novapulse_logout_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirmDialog = false
                        onLogoutLicense()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = QsColors.DangerCrimson),
                ) {
                    Text(stringResource(R.string.novapulse_logout_license_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LanguageSegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) QsColors.SurfaceElevated else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, QsColors.BorderStrong) else null,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) QsColors.ElectricCyan else QsColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                color = QsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04151E),
                checkedTrackColor = QsColors.ElectricCyan,
                uncheckedThumbColor = QsColors.TextSecondary,
                uncheckedTrackColor = QsColors.SurfaceInset,
                uncheckedBorderColor = QsColors.BorderStrong,
            )
        )
    }
}

@Composable
private fun SettingsCardHeader(
    iconRes: Int,
    title: String,
    tint: Color? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = tint?.let { ColorFilter.tint(it) },
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            color = QsColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}
