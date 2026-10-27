package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The real-delay display semantics of [ConfigRow.delayMs].
 *
 * A positive [ConfigRow.testDelayMillis] is a valid measurement; zero means
 * never measured and a negative value means the last measurement failed, and
 * both of those must render as "not available" rather than a number.
 */
class ConfigRowDelayTest {

    private fun row(testDelayMillis: Long) = ConfigRow(
        guid = "guid",
        remarks = "remarks",
        protocol = "VLESS",
        network = "ws",
        security = "tls",
        port = 443,
        server = "edge.example.com",
        source = ConfigSource.Subscription,
        eligible = true,
        active = false,
        testDelayMillis = testDelayMillis,
    )

    @Test
    fun `a positive delay is reported`() {
        assertEquals(123L, row(123L).delayMs)
    }

    @Test
    fun `zero means never measured and reports null`() {
        assertNull(row(0L).delayMs)
    }

    @Test
    fun `a negative delay means failed and reports null`() {
        assertNull(row(-1L).delayMs)
    }

    @Test
    fun `the default row is not measured`() {
        assertNull(
            ConfigRow(
                guid = "guid",
                remarks = "remarks",
                protocol = "VLESS",
                network = "ws",
                security = "tls",
                port = 443,
                server = "edge.example.com",
                source = ConfigSource.Manual,
                eligible = true,
                active = false,
            ).delayMs,
        )
    }
}