package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring between the Discovery UI, the engine and the connection flow.
 *
 * These are not value tests: they pin which component reads which state and
 * which one is allowed to start, stop or persist a run. That is exactly where
 * the reported defects lived (a button that started nothing, a run that ended
 * after a few ranges, a screen that owned the job).
 */
class DiscoveryScreenWiringTest {

    private val screen = SourceFiles.main("ui/qsn/QsDiscoveryScreen.kt")
    private val activity = SourceFiles.main("ui/qsn/QuietStormActivity.kt")
    private val rangesScreen = SourceFiles.main("ui/qsn/QsRangesScreen.kt")
    private val viewModel = SourceFiles.main("ui/qsn/QsViewModel.kt")
    private val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
    private val orchestrator = SourceFiles.main("ui/connect/ConnectionOrchestrator.kt")

    @Test
    fun theDiscoveryScreenShowsTheAddressesBeingMeasured() {
        assertTrue(
            "the screen must read the live sample addresses",
            screen.contains("status.measuring"),
        )
        assertTrue(
            "each measuring row must show the CIDR and its IPs",
            screen.contains("R.string.qs_discovery_measuring_row"),
        )
        assertTrue(
            "the screen must list every range with its own verdict",
            screen.contains("DiscoveryRangeRow("),
        )
    }

    @Test
    fun theDiscoveryScreenOnlyReadsStateAndAsksToStop() {
        assertFalse(
            "the screen must not start or cancel a run in its own scope",
            screen.contains("CloudflareScanner.") || screen.contains("CoroutineScope"),
        )
    }

    @Test
    fun theActivityShowsTheDiscoveryScreenAndCanCloseIt() {
        assertTrue(activity.contains("QsDiscoveryScreen("))
        assertTrue(activity.contains("closeDiscovery()"))
        assertTrue(activity.contains("state.discoveryOpen"))
    }

    @Test
    fun startingDiscoveryOpensTheScreenAndRefusesASecondRun() {
        val body = SourceFiles.body(viewModel, "fun startDiscovery(")
        assertTrue(
            "a start must open the live view",
            body.contains("discoveryOpen = true"),
        )
        assertTrue(
            "a second run must be refused while one is active",
            body.contains("CloudflareScanner.isDiscoveryActive"),
        )
        assertTrue(
            "the run must be started on the scanner, not in the ViewModel",
            body.contains("CloudflareScanner.discoverRanges("),
        )
    }

    @Test
    fun manualRangesAreValidatedAndStoredForTheSelectedIsp() {
        val add = SourceFiles.body(viewModel, "fun addManualRange(")
        assertTrue(add.contains("ManualRangePolicy.classify("))
        assertTrue(add.contains("IspManager.addManualCidr("))
        val remove = SourceFiles.body(viewModel, "fun removeManualRange(")
        assertTrue(remove.contains("IspManager.removeManualCidr("))
    }

    @Test
    fun theManualCardHasItsOwnTitleAndAddButton() {
        assertTrue(rangesScreen.contains("R.string.qs_manual_title"))
        assertTrue(rangesScreen.contains("R.string.qs_manual_add"))
        assertTrue(rangesScreen.contains("R.string.qs_manual_remove"))
        assertTrue(
            "a manual range must be removable from the list",
            rangesScreen.contains("ic_delete_24dp"),
        )
    }

    @Test
    fun theFirstGoodRangeUnblocksAConnectionWithoutEndingTheRun() {
        assertTrue(
            "the gate must be completed on a real Good result",
            scanner.contains("firstGood?.complete(range)"),
        )
        assertTrue(
            "a waiter must be released when the run ends without a good range",
            scanner.contains("firstGood?.complete(null)"),
        )
        assertTrue(scanner.contains("awaitFirstQualifiedRange"))
        val flow = SourceFiles.body(orchestrator, "private suspend fun resolveEdgeIp(")
        assertTrue(
            "a first connection waits for one range, not the whole dataset",
            flow.contains("awaitFirstQualifiedRange(FIRST_RANGE_TIMEOUT_MS)"),
        )
        assertFalse(
            "starting a connection must never stop discovery",
            flow.contains("stopDiscovery()"),
        )
    }

    @Test
    fun stageTwoMeasuresOnlyTheSelectedRangesOfTheIsp() {
        assertTrue(orchestrator.contains("Stage2Ranges.resolve("))
        assertTrue(orchestrator.contains("QsPrefs.selectedRanges("))
        val scan = SourceFiles.body(orchestrator, "suspend fun scanOnly(")
        assertTrue(scan.contains("ispProfile.goodCidrs"))
        assertTrue(scan.contains("ispProfile.manualCidrs"))
    }
}
