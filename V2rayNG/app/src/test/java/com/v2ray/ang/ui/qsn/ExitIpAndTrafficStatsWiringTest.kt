package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honest-exit-IP and removed-transfer-stats paths, checked as source.
 *
 * The exit IP must only ever be the real, observed IP that an external service
 * reports through the tunnel — never the origin server from the profile or the
 * Cloudflare edge — with a truthful "unavailable" fallback. The transfer stat
 * display is deliberately gone from the connected screen while the
 * notification keeps its own, independent traffic path. None of these
 * properties are observable through a public API, so the assertions read the
 * real files.
 */
class ExitIpAndTrafficStatsWiringTest {

    private fun source(relative: String): String = SourceFiles.main(relative)

    private fun body(text: String, signature: String): String = SourceFiles.body(text, signature)

    @Test
    fun theExitRowShowsTheObservedIpWithAnHonestFallback() {
        val connect = source("ui/qsn/QsConnectScreen.kt")
        assertTrue(connect.contains("R.string.qs_detail_exit"))
        assertTrue(connect.contains("state.exitIp ?: stringResource(R.string.qs_exit_unavailable)"))
    }

    @Test
    fun theExitIpComesOnlyFromTheObservedExternalLookup() {
        val refresh = body(source("ui/qsn/QsViewModel.kt"), "fun refreshExitIp()")
        assertTrue(refresh.contains("SpeedtestManager.getRemoteIPInfo()?.ipAddress"))
        assertTrue(refresh.contains("it.copy(exitIp = ip)"))
        // Never the origin server or the Cloudflare edge: those have their own rows.
        assertFalse("must not reuse the profile origin server", refresh.contains("profile.server"))
        assertFalse("must not reuse the Cloudflare edge", refresh.contains("qsEdgeIp"))
    }

    @Test
    fun aStopClearsTheExitIpSoItNeverLooksLive() {
        val running = body(source("ui/qsn/QsViewModel.kt"), "fun onCoreRunning(running: Boolean)")
        assertTrue(running.contains("if (running) refreshExitIp()"))
        assertTrue(running.contains("exitIp = null"))
    }

    @Test
    fun theConnectScreenShowsNoTransferStats() {
        val connect = source("ui/qsn/QsConnectScreen.kt")
        assertFalse(connect.contains("qs_detail_upload"))
        assertFalse(connect.contains("qs_detail_download"))
        assertFalse(connect.contains("qs_detail_speed"))
        assertFalse(connect.contains("state.traffic"))
    }

    @Test
    fun theViewModelNoLongerPollsForTraffic() {
        val vm = source("ui/qsn/QsViewModel.kt")
        assertFalse(vm.contains("MSG_QUERY_TRAFFIC"))
        assertFalse(vm.contains("TrafficUpdate"))
        assertFalse(vm.contains("TrafficSnapshot"))
        assertFalse(vm.contains("startTrafficPolling"))
    }

    @Test
    fun theStateNoLongerCarriesATrafficField() {
        val contract = source("ui/qsn/QsContract.kt")
        assertFalse(contract.contains("TrafficSnapshot"))
        assertFalse(contract.contains("val traffic:"))
    }

    @Test
    fun theNotificationStatsStillReadTheSharedSnapshot() {
        val notif = source("handler/NotificationManager.kt")
        assertTrue(notif.contains("CoreServiceManager.currentTrafficSnapshot()"))
    }
}