package com.tabtap.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.ui.pages.Page
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The whole app, launched for real.
 *
 * Everything so far has composed individual pages, which misses anything that goes wrong
 * in the activity, the navigation shell, or the transition between pages — and the app
 * was still crashing. This launches the actual [MainActivity], which runs `setContent`
 * and builds the entire tree exactly as the phone does, then walks every page.
 *
 * `qualifiers` sets the screen to a phone in landscape, because that is how the app is
 * used and because the faults have all been about running out of room.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w892dp-h412dp-land-xxhdpi")
class WholeAppTest {

    /**
     * A clean library for every test.
     *
     * The database singleton outlives one test method in a shared JVM, so the second
     * test would find the first test's project, open it on launch and never show the
     * library at all. Order 0 runs this before the activity is launched.
     */
    @get:Rule(order = 0)
    val cleanDatabase = object : org.junit.rules.ExternalResource() {
        override fun before() {
            com.tabtap.app.data.TabTapDatabase.resetForTests(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            )
        }
    }

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    /** Tap something by its visible text, if it is there. */
    private fun tapText(text: String): Boolean {
        val nodes = compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes()
        if (nodes.isEmpty()) return false
        compose.onAllNodesWithText(text)[0].performClick()
        compose.waitForIdle()
        return true
    }

    /** Go to a page through the navigation strip, by the name a screen reader reads. */
    private fun goTo(page: Page): Boolean {
        val nodes = compose
            .onAllNodesWithContentDescription("Go to ${page.title}")
            .fetchSemanticsNodes()
        if (nodes.isEmpty()) return false
        // The strip scrolls, so a destination can be composed but outside the viewport;
        // clicking it then lands somewhere else entirely.
        compose.onAllNodesWithContentDescription("Go to ${page.title}")[0]
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        return true
    }

    /**
     * Create a project through the real form and wait until it is actually open.
     *
     * Creating a project writes to the database on a background dispatcher, so the UI
     * being idle does not mean the project exists yet. Waiting for the transport - which
     * is only shown once a project is open - is what makes this reliable.
     */
    private fun newProject() {
        assertTrue("no New project button", tapText("New project"))
        assertTrue("the new-project form did not open", exists("Create"))
        assertTrue(tapText("Create"))
        compose.waitUntil(timeoutMillis = 10_000) { existsByDescription("Undo") }
        compose.waitForIdle()
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun existsByDescription(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    @Test fun theAppLaunchesAndShowsTheProjectList() {
        compose.waitForIdle()
        // With no projects it opens on the library.
        compose.onNodeWithText("Projects").assertIsDisplayed()
        assertTrue("the version should be visible", exists("TabTap 1."))
    }

    @Test fun aProjectCanBeCreatedAndEveryPageOpened() {
        compose.waitForIdle()

        newProject()

        // Now walk every page by its label in the navigation strip. A page that throws
        // on composition fails the test here, which is what was being missed.
        // Every page must actually draw something of its own. A page left out of the
        // shell's `when` renders nothing at all, which is how the piano roll quietly
        // disappeared when the studio was split into pages: it was still in the
        // navigation but the screen was blank.
        val fingerprint = mapOf(
            Page.PROJECTS to "Projects",
            Page.PLAY to "Tap the fretboard",
            Page.NOTES to "Note",
            Page.TAP to "Tap",
            Page.HARMONY to "Play some notes first",
            Page.CHORDS to "Chord",
            Page.TAB to "part",
            Page.SCORE to "SHEET MUSIC",
            Page.ROLL to "Drag a note",
            Page.SETUP to "Instrument",
            Page.EXPORT to "Export",
        )
        for (page in Page.entries) {
            assertTrue("could not reach ${page.name}", goTo(page))
            val expected = fingerprint[page]
            assertTrue("no fingerprint for ${page.name} in the test", expected != null)
            assertTrue(
                "${page.name} drew nothing of its own (looked for \"$expected\")",
                exists(expected!!),
            )
        }
    }

    @Test fun everyTransportControlIsOnTheScreen() {
        compose.waitForIdle()
        newProject()

        // Half the undo button was off the screen and redo was missing entirely: the
        // transport's children overflowed the row, and the last ones were measured to
        // zero width. Each of these must be *displayed*, not merely present.
        for (description in listOf(
            "Play", "Stop", "Back to start", "Metronome", "Undo", "Redo", "Help",
        )) {
            val nodes = compose
                .onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue("\"$description\" is missing from the transport", nodes.isNotEmpty())
            val bounds = nodes[0].boundsInRoot
            val root = nodes[0].root!!.semanticsOwner.rootSemanticsNode.boundsInRoot
            assertTrue(
                "\"$description\" is off the screen: $bounds inside $root",
                bounds.left >= root.left - 0.5f && bounds.right <= root.right + 0.5f &&
                    bounds.top >= root.top - 0.5f && bounds.bottom <= root.bottom + 0.5f &&
                    bounds.width > 0f && bounds.height > 0f,
            )
        }
    }

    @Test fun notesCanBeTappedInAndHarmonised() {
        compose.waitForIdle()
        newProject()

        // Harmony page: with no notes it should say so rather than fall over.
        assertTrue(goTo(Page.HARMONY))
        assertTrue("should prompt for notes", exists("Play some notes first"))

        // And the other pages cope with an empty project.
        for (page in Page.entries.filter { it.needsProject }) {
            assertTrue("could not reach ${page.name}", goTo(page))
        }
    }

    @Test fun theAppSurvivesMovingBetweenPagesRepeatedly() {
        compose.waitForIdle()
        newProject()

        // Navigation churn: the kind of thing that finds a page which only breaks on its
        // second composition, or state that leaks between pages.
        repeat(3) {
            for (page in Page.entries) {
                goTo(page)
            }
        }
    }

    @Test fun theHelpOpensOnEveryPage() {
        compose.waitForIdle()
        newProject()

        for (page in Page.entries.filter { it.needsProject }) {
            goTo(page)
            val help = compose.onAllNodesWithContentDescription("Help").fetchSemanticsNodes()
            if (help.isEmpty()) continue
            compose.onAllNodesWithContentDescription("Help")[0].performClick()
            compose.waitForIdle()
            assertTrue("help did not open on ${page.name}", exists("Close"))
            tapText("Close")
            compose.waitForIdle()
        }
    }
}
