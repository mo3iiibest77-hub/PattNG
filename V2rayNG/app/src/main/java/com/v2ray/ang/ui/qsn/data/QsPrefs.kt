package com.v2ray.ang.ui.qsn.data

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.domain.IpSelection
import com.v2ray.ang.ui.qsn.domain.ScanRange
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

/**
 * Durable storage for the Quiet Storm NG extras.
 *
 * It reuses the existing multi-process SETTING MMKV file through
 * [MmkvManager] instead of adding a second storage engine, and every key is
 * prefixed so it can never collide with a PattNG preference.
 *
 * Nothing stored here is a credential: the IP selection holds a hash plus
 * public measurements, and the ranges hold CIDRs.
 */
object QsPrefs {

    private const val TAG = "QsPrefs"

    private const val KEY_IP_SELECTION = "qsn_ip_selection_v1"
    private const val KEY_SCAN_RANGES = "qsn_scan_ranges_v1"
    private const val KEY_LAST_SCAN_AT = "qsn_last_scan_at"
    private const val KEY_PINNED_GUID = "qsn_pinned_guid"
    private const val KEY_ACTIVE_SUB = "qsn_active_sub_guid"
    private const val KEY_SUB_PANEL_OPEN = "qsn_sub_panel_open"

    // ---------- Selected Cloudflare IP ----------

    fun loadSelection(): IpSelection? {
        val json = MmkvManager.decodeSettingsString(KEY_IP_SELECTION)
        if (json.isNullOrBlank()) return null
        return try {
            JsonUtil.fromJsonSafe(json, IpSelection::class.java)
        } catch (e: Exception) {
            LogUtil.e(TAG, "loadSelection failed: " + e.javaClass.simpleName)
            null
        }
    }

    fun saveSelection(selection: IpSelection) {
        try {
            MmkvManager.encodeSettings(KEY_IP_SELECTION, JsonUtil.toJson(selection))
        } catch (e: Exception) {
            LogUtil.e(TAG, "saveSelection failed: " + e.javaClass.simpleName)
        }
    }

    fun clearSelection() {
        MmkvManager.encodeSettings(KEY_IP_SELECTION, "")
    }

    // ---------- Scan ranges ----------

    fun loadRanges(): List<ScanRange> {
        val json = MmkvManager.decodeSettingsString(KEY_SCAN_RANGES)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            JsonUtil.fromJsonSafe(json, Array<ScanRange>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            LogUtil.e(TAG, "loadRanges failed: " + e.javaClass.simpleName)
            emptyList()
        }
    }

    fun saveRanges(ranges: List<ScanRange>) {
        try {
            val json = JsonUtil.toJson(ranges.toTypedArray())
            MmkvManager.encodeSettings(KEY_SCAN_RANGES, json)
        } catch (e: Exception) {
            LogUtil.e(TAG, "saveRanges failed: " + e.javaClass.simpleName)
        }
    }

    // ---------- Explicitly selected configuration ----------

    /**
     * GUID the user picked on the Configurations screen.
     *
     * When it is set, the connect flow uses that configuration as-is instead of
     * refreshing a subscription first, which is what makes a manually imported
     * configuration usable without any subscription at all.
     */
    fun pinnedGuid(): String? =
        MmkvManager.decodeSettingsString(KEY_PINNED_GUID)?.takeIf { it.isNotBlank() }

    fun setPinnedGuid(guid: String?) {
        MmkvManager.encodeSettings(KEY_PINNED_GUID, guid.orEmpty())
    }

    // ---------- Active subscription ----------

    /**
     * Subscription the user chose in the panel. The connect flow and the panel
     * both read this, so they can never disagree about which one is active.
     */
    fun activeSubscriptionGuid(): String? =
        MmkvManager.decodeSettingsString(KEY_ACTIVE_SUB)?.takeIf { it.isNotBlank() }

    fun setActiveSubscriptionGuid(guid: String?) {
        MmkvManager.encodeSettings(KEY_ACTIVE_SUB, guid.orEmpty())
    }

    // ---------- Connection screen: subscription panel ----------

    /**
     * Whether the subscription panel on the Connection screen is expanded.
     *
     * Persisted so a tab switch, a rotation or a cold start shows the same
     * state the user left behind instead of reopening the form every time.
     */
    fun subPanelExpanded(): Boolean = MmkvManager.decodeSettingsBool(KEY_SUB_PANEL_OPEN, true)

    fun setSubPanelExpanded(expanded: Boolean) {
        MmkvManager.encodeSettings(KEY_SUB_PANEL_OPEN, expanded)
    }

    // ---------- Diagnostics ----------

    fun lastScanAt(): Long {
        val raw = MmkvManager.decodeSettingsString(KEY_LAST_SCAN_AT)
        return raw?.toLongOrNull() ?: 0L
    }

    fun markScanned(at: Long) {
        MmkvManager.encodeSettings(KEY_LAST_SCAN_AT, at.toString())
    }
}
