package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.util.SubscriptionQuota

/**
 * One row of the subscription panel.
 *
 * [url] is kept only so the panel can offer editing. [redactedUrl] is the only
 * form allowed in logs, reports or crash output: subscription URLs routinely
 * embed a per-user token in the path or the query string.
 */
data class SubscriptionRow(
    val guid: String,
    val remarks: String,
    val url: String,
    val enabled: Boolean,
    val configCount: Int,
    val lastUpdated: Long,
    val active: Boolean,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireAt: Long = 0,
) {
    /** What the panel shows as the title: the user's name, else the host. */
    val displayName: String
        get() = remarks.ifBlank { SubscriptionRules.hostOf(url) }

    val redactedUrl: String
        get() = SubscriptionRules.redact(url)

    /** 0f..1f fraction of the allowance already used, null when total is unknown. */
    val usageFraction: Float?
        get() = SubscriptionQuota.usageFraction(uploadBytes, downloadBytes, totalBytes)

    /** Whole-number usage percentage 0..100, null when total is unknown. */
    val usagePercent: Int?
        get() = SubscriptionQuota.usagePercent(uploadBytes, downloadBytes, totalBytes)
}

/**
 * Pure rules for the subscription panel.
 *
 * Kept free of Android and of storage so the behaviour that matters - what is
 * shown, what is rejected, what happens to a subscription created by an older
 * build - can be unit tested directly.
 */
object SubscriptionRules {

    /** Longest accepted display name. */
    const val MAX_REMARKS = 64

    /**
     * Validates a display name typed by the user.
     *
     * @return the trimmed name, or null when it is empty, too long or contains
     *   control characters (which would break the single-line row layout).
     */
    fun normalizeRemarks(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_REMARKS) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    /** Host part of a subscription URL, without credentials or path. */
    fun hostOf(url: String): String {
        val authority = authorityOf(url) ?: return ""
        return authority.substringAfterLast('@')
    }

    /**
     * Log-safe form of a subscription URL: scheme, host and a literal ellipsis.
     * No path, no query, no user-info.
     */
    fun redact(url: String): String {
        val host = hostOf(url)
        if (host.isBlank()) return "\u2014"
        val scheme = url.substringBefore("://", "").ifBlank { "https" }
        return scheme + "://" + host + "/\u2026"
    }

    private fun authorityOf(url: String): String? {
        val separator = url.indexOf("://")
        if (separator <= 0) return null
        val rest = url.substring(separator + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.ifBlank { null }
    }

    /**
     * Maps a stored subscription to a row.
     *
     * A subscription written by a build that predates the multi-subscription
     * panel has the same shape, so it simply becomes one row with
     * [SubscriptionRow.enabled] defaulting to true - no migration pass is
     * needed and nothing is rewritten on disk.
     */
    fun fromCache(cache: SubscriptionCache, configCount: Int, active: Boolean): SubscriptionRow =
        SubscriptionRow(
            guid = cache.guid,
            remarks = cache.subscription.remarks,
            url = cache.subscription.url,
            enabled = cache.subscription.enabled,
            configCount = configCount,
            lastUpdated = cache.subscription.lastUpdated,
            active = active,
            uploadBytes = cache.subscription.upload,
            downloadBytes = cache.subscription.download,
            totalBytes = cache.subscription.total,
            expireAt = cache.subscription.expire,
        )

    /** Active first, then enabled, then alphabetical - stable for the UI. */
    fun sortForDisplay(rows: List<SubscriptionRow>): List<SubscriptionRow> =
        rows.sortedWith(
            compareByDescending<SubscriptionRow> { it.active }
                .thenByDescending { it.enabled }
                .thenBy { it.displayName.lowercase() },
        )

    /** The subscription the panel highlights. */
    fun activeRow(rows: List<SubscriptionRow>): SubscriptionRow? =
        rows.firstOrNull { it.active } ?: rows.firstOrNull { it.enabled } ?: rows.firstOrNull()

    /**
     * The subscription the connect flow must refresh.
     *
     * The explicit choice wins, but only while it is enabled; otherwise the
     * first enabled subscription is used. Returns null when every subscription
     * is disabled, so the flow can report that instead of silently connecting
     * through a subscription the user switched off.
     */
    fun chooseActive(caches: List<SubscriptionCache>, activeGuid: String?): SubscriptionCache? {
        if (caches.isEmpty()) return null
        val explicit = activeGuid?.let { id ->
            caches.firstOrNull { it.guid == id && it.subscription.enabled }
        }
        if (explicit != null) return explicit
        return caches.firstOrNull { it.subscription.enabled }
    }
}
