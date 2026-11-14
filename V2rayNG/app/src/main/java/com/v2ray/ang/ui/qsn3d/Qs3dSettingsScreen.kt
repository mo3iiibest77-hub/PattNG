package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSpeedDisplayChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onRedeemNewLicense: (String) -> Unit,
    onScanLicenseQr: () -> Unit,
    onOpenMySubscription: () -> Unit,
    onLogoutLicense: () -> Unit,
) {
    var showChangeLicenseDialog by remember { mutableStateOf(false) }
    var licenseDraft by remember { mutableStateOf("") }
    var appQuery by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Illustrated Core & License Banner
        item {
            SectionHeroBanner(
                imageRes = R.drawable.np_hero_settings_core,
                title = stringResource(R.string.novapulse_hero_settings_title),
                subtitle = stringResource(R.string.novapulse_hero_settings_sub),
                accentColor = QsColors.PlasmaViolet,
                height = 116.dp,
            )
        }

        // 1. Account & License Management Card
        item {
            Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.40f)) {
                Text(
                    text = stringResource(R.string.novapulse_license_section_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                val maskedKey = state.savedLicenseKey.takeIf { it.isNotEmpty() } ?: "—"
                Text(
                    text = "${stringResource(R.string.novapulse_current_license_label)}: $maskedKey",
                    color = QsColors.AuroraMint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenMySubscription,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.novapulse_my_subscription_title))
                    }
                    OutlinedButton(
                        onClick = { showChangeLicenseDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.novapulse_change_license_btn))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onScanLicenseQr,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.novapulse_license_scan_qr))
                    }
                    OutlinedButton(
                        onClick = onLogoutLicense,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.novapulse_logout_license_btn), color = QsColors.DangerCrimson)
                    }
                }
            }
        }

        // 2. Smart Engine & Speed HUD Preferences
        item {
            Glass3dCard(glowColor = QsColors.ElectricCyan.copy(alpha = 0.35f)) {
                Text(
                    text = stringResource(R.string.qsn3d_smart_automation_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))

                ToggleSettingRow(
                    title = stringResource(R.string.novapulse_speed_display_label),
                    description = stringResource(R.string.novapulse_speed_display_desc),
                    checked = state.speedDisplayEnabled,
                    onCheckedChange = onSpeedDisplayChange
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))

                ToggleSettingRow(
                    title = stringResource(R.string.qsn3d_auto_discovery_label),
                    description = stringResource(R.string.qsn3d_auto_discovery_desc),
                    checked = state.autoPostConnectDiscovery,
                    onCheckedChange = onAutoDiscoveryChange
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.08f))

                ToggleSettingRow(
                    title = stringResource(R.string.qsn3d_auto_2h_optimize_label),
                    description = stringResource(R.string.qsn3d_auto_2h_optimize_desc),
                    checked = state.auto2hOptimize,
                    onCheckedChange = onAuto2hOptimizeChange
                )

                if (state.lastOptimizeAt > 0L) {
                    val dt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                        .format(Date(state.lastOptimizeAt))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.novapulse_last_opt_fmt, dt, "${state.lastOptimizeUploadKBps} KB/s"),
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. Language Selector Card (Persian Default)
        item {
            Glass3dCard(glowColor = QsColors.AuroraMint.copy(alpha = 0.25f)) {
                Text(
                    text = stringResource(R.string.qsn3d_language_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.currentLanguageCode == "fa" || state.currentLanguageCode == "auto",
                        onClick = { onLanguageSelected("fa") },
                        label = { Text("فارسی (پیش‌فرض)") }
                    )
                    FilterChip(
                        selected = state.currentLanguageCode == "en",
                        onClick = { onLanguageSelected("en") },
                        label = { Text("English") }
                    )
                }
            }
        }

        // 4. Per-App Split Tunneling Card
        item {
            Glass3dCard(glowColor = QsColors.CloudFrontSky.copy(alpha = 0.28f)) {
                Text(
                    text = stringResource(R.string.title_pref_per_app_proxy),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                ToggleSettingRow(
                    title = stringResource(R.string.per_app_proxy_settings_enable),
                    description = null,
                    checked = perAppEnabled,
                    onCheckedChange = onPerAppEnabledChange
                )
                if (perAppEnabled) {
                    Spacer(Modifier.height(6.dp))
                    ToggleSettingRow(
                        title = stringResource(R.string.switch_bypass_apps_mode),
                        description = null,
                        checked = perAppBypass,
                        onCheckedChange = onPerAppBypassChange
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = {
                            appQuery = it
                            onSearchApps(it)
                        },
                        placeholder = { Text(stringResource(R.string.qsn3d_search_apps_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (perAppEnabled) {
            if (appsLoading) {
                item { CircularProgressIndicator() }
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
    }

    if (showChangeLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showChangeLicenseDialog = false },
            title = { Text(stringResource(R.string.novapulse_change_license_btn)) },
            text = {
                OutlinedTextField(
                    value = licenseDraft,
                    onValueChange = { licenseDraft = it },
                    placeholder = { Text("NP-XXXX-XXXX-XXXX") },
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
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                color = QsColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
