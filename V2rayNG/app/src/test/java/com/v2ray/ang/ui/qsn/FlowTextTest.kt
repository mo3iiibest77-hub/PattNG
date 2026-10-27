package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.connect.AutoConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flow's localized messages, and the formatting contract that crashed
 * Connect.
 *
 * The reported crash was `MissingFormatArgumentException: Format specifier
 * '%s'`: `flowText()` read `qs_using_saved_ip` (a template) through the
 * formatting overload with no argument. These tests pin the template/value
 * pairing, so a message can no longer be produced with an unsubstituted
 * placeholder, and no state may claim Connected without the daemon.
 */
class FlowTextTest {

    private val text = QsFlowText()

    // ── The saved-edge message ──

    @Test
    fun theSavedEdgeMessageCarriesTheRealIp() {
        val message = text.savedEdge("104.16.21.7")
        assertNotNull(message)
        assertTrue(message!!.contains("104.16.21.7"))
        assertFalse("placeholder left in the message: " + message, message.contains("%"))
    }

    @Test
    fun thereIsNoSavedEdgeMessageWithoutAnIp() {
        // The flow only reuses an edge it really has. Without one it must show
        // nothing at all rather than a placeholder or a fabricated address.
        assertNull(text.savedEdge(null))
        assertNull(text.savedEdge(""))
        assertNull(text.savedEdge("   "))
    }

    @Test
    fun theReconnectMessageCarriesTheAttemptNumber() {
        val message = text.reconnectAttempt(3)
        assertTrue(message.contains("3"))
        assertFalse("placeholder left in the message: " + message, message.contains("%"))
    }

    @Test
    fun aTemplateWithoutAPlaceholderIsRenderedAsIs() {
        // A localized translation may legitimately drop the placeholder; that
        // must not throw.
        val plain = QsFlowText(usingSavedIp = "Using the saved edge")
        assertEquals("Using the saved edge", plain.savedEdge("104.16.21.7"))
    }

    // ── Every mapping branch that builds a message ──

    private fun messageOf(state: AutoConnectState): String? =
        QsUiState().withConnectState(state, text).message

    @Test
    fun noMappingBranchLeavesAPlaceholderInTheMessage() {
        val branches = listOf(
            AutoConnectState.WaitingForSubscription,
            AutoConnectState.ReusingSavedIp("104.16.21.7"),
            AutoConnectState.Reconnecting(2, "network changed"),
            AutoConnectState.Stopping,
            AutoConnectState.Failed("Core did not reach running state"),
        )
        branches.forEach { state ->
            val message = messageOf(state)
            assertNotNull("no message for " + state, message)
            assertFalse(
                "unsubstituted placeholder in the message for " + state + ": " + message,
                message!!.contains("%"),
            )
        }
    }

    @Test
    fun theReusingSavedIpBranchShowsTheIpThatWasReused() {
        val message = messageOf(AutoConnectState.ReusingSavedIp("104.17.3.9"))
        assertTrue(message!!.contains("104.17.3.9"))
    }

    // ── Truthful connection states ──

    @Test
    fun startingTheVpnIsNotConnected() {
        val state = QsUiState().withConnectState(AutoConnectState.StartingVpn)
        assertEquals(QsStatus.Connecting, state.status)
        assertTrue(state.busy)
    }

    @Test
    fun onlyTheDaemonConfirmationProducesConnected() {
        // Nothing before the confirmation may report Connected, even though the
        // UI is busy and the step list is nearly complete.
        val before = listOf(
            AutoConnectState.FetchingSubscription,
            AutoConnectState.ParsingConfigurations,
            AutoConnectState.FilteringConfigurations,
            AutoConnectState.ApplyingConnectionSettings,
            AutoConnectState.ScanningCloudflareIps,
            AutoConnectState.SelectingBestIp("104.16.21.7"),
            AutoConnectState.ApplyingEdge("104.16.21.7", 3),
            AutoConnectState.StartingVpn,
        )
        before.forEach { state ->
            assertFalse(
                "Connected reported too early for " + state,
                QsUiState().withConnectState(state, text).status == QsStatus.Connected,
            )
        }
        val confirmed = QsUiState().withConnectState(AutoConnectState.Connected("guid-1"), text)
        assertEquals(QsStatus.Connected, confirmed.status)
        assertEquals("guid-1", confirmed.connectedGuid)
    }

    @Test
    fun aFailureBeforeTheDaemonStartsIsAnError() {
        val state = QsUiState(coreRunning = false)
            .withConnectState(AutoConnectState.Failed("Core did not reach running state"), text)
        assertEquals(QsStatus.Error, state.status)
        assertFalse(state.coreRunning)
    }

    @Test
    fun aFailureWhileTheDaemonRunsDoesNotClaimADisconnect() {
        val state = QsUiState(coreRunning = true, connectedGuid = "guid-1")
            .withConnectState(AutoConnectState.Failed("Unexpected error"), text)
        assertEquals(QsStatus.Connected, state.status)
        assertEquals("guid-1", state.connectedGuid)
    }

    // ── Failure classification ──

    @Test
    fun theCoreStartFailureIsClassifiedAsACoreStartFailure() {
        assertEquals(
            FlowFailure.CoreStart,
            FlowFailureRules.classify("Core did not reach running state"),
        )
        assertEquals(
            FlowFailure.CoreStart,
            FlowFailureRules.classify("Core did not confirm running within timeout"),
        )
    }

    @Test
    fun everyMachineReasonMapsToAStableFailureKind() {
        assertEquals(
            FlowFailure.VpnPermission,
            FlowFailureRules.classify("VPN permission required"),
        )
        assertEquals(
            FlowFailure.Busy,
            FlowFailureRules.classify("Another auto-connect is already running"),
        )
        assertEquals(
            FlowFailure.NotEligible,
            FlowFailureRules.classify(
                "No eligible configs (ws/xhttp + TLS + CF HTTPS port required)",
            ),
        )
        assertEquals(
            FlowFailure.ScanFoundNothing,
            FlowFailureRules.classify("Cloudflare scan found no working IP"),
        )
        assertEquals(
            FlowFailure.Subscription,
            FlowFailureRules.classify("Subscription returned 0 configs"),
        )
        assertEquals(
            FlowFailure.ApplyFailed,
            FlowFailureRules.classify("Failed to apply best IP to profile"),
        )
        assertEquals(FlowFailure.Generic, FlowFailureRules.classify("Unexpected error"))
    }

    @Test
    fun aBusyReasonIsNotMistakenForACoreStartFailure() {
        // "already running" contains "running"; it must not become "the core
        // did not start", which would be a lie.
        assertEquals(
            FlowFailure.Busy,
            FlowFailureRules.classify("Another auto-connect is already running"),
        )
    }
}
