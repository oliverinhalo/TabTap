package com.tabtap.app.ui.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
) {
    PROJECTS("Projects", "Files", Icons.Default.FolderOpen, "projects", false, needsProject = false),
    PLAY("Play the notes in", "Play", Icons.Default.MusicNote, "fretboard", true),
    NOTES("Notes and rhythm", "Notes", Icons.AutoMirrored.Filled.QueueMusic, "rhythm", true),
    TAP("Tap the rhythm", "Tap", Icons.Default.TouchApp, "tap", true),
    HARMONY("Harmony", "Harmony", Icons.Default.Tune, "harmony", true),
    CHORDS("Chords", "Chords", Icons.Default.Piano, "chords", true),
    TAB("Tab", "Tab", Icons.Default.GridOn, "tab", true),
    SCORE("Score", "Score", Icons.Default.LibraryMusic, "score", true),
    SETUP("Instrument and key", "Setup", Icons.Default.Settings, "instrument", true),
    EXPORT("Export", "Export", Icons.Default.IosShare, "export", true),
    ;

    companion object {
        /** The page a project opens on. */
        val DEFAULT = PLAY
    }
}
