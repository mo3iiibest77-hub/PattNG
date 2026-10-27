package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Copy button's wiring, checked as source.
 *
 * The bug was not in a function's logic but in which function the icon called:
 * the clipboard icon was wired to the manual-duplicate action, so pressing
 * "copy" created a configuration. Android's clipboard itself needs an
 * instrumented test, but the wiring and the absence of storage writes are
 * ordinary source properties and are checked here, against the real files.
 */
class CopyButtonWiringTest {

    private fun source(relative: String): String = SourceFiles.main(relative)

    private fun body(text: String, signature: String): String = SourceFiles.body(text, signature)

    @Test
    fun theCopyIconCallsTheCopyAction() {
        val ui = source("ui/qsn/QsConfigScreen.kt")
        assertTrue(ui.contains("IconButton(onClick = onCopy)"))
        assertTrue(ui.contains("contentDescription = stringResource(R.string.qs_copy_config)"))
        assertFalse("the icon must not duplicate", ui.contains("IconButton(onClick = onDuplicate)"))
    }

    @Test
    fun theActivityWiresCopyToTheClipboardMethod() {
        val activity = source("ui/qsn/QuietStormActivity.kt")
        assertTrue(
            activity.contains("onCopyConfig = { qsViewModel.copyConfigToClipboard(it) }"),
        )
    }

    @Test
    fun copyUsesTheCanonicalSerialiserAndWritesNothing() {
        val copy = body(source("ui/qsn/QsViewModel.kt"), "fun copyConfigToClipboard(guid: String)")
        assertTrue(copy.contains("AngConfigManager.share2Clipboard"))
        assertFalse("copy must not persist a profile", copy.contains("encodeServerConfig"))
        assertFalse("copy must not duplicate", copy.contains("duplicateConfigToManual"))
        assertFalse("copy must not mint a guid", copy.contains("getUuid"))
        assertFalse("copy must not change the pin", copy.contains("setPinnedGuid"))
        assertFalse("copy must not change the selection", copy.contains("setSelectServer"))
    }

    @Test
    fun theManualDuplicateStaysASeparateLabelledAction() {
        val ui = source("ui/qsn/QsConfigScreen.kt")
        assertTrue(ui.contains("TextButton(onClick = onDuplicate"))
        assertTrue(ui.contains("R.string.qs_duplicate_manual"))
        assertTrue(source("ui/qsn/QsViewModel.kt").contains("fun duplicateConfigToManual(guid: String)"))
    }

    @Test
    fun theCanonicalSerialiserIsTheExportPath() {
        val manager = source("handler/AngConfigManager.kt")
        assertTrue(manager.contains("fun share2Clipboard(context: Context, guid: String): Int"))
        assertTrue(manager.contains("val conf = shareConfig(guid)"))
        assertTrue(manager.contains("Utils.setClipboard(context, conf)"))
    }
}
