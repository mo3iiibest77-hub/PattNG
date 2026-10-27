package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The IP Ranges header counts what is actually stored and enabled. */
class RangesSummaryTest {

    @Test
    fun `an empty set counts as zero`() {
        assertEquals(0, RangesSummary.total(emptyList()))
        assertEquals(0, RangesSummary.enabled(emptyList()))
    }

    @Test
    fun `disabled ranges are counted but not enabled`() {
        val ranges = listOf(
            ScanRange("104.16.0.0/24", enabled = true),
            ScanRange("104.17.0.0/24", enabled = false),
            ScanRange("104.18.0.0/24", enabled = true),
        )
        assertEquals(3, RangesSummary.total(ranges))
        assertEquals(2, RangesSummary.enabled(ranges))
    }

    @Test
    fun `a manually added range is part of the count`() {
        val ranges = listOf(ScanRange("104.16.0.0/24", enabled = true)) +
            ScanRangeParser.parse("203.0.113.0/24").let { parsed ->
                when (parsed) {
                    is RangeParseResult.Valid -> listOf(parsed.range)
                    is RangeParseResult.Invalid -> emptyList()
                }
            }
        assertEquals(2, RangesSummary.total(ranges))
    }
}
