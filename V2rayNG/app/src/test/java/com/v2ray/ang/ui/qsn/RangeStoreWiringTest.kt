package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range database has one writer and one reader, and the two stages are
 * independent.
 *
 * The reported defects were a split store (discovery wrote one place while the
 * connection flow read another), an early stop that hid unfinished ranges, and
 * Stage 2 cancelling a Stage 1 run. These checks pin the current wiring.
 */
class RangeStoreWiringTest {

    @Test
    fun discoveryPersistsEachResultAndEachGoodRangeThroughTheStore() {
        val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
        assertTrue(
            "every final range result must be written immediately",
            scanner.contains("IspManager.recordOutcome(context, isp, outcome)"),
        )
        assertTrue(
            "a qualified range must reach the canonical candidate store",
            scanner.contains("QualifiedRangeStore.add(listOf(range))"),
        )
        assertFalse(
            "the scanner must not write the database itself",
            scanner.contains("saveQualifiedRanges"),
        )
    }

    @Test
    fun theBackgroundPassDelegatesToTheScannerAndTouchesNoProfile() {
        val scheduler = SourceFiles.main("ui/connect/DiscoveryScheduler.kt")
        assertTrue(scheduler.contains("CloudflareScanner.discoverRanges("))
        listOf(
            "encodeServerConfig",
            "setSelectServer",
            "qsEdgeIp",
            "saveSelection",
            "startService",
        ).forEach { forbidden ->
            assertFalse(
                "background discovery must not call " + forbidden,
                scheduler.contains(forbidden),
            )
        }
    }

    @Test
    fun theConnectionFlowReadsTheStoreInsteadOfThePreferences() {
        val orchestrator = SourceFiles.main("ui/connect/ConnectionOrchestrator.kt")
        assertTrue(orchestrator.contains("QualifiedRangeStore.snapshot()"))
        assertFalse(orchestrator.contains("QsPrefs.loadQualifiedRanges()"))
    }

    @Test
    fun theStoreMergesAndIsTheOnlyWriter() {
        val store = SourceFiles.main("ui/qsn/data/QualifiedRangeStore.kt")
        assertTrue(store.contains("RangeSet.merge("))
        assertTrue(store.contains("QsPrefs.saveQualifiedRanges(merged)"))
        listOf(
            "ui/connect/ConnectionOrchestrator.kt",
            "ui/connect/DiscoveryScheduler.kt",
            "senpai/CloudflareScanner.kt",
        ).forEach { file ->
            assertFalse(
                file + " writes the database directly",
                SourceFiles.main(file).contains("saveQualifiedRanges"),
            )
        }
    }

    @Test
    fun theScreenAndTheStoreShareOneStateSource() {
        val viewModel = SourceFiles.main("ui/qsn/QsViewModel.kt")
        assertTrue(
            "the screen reads the run the scanner publishes",
            viewModel.contains("CloudflareScanner.discovery.collect"),
        )
        assertTrue(
            "the displayed list comes from the canonical store",
            viewModel.contains("QualifiedRangeStore.snapshot()"),
        )
    }

    @Test
    fun stageTwoDoesNotCancelDiscovery() {
        val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
        val cancel = SourceFiles.body(scanner, "fun cancel() {")
        assertTrue("Stage 2 cancel must only stop Stage 2", cancel.contains("cancelScan()"))
        assertFalse("Stage 2 cancel must not touch the discovery job", cancel.contains("discoveryJob"))

        val scanRanges = SourceFiles.body(scanner, "fun scanRanges(")
        assertTrue(scanRanges.contains("cancelScan()"))
        assertFalse(scanRanges.contains("discoveryJob.cancel"))
        assertFalse(scanRanges.contains("stopDiscovery"))
    }

    @Test
    fun stageTwoReadsTheSelectedIspsStoredRanges() {
        val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
        val scanForIsp = SourceFiles.body(scanner, "fun scanForIsp(")
        assertTrue(scanForIsp.contains("IspManager.getProfile(context, ispName)"))
        assertTrue(scanForIsp.contains("profile?.goodCidrs"))
        assertFalse(
            "an ISP scan must not silently fall back to unrelated ranges",
            scanForIsp.contains("scan(context"),
        )
    }

    @Test
    fun discoveryHasNoTargetOrHiddenRangeLimit() {
        val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
        val discovery = SourceFiles.body(scanner, "fun discoverRanges(")
        assertFalse(discovery.contains("TARGET_RANGES"))
        assertFalse(discovery.contains(".take(30)"))
        assertTrue(
            "the engine measures every range without a final result",
            scanner.contains("DiscoveryEngine("),
        )
    }
}

