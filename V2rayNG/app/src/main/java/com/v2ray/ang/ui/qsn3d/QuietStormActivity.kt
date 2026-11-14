package com.v2ray.ang.ui.qsn3d

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.senpai.SmartConnectCoordinator
import com.v2ray.ang.ui.ScannerActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyViewModel

class QuietStormActivity : BaseComponentActivity() {

    private val viewModel: Qs3dViewModel by viewModels { Qs3dViewModel.Factory(application) }
    private val perAppViewModel: PerAppProxyViewModel by viewModels()

    private var pendingStartGuid: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LauncherManager.startService(this, pendingStartGuid ?: MmkvManager.getSelectServer())
        }
        pendingStartGuid = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
        if (result.resultCode == RESULT_OK && raw.isNotEmpty()) {
            // QR scanner enters license activation flow strictly (raw URLs/configs are rejected)
            viewModel.redeemActivationInput(raw)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        perAppViewModel.loadApps(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchVpnWithPermission(guid: String?) {
        pendingStartGuid = guid
        if (!SettingsManager.isVpnMode()) {
            LauncherManager.startService(this, guid)
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            LauncherManager.startService(this, guid)
            pendingStartGuid = null
        } else {
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    private fun startSmartConnect(explicitGuid: String? = null, forceRescan: Boolean = false) {
        val state = viewModel.uiState.value
        SmartConnectCoordinator.runPrepareAndScan(
            context = this,
            rawActivationInput = null,
            explicitGuid = explicitGuid,
            forcedCdn = state.forcedCdn,
            forceRescan = forceRescan,
            onReadyToStartVpn = { guid, _ ->
                launchVpnWithPermission(guid)
            }
        )
    }

    @Composable
    override fun ScreenContent() {
        Qs3dTheme {
            val state by viewModel.uiState.collectAsState()
            val apps by perAppViewModel.displayedApps.collectAsState()
            val appsLoading by perAppViewModel.isLoading.collectAsState()
            val selectedApps by perAppViewModel.blacklist.collectAsState()
            val perAppEnabled by perAppViewModel.perAppProxyEnabled.collectAsState()
            val perAppBypass by perAppViewModel.bypassApps.collectAsState()

            val snackbarHost = remember { SnackbarHostState() }
            LaunchedEffect(state.toastMessage) {
                val msg = state.toastMessage ?: return@LaunchedEffect
                snackbarHost.showSnackbar(msg)
                viewModel.consumeToast()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val activeConfigRemarks = state.configs.firstOrNull { it.guid == state.selectedGuid }?.remarks.orEmpty()
                val flagIso = normalizeCountryCode(state.connectedCountry, activeConfigRemarks)
                val activeBgRes = when {
                    !state.hasActiveLicense -> R.drawable.np_hero_license
                    state.activeTab == Qs3dTab.Connect -> R.drawable.np_hero_connect_globe
                    state.activeTab == Qs3dTab.Configs -> R.drawable.np_hero_configs_nodes
                    state.activeTab == Qs3dTab.Ranges -> R.drawable.np_hero_ranges_radar
                    else -> R.drawable.np_hero_settings_core
                }
                Cosmic3dBackdrop(
                    isConnected = state.coreRunning,
                    backgroundImageRes = activeBgRes,
                    connectedCountryCode = flagIso,
                    showCountryFlag = state.hasActiveLicense &&
                        !state.showSubscriptionDetails &&
                        !state.showRangeExplorer &&
                        state.activeTab == Qs3dTab.Connect
                )

                if (!state.hasActiveLicense) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbarHost) }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            Qs3dLicenseGateScreen(
                                activationInput = state.activationInput,
                                isRedeeming = state.isRedeemingLicense,
                                onInputChange = viewModel::setActivationInput,
                                onRedeemClick = { viewModel.redeemActivationInput() },
                                onScanLicenseQrClick = {
                                    qrScannerLauncher.launch(Intent(this@QuietStormActivity, ScannerActivity::class.java))
                                }
                            )
                        }
                    }
                } else {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbarHost) },
                        topBar = {
                            NovaPulseTopBar(
                                isRunning = state.coreRunning,
                                onOpenSubscription = { viewModel.openSubscriptionDetails(true) },
                                onRefresh = { viewModel.refreshAllData(fetchRemoteQuota = true) }
                            )
                        },
                        bottomBar = {
                            NovaPulseBottomNavBar(
                                activeTab = state.activeTab,
                                onSelectTab = viewModel::selectTab
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when {
                                state.showSubscriptionDetails -> {
                                    Qs3dSubscriptionScreen(
                                        state = state,
                                        onBack = { viewModel.openSubscriptionDetails(false) },
                                        onRefreshQuota = { viewModel.refreshQuotaOnly() }
                                    )
                                }

                                state.showRangeExplorer -> {
                                    Qs3dRangesExplorerScreen(
                                        state = state,
                                        onSearchChange = viewModel::setRangeSearchQuery,
                                        onCopyAll = viewModel::copyVisibleRangesToClipboard,
                                        onBack = { viewModel.openRangeExplorer(false) }
                                    )
                                }

                                else -> when (state.activeTab) {
                                    Qs3dTab.Connect -> Qs3dConnectScreen(
                                        state = state,
                                        onConnectOrDisconnect = {
                                            if (state.coreRunning) {
                                                LauncherManager.stopService(this@QuietStormActivity)
                                            } else {
                                                startSmartConnect(forceRescan = false)
                                            }
                                        },
                                        onRescanIpAndConnect = {
                                            if (state.coreRunning) {
                                                LauncherManager.stopService(this@QuietStormActivity)
                                            }
                                            startSmartConnect(forceRescan = true)
                                        },
                                        onCancelFlow = SmartConnectCoordinator::cancelActiveFlow,
                                        onOpenSubscriptionDetails = { viewModel.openSubscriptionDetails(true) },
                                        onRefreshSubs = { viewModel.refreshQuotaOnly() },
                                        onTestConnectedPing = viewModel::testConnectedLatency,
                                        onOpenConfigsTab = { viewModel.selectTab(Qs3dTab.Configs) }
                                    )

                                    Qs3dTab.Configs -> Qs3dConfigsScreen(
                                        state = state,
                                        filteredConfigs = viewModel.filteredConfigs(),
                                        onSelectConfig = { guid ->
                                            viewModel.selectConfig(guid)
                                            if (state.coreRunning) {
                                                LauncherManager.restartService(this@QuietStormActivity)
                                            }
                                        },
                                        onSearchChange = viewModel::setConfigSearch,
                                        onToggleBulkSelect = viewModel::toggleBulkSelect,
                                        onClearBulkSelection = viewModel::clearBulkSelection,
                                        onDeleteBulkSelected = viewModel::deleteBulkSelected,
                                        onTestAllDelays = viewModel::testAllDelays,
                                        onSortByDelay = viewModel::sortConfigsByDelay
                                    )

                                    Qs3dTab.Ranges -> Qs3dRangesScreen(
                                        state = state,
                                        onSelectProvider = viewModel::selectRangeProvider,
                                        onStartDiscovery = viewModel::startProviderDiscovery,
                                        onStopDiscovery = viewModel::stopManualDiscovery,
                                        onSyncAws = viewModel::syncCloudFrontOfficialRanges,
                                        onOpenExplorer = { viewModel.openRangeExplorer(true) },
                                        onCopyAll = viewModel::copyVisibleRangesToClipboard
                                    )

                                    Qs3dTab.Apps -> Qs3dSettingsScreen(
                                        state = state,
                                        apps = apps,
                                        appsLoading = appsLoading,
                                        selectedApps = selectedApps,
                                        perAppEnabled = perAppEnabled,
                                        perAppBypass = perAppBypass,
                                        onPerAppEnabledChange = {
                                            perAppViewModel.setPerAppProxyEnabled(it)
                                            if (it && apps.isEmpty()) perAppViewModel.loadApps(this@QuietStormActivity)
                                        },
                                        onPerAppBypassChange = perAppViewModel::setBypassAppsEnabled,
                                        onToggleApp = perAppViewModel::toggle,
                                        onSearchApps = perAppViewModel::filterApps,
                                        onAutoDiscoveryChange = viewModel::setAutoPostConnectDiscovery,
                                        onAuto2hOptimizeChange = viewModel::setAuto2hOptimize,
                                        onSpeedDisplayChange = viewModel::setSpeedDisplayEnabled,
                                        onLanguageSelected = { code ->
                                            viewModel.setLanguage(code)
                                            recreate()
                                        },
                                        onRedeemNewLicense = { code -> viewModel.redeemActivationInput(code) },
                                        onScanLicenseQr = {
                                            qrScannerLauncher.launch(Intent(this@QuietStormActivity, ScannerActivity::class.java))
                                        },
                                        onOpenMySubscription = { viewModel.openSubscriptionDetails(true) },
                                        onLogoutLicense = viewModel::logoutLicense
                                    )
                                }
                            }
                        }
                    }

                    if (state.showOptimizeConsentPrompt) {
                        AlertDialog(
                            onDismissRequest = { viewModel.respondToOptimizeConsent(true) },
                            title = { Text(stringResource(R.string.novapulse_optimize_consent_title)) },
                            text = { Text(stringResource(R.string.novapulse_optimize_consent_body)) },
                            confirmButton = {
                                Button(onClick = { viewModel.respondToOptimizeConsent(true) }) {
                                    Text(stringResource(R.string.novapulse_consent_allow))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.respondToOptimizeConsent(false) }) {
                                    Text(stringResource(R.string.novapulse_consent_decline))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovaPulseTopBar(
    isRunning: Boolean,
    onOpenSubscription: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_stat_name),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            StatusDot(active = isRunning)
            Text(
                text = stringResource(R.string.app_name),
                color = QsColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBadgeButton(
                label = stringResource(R.string.novapulse_my_subscription_short),
                accent = QsColors.PlasmaViolet,
                onClick = onOpenSubscription
            )
            TopBadgeButton(
                label = stringResource(R.string.qsn3d_sync_btn),
                accent = QsColors.ElectricCyan,
                onClick = onRefresh
            )
        }
    }
}

@Composable
private fun TopBadgeButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(0.8.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NovaPulseBottomNavBar(
    activeTab: Qs3dTab,
    onSelectTab: (Qs3dTab) -> Unit,
) {
    NavigationBar(containerColor = Color(0x52060D20)) {
        val items = listOf(
            Triple(Qs3dTab.Connect, R.string.qsn3d_tab_connect, R.drawable.ic_flash_on_24dp),
            Triple(Qs3dTab.Configs, R.string.qsn3d_tab_configs, R.drawable.ic_subscriptions_24dp),
            Triple(Qs3dTab.Ranges, R.string.qsn3d_tab_ranges, R.drawable.ic_routing_24dp),
            Triple(Qs3dTab.Apps, R.string.qsn3d_tab_settings, R.drawable.ic_settings_24dp),
        )
        items.forEach { (tab, labelRes, iconRes) ->
            NavigationBarItem(
                selected = activeTab == tab,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = stringResource(labelRes),
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(labelRes),
                        fontSize = 11.sp,
                        fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = QsColors.ElectricCyan,
                    selectedTextColor = QsColors.ElectricCyan,
                    unselectedIconColor = QsColors.TextSecondary,
                    unselectedTextColor = QsColors.TextSecondary,
                    indicatorColor = QsColors.ElectricCyan.copy(alpha = 0.18f)
                )
            )
        }
    }
}
