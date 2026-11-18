package com.v2ray.ang.ui.qsn3d

import org.junit.Assert.assertEquals
import org.junit.Test

class NovaPulseFormattingTest {

    @Test
    fun formatTrafficLocalized_usesExact1024BinaryGibAndMbThreshold() {
        val hundredGiB = 100L * 1024L * 1024L * 1024L
        assertEquals("100.00 گیگابایت", formatTrafficLocalized(hundredGiB, "گیگابایت", "مگابایت"))

        val fiveTwelveMiB = 512L * 1024L * 1024L
        assertEquals("512.0 مگابایت", formatTrafficLocalized(fiveTwelveMiB, "گیگابایت", "مگابایت"))

        assertEquals("0.00 GB", formatGbCompact(0L))
    }

    @Test
    fun formatRemainingTimeText_computesExactDaysAndHoursOrExpired() {
        val nowMs = 1_700_000_000_000L
        val exp2Days5HoursSec = (nowMs + (2 * 24 + 5) * 3_600_000L) / 1000L
        val resDays = formatRemainingTimeText(
            expRawSeconds = exp2Days5HoursSec,
            nowMs = nowMs,
            unlimitedLabel = "Unlimited",
            startsOnFirstUseLabel = "FirstUse",
            expiredLabel = "Expired",
            daysHoursFmt = { d, h -> "${d}d ${h}h" },
            hoursLeftFmt = { h -> "${h}h" },
            durationDaysFmt = { d -> "plan ${d}d" },
        )
        assertEquals("2d 5h", resDays)

        val exp12HoursSec = (nowMs + 12 * 3_600_000L) / 1000L
        val resHours = formatRemainingTimeText(
            expRawSeconds = exp12HoursSec,
            nowMs = nowMs,
            unlimitedLabel = "Unlimited",
            startsOnFirstUseLabel = "FirstUse",
            expiredLabel = "Expired",
            daysHoursFmt = { d, h -> "${d}d ${h}h" },
            hoursLeftFmt = { h -> "${h}h" },
            durationDaysFmt = { d -> "plan ${d}d" },
        )
        assertEquals("12h", resHours)

        val resExpired = formatRemainingTimeText(
            expRawSeconds = (nowMs / 1000L) - 10L,
            nowMs = nowMs,
            unlimitedLabel = "Unlimited",
            startsOnFirstUseLabel = "FirstUse",
            expiredLabel = "Expired",
            daysHoursFmt = { d, h -> "${d}d ${h}h" },
            hoursLeftFmt = { h -> "${h}h" },
            durationDaysFmt = { d -> "plan ${d}d" },
        )
        assertEquals("Expired", resExpired)
    }
}
