package com.v2ray.ang.ui.qsn.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CIDR parsing and candidate planning.
 *
 * The limits are the point: a typo such as 104.16.0.0/0 must be rejected before
 * it can turn into a multi-million address scan.
 */
class ScanRangeParserTest {

    private fun valid(input: String): ScanRange =
        (ScanRangeParser.parse(input) as RangeParseResult.Valid).range

    private fun invalid(input: String): RangeError =
        (ScanRangeParser.parse(input) as RangeParseResult.Invalid).reason

    @Test
    fun `a valid range is normalised to its network address`() {
        assertEquals("104.16.5.0/24", valid("104.16.5.9/24").cidr)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals("104.16.0.0/16", valid("  104.16.0.0/16  ").cidr)
    }

    @Test
    fun `an empty range is rejected as empty`() {
        assertEquals(RangeError.EMPTY, invalid("   "))
    }

    @Test
    fun `a range without a prefix is rejected`() {
        assertEquals(RangeError.BAD_FORMAT, invalid("104.16.0.0"))
    }

    @Test
    fun `a range with two slashes is rejected`() {
        assertEquals(RangeError.BAD_FORMAT, invalid("104.16.0.0/24/8"))
    }

    @Test
    fun `a non numeric address is rejected`() {
        assertEquals(RangeError.BAD_IP, invalid("edge.example.com/24"))
    }

    @Test
    fun `an address with an octet above 255 is rejected`() {
        assertEquals(RangeError.BAD_IP, invalid("104.16.0.999/24"))
    }

    @Test
    fun `a prefix above 32 is rejected`() {
        assertEquals(RangeError.BAD_PREFIX, invalid("104.16.0.0/33"))
    }

    @Test
    fun `a non numeric prefix is rejected`() {
        assertEquals(RangeError.BAD_PREFIX, invalid("104.16.0.0/ab"))
    }

    @Test
    fun `a prefix below the minimum is rejected as too large`() {
        assertEquals(RangeError.TOO_LARGE, invalid("104.16.0.0/8"))
        assertEquals(RangeError.TOO_LARGE, invalid("0.0.0.0/0"))
    }

    @Test
    fun `the minimum prefix is accepted`() {
        assertEquals("104.16.0.0/16", valid("104.16.0.0/16").cidr)
    }

    @Test
    fun `a single host prefix is accepted`() {
        assertEquals("104.16.0.1/32", valid("104.16.0.1/32").cidr)
    }

    @Test
    fun `host count matches the prefix`() {
        assertEquals(256L, ScanRangeParser.hostCount(24))
        assertEquals(65_536L, ScanRangeParser.hostCount(16))
        assertEquals(1L, ScanRangeParser.hostCount(32))
    }

    @Test
    fun `contains accepts a nested range`() {
        assertTrue(
            ScanRangeParser.contains(
                ScanRange("104.16.0.0/16"),
                ScanRange("104.16.5.0/24"),
            )
        )
    }

    @Test
    fun `contains rejects a disjoint range`() {
        assertFalse(
            ScanRangeParser.contains(
                ScanRange("104.16.0.0/24"),
                ScanRange("104.17.0.0/24"),
            )
        )
    }

    @Test
    fun `contains rejects a wider range`() {
        assertFalse(
            ScanRangeParser.contains(
                ScanRange("104.16.5.0/24"),
                ScanRange("104.16.0.0/16"),
            )
        )
    }
}

class ScanRangePlannerTest {

    @Test
    fun `dedupe drops a range covered by another`() {
        val kept = ScanRangePlanner.dedupe(
            listOf(
                ScanRange("104.16.0.0/16"),
                ScanRange("104.16.5.0/24"),
            )
        )
        assertEquals(1, kept.size)
        assertEquals("104.16.0.0/16", kept[0].cidr)
    }

    @Test
    fun `dedupe keeps disjoint ranges`() {
        val kept = ScanRangePlanner.dedupe(
            listOf(
                ScanRange("104.16.0.0/24"),
                ScanRange("104.17.0.0/24"),
            )
        )
        assertEquals(2, kept.size)
    }

