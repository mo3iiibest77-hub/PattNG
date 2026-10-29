package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for the manual range list and for what Stage 2 may measure.
 *
 * These are pure decisions, so they are pinned here instead of being inferred
 * from a screenshot: a typo must be rejected, a duplicate must be reported, and
 * another ISP's range must never reach a scan.
 */
class ManualRangeTest {

    @Test
    fun aValidCidrIsNormalisedBeforeItIsStored() {
        val result = ManualRangePolicy.classify("104.16.5.9/24", emptyList())
        assertTrue(result is ManualRangeResult.Added)
        assertEquals("104.16.0.0/24", (result as ManualRangeResult.Added).cidr)
    }

    @Test
    fun aRangeAlreadyStoredIsReportedAsDuplicate() {
        val result = ManualRangePolicy.classify("104.16.0.0/24", listOf("104.16.0.0/24"))
        assertTrue(result is ManualRangeResult.Duplicate)
    }

    @Test
    fun theSameRangeWrittenDifferentlyIsStillADuplicate() {
        // 104.16.5.9/24 normalises to 104.16.0.0/24, so it must not be added twice.
        val result = ManualRangePolicy.classify("104.16.5.9/24", listOf("104.16.0.0/24"))
        assertTrue(result is ManualRangeResult.Duplicate)
    }

    @Test
    fun aDiscoveredRangeAlsoBlocksAManualDuplicate() {
        val result = ManualRangePolicy.classify("104.16.0.0/24", listOf("104.16.0.0/24"))
        assertTrue(result is ManualRangeResult.Duplicate)
    }

    @Test
    fun aMissingPrefixIsRejectedWithAFormatError() {
        val result = ManualRangePolicy.classify("104.16.0.0", emptyList())
        assertEquals(ManualRangeResult.Invalid(RangeError.BAD_FORMAT), result)
    }

    @Test
    fun aNonAddressIsRejected() {
        val result = ManualRangePolicy.classify("cloudflare/24", emptyList())
        assertEquals(ManualRangeResult.Invalid(RangeError.BAD_IP), result)
    }

    @Test
    fun aPrefixBelowTheFloorIsRejectedAsTooLarge() {
        val result = ManualRangePolicy.classify("104.16.0.0/8", emptyList())
        assertEquals(ManualRangeResult.Invalid(RangeError.TOO_LARGE), result)
    }

    @Test
    fun anEmptyInputIsRejected() {
        val result = ManualRangePolicy.classify("   ", emptyList())
        assertEquals(ManualRangeResult.Invalid(RangeError.EMPTY), result)
    }

    @Test
    fun withoutASelectionStageTwoUsesTheGoodRanges() {
        val ranges = Stage2Ranges.resolve(
            good = listOf("104.16.0.0/24", "104.17.0.0/24"),
            manual = listOf("104.18.0.0/24"),
        )
        assertEquals(listOf("104.16.0.0/24", "104.17.0.0/24"), ranges)
    }

    @Test
    fun manualRangesAreUsedWhenNothingQualified() {
        val ranges = Stage2Ranges.resolve(
            good = emptyList(),
            manual = listOf("104.18.0.0/24"),
        )
        assertEquals(listOf("104.18.0.0/24"), ranges)
    }

    @Test
    fun anExplicitSelectionNarrowsTheScan() {
        val ranges = Stage2Ranges.resolve(
            good = listOf("104.16.0.0/24", "104.17.0.0/24"),
            manual = listOf("104.18.0.0/24"),
            selected = setOf("104.17.0.0/24", "104.18.0.0/24"),
        )
        assertEquals(listOf("104.17.0.0/24", "104.18.0.0/24"), ranges)
    }

    @Test
    fun aSelectionCannotSmuggleInAnotherIspsRange() {
        val ranges = Stage2Ranges.resolve(
            good = listOf("104.16.0.0/24"),
            manual = emptyList(),
            selected = setOf("1.1.1.0/24"),
        )
        assertEquals(emptyList<String>(), ranges)
    }

    @Test
    fun duplicateGoodRangesAreCollapsed() {
        val ranges = Stage2Ranges.resolve(
            good = listOf("104.16.0.0/24", "104.16.0.0/24"),
            manual = emptyList(),
        )
        assertEquals(listOf("104.16.0.0/24"), ranges)
    }
}
