package com.v2ray.ang.ui.qsn

import java.io.File

/**
 * Reads production sources from the test JVM.
 *
 * A few guarantees are about wiring rather than about a value: which function a
 * button calls, which store a writer writes to, which component is allowed to
 * touch the profile. None of them can be observed through a public API from a
 * plain JVM test, so they are checked against the real files. The module
 * directory is found by walking up from the compiled test classes, so the test
 * does not depend on the working directory Gradle happens to use.
 */
internal object SourceFiles {

    private val moduleDir: File by lazy {
        val location = SourceFiles::class.java.protectionDomain?.codeSource?.location
        var dir = if (location != null) File(location.toURI()) else File(".")
        while (dir.parentFile != null) {
            if (File(dir, "src/main/java").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("module directory not found from " + File(".").absolutePath)
    }

    /** The text of a main-source file, given its path under `com/v2ray/ang/`. */
    fun main(relative: String): String =
        File(moduleDir, "src/main/java/com/v2ray/ang/" + relative).readText()

    /** The text of one function, from its signature to its closing brace. */
    fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "missing " + signature }
        val end = text.indexOf("\n    }\n", start)
        check(end > start) { "unterminated " + signature }
        return text.substring(start, end)
    }
}
