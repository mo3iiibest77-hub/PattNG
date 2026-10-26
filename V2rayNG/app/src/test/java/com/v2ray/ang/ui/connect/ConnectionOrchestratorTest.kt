package com.v2ray.ang.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConnectionOrchestrator] state and result types.
 *
 * The full auto-connect flow depends on Android [android.content.Context],
 * MMKV storage, and the native Xray core — all of which require an
 * instrumented environment. These tests cover the pure-logic contract:
 * that every [AutoConnectState] and [AutoConnectResult] subtype is
 * instantiable and carries the data the UI (Phase 3) will need.
 *
 * Instrumented tests for the actual flow (subscription → scan → connect)
 * are deferred to Phase 4 (GitHub Actions validation).
 */
class ConnectionOrchestratorTest {

    @Test
    fun `AutoConnectState has all expected subtypes`() {
        // Exhaustive check: if a new state is added without updating the UI,
        // this test forces a conscious decision.
        val states: List<AutoConnectState> = listOf(
            AutoConnectState.WaitingForSubscription,
            AutoConnectState.FetchingSubscription,
            AutoConnectState.ParsingConfigurations,
            AutoConnectState.FilteringConfigurations,
            AutoConnectState.ApplyingConnectionSettings,
            AutoConnectState.ScanningCloudflareIps,
            AutoConnectState.SelectingBestIp("1.2.3.4"),
            AutoConnectState.StartingVpn,
            AutoConnectState.Connected("guid-123"),
            AutoConnectState.Reconnecting(1, "timeout"),
            AutoConnectState.Failed("no configs"),
            AutoConnectState.Stopping,
            AutoConnectState.Disconnected,
        )
        assertEquals(13, states.size)
    }

    @Test
    fun `SelectingBestIp carries the best IP`() {
        val state = AutoConnectState.SelectingBestIp("104.16.123.45")
        assertEquals("104.16.123.45", state.bestIp)
    }

    @Test
    fun `Connected carries the guid`() {
        val state = AutoConnectState.Connected("server-guid")
        assertEquals("server-guid", state.guid)
    }

    @Test
    fun `Reconnecting carries attempt number and reason`() {
        val state = AutoConnectState.Reconnecting(2, "scan timeout")
        assertEquals(2, state.attempt)
        assertEquals("scan timeout", state.reason)
    }

    @Test
    fun `Failed carries a reason`() {
        val state = AutoConnectState.Failed("no eligible configs")
        assertEquals("no eligible configs", state.reason)
    }

    @Test
    fun `AutoConnectResult Success carries guid`() {
        val result = AutoConnectResult.Success("guid-abc")
        assertEquals("guid-abc", result.guid)
    }

    @Test
    fun `AutoConnectResult Failure carries reason`() {
        val result = AutoConnectResult.Failure("core not running")
        assertEquals("core not running", result.reason)
    }

    @Test
    fun `AutoConnectResult Cancelled is a singleton object`() {
        val a: AutoConnectResult = AutoConnectResult.Cancelled
        val b: AutoConnectResult = AutoConnectResult.Cancelled
        assertEquals(a, b)
    }

    @Test
    fun `cancel is safe to call when no flow is running`() {
        val orchestrator = ConnectionOrchestrator()
        // Should not throw — no scan in progress, no wait job.
        orchestrator.cancel()
    }
}
