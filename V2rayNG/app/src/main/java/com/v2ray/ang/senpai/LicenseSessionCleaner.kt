package com.v2ray.ang.senpai

import android.content.Context
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn3d.LicenseLogoutOrchestrator
import com.v2ray.ang.ui.qsn3d.LicenseLogoutStorage

/**
 * Shared wipe helper used by both UI (Qs3dViewModel) and `:RunSoLibV2RayDaemon`
 * (CoreServiceManager) to cancel periodic optimization and erase license-bound local state.
 */
object LicenseSessionCleaner {
    fun wipeBoundLicenseData(context: Context) {
        runCatching { SmartOptimizeWorker.cancel(context.applicationContext) }
        LicenseLogoutOrchestrator.wipeBoundLicenseData(
            object : LicenseLogoutStorage {
                override fun getActiveLicenseSubId(): String = LicenseBackendClient.getActiveLicenseSubId()
                override fun getAllSubscriptionIds(): List<String> =
                    MmkvManager.decodeSubscriptions().map { it.guid }
                override fun removeSubscription(subId: String) {
                    MmkvManager.removeSubscription(subId)
                }
                override fun removeServersWithoutSubIfNeeded(boundSubIds: Set<String>) {
                    val selected = MmkvManager.getSelectServer()
                    for (guid in MmkvManager.decodeAllServerList()) {
                        val item = MmkvManager.decodeServerConfig(guid) ?: continue
                        if (item.subscriptionId in boundSubIds || boundSubIds.isEmpty()) {
                            MmkvManager.removeServer(guid)
                            if (guid == selected) {
                                MmkvManager.setSelectServer("")
                            }
                        }
                    }
                }
                override fun clearQuota(subId: String) = SubscriptionQuotaStore.clear(subId)
                override fun clearSelectedServer() = MmkvManager.setSelectServer("")
                override fun clearExplicitSelection() = SmartConnectCoordinator.markExplicitServerSelected(false)
                override fun clearActiveLicenseSession() = LicenseBackendClient.clearActiveLicenseSession()
            }
        )
    }
}
