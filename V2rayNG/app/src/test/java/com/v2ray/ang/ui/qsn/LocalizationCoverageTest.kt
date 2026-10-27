package com.v2ray.ang.ui.qsn

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Quiet Storm NG string must exist in both offered languages.
 *
 * A missing Persian entry would silently fall back to English in the middle of
 * a Persian screen, which is exactly the kind of half-translated UI the
 * language control is supposed to prevent.
 */
class LocalizationCoverageTest {

    private val keyPattern = Regex("name=\"(qs_[a-z0-9_]+)\"")

    private fun resFile(relative: String): File {
        val candidates = listOf(
            File("src/main/res/" + relative),
            File("app/src/main/res/" + relative),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Cannot find " + relative + " from " + File(".").absolutePath)
    }

    private fun keys(relative: String): Set<String> =
        keyPattern.findAll(resFile(relative).readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `the english resource file is present`() {
        assertNotNull(resFile("values/strings.xml"))
    }

    @Test
    fun `every english quiet storm string has a persian translation`() {
        val english = keys("values/strings.xml")
        val persian = keys("values-fa/strings.xml")
        assertTrue("no qs_ strings found in the english file", english.isNotEmpty())
        val missing = english - persian
        assertTrue("missing persian strings: " + missing.sorted(), missing.isEmpty())
    }

    @Test
    fun `the persian file adds no key that english lacks`() {
        val english = keys("values/strings.xml")
        val persian = keys("values-fa/strings.xml")
        val extra = persian - english
        assertTrue("persian-only keys: " + extra.sorted(), extra.isEmpty())
    }
}
