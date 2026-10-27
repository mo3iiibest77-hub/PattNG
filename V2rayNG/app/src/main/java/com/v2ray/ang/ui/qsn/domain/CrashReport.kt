package com.v2ray.ang.ui.qsn.domain

/**
 * Turns a recorded stack trace into the two lines that name the cause.
 *
 * Pure and Android-free so the formatting is unit tested against real traces
 * instead of being trusted.
 */
object CrashReport {

    /** How many characters of the trace the UI shows. */
    const val MAX_SUMMARY_CHARS = 400

    /** Lines the recorder writes above the trace itself. */
    private val HEADER_PREFIXES = listOf("thread:", "time:")

    /**
     * @return the exception line plus the first stack frame, or null when the
     *   trace is empty. A stack trace carries class and method names only, so
     *   this never contains a credential.
     */
    fun summary(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val payload = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line -> HEADER_PREFIXES.any { line.startsWith(it) } }
            .toList()
        if (payload.isEmpty()) return null
        // The throwable line is the one that names a class; the frames follow.
        val exception = payload.firstOrNull { line ->
            line.contains("Exception") || line.contains("Error") || line.contains("Throwable")
        } ?: payload.first()
        val frame = payload.firstOrNull { it.startsWith("at ") }
        val summary = if (frame == null) exception else exception + "\n" + frame
        return summary.take(MAX_SUMMARY_CHARS)
    }
}
