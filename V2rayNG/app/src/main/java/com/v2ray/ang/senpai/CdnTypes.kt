package com.v2ray.ang.senpai

/**
 * CDN provider backing a proxy configuration's origin domain.
 */
enum class CdnType {
    CLOUDFLARE,
    CLOUDFRONT,
    UNKNOWN
}

/**
 * Origin of a CIDR entry stored in [ProviderRangeStore].
 */
enum class RangeOrigin {
    SEED,
    FETCHED,
    DISCOVERED,
    MANUAL
}

/**
 * Single CIDR range entry belonging to a specific [CdnType] pool.
 */
data class ProviderRangeEntry(
    val cidr: String,
    val provider: CdnType,
    val origin: RangeOrigin,
    val enabled: Boolean = true,
)

/**
 * Result of classifying a configuration domain via physical DNS resolution and ICMP ping.
 */
data class CdnDetectionResult(
    val domain: String,
    val resolvedIp: String?,
    val pingRttMs: Long?,
    val cdnType: CdnType,
    val reason: String,
)
