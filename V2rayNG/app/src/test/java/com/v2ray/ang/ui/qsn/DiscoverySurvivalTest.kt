package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a discovery run lives, and what survives an app close.
 *
 * The measurements are owned by [com.v2ray.ang.senpai.CloudflareScanner] and
 * persisted per range, so the question this file pins down is the hosting one:
 * a foreground service keeps the process alive while the run measures, it never
 * becomes a second owner of the run, and it is the only component allowed to
 * start a run again after the system killed the process. An explicit
 * force-stop is outside what any Android app can survive, so the run is then
 * left resumable instead of being silently restarted.
 *
 * These are wiring guarantees, not values, so they are checked against the real
 * sources. Nothing here needs a device: the Android behaviour they rely on is
 * documented platform behaviour, not something a JVM test can observe.
 */
class DiscoverySurvivalTest {

    private val service = SourceFiles.main("service/CfScanService.kt")
    private val scanner = SourceFiles.main("senpai/CloudflareScanner.kt")
    private val manager = SourceFiles.main("senpai/IspManager.kt")
    private val viewModel = SourceFiles.main("ui/qsn/QsViewModel.kt")
    private val prefs = SourceFiles.main("ui/qsn/data/QsPrefs.kt")
    private val manifest = SourceFiles.file("src/main/AndroidManifest.xml")

    /** The text of one function, from its signature to its matching brace. */
    private fun function(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "missing " + signature }
        var depth = 0
        var i = text.indexOf('{', start)
        check(i >= 0) { "no body for " + signature }
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        error("unterminated " + signature)
    }

    @Test
    fun theRunIsHostedByADeclaredForegroundService() {
        assertTrue(service.contains("class CfScanService : Service()"))
        val block = manifest.substring(
            manifest.indexOf("android:name=\".service.CfScanService\""),
            manifest.indexOf("</service>", manifest.indexOf("android:name=\".service.CfScanService\"")),
        )
        assertTrue("a long measurement needs a foreground service", block.contains("specialUse"))
        assertTrue(
            "targetSdk 34+ requires the special-use subtype to be declared",
            block.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
        )
        assertFalse("the service must outlive the task", block.contains("stopWithTask=\"true\""))
    }

    @Test
    fun hostModeNeverStartsASecondRun() {
        val host = function(service, "private fun host()")
        assertFalse("hosting must not own the run", host.contains("discoverRanges("))
        assertTrue(host.contains("CloudflareScanner.isDiscoveryActive"))
        assertTrue("an empty host must not keep a notification", host.contains("stopSelf()"))
    }

    @Test
    fun theHostWatchesTheRunsRealState() {
        val observe = function(service, "private fun observeDiscovery()")
        assertTrue(observe.contains("CloudflareScanner.discovery.collect"))
        assertTrue("the notification is updated from the run itself", observe.contains("updateNotif("))
        assertTrue("a run that ends must end the notification", observe.contains("stopSelf()"))
    }

    @Test
    fun aStoppedRunIsNotResumedByASystemRestart() {
        val resume = function(service, "private fun resumeAfterRestart()")
        assertTrue(resume.contains("DiscoveryPhase.Running"))
        assertTrue(resume.contains("DiscoveryPhase.Interrupted"))
        assertFalse("a user stop is a decision, not an interruption", resume.contains("DiscoveryPhase.Paused"))
        assertFalse("a resume continues, it never forgets results", resume.contains("force = true"))
    }

    @Test
    fun onlyTheHostingModesSurviveASystemKill() {
        assertTrue(function(service, "private fun host()").contains("return START_STICKY"))
        assertTrue(function(service, "private fun resumeAfterRestart()").contains("return START_STICKY"))
        assertFalse(
            "the legacy broadcast runner keeps its old contract",
            function(service, "private fun runLegacyDiscovery(").contains("START_STICKY"),
        )
    }

    @Test
    fun destroyingTheServiceDoesNotAbortTheRun() {
        // The service does not own the run. If Android destroys it (or the
        // process is going away) the run must not be cancelled from here: the
        // explicit stop paths are the only ones that end a measurement.
        val destroy = function(service, "override fun onDestroy()")
        assertFalse(destroy.contains("CloudflareScanner.stopDiscovery()"))
        assertFalse(destroy.contains("CloudflareScanner.cancel()"))
        assertTrue("the observer itself is released", destroy.contains("observer?.cancel()"))
    }

    @Test
    fun theNotificationStopEndsOnlyTheRunItHosts() {
        val start = function(service, "override fun onStartCommand(")
        assertTrue(start.contains("MODE_SCAN -> CloudflareScanner.cancel()"))
        assertTrue(start.contains("else -> CloudflareScanner.stopDiscovery()"))
    }

    @Test
    fun theHostRemembersWhatItWasMeasuring() {
        val host = function(service, "fun hostDiscovery(")
        assertTrue(host.contains("QsPrefs.setDiscoveryTarget("))
        val resume = function(service, "private fun resumeAfterRestart()")
        assertTrue(resume.contains("QsPrefs.discoveryTarget()"))
    }

    @Test
    fun theStoredTargetIsAnIdentifierPairOnly() {
        val setter = function(prefs, "fun setDiscoveryTarget(")
        assertTrue(setter.contains(".put(\"isp\""))
        assertTrue(setter.contains(".put(\"guid\""))
        assertFalse("no credential may reach this key", setter.contains("password"))
        assertTrue("storage goes through the shared settings file", setter.contains("MmkvManager.encodeSettings"))
    }

    @Test
    fun progressIsWrittenRangeByRange() {
        // This is what bounds the loss of a force-stop: everything already
        // measured is on disk before the next range starts.
        val record = function(scanner, "override fun record(isp: String, outcome: RangeOutcome)")
        assertTrue(record.contains("IspManager.recordOutcome("))
        val write = function(manager, "fun recordOutcome(")
        assertTrue("each result is persisted immediately", write.contains("write(all)"))
    }

    @Test
    fun theScreenHandsTheRunToTheServiceOnlyAfterItStarts() {
        val start = SourceFiles.body(viewModel, "fun startDiscovery(")
        val began = start.indexOf("CloudflareScanner.discoverRanges(")
        val hosted = start.indexOf("hostDiscoveryService(target.first, isp)")
        assertTrue("the run must start first", began in 0 until hosted)
    }

    @Test
    fun aSecondStartCannotCreateASecondRun() {
        val start = SourceFiles.body(viewModel, "fun startDiscovery(")
        val guard = start.indexOf("CloudflareScanner.isDiscoveryActive")
        val began = start.indexOf("CloudflareScanner.discoverRanges(")
        assertTrue("the single-flight check comes first", guard in 0 until began)
        val starts = start.split("CloudflareScanner.discoverRanges(").size - 1
        assertEquals("startDiscovery has exactly one start path", 1, starts)
    }

    @Test
    fun aStopKeepsEveryCompletedRange() {
        val stop = SourceFiles.body(viewModel, "fun stopDiscovery()")
        assertTrue(stop.contains("CloudflareScanner.stopDiscovery()"))
        assertFalse("stopping is not forgetting", stop.contains("resetDiscovery"))
        assertFalse(stop.contains("clearSelection"))
    }
}