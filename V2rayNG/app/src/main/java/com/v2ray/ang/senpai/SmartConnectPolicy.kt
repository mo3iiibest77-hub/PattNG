package com.v2ray.ang.senpai

/**
 * Pure policy rules governing 2-hour background optimization, safe IP replacement/rollback,
 * and provider-conditional one-time Discovery scheduling.
 */
object SmartConnectPolicy {

    const val OPTIMIZE_INTERVAL_HOURS = 2L
    const val MIN_VALID_UPLOAD_KBPS = 50L
    const val IMPROVEMENT_RATIO = 1.15

    /**
     * Returns true when [candidate] should replace [currentUploadKBps] on periodic optimization.
     */
    fun shouldSwitchEdgeIp(
        currentBestIp: String?,
        currentUploadKBps: Long,
        candidate: CandidateResult?,
    ): Boolean {
        if (candidate == null || !candidate.isSuccess) return false
        if (candidate.uploadKBps < MIN_VALID_UPLOAD_KBPS) return false
        if (currentBestIp.isNullOrBlank() || currentUploadKBps <= 0L) return true
        if (candidate.ip == currentBestIp) return false
        return candidate.uploadKBps >= (currentUploadKBps * IMPROVEMENT_RATIO).toLong()
    }

    /**
     * Determines which providers should be included in one-time post-connect Discovery based
     * strictly on the CDN providers actually present in the user's subscription/profile set.
     * CloudFront Discovery NEVER runs unless a CloudFront profile actually exists.
     */
    fun determineRequiredDiscoveryProviders(
        detectedProvidersInSubscription: Collection<CdnType>,
        activeProvider: CdnType,
    ): List<CdnType> {
        val combined = (detectedProvidersInSubscription + activeProvider)
            .filter { it == CdnType.CLOUDFLARE || it == CdnType.CLOUDFRONT }
            .toSet()

        if (combined.isEmpty()) {
            return listOf(CdnType.CLOUDFLARE)
        }
        val ordered = mutableListOf<CdnType>()
        if (CdnType.CLOUDFLARE in combined) ordered.add(CdnType.CLOUDFLARE)
        if (CdnType.CLOUDFRONT in combined) ordered.add(CdnType.CLOUDFRONT)
        return ordered
    }
}
