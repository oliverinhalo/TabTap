package com.tabtap.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crash reporter.
 *
 * It exists because a report of "it still crashes" cannot be acted on. A report has to
 * name the exception and where it came from, survive the process dying, and be shown
 * once and then forgotten.
 */
@RunWith(AndroidJUnit4::class)
class CrashReporterTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun clean() = CrashReporter.clear(context)

    @Test fun aCrashIsRecordedWithEnoughToDiagnoseIt() {
        val error = IllegalStateException("the thing that went wrong")
        CrashReporter.write(context, Thread.currentThread(), error)

        val report = CrashReporter.lastReport(context)
        assertTrue("nothing was recorded", report != null)
        for (expected in listOf(
            "TabTap ",
            "IllegalStateException",
            "the thing that went wrong",
            "com.tabtap.app.CrashReporterTest",
            "Android ",
        )) {
            assertTrue("the report does not mention \"$expected\": $report", report!!.contains(expected))
        }
        assertTrue(
            "the summary is not the exception",
            CrashReporter.summarise(report!!).contains("the thing that went wrong"),
        )
    }

    @Test fun aReportIsShownOnceAndThenForgotten() {
        CrashReporter.write(context, Thread.currentThread(), RuntimeException("once"))
        assertTrue(CrashReporter.lastReport(context) != null)
        CrashReporter.clear(context)
        assertNull("the report outlived being dismissed", CrashReporter.lastReport(context))
    }

    @Test fun anUncaughtExceptionIsRecordedAndStillPassedOn() {
        var passedOn: Throwable? = null
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            CrashReporter.install(context)
            val error = IllegalArgumentException("thrown for real")
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), error)

            assertTrue(
                "the crash was not recorded",
                CrashReporter.lastReport(context)?.contains("thrown for real") == true,
            )
            assertTrue("the handler swallowed the crash", passedOn === error)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
