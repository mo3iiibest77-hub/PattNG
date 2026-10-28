package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "Cloudflare-ready" badge.
 *
 * The reported defect was Persian letters stacked vertically. The cause was a
 * Row that measured the badge with whatever width the transport label left, so
 * a narrow card could leave the badge a few pixels wide. The fix gives the
 * transport label a weight, lets it ellipsise, and makes the badge a single
 * non-wrapping line. These checks read the real composable.
 */
class CloudflareReadyBadgeTest {

    private val screen = SourceFiles.main("ui/qsn/QsConfigScreen.kt")

    @Test
    fun theBadgeIsOneNonWrappingLine() {
        val badge = SourceFiles.body(screen, "private fun EligibilityBadge(")
        assertTrue("the badge must be limited to one line", badge.contains("maxLines = 1"))
        assertTrue("the badge must not soft-wrap", badge.contains("softWrap = false"))
        assertTrue("a long badge must ellipsise", badge.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun theTransportLabelYieldsSpaceInsteadOfSqueezingTheBadge() {
        val row = SourceFiles.body(screen, "Row(\n                    modifier = Modifier.fillMaxWidth(),")
        assertTrue(
            "the label must take the remaining width, not the badge",
            row.contains("Modifier.weight(1f, fill = false)"),
        )
        assertTrue(row.contains("maxLines = 1"))
        assertFalse(
            "the badge must not be given a hard width in dp",
            row.contains("EligibilityBadge(row)\n                        .width"),
        )
    }
}

