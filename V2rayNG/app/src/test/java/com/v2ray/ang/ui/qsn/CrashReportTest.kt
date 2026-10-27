package com.v2ray.ang.ui.qsn

import com.v2ray.ang.ui.qsn.domain.CrashReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash summary is the only thing that makes a process death explainable,
 * so its extraction is tested against real trace shapes.
 */
class CrashReportTest {

    private val nativeTrace = """
        thread: main
        time: 1761540000000
        java.lang.UnsatisfiedLinkError: dlopen failed: library "libgojni.so" not found
        	at java.lang.Runtime.loadLibrary0(Runtime.java:1082)
        	at com.v2ray.ang.senpai.CloudflareScanner.testCandidate(CloudflareScanner.kt:480)
    """.trimIndent()

    @Test
    fun keepsTheExceptionLineAndTheFirstFrame() {
        val summary = CrashReport.summary(nativeTrace)
        assertEquals(
            "java.lang.UnsatisfiedLinkError: dlopen failed: library \"libgojni.so\" not found\n" +
                "at java.lang.Runtime.loadLibrary0(Runtime.java:1082)",
            summary,
        )
    }

    @Test
    fun ignoresTheRecorderHeaderLines() {
        val summary = CrashReport.summary(nativeTrace).orEmpty()
        assertTrue(summary.isNotBlank())
        assertTrue(!summary.contains("thread:"))
        assertTrue(!summary.contains("time:"))
    }

    @Test
    fun returnsNullWhenNothingWasRecorded() {
        assertNull(CrashReport.summary(null))
        assertNull(CrashReport.summary(""))
        assertNull(CrashReport.summary("   \n  "))
    }

    @Test
    fun fallsBackToTheFirstPayloadLineWithoutAnExceptionWord() {
        val summary = CrashReport.summary("thread: main\ntime: 1\nsomething odd happened")
        assertEquals("something odd happened", summary)
    }

    @Test
    fun capsTheSummarySoTheCardStaysOneBlock() {
        val long = "java.lang.RuntimeException: " + "x".repeat(2_000)
        val summary = CrashReport.summary(long).orEmpty()
        assertTrue(summary.length <= CrashReport.MAX_SUMMARY_CHARS)
    }
}
