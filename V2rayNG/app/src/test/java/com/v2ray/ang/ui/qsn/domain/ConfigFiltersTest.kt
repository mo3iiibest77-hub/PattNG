package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the configuration filter engine. */
class ConfigFilterEngineTest {

    private fun row(
        guid: String,
        network: String = "ws",
        security: String = "tls",
        port: Int = 443,
        source: ConfigSource = ConfigSource.Manual,
        eligible: Boolean = true,
        active: Boolean = false,
    ) = ConfigRow(
        guid = guid,
        remarks = guid,
        protocol = "VLESS",
        network = network,
        security = security,
        port = port,
        server = "edge.example.com",
        source = source,
        eligible = eligible,
        active = active,
    )

    private val rows = listOf(
        row("ws-manual"),
        row("xhttp-sub", network = "xhttp", port = 2053, source = ConfigSource.Subscription),
        row("grpc-sub", network = "grpc", source = ConfigSource.Subscription, eligible = false),
        row("ws-none", network = "ws", security = "", eligible = false, active = true),
    )

    @Test
    fun `an empty selection returns everything`() {
        assertEquals(rows, ConfigFilterEngine.apply(emptySet(), rows))
    }

    @Test
    fun `All behaves like an empty selection`() {
        assertEquals(rows, ConfigFilterEngine.apply(setOf(ConfigFilter.All), rows))
    }

    @Test
    fun `filters combine with AND`() {
        val result = ConfigFilterEngine.apply(
            setOf(ConfigFilter.Subscription, ConfigFilter.CloudflareEligible),
            rows,
        )
        assertEquals(listOf("xhttp-sub"), result.map { it.guid })
    }

    @Test
    fun `the manual filter excludes subscription rows`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.Manual), rows)
        assertEquals(listOf("ws-manual", "ws-none"), result.map { it.guid })
    }

    @Test
    fun `websocket tls matches only ws plus tls`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.WebSocketTls), rows)
        assertEquals(listOf("ws-manual"), result.map { it.guid })
    }

    @Test
    fun `xhttp tls matches only xhttp plus tls`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.XhttpTls), rows)
        assertEquals(listOf("xhttp-sub"), result.map { it.guid })
    }

    @Test
    fun `other transport includes grpc and non tls`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.OtherTransport), rows)
        assertEquals(listOf("grpc-sub", "ws-none"), result.map { it.guid })
    }

    @Test
    fun `not eligible is the exact complement of eligible`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.NotEligible), rows)
        assertEquals(listOf("grpc-sub", "ws-none"), result.map { it.guid })
        assertTrue(result.none { it.eligible })
    }

    @Test
    fun `active matches only the configuration in use`() {
        val result = ConfigFilterEngine.apply(setOf(ConfigFilter.Active), rows)
        assertEquals(listOf("ws-none"), result.map { it.guid })
    }

    @Test
    fun `a combination that matches nothing returns an empty list`() {
        val result = ConfigFilterEngine.apply(
            setOf(ConfigFilter.Manual, ConfigFilter.XhttpTls),
            rows,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `toggle adds a filter`() {
        assertEquals(setOf(ConfigFilter.Manual), ConfigFilterEngine.toggle(emptySet(), ConfigFilter.Manual))
    }

    @Test
    fun `toggle removes an already selected filter`() {
        assertEquals(
            emptySet<ConfigFilter>(),
            ConfigFilterEngine.toggle(setOf(ConfigFilter.Manual), ConfigFilter.Manual),
        )
    }

    @Test
    fun `toggling All clears every other filter`() {
        val selected = setOf(ConfigFilter.Manual, ConfigFilter.Active)
        assertTrue(ConfigFilterEngine.toggle(selected, ConfigFilter.All).isEmpty())
    }

    @Test
    fun `All is never stored in the selection`() {
        val result = ConfigFilterEngine.toggle(emptySet(), ConfigFilter.All)
        assertFalse(result.contains(ConfigFilter.All))
    }

    @Test
    fun `matches is consistent with apply for every filter`() {
        ConfigFilter.entries.forEach { filter ->
            val expected = rows.filter { ConfigFilterEngine.matches(filter, it) }
            assertEquals(
                "filter " + filter,
                expected.map { it.guid },
                ConfigFilterEngine.apply(setOf(filter), rows).map { it.guid },
            )
        }
    }
}
