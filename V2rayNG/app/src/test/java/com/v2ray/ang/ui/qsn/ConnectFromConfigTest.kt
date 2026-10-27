package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.connect.AutoConnectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Connect from the Configurations screen.
 *
 * The rule under test is the one that keeps a row from ever starting a second
 * tunnel: while an attempt runs the row is inert, and a row whose configuration
 * is the one already running disconnects instead of reconnecting.
 */
class ConnectFromConfigTest {

    @Test
    fun aRowDoesNothingWhileAnAttemptIsRunning() {
        assertEquals(
            ConfigAction.Ignore,
            ConfigActionRules.decide(
                status = QsStatus.Connecting,
                coreRunning = false,
                connectedGuid = null,
                guid = "a",
            ),
        )
    }

    @Test
    fun aRowDoesNothingWhileDisconnecting() {
        assertEquals(
            ConfigAction.Ignore,
            ConfigActionRules.decide(
                status = QsStatus.Disconnecting,
                coreRunning = true,
                connectedGuid = "a",
                guid = "a",
            ),
        )
    }

    @Test
    fun theRunningConfigurationDisconnects() {
        assertEquals(
            ConfigAction.Disconnect,
            ConfigActionRules.decide(
                status = QsStatus.Connected,
                coreRunning = true,
                connectedGuid = "a",
                guid = "a",
            ),
        )
    }

    @Test
    fun anotherConfigurationStartsANewAttemptInsteadOfASecondTunnel() {
        // Connecting another config is allowed: the core is restarted with it,
        // which is why the row must not report Disconnect here.
        assertEquals(
            ConfigAction.Connect,
            ConfigActionRules.decide(
                status = QsStatus.Connected,
                coreRunning = true,
                connectedGuid = "a",
                guid = "b",
            ),
        )
    }

    @Test
    fun anUnknownRunningConfigurationStillOffersConnect() {
        assertEquals(
            ConfigAction.Connect,
            ConfigActionRules.decide(
                status = QsStatus.Connected,
                coreRunning = true,
                connectedGuid = null,
                guid = "a",
            ),
        )
    }

    @Test
    fun aSelectedButStoppedConfigurationOffersConnect() {
        assertEquals(
            ConfigAction.Connect,
            ConfigActionRules.decide(
                status = QsStatus.Disconnected,
                coreRunning = false,
                connectedGuid = null,
                guid = "a",
            ),
        )
    }

    @Test
    fun theConfirmedConfigurationIsRememberedInTheUiState() {
        val state = QsUiState().withConnectState(AutoConnectState.Connected("guid-1"))
        assertEquals(QsStatus.Connected, state.status)
        assertEquals("guid-1", state.connectedGuid)
    }

    @Test
    fun aStoppedDaemonForgetsTheConfirmedConfiguration() {
        val stopped = QsUiState(coreRunning = false, connectedGuid = "guid-1")
            .withConnectState(AutoConnectState.Stopping)
        assertEquals(QsStatus.Disconnected, stopped.status)
        assertNull(stopped.connectedGuid)
    }

    @Test
    fun aRecordedCrashIsNotShownByDefault() {
        assertNull(QsUiState().lastCrash)
    }
}
