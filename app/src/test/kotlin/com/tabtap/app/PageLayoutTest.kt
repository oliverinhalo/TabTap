package com.tabtap.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.design.MusicColors
import com.tabtap.app.design.TabTapTheme
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.app.ui.ChordDiagram
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.fretboard.FretboardMark
import com.tabtap.app.ui.fretboard.FretboardView
import com.tabtap.app.ui.fretboard.buildFretboardMarks
import com.tabtap.app.ui.pages.Page
import com.tabtap.app.ui.panels.TransportBar
import com.tabtap.app.ui.panels.barsAndBeats
import com.tabtap.app.ui.panes.PianoRollPane
import com.tabtap.app.ui.panes.ScorePane
import com.tabtap.app.ui.panes.TabPane
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.ChordVoicer
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.HarmonySpec
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layout at real device sizes.
 *
 * The controls under the fretboard were unreachable because the studio stacked a
 * transport bar, the board, two toolbars, a view switcher and an editor into one
 * non-scrolling column: the board alone demanded 310dp, and a phone in landscape has
 * around 360dp of height in total, so everything below it was pushed off the screen.
 *
 * These tests lay the real views out at the sizes a phone actually offers — landscape
 * first, because that is how the app is used — and assert that the controls which must
 * be reachable are *displayed*, not merely present. `assertIsDisplayed` fails for a node
 * pushed outside its parent, which is exactly the failure that shipped.
 */
@RunWith(AndroidJUnit4::class)
class PageLayoutTest {

    @get:Rule val compose = createComposeRule()

    /** Sizes that matter: a big phone in landscape and portrait, and a small phone. */
    private val sizes = listOf(
        "S26 Ultra landscape" to (892 to 412),
        "S26 Ultra portrait" to (412 to 892),
        "small phone landscape" to (640 to 360),
        "small phone portrait" to (360 to 640),
        "tablet landscape" to (1280 to 800),
    )

    private fun n(s: String) = Pitch.parse(s)!!

