package com.v2ray.ang.ui.qsn.data

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.qsn.domain.IpSelection
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeSet
import com.v2ray.ang.ui.qsn.domain.ScanRange
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import org.json.JSONArray
import org.json.JSONObject

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
    private const val KEY_QUALIFIED_RANGES = "qsn_qualified_ranges_v1"
    private const val KEY_RANGE_REFRESH = "qsn_range_refresh"
    private const val KEY_LAST_SCAN_AT = "qsn_last_scan_at"
    private const val KEY_PINNED_GUID = "qsn_pinned_guid"
    private const val KEY_ACTIVE_SUB = "qsn_active_sub_guid"
    private const val KEY_SUB_PANEL_OPEN = "qsn_sub_panel_open"
    private const val KEY_LAST_ISP = "qsn_last_isp"
    private const val KEY_RANGE_SELECTION = "qsn_range_selection_v1"

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

    // ---------- Last ISP used for discovery ----------

    /**
     * ISP the user last ran discovery for.
     *
     * The connection flow and the IP Ranges screen read the same value, so a
     * Stage 2 scan uses the ranges of the ISP the user actually discovered
     * instead of an unrelated default.
     */
    fun lastIsp(): String? =
        MmkvManager.decodeSettingsString(KEY_LAST_ISP)?.takeIf { it.isNotBlank() }

    fun setLastIsp(name: String) {
        MmkvManager.encodeSettings(KEY_LAST_ISP, name.trim())
    }

    // ---------- Stage 2 range selection ----------

    /**
     * Ranges the user chose to measure in Stage 2, per ISP.
     *
     * Empty means "not chosen yet", and the flow then uses every good range of
     * that ISP. A non-empty set is an explicit narrowing: a failed or unchecked
     * range can only reach Stage 2 when the user asks for it.
     */
    fun selectedRanges(isp: String): Set<String> {
        val raw = MmkvManager.decodeSettingsString(KEY_RANGE_SELECTION).orEmpty()
        if (raw.isBlank()) return emptySet()
        return try {
            val all = JSONObject(raw)
            val arr = all.optJSONArray(isp.trim()) ?: return emptySet()
            (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf(String::isNotBlank) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setSelectedRanges(isp: String, cidrs: Set<String>) {
        val raw = MmkvManager.decodeSettingsString(KEY_RANGE_SELECTION).orEmpty()
        val all = try {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
        all.put(isp.trim(), JSONArray(cidrs.sorted()))
        MmkvManager.encodeSettings(KEY_RANGE_SELECTION, all.toString())
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

    // ---------- Qualified (discovered) Cloudflare ranges ----------

    /**
     * The ranges real measurements qualified, newest measurement first.
     *
     * Kept apart from [loadSelection] on purpose: a saved edge answers "which
     * address worked", the range database answers "where to look next time".
     * Losing one must not destroy the other.
     *
     * A corrupt or older entry is dropped by [RangeSet.sanitize] instead of
     * failing the read, so a damaged file degrades to "no ranges yet" rather
     * than to a crash or a poisoned scan.
     */
    fun loadQualifiedRanges(): List<QualifiedRange> {
        val json = MmkvManager.decodeSettingsString(KEY_QUALIFIED_RANGES)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val parsed = JsonUtil.fromJsonSafe(json, Array<QualifiedRange>::class.java)?.toList()
                ?: return emptyList()
            RangeSet.sanitize(parsed)
        } catch (e: Exception) {
            LogUtil.e(TAG, "loadQualifiedRanges failed: " + e.javaClass.simpleName)
            emptyList()
        }
    }

    fun saveQualifiedRanges(ranges: List<QualifiedRange>) {
        try {
            val clean = RangeSet.sanitize(ranges)
            MmkvManager.encodeSettings(KEY_QUALIFIED_RANGES, JsonUtil.toJson(clean.toTypedArray()))
        } catch (e: Exception) {
            LogUtil.e(TAG, "saveQualifiedRanges failed: " + e.javaClass.simpleName)
        }
    }

    fun clearQualifiedRanges() {
        MmkvManager.encodeSettings(KEY_QUALIFIED_RANGES, "")
    }

    /**
     * Asks the next automatic attempt to run discovery instead of reusing the
     * saved ranges. One-shot: [consumeRangeRefresh] clears it.
     */
    fun requestRangeRefresh() {
        MmkvManager.encodeSettings(KEY_RANGE_REFRESH, "1")
    }

    fun consumeRangeRefresh(): Boolean {
        val requested = MmkvManager.decodeSettingsString(KEY_RANGE_REFRESH) == "1"
        if (requested) MmkvManager.encodeSettings(KEY_RANGE_REFRESH, "0")
        return requested
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
