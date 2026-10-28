package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership and lifecycle of a discovery run.
 *
 * A screen is a viewer, not the owner: it must not start a second run on
 * recreation, must not cancel the run when it goes away, and must show the
 * persisted state when it comes back. These checks pin the real wiring.
 */
class DiscoveryLifecycleTest {

    private val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
    private val viewModel = SourceFiles.main("ui/qsn/QsViewModel.kt")
    private val orchestrator = SourceFiles.main("ui/connect/ConnectionOrchestrator.kt")

    @Test
    fun discoveryHasItsOwnJobSeparateFromStageTwo() {
        assertTrue(scanner.contains("private var discoveryJob = SupervisorJob()"))
        assertTrue(scanner.contains("private var scanJob = SupervisorJob()"))
        val start = SourceFiles.body(scanner, "fun discoverRanges(")
        assertTrue(start.contains("discoveryScope = CoroutineScope(discoveryJob"))
    }

    @Test
    fun theConnectionFlowDoesNotStopDiscovery() {
        assertFalse(
            "starting Stage 2 must not cancel Stage 1",
            orchestrator.contains("DiscoveryScheduler.stop()"),
        )
        assertFalse(
            "the orchestrator must not cancel the discovery job",
            orchestrator.contains("stopDiscovery()"),
        )
    }

    @Test
    fun theScreenDoesNotOwnTheRun() {
        assertFalse(
            "the ViewModel must not cancel discovery in its own scope",
            viewModel.contains("viewModelScope.launch { CloudflareScanner.discoverRanges"),
        )
        assertTrue(
            "the screen reads the scanner's persisted/live state",
            viewModel.contains("CloudflareScanner.discovery.collect"),
        )
        assertTrue(
            "re-entry reconciles a run whose process died",
            viewModel.contains("CloudflareScanner.reconcileDiscovery("),
        )
    }

    @Test
    fun theRunStateAndResultsArePersisted() {
        val manager = SourceFiles.main("senpai/IspManager.kt")
        assertTrue(manager.contains("qsn_isp_profiles_v2"))
        assertTrue(manager.contains("MmkvManager.decodeSettingsString"))
        assertTrue(manager.contains("MmkvManager.encodeSettings"))
        assertTrue(manager.contains("fun recordOutcome("))
        assertTrue(manager.contains("fun setPhase("))
        assertTrue(manager.contains("fun markInterrupted("))
        assertTrue(manager.contains("LEGACY_PREF_FILE"))
    }

    @Test
    fun aCompletedIspIsReusedInsteadOfRediscovered() {
        val start = SourceFiles.body(scanner, "fun discoverRanges(")
        assertTrue(start.contains("existing.isComplete"))
        assertTrue(start.contains("reusing stored ranges"))
    }

    @Test
    fun discoveryStopsOnlyWhenTheUserAsksOrTheRunEnds() {
        val stop = SourceFiles.body(scanner, "fun stopDiscovery()")
        assertTrue(stop.contains("discoveryJob.cancel()"))
        assertTrue(stop.contains("DiscoveryPhase.Paused"))
        assertFalse("stop must not touch Stage 2", stop.contains("scanJob"))
    }
}

