package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real-delay and bulk-delete paths, checked as source.
 *
 * Both features are correct only if the right call is wired to the right
 * primitives: the delay button must reach the Xray core (never a bare
 * socket/ICMP probe), and the bulk delete must route through
 * [com.v2ray.ang.ui.qsn.domain.ConfigSelection.deletable] so a protected guid
 * can never be removed. Neither property is observable through a public API, so
 * the assertions read the real files.
 */
class DelayAndBulkDeleteWiringTest {

    private fun source(relative: String): String = SourceFiles.main(relative)

    private fun body(text: String, signature: String): String = SourceFiles.body(text, signature)

    @Test
    fun theDelayButtonCallsTheMeasurementAction() {
        val ui = source("ui/qsn/QsConfigScreen.kt")
        assertTrue(ui.contains("onTestDelay"))
    }

    @Test
    fun theActivityWiresDelayToTheViewModel() {
        val activity = source("ui/qsn/QuietStormActivity.kt")
        assertTrue(activity.contains("onTestDelay = { qsViewModel.measureRealDelay() }"))
    }

    @Test
    fun theMeasurementUsesTheXrayCoreNotABareProbe() {
        val measure = body(source("ui/qsn/QsViewModel.kt"), "fun measureRealDelay()")
        assertTrue(measure.contains("dataSource.sendMsg2TestService"))
        assertTrue(measure.contains("TestServiceMessage"))
        assertTrue(measure.contains("key = AppConfig.MSG_MEASURE_CONFIG_START"))
        assertTrue(measure.contains("serverGuids = guids"))
        assertTrue(measure.contains("onlyTcp = false"))
        assertFalse("the scanner must not fall back to an ICMP/TCP ping", measure.contains("ping"))
    }

    @Test
    fun theMeasurementClearsThenStoresPerGuid() {
        val measure = body(source("ui/qsn/QsViewModel.kt"), "fun measureRealDelay()")
        assertTrue(measure.contains("dataSource.cancelAllPing()"))
        assertTrue(measure.contains("dataSource.clearAllTestDelayResults(guids)"))
    }

    @Test
    fun theActivityWiresBulkDeleteToTheViewModel() {
        val activity = source("ui/qsn/QuietStormActivity.kt")
        assertTrue(activity.contains("onConfirmBulkDelete = { qsViewModel.confirmBulkDelete() }"))
        assertTrue(activity.contains("onRequestBulkDelete = { qsViewModel.requestBulkDelete() }"))
    }

    @Test
    fun theBulkDeleteProtectsTheActiveAndConnectedGuid() {
        val confirm = body(source("ui/qsn/QsViewModel.kt"), "fun confirmBulkDelete()")
        assertTrue(confirm.contains("ConfigSelection.deletable"))
        assertTrue(confirm.contains("MmkvManager.getSelectServer()"))
        assertTrue(confirm.contains("state.connectedGuid"))
    }

    @Test
    fun theBulkDeleteRemovesPerGuidAndClearsThePin() {
        val confirm = body(source("ui/qsn/QsViewModel.kt"), "fun confirmBulkDelete()")
        assertTrue(confirm.contains("MmkvManager.removeServer(guid)"))
        assertTrue(confirm.contains("if (QsPrefs.pinnedGuid() in deletable) QsPrefs.setPinnedGuid(null)"))
    }

    @Test
    fun selectAllOnlyTouchesVisibleRows() {
        val selectAll = body(source("ui/qsn/QsViewModel.kt"), "fun selectAllConfigs()")
        assertTrue(selectAll.contains("ConfigSelection.allVisibleGuids"))
    }
}