    @Test
    fun `dedupe ignores disabled ranges`() {
        val kept = ScanRangePlanner.dedupe(
            listOf(
                ScanRange("104.16.0.0/24", enabled = false),
                ScanRange("104.17.0.0/24", enabled = true),
            )
        )
        assertEquals(1, kept.size)
        assertEquals("104.17.0.0/24", kept[0].cidr)
    }

    @Test
    fun `plan is bounded by the hard candidate limit`() {
        val ranges = (0 until 40).map { ScanRange("104." + (16 + it) + ".0.0/24") }
        val plan = ScanRangePlanner.plan(
            ranges = ranges,
            perRange = 4,
            maxCandidates = ScanRangeParser.MAX_CANDIDATES,
            random = Random(1),
        )
        assertTrue(plan.size <= ScanRangeParser.MAX_CANDIDATES)
        assertEquals(plan.size, plan.distinct().size)
    }

    @Test
    fun `plan stays inside the configured range`() {
        val base = IpAddress.parse("104.16.0.0")!!
        val plan = ScanRangePlanner.plan(
            ranges = listOf(ScanRange("104.16.0.0/24")),
            perRange = 8,
            random = Random(7),
        )
        assertTrue(plan.isNotEmpty())
        plan.forEach { ip ->
            val value = IpAddress.parse(ip)!!
            assertTrue("outside range: " + ip, value > base && value < base + 255L)
        }
    }

    @Test
    fun `plan is deterministic for the same seed`() {
        val ranges = listOf(ScanRange("104.16.0.0/24"), ScanRange("104.17.0.0/24"))
        val first = ScanRangePlanner.plan(ranges, perRange = 4, random = Random(42))
        val second = ScanRangePlanner.plan(ranges, perRange = 4, random = Random(42))
        assertEquals(first, second)
    }

    @Test
    fun `plan returns nothing when every range is disabled`() {
        val plan = ScanRangePlanner.plan(
            listOf(ScanRange("104.16.0.0/24", enabled = false)),
            random = Random(1),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `plan returns nothing for a zero budget`() {
        assertTrue(ScanRangePlanner.plan(listOf(ScanRange("104.16.0.0/24")), perRange = 0).isEmpty())
        assertTrue(
            ScanRangePlanner.plan(
                listOf(ScanRange("104.16.0.0/24")),
                maxCandidates = 0,
            ).isEmpty()
        )
    }

    @Test
    fun `fingerprint is stable for the same range set`() {
        val ranges = listOf(ScanRange("104.17.0.0/24"), ScanRange("104.16.0.0/24"))
        assertEquals(ScanRangePlanner.fingerprint(ranges), ScanRangePlanner.fingerprint(ranges))
    }

    @Test
    fun `fingerprint ignores order`() {
        assertEquals(
            ScanRangePlanner.fingerprint(
                listOf(ScanRange("104.16.0.0/24"), ScanRange("104.17.0.0/24"))
            ),
            ScanRangePlanner.fingerprint(
                listOf(ScanRange("104.17.0.0/24"), ScanRange("104.16.0.0/24"))
            ),
        )
    }

    @Test
    fun `fingerprint changes when a range is added`() {
        assertNotEquals(
            ScanRangePlanner.fingerprint(listOf(ScanRange("104.16.0.0/24"))),
            ScanRangePlanner.fingerprint(
                listOf(ScanRange("104.16.0.0/24"), ScanRange("104.17.0.0/24"))
            ),
        )
    }

    @Test
    fun `fingerprint falls back to builtin when nothing is enabled`() {
        assertEquals("builtin", ScanRangePlanner.fingerprint(emptyList()))
        assertEquals(
            "builtin",
            ScanRangePlanner.fingerprint(listOf(ScanRange("104.16.0.0/24", enabled = false))),
        )
    }
}
