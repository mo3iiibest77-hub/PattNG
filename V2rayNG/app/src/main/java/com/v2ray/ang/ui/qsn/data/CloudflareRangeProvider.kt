package com.v2ray.ang.ui.qsn.data

import android.content.Context
import com.v2ray.ang.ui.qsn.domain.EdgeProvider
import com.v2ray.ang.ui.qsn.domain.ProviderRangeBucket
import com.v2ray.ang.ui.qsn.domain.SeedRanges

/**
 * Common abstraction for provider-specific range sources.
 * Kept separate per [EdgeProvider] so Cloudflare and CloudFront never mix.
 */
interface RangeProvider {
    val provider: EdgeProvider
    fun storedCidrs(): List<String>
}

/**
 * Cloudflare range provider combining the 46 bundled Seed CIDRs and the full
 * bundled `cf_ranges_v4.txt` discovery dataset.
 */
object CloudflareRangeProvider : RangeProvider {

    override val provider: EdgeProvider = EdgeProvider.CLOUDFLARE

    override fun storedCidrs(): List<String> =
        ProviderRangeStore.snapshot(EdgeProvider.CLOUDFLARE).enabledScanCidrs

    /** Loads the bundled full Cloudflare IPv4 dataset normalized and deduplicated with seeds. */
    fun loadDiscoveryDataset(context: Context): List<String> {
        val assetLines = try {
            context.assets.open("cf_ranges_v4.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) null
                    else ProviderRangeBucket.normalizeCidrOrNull(t)
                }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val bucket = ProviderRangeStore.snapshot(EdgeProvider.CLOUDFLARE)
        val combined = LinkedHashSet<String>()
        SeedRanges.CLOUDFLARE_SEED_CIDRS.mapNotNullTo(combined) {
            ProviderRangeBucket.normalizeCidrOrNull(it)
        }
        assetLines.forEach { combined.add(it) }
        bucket.manualCidrs.forEach { if (it.enabled) combined.add(it.cidr) }
        return combined.toList()
    }
}
