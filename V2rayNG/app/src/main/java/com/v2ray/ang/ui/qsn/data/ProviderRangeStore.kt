package com.v2ray.ang.ui.qsn.data

import com.v2ray.ang.ui.qsn.domain.EdgeProvider
import com.v2ray.ang.ui.qsn.domain.ManagedRange
import com.v2ray.ang.ui.qsn.domain.ProviderRangeBucket
import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeOrigin
import com.v2ray.ang.ui.qsn.domain.SeedRanges
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

/**
 * Persistent structured range repository separated cleanly per [EdgeProvider].
 *
 * - Cloudflare starts pre-seeded with the 46 permanent seed CIDRs on first access.
 * - Discovery only merges into `discoveredCidrs`, never erasing `seedCidrs` or `manualCidrs`.
 * - CloudFront ranges are kept strictly separate under [EdgeProvider.CLOUDFRONT].
 * - Zero artificial 10 or 30 range truncation limits.
 */
object ProviderRangeStore {

    private const val TAG = "ProviderRangeStore"

    fun snapshot(provider: EdgeProvider): ProviderRangeBucket = synchronized(this) {
        loadLocked(provider)
    }

    fun mergeDiscovered(provider: EdgeProvider, found: List<QualifiedRange>): ProviderRangeBucket =
        synchronized(this) {
            val current = loadLocked(provider)
            val updated = current.mergeDiscovered(found)
            saveLocked(updated)
            updated
        }

    fun replaceFetched(provider: EdgeProvider, cidrs: List<String>): ProviderRangeBucket =
        synchronized(this) {
            val current = loadLocked(provider)
            val next = cidrs.mapNotNull { raw ->
                val norm = ProviderRangeBucket.normalizeCidrOrNull(raw) ?: return@mapNotNull null
                val wasDisabled = norm in current.disabledCidrs
                ManagedRange(
                    cidr = norm,
                    provider = provider,
                    origin = RangeOrigin.FETCHED,
                    enabled = !wasDisabled,
                )
            }.distinctBy { it.cidr }
            val updated = current.copy(fetchedCidrs = next)
            saveLocked(updated)
            updated
        }

    fun addManual(provider: EdgeProvider, cidr: String): ProviderRangeBucket =
        synchronized(this) {
            val norm = ProviderRangeBucket.normalizeCidrOrNull(cidr) ?: return loadLocked(provider)
            val current = loadLocked(provider)
            if (current.manualCidrs.any { it.cidr == norm }) return current
            val next = current.manualCidrs + ManagedRange(
                cidr = norm,
                provider = provider,
                origin = RangeOrigin.MANUAL,
                enabled = true,
            )
            val updated = current.copy(
                manualCidrs = next,
                disabledCidrs = current.disabledCidrs - norm,
            )
            saveLocked(updated)
            updated
        }

    fun toggleEnabled(provider: EdgeProvider, cidr: String): ProviderRangeBucket =
        synchronized(this) {
            val norm = ProviderRangeBucket.normalizeCidrOrNull(cidr) ?: return loadLocked(provider)
            val current = loadLocked(provider)
            val nextDisabled = if (norm in current.disabledCidrs) {
                current.disabledCidrs - norm
            } else {
                current.disabledCidrs + norm
            }
            val updated = current.copy(disabledCidrs = nextDisabled)
            saveLocked(updated)
            updated
        }

    fun removeRange(provider: EdgeProvider, cidr: String): ProviderRangeBucket =
        synchronized(this) {
            val norm = ProviderRangeBucket.normalizeCidrOrNull(cidr) ?: return loadLocked(provider)
            val current = loadLocked(provider)
            val nextManual = current.manualCidrs.filterNot { it.cidr == norm }
            val nextDiscovered = current.discoveredCidrs.filterNot { it.cidr == norm }
            val nextFetched = current.fetchedCidrs.filterNot { it.cidr == norm }
            val nextSeed = current.seedCidrs.filterNot { it.cidr == norm }
            val nextRemovedSeeds = if (
                provider == EdgeProvider.CLOUDFLARE &&
                SeedRanges.CLOUDFLARE_SEED_CIDRS.any {
                    ProviderRangeBucket.normalizeCidrOrNull(it) == norm
                }
            ) {
                current.removedSeedCidrs + norm
            } else {
                current.removedSeedCidrs
            }
            val updated = current.copy(
                seedCidrs = nextSeed,
                discoveredCidrs = nextDiscovered,
                manualCidrs = nextManual,
                fetchedCidrs = nextFetched,
                disabledCidrs = current.disabledCidrs - norm,
                removedSeedCidrs = nextRemovedSeeds,
            )
            saveLocked(updated)
            updated
        }

    fun restoreCloudflareSeeds(): ProviderRangeBucket = synchronized(this) {
        val current = loadLocked(EdgeProvider.CLOUDFLARE)
        val restored = SeedRanges.ensureCloudflareSeeds(
            current.copy(removedSeedCidrs = emptySet()),
        )
        saveLocked(restored)
        restored
    }

    private fun loadLocked(provider: EdgeProvider): ProviderRangeBucket {
        val json = QsPrefs.loadProviderBucketJson(provider)
        val parsed = if (json.isNullOrBlank()) {
            ProviderRangeBucket(provider = provider)
        } else {
            try {
                JsonUtil.fromJsonSafe(json, ProviderRangeBucket::class.java)
                    ?: ProviderRangeBucket(provider = provider)
            } catch (e: Exception) {
                LogUtil.e(TAG, "loadLocked failed for " + provider.id + ": " + e.javaClass.simpleName)
                ProviderRangeBucket(provider = provider)
            }
        }
        val normalized = if (provider == EdgeProvider.CLOUDFLARE) {
            // Also pull any qualified ranges previously stored in QsPrefs so
            // existing discoveries migrate seamlessly alongside the 46 seeds.
            val legacyQualified = QsPrefs.loadQualifiedRanges()
            val withSeeds = SeedRanges.ensureCloudflareSeeds(parsed.copy(provider = provider))
            if (legacyQualified.isNotEmpty() && withSeeds.discoveredCidrs.isEmpty()) {
                withSeeds.mergeDiscovered(legacyQualified)
            } else {
                withSeeds
            }
        } else {
            parsed.copy(provider = provider)
        }
        if (normalized != parsed || json.isNullOrBlank()) {
            saveLocked(normalized)
        }
        return normalized
    }

    private fun saveLocked(bucket: ProviderRangeBucket) {
        try {
            QsPrefs.saveProviderBucketJson(bucket.provider, JsonUtil.toJson(bucket))
        } catch (e: Exception) {
            LogUtil.e(TAG, "saveLocked failed: " + e.javaClass.simpleName)
        }
    }
}
