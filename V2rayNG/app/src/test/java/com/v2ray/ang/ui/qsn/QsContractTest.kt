package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.connect.AutoConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the real mapping used by the connect screen, not a copy of it.
 *
 * These are pure JVM tests: [QsUiState.withConnectState] and [QsUrlValidator] deliberately have
 * no Android dependency, which is what makes the connection-state contract testable without a
 * device or a running core.
 */
class QsContractTest {

    // ---------- URL validation ----------

    @Test
    fun acceptsHttpsSubscriptionUrl() {
        assertEquals("https://example.com/sub", QsUrlValidator.normalize("https://example.com/sub"))
    }

    @Test
    fun acceptsHttpSubscriptionUrl() {
        assertEquals("http://example.com:8080/sub", QsUrlValidator.normalize("http://example.com:8080/sub"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com/sub", QsUrlValidator.normalize("  https://example.com/sub  "))
    }

    @Test
    fun rejectsBlankInput() {
        assertNull(QsUrlValidator.normalize(""))
        assertNull(QsUrlValidator.normalize("   "))
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertNull(QsUrlValidator.normalize("ftp://example.com/sub"))
        assertNull(QsUrlValidator.normalize("vmess://abcdef"))
    }

    @Test
    fun rejectsUrlWithoutScheme() {
        assertNull(QsUrlValidator.normalize("example.com/sub"))
    }

    @Test
    fun rejectsUrlWithoutHost() {
        assertNull(QsUrlValidator.normalize("https://"))
        assertNull(QsUrlValidator.normalize("https:///sub"))
    }

    @Test
    fun rejectsEmbeddedWhitespace() {
        assertNull(QsUrlValidator.normalize("https://example.com/a b"))
    }

    @Test
    fun acceptsNonAsciiHost() {
        assertNotNull(QsUrlValidator.normalize("https://مثال.ایران/sub"))
    }

    // ---------- Connection state mapping ----------

    @Test
    fun fetchingSubscriptionShowsConnecting() {
        val state = QsUiState().withConnectState(AutoConnectState.FetchingSubscription)
        assertEquals(QsStatus.Connecting, state.status)
        assertEquals(QsStep.FetchingSubscription, state.step)
        assertTrue(state.busy)
    }

    @Test
    fun parsingFilteringAndApplyingShareCheckingStep() {
        val parsing = QsUiState().withConnectState(AutoConnectState.ParsingConfigurations)
        val filtering = QsUiState().withConnectState(AutoConnectState.FilteringConfigurations)
        val applying = QsUiState().withConnectState(AutoConnectState.ApplyingConnectionSettings)
        assertEquals(QsStep.CheckingConfigs, parsing.step)
        assertEquals(QsStep.CheckingConfigs, filtering.step)
        assertEquals(QsStep.CheckingConfigs, applying.step)
        assertEquals(QsStatus.Connecting, parsing.status)
    }

    @Test
    fun scanningResetsProgress() {
        val state = QsUiState(scanDone = 7, scanTotal = 9)
            .withConnectState(AutoConnectState.ScanningCloudflareIps)
        assertEquals(QsStep.ScanningIps, state.step)
        assertEquals(0, state.scanDone)
        assertEquals(0, state.scanTotal)
        assertNull(state.scanFraction)
    }

    @Test
    fun scanProgressProducesRealFraction() {
        val state = QsUiState().withConnectState(AutoConnectState.ScanProgress(3, 10))
        assertEquals(QsStep.ScanningIps, state.step)
        assertEquals(3, state.scanDone)
        assertEquals(10, state.scanTotal)
        assertEquals(0.3f, state.scanFraction!!, 0.0001f)
    }

    @Test
    fun connectedRequiresDaemonConfirmation() {
        val state = QsUiState(status = QsStatus.Connecting, busy = true)
            .withConnectState(AutoConnectState.Connected("guid-1"))
        assertEquals(QsStatus.Connected, state.status)
        assertEquals(QsStep.Connected, state.step)
        assertTrue(state.coreRunning)
        assertFalse(state.busy)
        assertNull(state.message)
    }

    @Test
    fun noStateClaimsConnectedWithoutARunningCore() {
        val states = listOf(
            AutoConnectState.WaitingForSubscription,
            AutoConnectState.FetchingSubscription,
            AutoConnectState.ParsingConfigurations,
            AutoConnectState.FilteringConfigurations,
            AutoConnectState.ApplyingConnectionSettings,
            AutoConnectState.ScanningCloudflareIps,
            AutoConnectState.ScanProgress(1, 2),
            AutoConnectState.SelectingBestIp("1.1.1.1"),
            AutoConnectState.ReusingSavedIp("104.16.0.1"),
            AutoConnectState.StartingVpn,
            AutoConnectState.Reconnecting(1, "retry"),
            AutoConnectState.Failed("boom"),
            AutoConnectState.Stopping,
            AutoConnectState.Disconnected,
        )
        states.forEach { state ->
            val mapped = QsUiState(coreRunning = false).withConnectState(state)
            assertFalse(
                "state " + state + " must not report Connected while the core is down",
                mapped.status == QsStatus.Connected,
            )
        }
    }

    @Test
    fun failureKeepsAnExistingTunnelConnected() {
        val state = QsUiState(coreRunning = true, status = QsStatus.Connected)
            .withConnectState(AutoConnectState.Failed("scan found nothing"))
        assertEquals(QsStatus.Connected, state.status)
        assertEquals("scan found nothing", state.message)
        assertFalse(state.busy)
    }

    @Test
    fun failureWithoutTunnelShowsError() {
        val state = QsUiState(coreRunning = false, status = QsStatus.Connecting, busy = true)
            .withConnectState(AutoConnectState.Failed("core did not start"))
        assertEquals(QsStatus.Error, state.status)
        assertEquals("core did not start", state.message)
        assertFalse(state.busy)
    }

    @Test
    fun cancelReportsDisconnectedWhenTunnelWasNeverUp() {
        val state = QsUiState(coreRunning = false, status = QsStatus.Connecting, busy = true)
            .withConnectState(AutoConnectState.Stopping)
        assertEquals(QsStatus.Disconnected, state.status)
        assertEquals(QsStep.Idle, state.step)
        assertEquals("Cancelled", state.message)
        assertFalse(state.busy)
    }

    @Test
    fun cancelKeepsTunnelStateWhenCoreStayedUp() {
        val state = QsUiState(coreRunning = true, status = QsStatus.Connecting)
            .withConnectState(AutoConnectState.Stopping)
        assertEquals(QsStatus.Connected, state.status)
    }

    @Test
    fun missingSubscriptionIsAnError() {
        val state = QsUiState().withConnectState(AutoConnectState.WaitingForSubscription)
        assertEquals(QsStatus.Error, state.status)
        assertEquals("No subscription configured", state.message)
        assertFalse(state.busy)
    }

    @Test
    fun reconnectingShowsAttemptNumber() {
        val state = QsUiState().withConnectState(AutoConnectState.Reconnecting(2, "retry"))
        assertEquals(QsStatus.Connecting, state.status)
        assertEquals(QsStep.StartingVpn, state.step)
        assertTrue(state.message!!.contains("2"))
    }

    @Test
    fun selectingIpAndStartingVpnHaveDistinctSteps() {
        val selecting = QsUiState().withConnectState(AutoConnectState.SelectingBestIp("104.16.0.1"))
        val starting = QsUiState().withConnectState(AutoConnectState.StartingVpn)
        assertEquals(QsStep.SelectingIp, selecting.step)
        assertEquals(QsStep.StartingVpn, starting.step)
    }

    @Test
    fun progressStepsAreOrderedAndExcludeIdle() {
        val steps = QsStep.progressSteps
        // Ten rows now: the flow gained the config choice, the IP choice and
        // the edge application, each backed by a real orchestrator phase.
        assertEquals(10, steps.size)
        assertFalse(steps.contains(QsStep.Idle))
        assertEquals(QsStep.Preparing, steps.first())
        assertEquals(QsStep.Connected, steps.last())
        assertEquals(steps.size, steps.toSet().size)
    }

    @Test
    fun scanFractionIsClamped() {
        val state = QsUiState(step = QsStep.ScanningIps, scanDone = 15, scanTotal = 10)
        assertEquals(1f, state.scanFraction!!, 0.0001f)
    }

    @Test
    fun scanFractionIsNullOutsideScanStep() {
        val state = QsUiState(step = QsStep.StartingVpn, scanDone = 5, scanTotal = 10)
        assertNull(state.scanFraction)
    }
}
