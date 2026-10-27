package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.connect.AutoConnectState
import com.v2ray.ang.ui.qsn.domain.ConfigChoice
import com.v2ray.ang.ui.qsn.domain.ScanCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visible connection flow.
 *
 * The step list is the contract the user sees, so it is asserted literally: the
 * order, the mapping from a real orchestrator state to a step, and the fact
 * that a live scan result reaches the UI while the scan is still running.
 */
class ConnectFlowContractTest {

    @Test
    fun `the progress list matches the required order`() {
        assertEquals(
            listOf(
                QsStep.Preparing,
                QsStep.FetchingSubscription,
                QsStep.CheckingConfigs,
                QsStep.ChoosingConfig,
                QsStep.ScanningIps,
                QsStep.ChoosingIp,
                QsStep.SelectingIp,
                QsStep.ApplyingEdge,
                QsStep.StartingVpn,
                QsStep.Connected,
            ),
            QsStep.progressSteps,
        )
    }

    @Test
    fun `the subscription update happens before the config check`() {
        val fetchIndex = QsStep.progressSteps.indexOf(QsStep.FetchingSubscription)
        val checkIndex = QsStep.progressSteps.indexOf(QsStep.CheckingConfigs)
        assertTrue(fetchIndex in 0 until checkIndex)
    }

    @Test
    fun `real states map to non decreasing steps`() {
        val states = listOf(
            AutoConnectState.FetchingSubscription,
            AutoConnectState.ParsingConfigurations,
            AutoConnectState.FilteringConfigurations,
            AutoConnectState.ApplyingConnectionSettings,
            AutoConnectState.AwaitingConfigChoice(emptyList(), null),
            AutoConnectState.ScanningCloudflareIps,
            AutoConnectState.ScanProgress(1, 4),
            AutoConnectState.AwaitingIpChoice(emptyList(), null),
            AutoConnectState.SelectingBestIp("104.16.0.1"),
            AutoConnectState.ApplyingEdge("104.16.0.1", 3),
            AutoConnectState.StartingVpn,
            AutoConnectState.Connected("guid"),
        )
        var previous = -1
        states.forEach { state ->
            val step = QsUiState().withConnectState(state).step
            val index = QsStep.progressSteps.indexOf(step)
            assertTrue("step " + step + " went backwards", index >= previous)
            previous = index
        }
    }

    @Test
    fun `the config picker exposes only the offered choices`() {
        val choices = listOf(
            ConfigChoice("a", "one", "VLESS", "ws", "tls", 443, "sni.example.com", "Sub"),
            ConfigChoice("b", "two", "VMESS", "xhttp", "tls", 2053, "", "Sub"),
        )
        val state = QsUiState().withConnectState(
            AutoConnectState.AwaitingConfigChoice(choices, "a"),
        )
        assertEquals(QsStep.ChoosingConfig, state.step)
        assertEquals(2, state.configChoices.size)
        assertEquals("a", state.chosenConfigGuid)
        assertTrue(state.awaitingChoice)
        // Nothing is scanned yet.
        assertTrue(state.scanCandidates.isEmpty())
    }

    @Test
    fun `a live scan result is accumulated while scanning`() {
        val first = ScanCandidate(ip = "1.1.1.1", pingMs = 20, successes = 3, score = 90.0)
        val second = ScanCandidate(ip = "2.2.2.2", pingMs = 40, successes = 3, score = 50.0)
        val state = QsUiState()
            .withConnectState(AutoConnectState.ScanningCloudflareIps)
            .withConnectState(AutoConnectState.ScanCandidateFound(first, 1, 2))
            .withConnectState(AutoConnectState.ScanCandidateFound(second, 2, 2))
        assertEquals(QsStep.ScanningIps, state.step)
        assertEquals(2, state.scanCandidates.size)
        // Ranked, so the suggestion is stable.
        assertEquals("1.1.1.1", state.scanCandidates.first().ip)
        assertEquals(2, state.scanDone)
    }

    @Test
    fun `a repeated measurement of the same ip replaces the old row`() {
        val first = ScanCandidate(ip = "1.1.1.1", pingMs = 90, successes = 1)
        val improved = ScanCandidate(ip = "1.1.1.1", pingMs = 10, successes = 3)
        val state = QsUiState()
            .withConnectState(AutoConnectState.ScanCandidateFound(first, 1, 2))
            .withConnectState(AutoConnectState.ScanCandidateFound(improved, 2, 2))
        assertEquals(1, state.scanCandidates.size)
        assertEquals(10L, state.scanCandidates.first().pingMs)
    }

    @Test
    fun `the ip choice keeps the suggestion but does not apply it`() {
        val candidates = listOf(
            ScanCandidate(ip = "1.1.1.1", pingMs = 20, successes = 3, score = 90.0),
            ScanCandidate(ip = "2.2.2.2", pingMs = 60, successes = 3, score = 40.0),
        )
        val state = QsUiState().withConnectState(
            AutoConnectState.AwaitingIpChoice(candidates, "1.1.1.1"),
        )
        assertEquals(QsStep.ChoosingIp, state.step)
        assertEquals("1.1.1.1", state.suggestedIp)
        assertEquals("1.1.1.1", state.pendingIp)
        // Still not connected: the flow is waiting for the user.
        assertEquals(QsStatus.Connecting, state.status)
        assertTrue(state.awaitingChoice)
    }

    @Test
    fun `applying the edge is its own step before the vpn starts`() {
        val state = QsUiState().withConnectState(AutoConnectState.ApplyingEdge("1.1.1.1", 3))
        assertEquals(QsStep.ApplyingEdge, state.step)
        val applyIndex = QsStep.progressSteps.indexOf(QsStep.ApplyingEdge)
        val startIndex = QsStep.progressSteps.indexOf(QsStep.StartingVpn)
        assertTrue(applyIndex < startIndex)
    }

    @Test
    fun `a failure never claims connected when the core is down`() {
        val state = QsUiState(coreRunning = false, status = QsStatus.Connecting)
            .withConnectState(AutoConnectState.Failed("no working IP"))
        assertEquals(QsStatus.Error, state.status)
        assertTrue(state.busy.not())
        assertNull(state.suggestedIp)
    }

    @Test
    fun `a new scan clears the previous results`() {
        val dirty = QsUiState(
            scanCandidates = listOf(ScanCandidate(ip = "9.9.9.9")),
            suggestedIp = "9.9.9.9",
        ).withConnectState(AutoConnectState.ScanningCloudflareIps)
        assertTrue(dirty.scanCandidates.isEmpty())
        assertNull(dirty.suggestedIp)
    }
}
