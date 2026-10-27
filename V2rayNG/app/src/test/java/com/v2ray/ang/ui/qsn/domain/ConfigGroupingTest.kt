package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Configurations screen groups by origin, never by list position.
 *
 * A config must appear under the subscription stored on the profile, manual
 * imports must appear under "Added by You", and a subscription section must
 * only contain Cloudflare-ready configs.
 */
class ConfigGroupingTest {

    private fun row(
        guid: String,
        subscriptionId: String,
        eligible: Boolean = true,
        source: ConfigSource = ConfigSource.Subscription,
    ) = ConfigRow(
        guid = guid,
        remarks = guid,
        protocol = "VLESS",
        network = if (eligible) "ws" else "grpc",
        security = "tls",
        port = 443,
        server = "origin.example.com",
        source = source,
        eligible = eligible,
        active = false,
        subscriptionId = subscriptionId,
    )

    private fun subscription(guid: String, remarks: String, count: Int) = SubscriptionRow(
        guid = guid,
        remarks = remarks,
        url = "https://example.com/" + guid,
        enabled = true,
        configCount = count,
        lastUpdated = 0L,
        active = false,
    )

    @Test
    fun `each config lands under its own subscription`() {
        val groups = ConfigGrouping.groups(
            subscriptions = listOf(subscription("s1", "One", 2), subscription("s2", "Two", 1)),
            rows = listOf(row("a", "s1"), row("b", "s1"), row("c", "s2")),
            manualTitle = "Added by You",
        )
        assertEquals("One", groups[0].title)
        assertEquals(listOf("a", "b"), groups[0].rows.map { it.guid })
        assertEquals(listOf("c"), groups[1].rows.map { it.guid })
    }

    @Test
    fun `a subscription section only lists cloudflare-ready configs`() {
        val groups = ConfigGrouping.groups(
            subscriptions = listOf(subscription("s1", "One", 2)),
            rows = listOf(row("a", "s1"), row("b", "s1", eligible = false)),
            manualTitle = "Added by You",
        )
        assertEquals(listOf("a"), groups[0].rows.map { it.guid })
        // The header still reports the real total, so the hidden config is
        // accounted for instead of silently vanishing.
        assertEquals(2, groups[0].totalConfigs)
    }

    @Test
    fun `manual configs only appear under Added by You`() {
        val groups = ConfigGrouping.groups(
            subscriptions = listOf(subscription("s1", "One", 1)),
            rows = listOf(
                row("a", "s1"),
                row("m1", "", source = ConfigSource.Manual),
                row("m2", "", source = ConfigSource.Manual, eligible = false),
            ),
            manualTitle = "Added by You",
        )
        val manual = groups.last()
        assertTrue(manual.manual)
        assertEquals("Added by You", manual.title)
        assertEquals(listOf("m1", "m2"), manual.rows.map { it.guid })
        assertTrue(groups[0].rows.none { it.source == ConfigSource.Manual })
    }

    @Test
    fun `a config whose subscription is gone is still reachable`() {
        val groups = ConfigGrouping.groups(
            subscriptions = emptyList(),
            rows = listOf(row("orphan", "deleted-sub")),
            manualTitle = "Added by You",
        )
        assertEquals(1, groups.size)
        assertTrue(groups[0].manual)
        assertEquals(listOf("orphan"), groups[0].rows.map { it.guid })
    }

    @Test
    fun `no manual section is created when there is nothing manual`() {
        val groups = ConfigGrouping.groups(
            subscriptions = listOf(subscription("s1", "One", 1)),
            rows = listOf(row("a", "s1")),
            manualTitle = "Added by You",
        )
        assertEquals(1, groups.size)
        assertTrue(groups.none { it.manual })
    }

    @Test
    fun `the ready count of a section is its row count`() {
        val groups = ConfigGrouping.groups(
            subscriptions = listOf(subscription("s1", "One", 5)),
            rows = listOf(row("a", "s1"), row("b", "s1"), row("c", "s1", eligible = false)),
            manualTitle = "Added by You",
        )
        assertEquals(2, groups[0].rows.size)
    }
}
