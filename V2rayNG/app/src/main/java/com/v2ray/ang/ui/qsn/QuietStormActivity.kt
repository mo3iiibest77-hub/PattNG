package com.v2ray.ang.ui.qsn

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.perappproxy.PerAppProxyViewModel
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.util.LogUtil

private const val TAG = "QuietStormActivity"

/**
 * Launcher activity of Quiet Storm NG.
 *
 * Four destinations: Connection, Configurations, IP Ranges and Apps. The full
 * PattNG UI (MainActivity) still exists and keeps every internal capability, but
 * it is no longer the launcher entry point.
 *
 * State lives in [QsViewModel] / [PerAppProxyViewModel], so a rotation or a
 * recreate does not lose the in-flight connection state, and the real daemon
 * state is re-probed on every cold start instead of being assumed. Switching
 * tabs never touches the tunnel: it only changes which screen is composed.
 */
class QuietStormActivity : HelperBaseComponentActivity() {

    private val qsViewModel: QsViewModel by viewModels {
        QsViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val appsViewModel: PerAppProxyViewModel by viewModels()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                qsViewModel.connect()
            } else {
                qsViewModel.onVpnPermissionDenied()
            }
        }

    /** The protocol editors report back with [ProfileEditorResult.EXTRA_ACTION]. */
    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // The editor may have renamed, retargeted or deleted the profile.
                qsViewModel.reload()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appsViewModel.loadApps(this)
        // The foreground service notification needs POST_NOTIFICATIONS on API 33+.
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onResume() {
        super.onResume()
        applyPendingServiceRestart()
        // A profile may have changed while this activity was in the background.
        qsViewModel.reload()
    }

    /**
     * Restarts a running tunnel after a setting that affects the core changed (for example the
     * per-app selection). Uses the project-wide [SettingsChangeManager] flag so the restart happens
     * once, no matter how many screens set it.
     */
    private fun applyPendingServiceRestart() {
        if (!SettingsChangeManager.consumeRestartService()) return
        if (qsViewModel.uiState.value.coreRunning) {
            LauncherManager.restartService(this)
        }
    }

    /**
     * Requests VPN consent when the current run mode needs it, then starts the flow.
     *
     * Without consent the daemon stops itself without broadcasting a failure, so the consent has
     * to be collected before the flow starts rather than letting it time out.
     */
    private fun requestConnect() {
        val needsConsent = !SettingsManager.isRootMode() && SettingsManager.isVpnMode()
        if (needsConsent) {
            val intent = try {
                VpnService.prepare(this)
            } catch (e: Exception) {
                null
            }
            if (intent != null) {
                // Launching an unresolvable consent activity throws
                // ActivityNotFoundException, which used to close the app the
                // moment Connect was tapped on such a device.
                val launched = try {
                    vpnPermissionLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    LogUtil.e(TAG, "VPN consent could not be requested: " + e.javaClass.simpleName)
                    false
                }
                if (launched) return
                qsViewModel.onVpnPermissionDenied()
                return
            }
        }
        qsViewModel.connect()
    }

    /** Opens the full editor for one configuration and refreshes on return. */
    private fun openEditor(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid)
        if (profile == null) {
            LogUtil.w(TAG, "openEditor: configuration is gone")
            qsViewModel.reload()
            return
        }
        profileEditorLauncher.launch(
            ProfileEditorResult.editIntent(
                context = this,
                guid = guid,
                profile = profile,
                isRunning = qsViewModel.uiState.value.coreRunning,
                subscriptionId = profile.subscriptionId,
            )
        )
    }

    @Composable
    override fun ScreenContent() {
        val state by qsViewModel.uiState.collectAsStateWithLifecycle()
        val apps by appsViewModel.displayedApps.collectAsStateWithLifecycle()
        val appsLoading by appsViewModel.isLoading.collectAsStateWithLifecycle()
        val selectedApps by appsViewModel.blacklist.collectAsStateWithLifecycle()
        val perAppEnabled by appsViewModel.perAppProxyEnabled.collectAsStateWithLifecycle()
        val bypassApps by appsViewModel.bypassApps.collectAsStateWithLifecycle()

        var tab by rememberSaveable { mutableStateOf(0) }

        // Steps that own the whole Connection tab: choosing a config, watching
        // the scan, confirming the IP, and the short write/start phases.
        val showScanStep = state.status == QsStatus.Connecting && (
            state.step == QsStep.ChoosingConfig ||
                // Stage 1 owns the screen too: it is the step the user waits
                // through before any candidate exists.
                state.step == QsStep.Discovering ||
                state.step == QsStep.ScanningIps ||
                state.step == QsStep.ChoosingIp ||
                state.step == QsStep.SelectingIp ||
                state.step == QsStep.ApplyingEdge
            )

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = {
                            tab = 0
                            applyPendingServiceRestart()
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_play_24dp),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.qs_tab_connection)) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = {
                            tab = 1
                            qsViewModel.reload()
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_subscriptions_24dp),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.qs_tab_configs)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_scan_24dp),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.qs_tab_ranges)) },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = {
                            tab = 3
                            appsViewModel.loadApps(this@QuietStormActivity)
                        },
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_per_apps_24dp),
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.qs_tab_apps)) },
                    )
                }
            },
        ) { padding ->
            QsBackground(modifier = Modifier.padding(padding)) {
                Box {
                    // The Discovery view is its own full screen: the run keeps
                    // measuring while it is open or closed, and this screen only
                    // reads the state the scanner publishes.
                    if (state.discoveryOpen) {
                        QsDiscoveryScreen(
                            state = state,
                            onBack = { qsViewModel.closeDiscovery() },
                            onStop = { qsViewModel.stopDiscovery() },
                            onResume = { qsViewModel.startDiscovery() },
                            onRestart = {
                                qsViewModel.startDiscovery(force = true)
                            },
                        )
                        return@Box
                    }
                    when (tab) {
                        // The scan step is a full screen of its own: the flow
                        // is parked there until the user answers or cancels.
                        0 -> if (showScanStep) {
                            QsScanScreen(
                                state = state,
                                onChooseConfig = { qsViewModel.chooseConfig(it) },
                                onSelectIp = { qsViewModel.selectPendingIp(it) },
                                onUseIp = { qsViewModel.useCandidateNow(it) },
                                onConfirmIp = { qsViewModel.confirmIp() },
                                onCancel = { qsViewModel.cancel() },
                            )
                        } else {
                            QsConnectScreen(
                                state = state,
                                onUrlChange = { qsViewModel.onSubscriptionInputChange(it) },
                                onSave = { qsViewModel.saveSubscription(state.subscriptionUrl) },
                                onUpdate = { qsViewModel.refreshSubscription() },
                                onTogglePanel = { qsViewModel.toggleSubscriptionPanel() },
                                onConnect = { requestConnect() },
                                onDisconnect = { qsViewModel.disconnect() },
                                onCancel = { qsViewModel.cancel() },
                                onDismissMessage = { qsViewModel.dismissMessage() },
                                onDismissCrash = { qsViewModel.dismissCrash() },
                                onToggleLanguage = { qsViewModel.toggleLanguage() },
                                onOpenAddConfig = { qsViewModel.openImport() },
                                onCloseAddConfig = { qsViewModel.closeImport() },
                                onAddConfig = { qsViewModel.importManualConfig(it) },
                            )
                        }

                        1 -> QsConfigScreen(
                            state = state,
                            onToggleSubscriptions = { qsViewModel.toggleSubscriptionsExpanded() },
                            onAddSubscription = { remarks, url ->
                                qsViewModel.addSubscription(remarks, url)
                            },
                            onRenameSubscription = { guid, name ->
                                qsViewModel.renameSubscription(guid, name)
                            },
                            onUpdateSubscription = { qsViewModel.updateSubscription(it) },
                            onUpdateAll = { qsViewModel.updateAllSubscriptions() },
                            onToggleSubscription = { guid, enabled ->
                                qsViewModel.setSubscriptionEnabled(guid, enabled)
                            },
                            onActivateSubscription = { qsViewModel.setActiveSubscription(it) },
                            onRequestDeleteSubscription = {
                                qsViewModel.requestDeleteSubscription(it)
                            },
                            onDeleteSubscription = { qsViewModel.deleteSubscription(it) },
                            onDismissDeleteSubscription = {
                                qsViewModel.dismissDeleteSubscription()
                            },
                            onToggleFilter = { qsViewModel.toggleFilter(it) },
                            onClearFilters = { qsViewModel.clearFilters() },
                            onToggleGroup = { qsViewModel.toggleConfigGroup(it) },
                            onTestDelay = { qsViewModel.measureRealDelay() },
                            onToggleConfigSelection = {
                                qsViewModel.toggleConfigSelection(it)
                            },
                            onSelectAllConfigs = { qsViewModel.selectAllConfigs() },
                            onDeselectAllConfigs = { qsViewModel.deselectAllConfigs() },
                            onRequestBulkDelete = { qsViewModel.requestBulkDelete() },
                            onConfirmBulkDelete = { qsViewModel.confirmBulkDelete() },
                            onDismissBulkDelete = { qsViewModel.dismissBulkDelete() },
                            onToggleLanguage = { qsViewModel.toggleLanguage() },
                            onSelectConfig = { qsViewModel.selectConfig(it) },
                            // A started connect shows its real progress on the
                            // Connection tab; a disconnect stays here.
                            onConfigAction = { guid ->
                                if (qsViewModel.configAction(guid)) tab = 0
                            },
                            onEditConfig = { openEditor(it) },
                            onCopyConfig = { qsViewModel.copyConfigToClipboard(it) },
                            onRequestDeleteConfig = { qsViewModel.requestDeleteConfig(it) },
                            onDeleteConfig = { qsViewModel.deleteConfig(it) },
                            onDismissDeleteConfig = { qsViewModel.dismissDeleteConfig() },
                            onOpenImport = { qsViewModel.openImport() },
                            onCloseImport = { qsViewModel.closeImport() },
                            onImport = { qsViewModel.importManualConfig(it) },
                        )

                        2 -> QsRangesScreen(
                            state = state,
                            onAdd = { qsViewModel.addRange(it) },
                            onRemove = { qsViewModel.removeRange(it) },
                            onToggle = { qsViewModel.toggleRange(it) },
                            onScanNow = { qsViewModel.scanNow() },
                            onRescanNext = { qsViewModel.rescanOnNextConnect() },
                            onDismissMessage = { qsViewModel.dismissMessage() },
                            onToggleLanguage = { qsViewModel.toggleLanguage() },
                            onSelectIsp = { qsViewModel.selectIsp(it) },
                            onStartDiscovery = { qsViewModel.startDiscovery(it) },
                            onResumeDiscovery = { qsViewModel.startDiscovery() },
                            onRestartDiscovery = {
                                qsViewModel.startDiscovery(it, force = true)
                            },
                            onStopDiscovery = { qsViewModel.stopDiscovery() },
                            onUseCandidate = { qsViewModel.useCandidateNow(it) },
                            onAddManual = { qsViewModel.addManualRange(it) },
                            onRemoveManual = { qsViewModel.removeManualRange(it) },
                            onToggleRange = { qsViewModel.toggleRangeSelection(it) },
                            onOpenDiscovery = { qsViewModel.openDiscovery() },
                        )

                        else -> QsAppsScreen(
                            apps = apps,
                            isLoading = appsLoading,
                            selected = selectedApps,
                            enabled = perAppEnabled,
                            bypass = bypassApps,
                            onEnabledChange = {
                                appsViewModel.setPerAppProxyEnabled(it)
                                SettingsChangeManager.makeRestartService()
                            },
                            onBypassChange = {
                                appsViewModel.setBypassAppsEnabled(it)
                                SettingsChangeManager.makeRestartService()
                            },
                            onToggle = { appsViewModel.toggle(it) },
                            onSearch = { appsViewModel.filterApps(it) },
                        )
                    }
                }
            }
        }
    }
}
