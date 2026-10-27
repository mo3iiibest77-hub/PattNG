package com.v2ray.ang.ui.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

object ProfileEditorResult {

    const val EXTRA_ACTION =
        "com.v2ray.ang.extra.PROFILE_EDITOR_ACTION"

    const val EXTRA_GUID =
        "com.v2ray.ang.extra.PROFILE_EDITOR_GUID"

    const val EXTRA_RESTART_SERVICE =
        "com.v2ray.ang.extra.PROFILE_EDITOR_RESTART_SERVICE"

    const val ACTION_SAVED = "saved"
    const val ACTION_DELETED = "deleted"

    fun Activity.finishSaved(
        guid: String,
        restartService: Boolean
    ) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_ACTION, ACTION_SAVED)
                putExtra(EXTRA_GUID, guid)
                putExtra(EXTRA_RESTART_SERVICE, restartService)
            }
        )
        finish()
    }

    /**
     * Builds the intent that opens the full editor for [profile].
     *
     * Shared by every entry point so the protocol-to-screen mapping exists in
     * exactly one place. The editor reports back through [EXTRA_ACTION].
     */
    fun editIntent(
        context: Context,
        guid: String,
        profile: ProfileItem,
        isRunning: Boolean,
        subscriptionId: String,
    ): Intent {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        return Intent(context, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", subscriptionId)
        }
    }

    fun Activity.finishDeleted(guid: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_ACTION, ACTION_DELETED)
                putExtra(EXTRA_GUID, guid)
                putExtra(EXTRA_RESTART_SERVICE, false)
            }
        )
        finish()
    }
}