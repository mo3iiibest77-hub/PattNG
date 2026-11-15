package com.v2ray.ang.senpai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRangeRepositoryTest {

    private class InMemoryRangeStore : RangeKeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun cloudflareSeeds_containExact46UserRangesOnFirstInit() {
        assertEquals(46, CloudflareSeedRanges.SEED_CIDRS.distinct().size)
        val repo = ProviderRangeRepository(store = InMemoryRangeStore())

        val entries = repo.getEntries(CdnType.CLOUDFLARE)
        assertEquals(46, entries.size)
        assertTrue(entries.all { it.origin == RangeOrigin.SEED && it.enabled })
        assertEquals(CloudflareSeedRanges.SEED_CIDRS.toSet(), entries.map { it.cidr }.toSet())
    }

    @Test
    fun cloudflareDiscovery_appendsWithoutOverwriting46SeedsOrManualCidrs() {
        val repo = ProviderRangeRepository(store = InMemoryRangeStore())
        assertTrue(repo.addManualCidr(CdnType.CLOUDFLARE, "172.64.10.5/24")) // normalizes to 172.64.10.0/24

        repo.appendDiscoveredCidrs(
            CdnType.CLOUDFLARE,
            listOf("172.67.1.0/24", "141.101.64.0/18", "104.16.0.0/13")
        )

        val entries = repo.getEntries(CdnType.CLOUDFLARE)
        // 46 seeds + 1 manual + 2 new discovered (104.16.0.0/13 already exists as seed)
        assertEquals(49, entries.size)

        val seedSet = CloudflareSeedRanges.SEED_CIDRS.toSet()
        val allCidrs = entries.map { it.cidr }.toSet()
        assertTrue(allCidrs.containsAll(seedSet))
        assertTrue("172.64.10.0/24" in allCidrs)
        assertTrue("172.67.1.0/24" in allCidrs)
        assertTrue("141.101.64.0/18" in allCidrs)
    }

    @Test
    fun cloudfrontPool_staysSeparateAndSupportsMoreThan60EntriesWithoutTruncation() {
        val bundled = (1..80).map { "13.32.$it.0/24" }
        val repo = ProviderRangeRepository(
            store = InMemoryRangeStore(),
            defaultCloudFrontCidrsProvider = { bundled }
        )

        repo.appendDiscoveredCidrs(CdnType.CLOUDFRONT, listOf("99.84.10.0/24"))
        val frontEntries = repo.getEntries(CdnType.CLOUDFRONT)
        val cfEntries = repo.getEntries(CdnType.CLOUDFLARE)

        assertEquals(81, frontEntries.size)
        assertEquals(46, cfEntries.size)
        assertEquals("99.84.10.0/24", repo.getScanPoolCidrs(CdnType.CLOUDFRONT).first())
        assertFalse(cfEntries.any { it.cidr == "99.84.10.0/24" })
    }

    @Test
    fun togglingDisable_excludesFromScanPoolButPreservesInList() {
        val repo = ProviderRangeRepository(store = InMemoryRangeStore())
        val firstSeed = CloudflareSeedRanges.SEED_CIDRS.first()

        repo.setCidrEnabled(CdnType.CLOUDFLARE, firstSeed, enabled = false)
        assertFalse(firstSeed in repo.getScanPoolCidrs(CdnType.CLOUDFLARE))
        assertEquals(46, repo.getEntries(CdnType.CLOUDFLARE).size)

        repo.resetSeeds(CdnType.CLOUDFLARE)
        assertTrue(firstSeed in repo.getScanPoolCidrs(CdnType.CLOUDFLARE))
    }

    @Test
    fun cloudflareCuratedMerge_preserves46SeedsAndDeduplicates60DiscoveredCidrs() {
        assertEquals(60, CloudflareSeedRanges.CURATED_DISCOVERED_CIDRS.distinct().size)
        val overlap = CloudflareSeedRanges.SEED_CIDRS.toSet().intersect(CloudflareSeedRanges.CURATED_DISCOVERED_CIDRS.toSet())
        assertTrue("Expected zero overlap between 46 seeds and 60 curated discovered CIDRs", overlap.isEmpty())

        val repo = ProviderRangeRepository(
            store = InMemoryRangeStore(),
            defaultCloudflareDiscoveredProvider = { CloudflareSeedRanges.CURATED_DISCOVERED_CIDRS },
        )
        val entries = repo.getEntries(CdnType.CLOUDFLARE)
        assertEquals(106, entries.size)
        assertEquals(106, entries.map { it.cidr }.distinct().size)
        assertEquals(46, entries.count { it.origin == RangeOrigin.SEED })
        assertEquals(60, entries.count { it.origin == RangeOrigin.DISCOVERED })
    }
}
