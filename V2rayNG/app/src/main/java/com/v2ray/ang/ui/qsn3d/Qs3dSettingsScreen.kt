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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    onSaveBackendUrl: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onOpenClassicMode: () -> Unit,
    onOpenCoreSettings: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenLegacyCfScan: () -> Unit,
    onOpenLogcat: () -> Unit,
) {
    var backendDraft by remember(state.backendBaseUrl) { mutableStateOf(state.backendBaseUrl) }
    var appQuery by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Smart Automation Controls
        item {
            Glass3dCard(glowColor = QsColors.ElectricCyan.copy(alpha = 0.35f)) {
                Text(
                    text = stringResource(R.string.qsn3d_smart_automation_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.qsn3d_auto_discovery_label),
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.qsn3d_auto_discovery_desc),
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = state.autoPostConnectDiscovery,
                        onCheckedChange = onAutoDiscoveryChange
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.qsn3d_auto_2h_optimize_label),
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.qsn3d_auto_2h_optimize_desc),
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = state.auto2hOptimize,
                        onCheckedChange = onAuto2hOptimizeChange
                    )
                }

                if (state.lastOptimizeAt > 0L && state.lastOptimizeIp.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    val dt = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                        .format(Date(state.lastOptimizeAt))
                    Text(
                        text = "${stringResource(R.string.qsn3d_last_optimize)}: ${state.lastOptimizeIp} (${state.lastOptimizeUploadKBps} KB/s @ $dt)",
                        color = QsColors.AuroraMint,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 2. Language & Backend Panel API Config
        item {
            Glass3dCard(glowColor = QsColors.PlasmaViolet.copy(alpha = 0.30f)) {
                Text(
                    text = stringResource(R.string.qsn3d_language_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.currentLanguageCode == "auto",
                        onClick = { onLanguageSelected("auto") },
                        label = { Text(stringResource(R.string.qsn3d_lang_auto)) }
                    )
                    FilterChip(
                        selected = state.currentLanguageCode == "en",
                        onClick = { onLanguageSelected("en") },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = state.currentLanguageCode == "fa",
                        onClick = { onLanguageSelected("fa") },
                        label = { Text("فارسی") }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.qsn3d_backend_url_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.qsn3d_backend_url_desc),
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = backendDraft,
                    onValueChange = { backendDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://panel.example.com", fontSize = 12.sp) }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSaveBackendUrl(backendDraft) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.qsn3d_save))
                }
            }
        }

        // 3. Classic & Advanced System Activities
        item {
            Glass3dCard(glowColor = QsColors.SolarGold.copy(alpha = 0.25f)) {
                Text(
                    text = stringResource(R.string.qsn3d_advanced_tools_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onOpenClassicMode, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qsn3d_classic_ui), fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenCoreSettings, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qsn3d_core_settings), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onOpenRouting, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qsn3d_routing_rules), fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenLegacyCfScan, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qsn3d_matrix_scanner), fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onOpenLogcat, modifier = Modifier.weight(1f)) {
                        Text("Logcat", fontSize = 11.sp)
                    }
                }
            }
        }

        // 4. Per-App Proxy Header
        item {
            Glass3dCard(glowColor = QsColors.AuroraMint.copy(alpha = 0.28f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.qsn3d_per_app_title),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = stringResource(R.string.qsn3d_per_app_desc),
                            color = QsColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(checked = perAppEnabled, onCheckedChange = onPerAppEnabledChange)
                }

                if (perAppEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.qsn3d_per_app_bypass),
                            color = QsColors.TextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = perAppBypass, onCheckedChange = onPerAppBypassChange)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = {
                            appQuery = it
                            onSearchApps(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.qsn3d_search_apps), fontSize = 12.sp) }
                    )
                }
            }
        }

        if (perAppEnabled) {
            if (appsLoading && apps.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = QsColors.ElectricCyan)
                    }
                }
            } else {
                items(apps, key = { it.packageName }) { app ->
                    AppListItem(
                        appName = app.appName,
                        packageName = app.packageName,
                        icon = app.appIcon,
                        checked = app.packageName in selectedApps,
                        onCheckedChange = { onToggleApp(app.packageName) }
                    )
                }
            }
        }
    }
}
