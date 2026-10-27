package com.v2ray.ang.ui.qsn.domain

import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the subscription panel rules.
 *
 * These cover the two things that can leak or break: a URL printed with its
 * token, and a subscription written by an older build that must keep working.
 */
class SubscriptionRulesTest {

    private fun cache(
        guid: String,
        remarks: String = "",
        url: String = "https://sub.example.com/token",
        enabled: Boolean = true,
        lastUpdated: Long = 0L,
    ) = SubscriptionCache(
        guid = guid,
        subscription = SubscriptionItem(
            remarks = remarks,
            url = url,
            enabled = enabled,
            lastUpdated = lastUpdated,
        ),
    )

    // ── Redaction ──

    @Test
    fun `redaction keeps the scheme and host only`() {
        assertEquals(
            "https://sub.example.com/" + "\u2026",
            SubscriptionRules.redact("https://sub.example.com/token123?x=1"),
        )
    }

    @Test
    fun `redaction removes user info`() {
        assertEquals(
            "https://sub.example.com/" + "\u2026",
            SubscriptionRules.redact("https://user:secret@sub.example.com/x"),
        )
    }

    @Test
    fun `redaction of something that is not a url is a dash`() {
        assertEquals("\u2014", SubscriptionRules.redact("not a url"))
        assertEquals("\u2014", SubscriptionRules.redact(""))
    }

    @Test
    fun `redaction never leaks the path`() {
        val redacted = SubscriptionRules.redact("https://sub.example.com/very/secret/path")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("very"))
    }

    @Test
    fun `hostOf strips credentials and path`() {
        assertEquals("sub.example.com", SubscriptionRules.hostOf("https://user:pass@sub.example.com/a/b"))
        assertEquals("", SubscriptionRules.hostOf("nonsense"))
    }

    // ── Names ──

    @Test
    fun `names are trimmed`() {
        assertEquals("Home", SubscriptionRules.normalizeRemarks("  Home  "))
    }

    @Test
    fun `blank names are rejected`() {
        assertNull(SubscriptionRules.normalizeRemarks("   "))
        assertNull(SubscriptionRules.normalizeRemarks(""))
    }

    @Test
    fun `names longer than the limit are rejected`() {
        val tooLong = "x".repeat(SubscriptionRules.MAX_REMARKS + 1)
        assertNull(SubscriptionRules.normalizeRemarks(tooLong))
        assertEquals(
            SubscriptionRules.MAX_REMARKS,
            SubscriptionRules.normalizeRemarks("x".repeat(SubscriptionRules.MAX_REMARKS))!!.length,
        )
    }

    @Test
    fun `names with control characters are rejected`() {
        assertNull(SubscriptionRules.normalizeRemarks("line\nbreak"))
    }

    @Test
    fun `the display name falls back to the host`() {
        val row = SubscriptionRules.fromCache(cache("g1", remarks = ""), 0, false)
        assertEquals("sub.example.com", row.displayName)
    }

    // ── Migration / backwards compatibility ──

    @Test
    fun `a legacy single subscription becomes one enabled row`() {
        // A subscription written by a build that predates the panel has the
        // same shape: it must appear unchanged, with no migration pass.
        val row = SubscriptionRules.fromCache(
            cache("legacy-guid", remarks = "Old", lastUpdated = 1234L),
            configCount = 12,
            active = true,
        )
        assertTrue(row.enabled)
        assertTrue(row.active)
        assertEquals("legacy-guid", row.guid)
        assertEquals("Old", row.displayName)
        assertEquals(12, row.configCount)
        assertEquals(1234L, row.lastUpdated)
    }

    @Test
    fun `a disabled stored subscription stays disabled`() {
        val row = SubscriptionRules.fromCache(cache("g2", enabled = false), 3, false)
        assertFalse(row.enabled)
        assertFalse(row.active)
    }

    // ── Ordering and selection ──

    @Test
    fun `sortForDisplay puts the active row first`() {
        val rows = listOf(
            SubscriptionRules.fromCache(cache("a", remarks = "Alpha"), 0, false),
            SubscriptionRules.fromCache(cache("b", remarks = "Beta"), 0, true),
            SubscriptionRules.fromCache(cache("c", remarks = "Gamma", enabled = false), 0, false),
        )
        val sorted = SubscriptionRules.sortForDisplay(rows)
        assertEquals(listOf("b", "a", "c"), sorted.map { it.guid })
    }

    @Test
    fun `activeRow prefers the active entry`() {
        val rows = listOf(
            SubscriptionRules.fromCache(cache("a"), 0, false),
            SubscriptionRules.fromCache(cache("b"), 0, true),
        )
        assertEquals("b", SubscriptionRules.activeRow(rows)!!.guid)
    }

    @Test
    fun `chooseActive prefers the explicit choice`() {
        val caches = listOf(cache("a"), cache("b"))
        assertEquals("b", SubscriptionRules.chooseActive(caches, "b")!!.guid)
    }

    @Test
    fun `chooseActive skips a disabled explicit choice`() {
        val caches = listOf(cache("a"), cache("b", enabled = false))
        assertEquals("a", SubscriptionRules.chooseActive(caches, "b")!!.guid)
    }

    @Test
    fun `chooseActive falls back to the first enabled subscription`() {
        val caches = listOf(cache("a", enabled = false), cache("b"))
        assertEquals("b", SubscriptionRules.chooseActive(caches, null)!!.guid)
    }

    @Test
    fun `chooseActive returns null when every subscription is disabled`() {
        val caches = listOf(cache("a", enabled = false), cache("b", enabled = false))
        assertNull(SubscriptionRules.chooseActive(caches, null))
    }

    @Test
    fun `chooseActive returns null for an empty list`() {
        assertNull(SubscriptionRules.chooseActive(emptyList(), "a"))
    }
}
