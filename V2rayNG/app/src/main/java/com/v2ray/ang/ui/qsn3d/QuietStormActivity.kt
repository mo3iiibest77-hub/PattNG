package com.v2ray.ang.ui.qsn3d

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.BuildConfig
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

    override fun onResume() {
        super.onResume()
        viewModel.verifyLicenseSessionNow(force = false)
        viewModel.checkForAppUpdatesIfStale()
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

            BackHandler(
                enabled = state.showAboutPromo || state.showSubscriptionDetails || state.showRangeExplorer
            ) {
                when {
                    state.showAboutPromo -> viewModel.openAboutPromo(false)
                    state.showSubscriptionDetails -> viewModel.openSubscriptionDetails(false)
                    state.showRangeExplorer -> viewModel.openRangeExplorer(false)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val activeBgRes = when {
                    state.showAboutPromo -> R.drawable.np_promo_hero
                    !state.hasActiveLicense -> R.drawable.np_hero_license
                    state.activeTab == Qs3dTab.Connect -> R.drawable.np_hero_connect_globe
                    state.activeTab == Qs3dTab.Configs -> R.drawable.np_hero_configs_nodes
                    state.activeTab == Qs3dTab.Ranges -> R.drawable.np_hero_ranges_radar
                    else -> R.drawable.np_hero_settings_core
                }
                Cosmic3dBackdrop(
                    isConnected = state.coreRunning,
                    backgroundImageRes = activeBgRes,
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
                            if (state.showAboutPromo) {
                                Qs3dAboutPromoScreen(
                                    hasActiveLicense = false,
                                    onBack = { viewModel.openAboutPromo(false) }
                                )
                            } else {
                                Qs3dLicenseGateScreen(
                                    activationInput = state.activationInput,
                                    isRedeeming = state.isRedeemingLicense,
                                    onInputChange = viewModel::setActivationInput,
                                    onRedeemClick = { viewModel.redeemActivationInput() },
                                    onScanLicenseQrClick = {
                                        qrScannerLauncher.launch(Intent(this@QuietStormActivity, ScannerActivity::class.java))
                                    },
                                    onOpenAboutPromo = { viewModel.openAboutPromo(true) }
                                )
                            }
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
                            Column {
                                NovaPulseTopBar(
                                    isRunning = state.coreRunning,
                                    onOpenSubscription = { viewModel.openSubscriptionDetails(true) },
                                    onOpenPromo = { viewModel.openAboutPromo(true) },
                                    onRefresh = { viewModel.refreshAllData(fetchRemoteQuota = true) }
                                )
                                val upd = state.availableUpdate
                                if (upd?.hasUpdate == true && !upd.latestVersion.isNullOrBlank()) {
                                    UpdateAvailableStrip(
                                        latestVersion = upd.latestVersion,
                                        onClick = { viewModel.openUpdateDialog(true) }
                                    )
                                }
                            }
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
                                state.showAboutPromo -> {
                                    Qs3dAboutPromoScreen(
                                        hasActiveLicense = true,
                                        onBack = { viewModel.openAboutPromo(false) }
                                    )
                                }

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
                                        onOpenAboutPromo = { viewModel.openAboutPromo(true) },
                                        onCheckUpdates = { viewModel.checkForAppUpdates(silent = false) },
                                        onOpenAvailableUpdate = { viewModel.openUpdateDialog(true) },
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

                val updateInfo = state.availableUpdate
                if (state.showUpdateDialog && updateInfo != null && updateInfo.hasUpdate) {
                    NovaPulseUpdateDialog(
                        state = state,
                        onDownloadOption = viewModel::downloadAndInstallAppUpdate,
                        onInstallReady = viewModel::installReadyUpdateApk,
                        onRecheck = { viewModel.checkForAppUpdates(silent = false, openDialogIfUpdate = true) },
                        onDismiss = { viewModel.openUpdateDialog(false) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableStrip(
    latestVersion: String,
    onClick: () -> Unit,
) {
    val pulseTransition = rememberInfiniteTransition(label = "update_strip_pulse")
    val glowAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "strip_glow",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        QsColors.AuroraMint.copy(alpha = 0.20f),
                        QsColors.ElectricCyan.copy(alpha = 0.14f),
                        QsColors.PlasmaViolet.copy(alpha = 0.14f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        QsColors.AuroraMint.copy(alpha = glowAlpha),
                        QsColors.ElectricCyan.copy(alpha = glowAlpha * 0.85f),
                    )
                ),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(QsColors.AuroraMint.copy(alpha = glowAlpha))
            )
            Text(
                text = stringResource(R.string.novapulse_update_available_banner_fmt, latestVersion),
                color = QsColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(QsColors.AuroraMint, QsColors.ElectricCyan)
                    )
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.update_now),
                color = QsColors.DeepVoid,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NovaPulseUpdateDialog(
    state: Qs3dUiState,
    onDownloadOption: (com.v2ray.ang.dto.ApkAssetOption) -> Unit,
    onInstallReady: () -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val result = state.availableUpdate ?: return
    val versionStr = result.latestVersion ?: ""
    val recommended = result.availableAssets.firstOrNull { it.isDeviceMatch }
        ?: result.availableAssets.firstOrNull()
    val universal = result.availableAssets.firstOrNull { it.isUniversal && it != recommended }

    val pulseTransition = rememberInfiniteTransition(label = "update_dialog_halo")
    val haloAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "halo_alpha",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF5111D3B),
                            QsColors.MidnightNavy.copy(alpha = 0.97f),
                            QsColors.DeepVoid.copy(alpha = 0.98f),
                        )
                    )
                )
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            QsColors.ElectricCyan.copy(alpha = haloAlpha),
                            QsColors.PlasmaViolet.copy(alpha = 0.55f),
                            QsColors.AuroraMint.copy(alpha = haloAlpha),
                        )
                    ),
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header: Orbital Emblem + Title + New Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        QsColors.AuroraMint.copy(alpha = 0.34f),
                                        QsColors.ElectricCyan.copy(alpha = 0.12f),
                                    )
                                )
                            )
                            .border(1.2.dp, QsColors.AuroraMint.copy(alpha = haloAlpha), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↑",
                            color = QsColors.AuroraMint,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(QsColors.AuroraMint.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.novapulse_update_badge_new),
                                color = QsColors.AuroraMint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.novapulse_update_available_title_fmt, versionStr),
                            color = QsColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }

                // Version transition pills: current -> latest
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(QsColors.GlassSurface)
                        .border(0.8.dp, QsColors.GlassBorderBottom, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(QsColors.TextMuted.copy(alpha = 0.22f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = QsColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "  ➜  ",
                        color = QsColors.ElectricCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(QsColors.AuroraMint.copy(alpha = 0.22f))
                            .border(0.8.dp, QsColors.AuroraMint.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "v$versionStr",
                            color = QsColors.AuroraMint,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }

                if (state.isCheckingUpdate && state.updateDownloadProgress == null) {
                    Text(
                        text = stringResource(R.string.novapulse_update_checking),
                        color = QsColors.ElectricCyan,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (!result.releaseNotes.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(QsColors.GlassSurfaceElevated.copy(alpha = 0.45f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_update_whats_new),
                            color = QsColors.ElectricCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 155.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = result.releaseNotes,
                                color = QsColors.TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }

                // Action / Progress Section
                when {
                    state.updateDownloadProgress != null -> {
                        val pct = state.updateDownloadProgress.coerceIn(0, 100)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(QsColors.GlassSurface)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_update_downloading_fmt, pct),
                                    color = QsColors.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "$pct%",
                                    color = QsColors.AuroraMint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(9.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.White.copy(alpha = 0.10f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((pct / 100f).coerceIn(0.03f, 1f))
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(QsColors.AuroraMint, QsColors.ElectricCyan, QsColors.PlasmaViolet)
                                            )
                                        )
                                )
                            }
                            Text(
                                text = stringResource(R.string.novapulse_update_fast_gateway_note),
                                color = QsColors.TextMuted,
                                fontSize = 10.5.sp,
                            )
                        }
                    }

                    state.readyUpdateApkPath != null -> {
                        GradientUpdateButton(
                            text = stringResource(R.string.novapulse_update_install_now_btn),
                            onClick = onInstallReady,
                        )
                    }

                    else -> {
                        recommended?.let { opt ->
                            GradientUpdateButton(
                                text = stringResource(R.string.novapulse_update_device_abi_btn_fmt, opt.abi),
                                onClick = { onDownloadOption(opt) },
                            )
                        }
                        universal?.let { opt ->
                            OutlinedButton(
                                onClick = { onDownloadOption(opt) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.45f)),
                                contentPadding = PaddingValues(vertical = 11.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.novapulse_update_universal_btn),
                                    color = QsColors.ElectricCyan,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.novapulse_update_fast_gateway_note),
                            color = QsColors.TextMuted,
                            fontSize = 10.5.sp,
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onRecheck,
                        enabled = !state.isCheckingUpdate && state.updateDownloadProgress == null,
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_update_check_btn),
                            color = QsColors.ElectricCyan,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.novapulse_update_later_btn),
                            color = QsColors.TextSecondary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GradientUpdateButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(QsColors.AuroraMint, QsColors.ElectricCyan)
                )
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = QsColors.DeepVoid,
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Text(
            text = text,
            color = QsColors.DeepVoid,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun NovaPulseTopBar(
    isRunning: Boolean,
    onOpenSubscription: () -> Unit,
    onOpenPromo: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
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
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopBadgeButton(
                label = stringResource(R.string.novapulse_promo_top_badge),
                accent = QsColors.AuroraMint,
                onClick = onOpenPromo
            )
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
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