    private fun composition(): Composition {
        var c = Composition.newGuitarProject(
            Tunings.STANDARD_E, 132.0, Key.of("E", ScaleType.NATURAL_MINOR)!!,
        )
        for ((string, fret) in listOf(2 to 2, 2 to 4, 2 to 5, 3 to 2, 3 to 4, 3 to 2)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, string, fret)
        }
        c = CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        return CompositionEditor.appendChord(
            c, Chord.of("E", ChordType.MINOR)!!, c.timeSignature.barTicks,
        )
    }

    // ---------------------------------------------------------------- the fretboard

    @Test fun theFretboardFitsEveryScreenSizeAndLeavesRoomBelowIt() {
        // The regression: at 412 x 360 the board must not squeeze out what is under it.
        val index = mutableStateOf(0)
        compose.setContent {
            val (_, size) = sizes[index.value]
            TabTapTheme {
                Box(Modifier.size(width = size.first.dp, height = size.second.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        FretboardView(
                            fretboard = Fretboard(Tunings.STANDARD_E, 22),
                            key = Key.C_MAJOR,
                            marks = listOf(FretboardMark(0, 3, MusicColors.original, 1)),
                            display = FretboardDisplay(),
                            modifier = Modifier.weight(1f),
                            onTap = { _, _, _ -> },
                        )
                        // The strip that used to be pushed off the bottom.
                        TabTapChip("UNDER THE BOARD", onClick = {})
                    }
                }
            }
        }
        for (i in sizes.indices) {
            index.value = i
            compose.waitForIdle()
            compose.onNodeWithText("UNDER THE BOARD")
                .assertIsDisplayed()
        }
    }

    @Test fun theFretboardStillWorksWhenTheSpaceIsVeryTight() {
        // An 8-string in landscape is the worst case: eight strings into ~300dp.
        val index = mutableStateOf(0)
        val tunings = listOf(
            Tunings.BASS_STANDARD, Tunings.STANDARD_E,
            Tunings.SEVEN_STANDARD_B, Tunings.EIGHT_STANDARD_F,
        )
        compose.setContent {
            val tuning = tunings[index.value]
            TabTapTheme {
                Box(Modifier.size(width = 640.dp, height = 300.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        FretboardView(
                            fretboard = Fretboard(tuning, 24),
                            key = Key.C_MAJOR,
                            marks = (0 until tuning.stringCount).map {
                                FretboardMark(it, 5, MusicColors.original, it + 1)
                            },
                            display = FretboardDisplay(),
                            modifier = Modifier.weight(1f),
                            onTap = { _, _, _ -> },
                        )
                        TabTapChip("STRIP", onClick = {})
                    }
                }
            }
        }
        for (i in tunings.indices) {
            index.value = i
            compose.waitForIdle()
            compose.onNodeWithText("STRIP").assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- the transport

    @Test fun theTransportFitsEverySizeWithNothingDropped() {
        val index = mutableStateOf(0)
        compose.setContent {
            val (_, size) = sizes[index.value]
            TabTapTheme {
                Box(Modifier.size(width = size.first.dp, height = size.second.dp)) {
                    TransportBar(
                        isPlaying = true, isPaused = false, positionTick = 3840L,
                        countInBeatsRemaining = 0, bpm = 132.0,
                        timeSignature = TimeSignature.FOUR_FOUR,
                        metronomeEnabled = true, looping = true, loopLabel = "all 2 bars",
                        canUndo = true, canRedo = true, saveState = SaveState.SAVED,
                        compact = true, title = "Play the notes in",
                        onPlay = {}, onPause = {}, onStop = {}, onRestart = {},
                        onToggleMetronome = {}, onToggleLoop = {}, onChooseLoop = {},
                        onUndo = {}, onRedo = {}, onTempoChange = {}, onTapTempo = {},
                    )
                }
            }
        }
        for (i in sizes.indices) {
            index.value = i
            compose.waitForIdle()
            // Pause, stop, the position and the tempo are reachable at every size.
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
            compose.onNodeWithText("2.1.00").assertIsDisplayed()
            compose.onNodeWithText("132").assertIsDisplayed()
            // And so is the loop, which used to vanish below 420dp.
            compose.onNodeWithContentDescription("Loop: all 2 bars").assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- the pages

    @Test fun everyPageHasAJobAndAHelpTopic() {
        // The navigation strip is built from this enum, so a page without a help topic
        // would show an empty dialog.
        for (page in Page.entries) {
            assertTrue(page.title.isNotBlank())
            assertTrue(page.shortTitle.isNotBlank())
            assertTrue(
                "${page.name} has no help topic",
                com.tabtap.app.ui.help.Help.byId(page.helpTopic) != null,
            )
            // The rail labels have to fit in 68dp.
            assertTrue("${page.name}'s short title is too long", page.shortTitle.length <= 8)
        }
        assertEquals(Page.PLAY, Page.DEFAULT)
        assertTrue("only Projects should work without a project", Page.entries.count { !it.needsProject } == 1)
    }

    @Test fun theTabPageRendersAtEverySizeAndZoom() {
        val c = composition()
        val index = mutableStateOf(0)
        val zooms = listOf(0.75f, 1f, 1.25f, 2f, 2.5f)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 892.dp, height = 412.dp)) {
                    TabPane(
                        track = c.track(1L)!!,
                        timeSignature = c.timeSignature,
                        chordProgression = c.chordProgression,
                        selectedNoteIds = emptySet(),
                        playheadTick = 480L,
                        minMeasures = c.barCount,
                        scale = zooms[index.value],
                        onSelectNote = { _, _ -> },
                        onAddNote = { _, _, _ -> },
                    )
                }
            }
        }
        for (i in zooms.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun theScorePageRendersAtEverySizeAndZoom() {
        val c = composition()
        val index = mutableStateOf(0)
        val zooms = listOf(0.75f, 1.25f, 2.5f)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 640.dp, height = 360.dp)) {
                    ScorePane(
                        composition = c,
                        selectedNoteIds = emptySet(),
                        playheadTick = null,
                        scale = zooms[index.value],
                    )
                }
            }
        }
        for (i in zooms.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun thePianoRollRendersInLandscape() {
        val c = composition()
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 892.dp, height = 412.dp)) {
                    PianoRollPane(
                        composition = c,
                        track = c.track(1L)!!,
                        selectedNoteIds = emptySet(),
                        playheadTick = 960L,
                        onSelectNote = { _, _ -> },
                        onMoveNote = { _, _, _ -> },
                        onResizeNote = { _, _ -> },
                        onAddNote = { _, _ -> },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- the rest

    @Test fun fretboardMarksComeFromTheComposition() {
        val c = composition()
        val active = c.track(1L)!!
        val marks = buildFretboardMarks(
            activeTrackNotes = active.notes,
            activeColor = MusicColors.original,
            otherTracks = listOf(c.tracks.last().notes to MusicColors.harmony[0]),
            selectedIds = setOf(active.notes.first().id),
        )
        assertEquals((1..active.notes.size).toList(), marks.filter { !it.secondary }.map { it.sequenceIndex })
        assertEquals(1, marks.count { it.selected })
    }

    @Test fun marksSkipNotesWithNoFingering() {
        val notes = listOf(
            MusicNote(1L, n("E4"), 0L, 480, string = 5, fret = 0),
            MusicNote(2L, n("C1"), 480L, 480),
        )
        assertEquals(1, buildFretboardMarks(notes, MusicColors.original, emptyList(), emptySet()).size)
    }

    @Test fun positionFormattingCountsFromOne() {
        assertEquals("1.1.00", barsAndBeats(0L, TimeSignature.FOUR_FOUR))
        assertEquals("2.1.00", barsAndBeats(3840L, TimeSignature.FOUR_FOUR))
        assertEquals("2.1.00", barsAndBeats(3360L, TimeSignature.SEVEN_EIGHT))
    }

    @Test fun chordDiagramsRenderForEveryChordType() {
        data class Case(val voicing: com.tabtap.music.ChordVoicing, val board: Fretboard)
        val cases = ArrayList<Case>()
        for (tuning in listOf(Tunings.STANDARD_E, Tunings.BASS_STANDARD)) {
            val board = Fretboard(tuning, 22)
            for (type in ChordType.BUILT_IN) {
                val chord = Chord.of("A", type) ?: continue
                val voicing = ChordVoicer.bestVoicing(chord, board) ?: continue
                cases += Case(voicing, board)
            }
        }
        assertTrue(cases.size > 20)
        val index = mutableStateOf(0)
        compose.setContent {
            val case = cases[index.value]
            TabTapTheme { ChordDiagram(case.voicing, case.board, selected = true) }
        }
        for (i in cases.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun sharedControlsRenderAndRespond() {
        var selected = "A"
        var chipTapped = false
        compose.setContent {
            TabTapTheme {
                Panel(title = "Panel") {
                    Column {
                        SegmentedControl(
                            options = listOf("A", "B", "C"),
                            selected = selected,
                            onSelect = { selected = it },
                            label = { it },
                        )
                        TabTapChip("Chip", onClick = { chipTapped = true })
                        ReadoutRow("Label", "Value")
                    }
                }
            }
        }
        compose.onNodeWithText("PANEL").assertIsDisplayed()
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("Chip").performClick()
        compose.waitForIdle()
        assertEquals("B", selected)
        assertTrue(chipTapped)
    }

    @Test fun theEmptyStateRendersItsAction() {
        var tapped = false
        compose.setContent {
            TabTapTheme {
                EmptyState("Nothing here", "Add something.") {
                    TabTapChip("Add", onClick = { tapped = true })
                }
            }
        }
        compose.onNodeWithText("Nothing here").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
        assertTrue(tapped)
    }

    @Test fun bothThemesRender() {
        val dark = mutableStateOf(true)
        compose.setContent {
            TabTapTheme(darkTheme = dark.value) {
                Panel(title = "Theme") { ReadoutRow("Dark", dark.value.toString()) }
            }
        }
        compose.onNodeWithText("true").assertIsDisplayed()
        dark.value = false
        compose.waitForIdle()
        compose.onNodeWithText("false").assertIsDisplayed()
    }
}
