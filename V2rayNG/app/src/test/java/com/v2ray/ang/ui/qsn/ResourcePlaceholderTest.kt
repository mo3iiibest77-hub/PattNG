package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped string resources, checked as data.
 *
 * This is the resource side of the Connect crash: a message that is rendered
 * with one value must contain exactly one placeholder, and every locale must
 * agree about it. A translation that adds, drops or reorders a placeholder is
 * a runtime exception waiting for the user who selected that language, so it
 * fails here instead.
 */
class ResourcePlaceholderTest {

    private val specifier = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

    private fun resDir(): File {
        val candidates = buildList {
            // The module the test class was compiled into, found from the
            // class file itself: this does not depend on the working
            // directory the Gradle test worker happens to use.
            moduleDirFromClasspath()?.let { add(File(it, "src/main/res")) }
            add(File("src/main/res"))
            add(File("app/src/main/res"))
            add(File("V2rayNG/app/src/main/res"))
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: error("res directory not found from " + File(".").absolutePath)
    }

    /** Walks up from the compiled test class to the Gradle module directory. */
    private fun moduleDirFromClasspath(): File? {
        val location = ResourcePlaceholderTest::class.java
            .protectionDomain?.codeSource?.location ?: return null
        var dir = try {
            File(location.toURI())
        } catch (_: Exception) {
            return null
        }
        while (dir.parentFile != null) {
            if (File(dir, "src/main/res").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun readStrings(dir: File): Map<String, String> {
        val file = File(dir, "strings.xml")
        assertTrue("missing " + file.path, file.isFile)
        val text = file.readText()
        return Regex(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Placeholders in order, ignoring escaped percent signs. */
    private fun placeholders(value: String): List<String> =
        specifier.findAll(value.replace("%%", ""))
            .map { it.value }
            .filter { it != "%" }
            .toList()

    /** Every values directory that actually translates Quiet Storm NG. */
    private fun locales(): Map<String, Map<String, String>> {
        val root = resDir()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .mapNotNull { dir ->
                val file = File(dir, "strings.xml")
                if (!file.isFile) return@mapNotNull null
                val map = readStrings(dir)
                if (map.keys.none { it.startsWith("qs_") }) null else dir.name to map
            }
            .toMap()
            // The base locale lives in res/values, not in res itself.
            .plus("values" to readStrings(File(root, "values")))
    }

    @Test
    fun everyQuietStormStringIsTranslatedWithTheSamePlaceholders() {
        val locales = locales()
        val english = locales.getValue("values")
        val qsKeys = english.keys.filter { it.startsWith("qs_") }
        assertTrue("no qs_ strings found", qsKeys.isNotEmpty())

        locales.forEach { (name, map) ->
            qsKeys.forEach { key ->
                assertTrue(
                    "missing translation for " + key + " in " + name,
                    map.containsKey(key),
                )
                assertEquals(
                    "placeholder mismatch for " + key + " in " + name,
                    placeholders(english.getValue(key)),
                    placeholders(map.getValue(key)),
                )
            }
        }
    }

    @Test
    fun theFlowTemplatesHaveExactlyOnePlaceholderEach() {
        // QsFlowText supplies exactly one value to each of these; a second
        // placeholder would throw MissingFormatArgumentException at runtime,
        // and none would silently hide the IP or the attempt number.
        val templates = mapOf(
            "qs_using_saved_ip" to "%s",
            "qs_reconnecting" to "%d",
        )
        locales().forEach { (name, map) ->
            templates.forEach { (key, expected) ->
                val value = map[key] ?: return@forEach
                assertEquals(
                    "template " + key + " in " + name,
                    listOf(expected),
                    placeholders(value),
                )
            }
        }
    }

    @Test
    fun noQuietStormStringMixesIndexedAndPlainPlaceholders() {
        // Mixing styles makes the argument order ambiguous and is what breaks
        // when a translation is reordered.
        locales().forEach { (name, map) ->
            map.filterKeys { it.startsWith("qs_") }.forEach { (key, value) ->
                val specs = placeholders(value)
                val indexed = specs.count { it.contains('$') }
                assertTrue(
                    "mixed placeholder styles in " + key + " (" + name + "): " + specs,
                    indexed == 0 || indexed == specs.size,
                )
            }
        }
    }
}
