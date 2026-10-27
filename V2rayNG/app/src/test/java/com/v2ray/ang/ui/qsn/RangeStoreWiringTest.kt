package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range database has one writer and one reader.
 *
 * The reported defect was a split: discovery saved its results through one
 * component while the connection flow read another, so a successful scan could
 * leave the next connection with nothing to scan. These checks pin the single
 * canonical store, and pin the two things the background pass must never do:
 * write a profile or start a tunnel.
 */
class RangeStoreWiringTest {

    @Test
    fun discoveryPersistsEachQualifiedRangeThroughTheStore() {
        val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
        assertTrue(
            "a range must be saved the moment it qualifies",
            scanner.contains("QualifiedRangeStore.add(listOf(qualified))"),
        )
        assertFalse(
            "the scanner must not write the database itself",
            scanner.contains("saveQualifiedRanges"),
        )
    }

    @Test
    fun theBackgroundPassUsesTheSameStoreAndTouchesNoProfile() {
        val scheduler = SourceFiles.main("ui/connect/DiscoveryScheduler.kt")
        assertTrue(scheduler.contains("QualifiedRangeStore.add(outcome.qualified)"))
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
}
