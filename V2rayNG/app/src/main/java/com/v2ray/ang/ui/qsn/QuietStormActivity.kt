package com.v2ray.ang.ui.qsn

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainRepository
import com.v2ray.ang.ui.perappproxy.PerAppProxyViewModel

/**
 * Launcher activity of Quiet Storm NG.
 *
 * Two destinations only: Connection and Apps. The full PattNG UI (MainActivity) still exists and
 * keeps every internal capability, but it is no longer the launcher entry point.
 *
 * State lives in [QsViewModel] / [PerAppProxyViewModel], so a rotation or a recreate does not
 * lose the in-flight connection state, and the real daemon state is re-probed on every cold
 * start instead of being assumed.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appsViewModel.loadApps(this)
        // The foreground service notification needs POST_NOTIFICATIONS on API 33+.
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onResume() {
        super.onResume()
        applyPendingServiceRestart()
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
                vpnPermissionLauncher.launch(intent)
                return
            }
        }
        qsViewModel.connect()
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

        Scaffold(
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
            Box(modifier = Modifier.padding(padding)) {
                if (tab == 0) {
                    QsConnectScreen(
                        state = state,
                        onUrlChange = { qsViewModel.onSubscriptionInputChange(it) },
                        onSave = { qsViewModel.saveSubscription(state.subscriptionUrl) },
                        onUpdate = { qsViewModel.refreshSubscription() },
                        onConnect = { requestConnect() },
                        onDisconnect = { qsViewModel.disconnect() },
                        onCancel = { qsViewModel.cancel() },
                        onDismissMessage = { qsViewModel.dismissMessage() },
                    )
                } else {
                    QsAppsScreen(
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
