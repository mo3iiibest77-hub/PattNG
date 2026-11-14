package com.v2ray.ang.ui.qsn3d

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.senpai.CdnType
import com.v2ray.ang.senpai.SmartConnectCoordinator
import com.v2ray.ang.ui.CfScanActivity
import com.v2ray.ang.ui.ScannerActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyViewModel
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            lifecycleScope.launch(Dispatchers.IO) {
                AngConfigManager.importBatchConfig(raw, "", false)
                viewModel.loadConfigsAndSubs()
            }
        }
    }

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.loadConfigsAndSubs()
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

    private fun startSmartConnect(explicitGuid: String? = null) {
        val state = viewModel.uiState.value
        SmartConnectCoordinator.runPrepareAndScan(
            context = this,
            rawActivationInput = state.activationInput,
            explicitGuid = explicitGuid ?: state.selectedGuid,
            forcedCdn = state.forcedCdn,
            onReadyToStartVpn = { guid, _ ->
                launchVpnWithPermission(guid)
            }
        )
    }

    private fun openConfigEditor(row: ConfigUiRow) {
        val profile = MmkvManager.decodeServerConfig(row.guid) ?: return
        val cls = when (profile.configType) {
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerCustomConfigActivity::class.java
        }
        editorLauncher.launch(Intent(this, cls).apply {
            putExtra("guid", row.guid)
            putExtra("isRunning", viewModel.uiState.value.coreRunning)
        })
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
                Cosmic3dBackdrop()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    containerColor = Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbarHost) },
                    topBar = {
                        Qs3dTopBar(
                            coreRunning = state.coreRunning,
                            currentLang = state.currentLanguageCode,
                            onToggleLang = {
                                val next = if (state.currentLanguageCode == "fa") "en" else "fa"
                                viewModel.setLanguage(next)
                            },
                            onOpenClassic = {
                                startActivity(Intent(this@QuietStormActivity, MainActivity::class.java))
                            }
                        )
                    },
                    bottomBar = {
                        Qs3dBottomNav(
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
                        when (state.activeTab) {
                            Qs3dTab.Connect -> Qs3dConnectScreen(
                                state = state,
                                onSmartConnectOrDisconnect = {
                                    if (state.coreRunning) {
                                        LauncherManager.stopService(this@QuietStormActivity)
                                    } else {
                                        startSmartConnect()
                                    }
                                },
                                onDirectConnect = {
                                    launchVpnWithPermission(state.selectedGuid)
                                },
                                onCancelFlow = { SmartConnectCoordinator.cancelActiveFlow() },
                                onActivationInputChange = viewModel::setActivationInput,
                                onRedeemActivation = viewModel::redeemActivationInput,
                                onRefreshSubs = viewModel::refreshAllSubscriptions,
                                onDetectCdnPing = viewModel::detectCdnForSelected,
                                onForcedCdnChange = viewModel::setForcedCdn,
                                onTestConnectedPing = viewModel::testConnectedLatency,
                                onOpenConfigsTab = { viewModel.selectTab(Qs3dTab.Configs) },
                            )

                            Qs3dTab.Configs -> Qs3dConfigsScreen(
                                state = state,
                                filteredConfigs = viewModel.filteredConfigs(),
                                onSelectConfig = viewModel::selectConfig,
                                onSmartConnectConfig = { guid ->
                                    viewModel.selectConfig(guid)
                                    viewModel.selectTab(Qs3dTab.Connect)
                                    startSmartConnect(guid)
                                },
                                onSearchChange = viewModel::setConfigSearch,
                                onToggleFilter = viewModel::toggleFilter,
                                onImportClipboard = viewModel::importClipboardConfigs,
                                onLaunchQrScanner = {
                                    qrScannerLauncher.launch(Intent(this@QuietStormActivity, ScannerActivity::class.java))
                                },
                                onLaunchAddManualConfig = {
                                    editorLauncher.launch(Intent(this@QuietStormActivity, ServerVlessActivity::class.java))
                                },
                                onEditConfig = ::openConfigEditor,
                                onCopyConfig = viewModel::copyConfigToClipboard,
                                onGetQrBitmap = viewModel::shareQrBitmap,
                                onDeleteConfig = viewModel::deleteSingleConfig,
                                onToggleBulkSelect = viewModel::toggleBulkSelect,
                                onClearBulkSelection = viewModel::clearBulkSelection,
                                onDeleteBulkSelected = viewModel::deleteBulkSelected,
                                onTestAllDelays = viewModel::testAllDelays,
                                onSortByDelay = viewModel::sortConfigsByDelay,
                                onRemoveInvalid = viewModel::removeInvalidConfigs,
                                onAddSubscription = viewModel::addOrUpdateSubscription,
                                onToggleSubscription = viewModel::toggleSubscriptionEnabled,
                                onRenameSubscription = viewModel::renameSubscription,
                                onDeleteSubscription = viewModel::deleteSubscription,
                                onRefreshAllSubs = viewModel::refreshAllSubscriptions,
                            )

                            Qs3dTab.Ranges -> Qs3dRangesScreen(
                                state = state,
                                onSelectProvider = viewModel::selectRangeProvider,
                                onSearchChange = viewModel::setRangeSearchQuery,
                                onAddManualCidrs = { raw -> viewModel.addManualCidrs(raw) },
                                onToggleEntry = viewModel::toggleCidrEnabled,
                                onRemoveEntry = viewModel::removeCidr,
                                onResetCfSeeds = viewModel::resetCloudflareSeeds,
                                onClearDiscovered = viewModel::clearDiscoveredRanges,
                                onCopyAllVisible = viewModel::copyVisibleRangesToClipboard,
                                onSyncAwsCloudFront = viewModel::syncCloudFrontOfficialRanges,
                                onStartDiscovery = viewModel::startProviderDiscovery,
                                onStartSequentialDiscovery = viewModel::runSequentialDiscoveryNow,
                                onStopDiscovery = viewModel::stopManualDiscovery,
                            )

                            Qs3dTab.Apps -> Qs3dSettingsScreen(
                                state = state,
                                apps = apps,
                                appsLoading = appsLoading,
                                selectedApps = selectedApps,
                                perAppEnabled = perAppEnabled,
                                perAppBypass = perAppBypass,
                                onPerAppEnabledChange = perAppViewModel::setPerAppProxyEnabled,
                                onPerAppBypassChange = perAppViewModel::setBypassAppsEnabled,
                                onToggleApp = perAppViewModel::toggle,
                                onSearchApps = perAppViewModel::filterApps,
                                onAutoDiscoveryChange = viewModel::setAutoPostConnectDiscovery,
                                onAuto2hOptimizeChange = viewModel::setAuto2hOptimize,
                                onSaveBackendUrl = viewModel::saveBackendBaseUrl,
                                onLanguageSelected = viewModel::setLanguage,
                                onOpenClassicMode = {
                                    startActivity(Intent(this@QuietStormActivity, MainActivity::class.java))
                                },
                                onOpenCoreSettings = {
                                    startActivity(Intent(this@QuietStormActivity, SettingsActivity::class.java))
                                },
                                onOpenRouting = {
                                    startActivity(Intent(this@QuietStormActivity, RoutingSettingActivity::class.java))
                                },
                                onOpenLegacyCfScan = {
                                    startActivity(Intent(this@QuietStormActivity, CfScanActivity::class.java))
                                },
                                onOpenLogcat = {
                                    startActivity(Intent(this@QuietStormActivity, LogcatActivity::class.java))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Qs3dTopBar(
    coreRunning: Boolean,
    currentLang: String,
    onToggleLang: () -> Unit,
    onOpenClassic: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "QuietStorm NG",
                color = QsColors.TextPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
            Text(
                text = if (coreRunning) stringResource(R.string.qsn3d_status_protected)
                else stringResource(R.string.qsn3d_status_standby),
                color = if (coreRunning) QsColors.ConnectedEmerald else QsColors.ElectricCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(QsColors.GlassSurface)
                    .border(1.dp, QsColors.GlassBorderTop, RoundedCornerShape(50))
                    .clickable(onClick = onToggleLang)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (currentLang == "fa") "EN" else "FA",
                    color = QsColors.ElectricCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(QsColors.GlassSurface)
                    .border(1.dp, QsColors.GlassBorderBottom, RoundedCornerShape(50))
                    .clickable(onClick = onOpenClassic)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.qsn3d_classic_short),
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun Qs3dBottomNav(
    activeTab: Qs3dTab,
    onSelectTab: (Qs3dTab) -> Unit,
) {
    NavigationBar(
        containerColor = QsColors.MidnightNavy.copy(alpha = 0.92f),
        contentColor = QsColors.TextPrimary
    ) {
        val items = listOf(
            Triple(Qs3dTab.Connect, R.string.qsn3d_tab_connect, R.drawable.ic_play_24dp),
            Triple(Qs3dTab.Configs, R.string.qsn3d_tab_configs, R.drawable.ic_subscriptions_24dp),
            Triple(Qs3dTab.Ranges, R.string.qsn3d_tab_ranges, R.drawable.ic_qu_scan_24dp),
            Triple(Qs3dTab.Apps, R.string.qsn3d_tab_apps, R.drawable.ic_per_apps_24dp),
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
                        fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = QsColors.ElectricCyan,
                    selectedTextColor = QsColors.ElectricCyan,
                    unselectedIconColor = QsColors.TextSecondary,
                    unselectedTextColor = QsColors.TextSecondary,
                    indicatorColor = QsColors.ElectricCyan.copy(alpha = 0.16f)
                )
            )
        }
    }
}
