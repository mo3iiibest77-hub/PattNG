package com.v2ray.ang.senpai

/**
 * Pure policy rules governing 2-hour background optimization and safe IP replacement/rollback.
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
     * Orders background Discovery sequentially: CloudFront Discovery first, then Cloudflare Discovery.
     */
    fun postConnectDiscoveryOrder(): List<CdnType> =
        listOf(CdnType.CLOUDFRONT, CdnType.CLOUDFLARE)
}
