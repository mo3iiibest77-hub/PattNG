package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the persistent IP cache: [IpSelection], [IpFingerprint] and
 * [IpAddress].
 *
 * The cache is what stops a reconnect from rescanning, so the negative cases
 * matter as much as the positive one: a stale, foreign or malformed entry must
 * never be reused.
 */
class IpSelectionTest {

    private val now = 1_700_000_000_000L
    private val fingerprint = "a".repeat(64)

    private fun selection(
        ip: String = "104.16.0.1",
        validatedAt: Long = now,
        fp: String = fingerprint,
    ) = IpSelection(fingerprint = fp, ip = ip, validatedAt = validatedAt)

    @Test
    fun `a fresh selection is usable and fresh`() {
        val entry = selection()
        assertTrue(entry.isUsable(now, fingerprint))
        assertTrue(entry.isFresh(now))
    }

    @Test
    fun `a selection from another configuration is not usable`() {
        assertFalse(selection().isUsable(now, "b".repeat(64)))
    }

    @Test
    fun `a selection older than the ttl is not usable`() {
        val old = selection(validatedAt = now - IpSelection.DEFAULT_TTL_MS - 1)
        assertFalse(old.isUsable(now, fingerprint))
    }

    @Test
    fun `a selection exactly at the ttl edge is still usable`() {
        val edge = selection(validatedAt = now - IpSelection.DEFAULT_TTL_MS)
        assertTrue(edge.isUsable(now, fingerprint))
    }

    @Test
    fun `a timestamp in the future is not usable`() {
        val future = selection(validatedAt = now + 60_000)
        assertFalse(future.isUsable(now, fingerprint))
    }

    @Test
    fun `a zero timestamp is not usable`() {
        assertFalse(selection(validatedAt = 0L).isUsable(now, fingerprint))
        assertFalse(selection(validatedAt = 0L).isFresh(now))
    }

    @Test
    fun `a host name is not usable as an edge`() {
        assertFalse(selection(ip = "edge.example.com").isUsable(now, fingerprint))
        assertFalse(selection(ip = "edge.example.com").isFresh(now))
    }

    @Test
    fun `an out of range address is not usable`() {
        assertFalse(selection(ip = "104.16.0.256").isUsable(now, fingerprint))
    }

    @Test
    fun `a stale but still valid selection is usable yet not fresh`() {
        val twoHoursOld = selection(validatedAt = now - 2L * 60L * 60L * 1000L)
        assertTrue(twoHoursOld.isUsable(now, fingerprint))
        assertFalse(twoHoursOld.isFresh(now))
    }

    // ── Fingerprint ──

    @Test
    fun `the fingerprint is stable for identical input`() {
        assertEquals(fingerprintFor(), fingerprintFor())
    }

    @Test
    fun `the fingerprint changes when the port changes`() {
        assertNotEquals(fingerprintFor(port = 443), fingerprintFor(port = 8443))
    }

    @Test
    fun `the fingerprint changes when the range set changes`() {
        assertNotEquals(
            fingerprintFor(rangeFingerprint = "aaaa"),
            fingerprintFor(rangeFingerprint = "bbbb"),
        )
    }

    @Test
    fun `the fingerprint changes when the settings version changes`() {
        assertNotEquals(
            fingerprintFor(settingsVersion = 2),
            fingerprintFor(settingsVersion = 3),
        )
    }

    @Test
    fun `the fingerprint is a 64 character hex digest`() {
        val value = fingerprintFor()
        assertEquals(64, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the fingerprint does not contain the guid`() {
        val guid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertFalse(fingerprintFor(guid = guid).contains(guid))
    }

    // ── IPv4 helpers ──

    @Test
    fun `parse rejects malformed literals`() {
        assertNull(IpAddress.parse(""))
        assertNull(IpAddress.parse(null))
        assertNull(IpAddress.parse("104.16.0"))
        assertNull(IpAddress.parse("104.16.0.1.2"))
        assertNull(IpAddress.parse("104.16.0.a"))
        assertNull(IpAddress.parse("104.16.0.-1"))
    }

    @Test
    fun `parse accepts the boundary octets`() {
        assertEquals(0L, IpAddress.parse("0.0.0.0"))
        assertEquals(0xFFFFFFFFL, IpAddress.parse("255.255.255.255"))
    }

    @Test
    fun `format round trips every parsed address`() {
        for (value in listOf("0.0.0.0", "104.16.0.1", "255.255.255.255", "1.2.3.4")) {
            val parsed = IpAddress.parse(value)!!
            assertEquals(value, IpAddress.format(parsed))
        }
    }

    private fun fingerprintFor(
        guid: String = "guid-1",
        port: Int = 443,
        rangeFingerprint: String = "builtin",
        settingsVersion: Int = IpFingerprint.SCAN_SETTINGS_VERSION,
    ) = IpFingerprint.of(
        guid = guid,
        configType = "VLESS",
        port = port,
        network = "ws",
        security = "tls",
        sni = "example.com",
        host = "example.com",
        path = "/ws",
        xhttpMode = null,
        rangeFingerprint = rangeFingerprint,
        settingsVersion = settingsVersion,
    )
}
