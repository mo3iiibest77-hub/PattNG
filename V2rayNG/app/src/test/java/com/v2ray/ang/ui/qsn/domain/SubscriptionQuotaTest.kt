package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.util.SubscriptionQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic tests for [SubscriptionQuota], the pure parser in the util layer
 * that turns the Subscription-Userinfo response header into a quota row.
 *
 * No network, no Android: everything here runs on the JVM in CI.
 */
class SubscriptionQuotaTest {

    @Test
    fun `no header parses to all zeros`() {
        val quota = SubscriptionQuota.parse(null)
        assertEquals(0L, quota.upload)
        assertEquals(0L, quota.download)
        assertEquals(0L, quota.total)
        assertEquals(0L, quota.expire)
    }

    @Test
    fun `blank header parses to all zeros`() {
        val quota = SubscriptionQuota.parse("   ")
        assertEquals(0L, quota.upload)
        assertEquals(0L, quota.download)
        assertEquals(0L, quota.total)
        assertEquals(0L, quota.expire)
    }

    @Test
    fun `full header parses every field`() {
        val quota = SubscriptionQuota.parse(
            "upload=213123; download=534; total=109387; expire=1666000000",
        )
        assertEquals(213123L, quota.upload)
        assertEquals(534L, quota.download)
        assertEquals(109387L, quota.total)
        assertEquals(1666000000L, quota.expire)
    }

    @Test
    fun `partial header leaves the missing fields at zero`() {
        val quota = SubscriptionQuota.parse("upload=100; total=500")
        assertEquals(100L, quota.upload)
        assertEquals(0L, quota.download)
        assertEquals(500L, quota.total)
        assertEquals(0L, quota.expire)
    }

    @Test
    fun `malformed entries are skipped without throwing`() {
        val quota = SubscriptionQuota.parse("upload=; download; =42; total=abc; expire=1700000000")
        assertEquals(0L, quota.upload)
        assertEquals(0L, quota.download)
        assertEquals(0L, quota.total)
        assertEquals(1700000000L, quota.expire)
    }

    @Test
    fun `surrounding whitespace around a value is trimmed`() {
        val quota = SubscriptionQuota.parse("upload= 213123 ; download=534")
        assertEquals(213123L, quota.upload)
        assertEquals(534L, quota.download)
    }

    @Test
    fun `usage fraction is null when total is unknown`() {
        assertNull(SubscriptionQuota.usageFraction(100L, 200L, 0L))
        assertNull(SubscriptionQuota.usageFraction(100L, 200L, -5L))
    }

    @Test
    fun `usage fraction is zero when nothing was used`() {
        assertEquals(0f, SubscriptionQuota.usageFraction(0L, 0L, 1000L)!!)
    }

    @Test
    fun `usage fraction combines upload and download`() {
        assertEquals(0.5f, SubscriptionQuota.usageFraction(250L, 250L, 1000L)!!)
    }

    @Test
    fun `usage fraction clamps at one hundred percent`() {
        assertEquals(1f, SubscriptionQuota.usageFraction(700L, 700L, 1000L)!!)
    }

    @Test
    fun `usage percent returns null when total is unknown`() {
        assertNull(SubscriptionQuota.usagePercent(10L, 10L, 0L))
    }

    @Test
    fun `usage percent is a whole number in range`() {
        assertEquals(50, SubscriptionQuota.usagePercent(250L, 250L, 1000L))
        assertEquals(100, SubscriptionQuota.usagePercent(700L, 700L, 1000L))
        assertEquals(0, SubscriptionQuota.usagePercent(0L, 0L, 1000L))
    }
}