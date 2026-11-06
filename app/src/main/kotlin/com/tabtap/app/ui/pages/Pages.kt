package com.tabtap.app.ui.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's pages: one job each.
 *
 * The studio used to stack the fretboard, a workflow bar, an entry toolbar, a view
 * switcher and an editor into a single column. On a phone in landscape — which is how
 * this app is actually used — there is around 360dp of height, and the fretboard alone
 * wanted 310dp of it, so everything underneath was pushed off the screen and could not
 * be reached.
 *
 * Each page now does one thing, fits the height it is given, and scrolls if it cannot.
 * Nothing is stacked behind anything else.
 */
enum class Page(
    val title: String,
    /** A shorter label, for the narrow rail. */
    val shortTitle: String,
    val icon: ImageVector,
    /** The help topic this page opens on. */
    val helpTopic: String,
    /** Whether the transport bar belongs on this page. */
    val showTransport: Boolean,
    /** Whether the page needs an open project. */
    val needsProject: Boolean = true,
    /**
     * Where this page sits in the order of work, shown as a number in the rail.
     *
     * The app does not force an order, but there is one that makes sense - notes, then
     * rhythm, then harmony, then reading and exporting it - and a player has no way to
     * guess it from nine icons. Pages that are not a step in that sequence have none.
     */
    val step: Int? = null,
) {
    PROJECTS("Projects", "Files", Icons.Default.FolderOpen, "projects", false, needsProject = false),
    PLAY("Play the notes in", "Play", Icons.Default.MusicNote, "fretboard", true, step = 1),
    NOTES(
        "Notes and rhythm", "Notes", Icons.AutoMirrored.Filled.QueueMusic, "rhythm", true,
        step = 2,
    ),
    TAP("Tap the rhythm", "Tap", Icons.Default.TouchApp, "tap", true, step = 3),
    HARMONY("Harmony", "Harmony", Icons.Default.Tune, "harmony", true, step = 4),
    CHORDS("Chords", "Chords", Icons.Default.Piano, "chords", true, step = 5),
    TAB("Tab", "Tab", Icons.Default.GridOn, "tab", true, step = 6),
    SCORE("Score", "Score", Icons.Default.LibraryMusic, "score", true, step = 7),
    ROLL("Piano roll", "Roll", Icons.Default.Album, "pianoroll", true),
    SETUP("Instrument and key", "Setup", Icons.Default.Settings, "instrument", true),
    EXPORT("Export", "Export", Icons.Default.IosShare, "export", true, step = 8),
    ;

    companion object {
        /** The page a project opens on. */
        val DEFAULT = PLAY
    }
}
