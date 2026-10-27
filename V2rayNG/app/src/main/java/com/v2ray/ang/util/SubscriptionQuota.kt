package com.v2ray.ang.util

import kotlin.math.roundToInt

/**
 * Parses and summarises the `Subscription-Userinfo` response header that some
 * subscription servers attach to the config body. The header lists the account's
 * traffic allowance and expiry, for example
 * `upload=213123; download=534; total=109387; expire=1666000000`.
 *
 * Every value is optional. A subscription that reports no quota at all yields
 * all zeros, which the UI renders as "no quota info" rather than a fabricated
 * percentage, and a non-numeric value is treated as unknown instead of guessed.
 *
 * Pure Kotlin on purpose: the parsing and the percentage are unit-tested
 * without a device or a network.
 */
object SubscriptionQuota {

    /** Bytes; [expire] is a Unix epoch in seconds. Zero means unknown. */
    data class Quota(
        val upload: Long = 0,
        val download: Long = 0,
        val total: Long = 0,
        val expire: Long = 0,
    )

    /** Parses a `Subscription-Userinfo` header value. */
    fun parse(header: String?): Quota {
        if (header.isNullOrBlank()) return Quota()
        val fields = HashMap<String, String>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            fields[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        fun long(key: String): Long = fields[key]?.toLongOrNull() ?: 0L
        return Quota(
            upload = long("upload"),
            download = long("download"),
            total = long("total"),
            expire = long("expire"),
        )
    }

    /**
     * Fraction of the allowance already used, in `0f..1f`, or null when the
     * total is unknown or non-positive. Traffic beyond the total is clamped to
     * 100% rather than shown as an impossible over-complete bar.
     */
    fun usageFraction(upload: Long, download: Long, total: Long): Float? {
        if (total <= 0L) return null
        val used = upload + download
        if (used <= 0L) return 0f
        return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** [usageFraction] as a whole-number percentage 0..100, null when unknown. */
    fun usagePercent(upload: Long, download: Long, total: Long): Int? =
        usageFraction(upload, download, total)?.let { (it * 100f).roundToInt().coerceIn(0, 100) }
}