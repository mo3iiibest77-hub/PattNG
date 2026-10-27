package com.v2ray.ang.ui.qsn.data

import android.content.Context
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the last uncaught exception of the app process.
 *
 * A process that dies from a native failure, an [Error] or a system kill leaves
 * nothing behind, which is why "the app just closes" could not be diagnosed.
 * This keeps the cause on disk so the next launch can show it and the user can
 * copy it.
 *
 * It deliberately does not swallow the crash: the previous handler still runs,
 * so behaviour is unchanged apart from the recorded file. Nothing here can
 * throw into the crashing path.
 */
object QsCrashRecorder {

    private const val TAG = "QsCrashRecorder"
    private const val FILE_NAME = "qs_last_crash.txt"

    /** Cap so a runaway trace cannot grow the file without bound. */
    private const val MAX_CHARS = 8_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(app, thread, throwable)
            } catch (_: Throwable) {
                // Recording must never replace the real crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The last recorded trace, or null when nothing was recorded. */
    fun readLast(context: Context): String? = try {
        val file = fileOf(context)
        if (!file.exists()) null else file.readText().takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            fileOf(context).delete()
        } catch (_: Throwable) {
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
        val text = buildString {
            append("thread: ").append(thread.name).append('\n')
            append("time: ").append(System.currentTimeMillis()).append('\n')
            append(stack)
        }
        fileOf(context).writeText(text.take(MAX_CHARS))
        LogUtil.e(TAG, "Uncaught exception recorded: " + throwable.javaClass.name)
    }

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)
}
