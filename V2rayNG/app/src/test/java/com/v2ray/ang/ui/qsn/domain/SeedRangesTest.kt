package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedRangesTest {

    @Test
    fun seedListContainsExactly46UniqueNormalizedIpv4Cidrs() {
        assertEquals(46, SeedRanges.CLOUDFLARE_SEED_CIDRS.size)
        val normalized = SeedRanges.CLOUDFLARE_SEED_CIDRS.map { raw ->
            val norm = ProviderRangeBucket.normalizeCidrOrNull(raw)
            assertNotNull("Invalid seed CIDR: $raw", norm)
            norm!!
        }
        assertEquals(46, normalized.distinct().size)
        assertTrue("Expected 5.10.214.0/23", "5.10.214.0/23" in normalized)
        assertTrue("Expected 45.135.235.0/24", "45.135.235.0/24" in normalized)
    }

    @Test
    fun ensureSeedsPopulatesAll46WithoutOverwritingDiscoveredOrManual() {
        val empty = ProviderRangeBucket(provider = EdgeProvider.CLOUDFLARE)
        val seeded = SeedRanges.ensureCloudflareSeeds(empty)
        assertEquals(46, seeded.seedCidrs.size)
        assertEquals(46, seeded.allCidrs.size)

        val discovered = (1..120).map { i ->
            QualifiedRange(
                cidr = "104.${i % 256}.0.0/24",
                uploadKBps = 400L + i,
                latencyMs = 80L + i,
                validatedAt = 1000L + i,
            )
        }
        val merged = seeded
            .copy(
                manualCidrs = listOf(
                    ManagedRange("172.64.10.0/24", EdgeProvider.CLOUDFLARE, RangeOrigin.MANUAL),
                ),
            )
            .mergeDiscovered(discovered)

        // Zero artificial 10 or 30 truncation cap
        assertEquals(46 + 120 + 1, merged.allCidrs.size)
        assertTrue(merged.allCidrs.any { it.cidr == "5.10.214.0/23" && it.origin == RangeOrigin.SEED })
        assertTrue(merged.allCidrs.any { it.cidr == "172.64.10.0/24" && it.origin == RangeOrigin.MANUAL })
        assertTrue(merged.allCidrs.any { it.cidr == "104.120.0.0/24" && it.origin == RangeOrigin.DISCOVERED })
    }

    @Test
    fun removedSeedsAreNotReaddedUntilRestored() {
        val initial = SeedRanges.ensureCloudflareSeeds(
            ProviderRangeBucket(provider = EdgeProvider.CLOUDFLARE),
        )
        val deleted = initial.copy(
            seedCidrs = initial.seedCidrs.filterNot { it.cidr == "8.6.112.0/24" },
            removedSeedCidrs = setOf("8.6.112.0/24"),
        )
        val reensured = SeedRanges.ensureCloudflareSeeds(deleted)
        assertEquals(45, reensured.seedCidrs.size)
        assertFalse(reensured.allCidrs.any { it.cidr == "8.6.112.0/24" })

        val restored = SeedRanges.ensureCloudflareSeeds(
            reensured.copy(removedSeedCidrs = emptySet()),
        )
        assertEquals(46, restored.seedCidrs.size)
        assertTrue(restored.allCidrs.any { it.cidr == "8.6.112.0/24" })
    }

    @Test
    fun rangeSetAllowsAtLeast4096EntriesWithoutCap() {
        val large = (0 until 1500).map { idx ->
            val a = 10 + (idx / 250)
            val b = idx % 250
            QualifiedRange(cidr = "$a.$b.0.0/24", uploadKBps = idx.toLong())
        }
        val capped = RangeSet.sanitize(large)
        assertEquals(1500, capped.size)
    }
}
