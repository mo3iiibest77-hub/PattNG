package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the multi-selection rules behind bulk delete. */
class ConfigSelectionTest {

    private fun row(
        guid: String,
        source: ConfigSource = ConfigSource.Manual,
        eligible: Boolean = true,
        active: Boolean = false,
    ) = ConfigRow(
        guid = guid,
        remarks = guid,
        protocol = "VLESS",
        network = "ws",
        security = "tls",
        port = 443,
        server = "edge.example.com",
        source = source,
        eligible = eligible,
        active = active,
    )

    private val rows = listOf(
        row("manual-a"),
        row("sub-eligible", source = ConfigSource.Subscription),
        row("sub-ineligible", source = ConfigSource.Subscription, eligible = false),
        row("manual-none", eligible = false),
    )

    @Test
    fun `selectable keeps eligible subscriptions and every manual config`() {
        assertEquals(
            setOf("manual-a", "sub-eligible", "manual-none"),
            ConfigSelection.selectable(rows).map { it.guid }.toSet(),
        )
    }

    @Test
    fun `selectable drops ineligible subscription configs`() {
        assertFalse(
            ConfigSelection.selectable(rows).any { it.guid == "sub-ineligible" },
        )
    }

    @Test
    fun `all visible respects the active filters`() {
        val guids = ConfigSelection.allVisibleGuids(rows, setOf(ConfigFilter.Manual))
        assertEquals(setOf("manual-a", "manual-none"), guids)
    }

    @Test
    fun `all visible with no filters returns every selectable row`() {
        assertEquals(
            setOf("manual-a", "sub-eligible", "manual-none"),
            ConfigSelection.allVisibleGuids(rows, emptySet()),
        )
    }

    @Test
    fun `deletable removes the active guid`() {
        val result = ConfigSelection.deletable(
            setOf("manual-a", "sub-eligible"),
            activeGuid = "sub-eligible",
            connectedGuid = null,
        )
        assertEquals(setOf("manual-a"), result)
    }

    @Test
    fun `deletable removes the connected guid`() {
        val result = ConfigSelection.deletable(
            setOf("manual-a", "sub-eligible"),
            activeGuid = null,
            connectedGuid = "manual-a",
        )
        assertEquals(setOf("sub-eligible"), result)
    }

    @Test
    fun `deletable removes both protected guids`() {
        val result = ConfigSelection.deletable(
            setOf("manual-a", "sub-eligible", "manual-none"),
            activeGuid = "manual-a",
            connectedGuid = "sub-eligible",
        )
        assertEquals(setOf("manual-none"), result)
    }

    @Test
    fun `deletable keeps a selection with no protected guids`() {
        val selected = setOf("manual-a", "sub-eligible")
        assertEquals(selected, ConfigSelection.deletable(selected, null, null))
    }

    @Test
    fun `deletable with an empty selection is empty`() {
        assertTrue(ConfigSelection.deletable(emptySet(), "manual-a", null).isEmpty())
    }
}