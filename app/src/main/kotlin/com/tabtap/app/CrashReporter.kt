package com.tabtap.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers the last crash so it can be read, rather than only guessed at.
 *
 * TabTap has no network permission and sends nothing anywhere: the report is written to
 * the app's own storage and shown the next time the app starts, with a button to share
 * it. That is the only way a crash on someone else's phone can be diagnosed at all - a
 * report of "it still crashes" cannot be acted on, and a stack trace can.
 *
 * The handler does as little as possible and always passes the throwable on to the
 * handler that was already installed, so the usual system behaviour is unchanged.
 */
object CrashReporter {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report from the last crash, or null if the last run ended normally. */
    fun lastReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        return if (file.isFile) file.readText().ifBlank { null } else null
    }

    /** Called once the report has been read or shared. */
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }

    /** Exposed for tests and for recording a non-fatal fault worth knowing about. */
    fun write(context: Context, thread: Thread?, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val report = buildString {
            appendLine("TabTap ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("$when_  ·  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            if (thread != null) appendLine("thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(context.applicationContext.filesDir, FILE).writeText(report)
    }

    /** The first line of a report: enough to show in a banner. */
    fun summarise(report: String): String =
        report.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()
            ?.take(160)
            ?: report.lineSequence().firstOrNull()?.trim().orEmpty()
}
