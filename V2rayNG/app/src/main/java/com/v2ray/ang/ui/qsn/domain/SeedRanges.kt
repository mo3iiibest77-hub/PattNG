package com.v2ray.ang.ui.qsn.domain

/**
 * Official 46 Cloudflare Seed CIDRs bundled permanently for immediate first-run availability.
 *
 * Stored in the structured [ProviderRangeBucket.seedCidrs] tier and never overwritten
 * or erased by discovery runs. Explicit user removals are tracked via
 * [ProviderRangeBucket.removedSeedCidrs] so deleted entries stay removed until reset.
 */
object SeedRanges {

    val CLOUDFLARE_SEED_CIDRS: List<String> = listOf(
        "5.10.214.0/23",
        "5.10.244.0/22",
        "5.175.141.0/24",
        "5.182.84.0/23",
        "5.226.179.0/24",
        "5.226.181.0/24",
        "5.226.183.0/24",
        "8.6.112.0/24",
        "8.6.144.0/23",
        "8.9.231.0/24",
        "8.10.148.0/24",
        "8.14.201.0/24",
        "8.14.202.0/24",
        "8.14.204.0/24",
        "8.14.205.0/24",
        "8.19.124.0/22",
        "8.23.164.0/24",
        "8.23.224.0/24",
        "8.24.75.0/24",
        "8.24.106.0/23",
        "8.25.82.0/24",
        "8.26.182.0/24",
        "8.29.230.0/24",
        "8.30.213.0/24",
        "8.34.122.0/24",
        "8.34.212.0/24",
        "8.35.211.0/24",
        "8.36.74.0/24",
        "8.37.211.0/24",
        "8.38.68.0/24",
        "8.38.80.0/24",
        "8.38.116.0/24",
        "8.39.208.0/24",
        "8.39.212.0/24",
        "8.41.158.0/24",
        "8.44.170.0/23",
        "8.45.108.0/23",
        "8.47.69.0/24",
        "8.47.129.0/24",
        "8.210.180.0/23",
        "23.105.118.0/24",
        "23.109.104.0/24",
        "23.227.38.0/24",
        "23.227.60.0/24",
        "45.131.6.0/23",
        "45.135.235.0/24",
    )

    /** Builds the canonical normalized seed entries for Cloudflare. */
    fun initialCloudflareSeeds(): List<ManagedRange> =
        CLOUDFLARE_SEED_CIDRS.mapNotNull { raw ->
            val norm = ProviderRangeBucket.normalizeCidrOrNull(raw) ?: return@mapNotNull null
            ManagedRange(
                cidr = norm,
                provider = EdgeProvider.CLOUDFLARE,
                origin = RangeOrigin.SEED,
                enabled = true,
            )
        }.distinctBy { it.cidr }

    /**
     * Ensures [bucket] retains every seed CIDR not explicitly deleted by the user,
     * preserving existing toggle/metadata flags as well as discovered & manual entries.
     */
    fun ensureCloudflareSeeds(bucket: ProviderRangeBucket): ProviderRangeBucket {
        if (bucket.provider != EdgeProvider.CLOUDFLARE) return bucket
        val byCidr = LinkedHashMap<String, ManagedRange>()
        bucket.seedCidrs.forEach { existing ->
            val norm = ProviderRangeBucket.normalizeCidrOrNull(existing.cidr) ?: return@forEach
            if (norm !in bucket.removedSeedCidrs) {
                byCidr[norm] = existing.copy(cidr = norm, origin = RangeOrigin.SEED)
            }
        }
        initialCloudflareSeeds().forEach { seed ->
            if (seed.cidr !in bucket.removedSeedCidrs && !byCidr.containsKey(seed.cidr)) {
                byCidr[seed.cidr] = seed
            }
        }
        return bucket.copy(seedCidrs = byCidr.values.toList())
    }
}
