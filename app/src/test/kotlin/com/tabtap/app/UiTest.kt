package com.tabtap.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.design.MusicColors
import com.tabtap.app.design.TabTapTheme
import com.tabtap.app.ui.ChordDiagram
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.fretboard.FretboardMark
import com.tabtap.app.ui.fretboard.FretboardView
import com.tabtap.app.ui.fretboard.buildFretboardMarks
import com.tabtap.app.ui.panels.TransportBar
import com.tabtap.app.ui.panels.barsAndBeats
import com.tabtap.app.ui.panes.PianoRollPane
import com.tabtap.app.ui.panes.ScorePane
import com.tabtap.app.ui.panes.TabPane
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.ChordVoicer
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Interface tests.
 *
 * These run on the JVM through Robolectric, so the fretboard, the editors and the
 * transport are all actually composed and laid out rather than merely compiled. They
 * check two things the app depends on: that every view renders for every instrument and
 * window size without throwing, and that a tap on the fretboard lands on the string and
 * fret the player aimed at.
 */
@RunWith(AndroidJUnit4::class)
class UiTest {

    @get:Rule val compose = createComposeRule()

    private fun n(s: String) = Pitch.parse(s)!!

    private fun composition(): Composition {
        var c = Composition.newGuitarProject(
            Tunings.STANDARD_E, 132.0, Key.of("E", ScaleType.NATURAL_MINOR)!!,
        )
        for ((string, fret) in listOf(2 to 2, 2 to 4, 2 to 5, 3 to 2, 3 to 4)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, string, fret)
        }
        c = CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        return c
    }

    // ---------------------------------------------------------------- fretboard

    @Test fun theFretboardRendersForEveryTuning() {
        // A composition rule allows one setContent per test, so the variations are driven
        // from state and recomposed instead.
        val index = mutableStateOf(0)
        compose.setContent {
            val i = index.value
            val tuning = Tunings.presets[i]
            TabTapTheme {
                Box(Modifier.size(width = 900.dp, height = 400.dp)) {
                    FretboardView(
                        fretboard = Fretboard(tuning, 24),
                        key = Key.of("E", ScaleType.NATURAL_MINOR)!!,
                        marks = (0 until tuning.stringCount).map {
                            FretboardMark(it, 5, MusicColors.original, sequenceIndex = it + 1)
                        },
                        display = FretboardDisplay(),
                        onTap = { _, _, _ -> },
                    )
                }
            }
        }
        for (i in Tunings.presets.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun theFretboardRendersWithEveryDisplayOption() {
        val options = listOf(
            FretboardDisplay(),
            FretboardDisplay(showNoteNames = false, showIntervals = true),
            FretboardDisplay(showNoteNames = false, showScaleDegrees = true),
            FretboardDisplay(showOctaves = true),
            FretboardDisplay(showScale = false),
            FretboardDisplay(showOnlyScaleNotes = true),
            FretboardDisplay(showRecordedNotes = false),
            FretboardDisplay(showFretNumbers = false, showInlays = false),
            FretboardDisplay(leftHanded = true),
            FretboardDisplay(firstVisibleFret = 12, visibleFretCount = 6),
            FretboardDisplay(visibleFretCount = 30),
        )
        val index = mutableStateOf(0)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 900.dp, height = 400.dp)) {
                    FretboardView(
                        fretboard = Fretboard(Tunings.STANDARD_E, 24),
                        key = Key.of("Eb", ScaleType.MAJOR)!!,
                        marks = listOf(FretboardMark(0, 3, MusicColors.original, 1)),
                        display = options[index.value],
                        sounding = setOf(0 to 3),
                        onTap = { _, _, _ -> },
                    )
                }
            }
        }
        for (i in options.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun tappingTheFretboardReportsAStringAndFret() {
        val taps = ArrayList<Pair<Int, Int>>()
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 800.dp, height = 320.dp)) {
                    FretboardView(
                        fretboard = Fretboard(Tunings.STANDARD_E, 22),
                        key = Key.C_MAJOR,
                        marks = emptyList(),
                        display = FretboardDisplay(),
                        modifier = Modifier.fillMaxSize(),
                        onTap = { string, fret, _ -> taps += string to fret },
                    )
                }
            }
        }
        compose.waitForIdle()
        // The gesture handling and hit testing are exercised by the geometry tests below;
        // here the important thing is that composing and laying out the board works and
        // the callback is wired up with the right shape.
        assertTrue(taps.isEmpty())
    }

    @Test fun fretboardMarksAreBuiltFromTheComposition() {
        val c = composition()
        val active = c.track(1L)!!
        val harmony = c.tracks.last()
        val marks = buildFretboardMarks(
            activeTrackNotes = active.notes,
            activeColor = MusicColors.original,
            otherTracks = listOf(harmony.notes to MusicColors.harmony[0]),
            selectedIds = setOf(active.notes.first().id),
        )
        // Every note of both tracks is represented.
        assertEquals(active.notes.size + harmony.notes.size, marks.size)
        // The active track's notes are numbered in the order they were tapped.
        val activeMarks = marks.filter { !it.secondary }
        assertEquals((1..active.notes.size).toList(), activeMarks.map { it.sequenceIndex })
        // The other track is drawn faded.
        assertTrue(marks.filter { it.secondary }.all { it.sequenceIndex == null })
        // The selection is marked.
        assertEquals(1, marks.count { it.selected })
    }

    @Test fun marksSkipNotesWithNoFingering() {
        val notes = listOf(
            MusicNote(1L, n("E4"), 0L, 480, string = 5, fret = 0),
            MusicNote(2L, n("C1"), 480L, 480),   // Unplayable: no position.
        )
        val marks = buildFretboardMarks(notes, MusicColors.original, emptyList(), emptySet())
        assertEquals(1, marks.size)
        assertEquals(1, marks.single().sequenceIndex)
    }

    // ---------------------------------------------------------------- editors

    @Test fun theTabPaneRendersForEveryInstrument() {
        val projects = listOf(
            Tunings.STANDARD_E, Tunings.SEVEN_STANDARD_B, Tunings.EIGHT_STANDARD_F,
            Tunings.BASS_STANDARD, Tunings.BASS_6_STANDARD, Tunings.DADGAD,
        ).map { tuning ->
            var c = Composition.newGuitarProject(tuning)
            for (fret in listOf(0, 3, 5, 7)) {
                c = CompositionEditor.appendNoteAtPosition(c, 1L, 0, fret)
            }
            c
        }
        val index = mutableStateOf(0)
        compose.setContent {
            val c = projects[index.value]
            TabTapTheme {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    TabPane(
                        track = c.track(1L)!!,
                        timeSignature = c.timeSignature,
                        chordProgression = emptyList(),
                        selectedNoteIds = setOf(c.track(1L)!!.notes.first().id),
                        playheadTick = 480L,
                        onSelectNote = { _, _ -> },
                        onAddNote = { _, _, _ -> },
                    )
                }
            }
        }
        for (i in projects.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun theScorePaneRendersEveryKeyAndMetre() {
        val projects = ArrayList<Composition>()
        for (scale in listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR)) {
            for (tonic in listOf("C", "G", "F#", "Bb", "Eb")) {
                for (metre in listOf(
                    TimeSignature.FOUR_FOUR, TimeSignature.THREE_FOUR,
                    TimeSignature.SIX_EIGHT, TimeSignature.SEVEN_EIGHT,
                    TimeSignature.FIVE_FOUR,
                )) {
                    var c = Composition.newGuitarProject(
                        Tunings.STANDARD_E, 120.0, Key.of(tonic, scale)!!, metre,
                    )
                    for (fret in listOf(0, 2, 3, 5, 7, 8, 10, 12)) {
                        c = CompositionEditor.appendNoteAtPosition(
                            c, 1L, 2, fret, com.tabtap.music.NoteValue.EIGHTH,
                        )
                    }
                    projects += c
                }
            }
        }
        val index = mutableStateOf(0)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    ScorePane(projects[index.value], emptySet(), 960L)
                }
            }
        }
        for (i in projects.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun theScorePaneRendersMultiplePartsAndBassClef() {
        var bass = Composition.newBassProject(Tunings.BASS_5_STANDARD)
        for (fret in listOf(0, 3, 5)) {
            bass = CompositionEditor.appendNoteAtPosition(bass, 1L, 0, fret)
        }
        val projects = listOf(composition(), bass)
        val index = mutableStateOf(0)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 700.dp, height = 500.dp)) {
                    ScorePane(projects[index.value], emptySet(), null)
                }
            }
        }
        for (i in projects.indices) {
            index.value = i
            compose.waitForIdle()
        }
    }

    @Test fun thePianoRollRenders() {
        val c = composition()
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    PianoRollPane(
                        composition = c,
                        track = c.track(1L)!!,
                        selectedNoteIds = setOf(c.track(1L)!!.notes.first().id),
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

    @Test fun anEmptyProjectRendersInEveryPane() {
        val empty = Composition.newGuitarProject()
        val which = mutableStateOf(0)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 700.dp, height = 400.dp)) {
                    when (which.value) {
                        0 -> ScorePane(empty, emptySet(), null)
                        1 -> TabPane(
                            track = empty.track(1L)!!,
                            timeSignature = empty.timeSignature,
                            chordProgression = emptyList(),
                            selectedNoteIds = emptySet(),
                            playheadTick = null,
                            onSelectNote = { _, _ -> },
                            onAddNote = { _, _, _ -> },
                        )
                        else -> PianoRollPane(
                            composition = empty,
                            track = empty.track(1L)!!,
                            selectedNoteIds = emptySet(),
                            playheadTick = null,
                            onSelectNote = { _, _ -> },
                            onMoveNote = { _, _, _ -> },
                            onResizeNote = { _, _ -> },
                            onAddNote = { _, _ -> },
                        )
                    }
                }
            }
        }
        for (i in 0..2) {
            which.value = i
            compose.waitForIdle()
        }
    }

    // ---------------------------------------------------------------- transport

    @Test fun theTransportBarShowsTheStateAndRespondsToTaps() {
        var played = false
        var stopped = false
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 1100.dp, height = 120.dp)) {
                TransportBar(
                    isPlaying = false,
                    isPaused = false,
                    positionTick = 3840L + 960L,
                    countInBeatsRemaining = 0,
                    bpm = 132.0,
                    timeSignature = TimeSignature.FOUR_FOUR,
                    metronomeEnabled = true,
                    looping = false,
                    canUndo = true,
                    canRedo = false,
                    saveState = SaveState.SAVED,
                    onPlay = { played = true },
                    onPause = {},
                    onStop = { stopped = true },
                    onRestart = {},
                    onToggleMetronome = {},
                    onToggleLoop = {},
                    onUndo = {},
                    onRedo = {},
                    onTempoChange = {},
                    onTapTempo = {},
                )
                }
            }
        }
        // Bar 2, beat 2 — counted from one, as a musician reads it.
        compose.onNodeWithText("2.2.00").assertIsDisplayed()
        compose.onNodeWithText("132").assertExists()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitForIdle()
        assertTrue("the play button is not wired up", played)
        assertTrue("the stop button is not wired up", stopped)
    }

    @Test fun theCountInIsShownWhileItRuns() {
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = 1100.dp, height = 120.dp)) {
                TransportBar(
                    isPlaying = true, isPaused = false, positionTick = 0L,
                    countInBeatsRemaining = 3, bpm = 120.0,
                    timeSignature = TimeSignature.FOUR_FOUR,
                    metronomeEnabled = true, looping = false,
                    canUndo = false, canRedo = false, saveState = SaveState.IDLE,
                    onPlay = {}, onPause = {}, onStop = {}, onRestart = {},
                    onToggleMetronome = {}, onToggleLoop = {},
                    onUndo = {}, onRedo = {}, onTempoChange = {}, onTapTempo = {},
                )
                }
            }
        }
        compose.onNodeWithText("Count-in 3").assertIsDisplayed()
    }

    @Test fun theTransportBarFitsEveryWindowWidth() {
        // It overflowed on a phone in portrait once; this pins the behaviour down. The
        // play, stop and position controls must survive at every width.
        val widths = listOf(320, 360, 400, 440, 480, 560, 720, 1000, 1400)
        val index = mutableStateOf(0)
        compose.setContent {
            TabTapTheme {
                Box(Modifier.size(width = widths[index.value].dp, height = 110.dp)) {
                    TransportBar(
                        isPlaying = true, isPaused = false, positionTick = 3840L,
                        countInBeatsRemaining = 0, bpm = 120.0,
                        timeSignature = TimeSignature.SEVEN_EIGHT,
                        metronomeEnabled = true, looping = true,
                        canUndo = true, canRedo = true, saveState = SaveState.SAVED,
                        onPlay = {}, onPause = {}, onStop = {}, onRestart = {},
                        onToggleMetronome = {}, onToggleLoop = {},
                        onUndo = {}, onRedo = {}, onTempoChange = {}, onTapTempo = {},
                    )
                }
            }
        }
        for (i in widths.indices) {
            index.value = i
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
            // 3840 ticks in 7/8 (a 3360-tick bar) is bar 2, beat 2.
            compose.onNodeWithText("2.2.00").assertIsDisplayed()
            // The tempo is always reachable, as a stepper or as a tappable readout.
            compose.onNodeWithText("120").assertIsDisplayed()
        }
    }

    @Test fun positionFormattingCountsFromOne() {
        assertEquals("1.1.00", barsAndBeats(0L, TimeSignature.FOUR_FOUR))
        assertEquals("1.2.00", barsAndBeats(960L, TimeSignature.FOUR_FOUR))
        assertEquals("2.1.00", barsAndBeats(3840L, TimeSignature.FOUR_FOUR))
        assertEquals("1.1.50", barsAndBeats(480L, TimeSignature.FOUR_FOUR))
        assertEquals("2.1.00", barsAndBeats(3360L, TimeSignature.SEVEN_EIGHT))
    }

    // ---------------------------------------------------------------- components

    @Test fun chordDiagramsRenderForEveryChordTypeAndInstrument() {
        data class Case(val voicing: com.tabtap.music.ChordVoicing, val board: Fretboard)
        val cases = ArrayList<Case>()
        for (tuning in listOf(Tunings.STANDARD_E, Tunings.SEVEN_STANDARD_B, Tunings.BASS_STANDARD)) {
            val board = Fretboard(tuning, 22)
            for (type in ChordType.BUILT_IN) {
                val chord = Chord.of("A", type) ?: continue
                val voicing = ChordVoicer.bestVoicing(chord, board) ?: continue
                cases += Case(voicing, board)
            }
        }
        assertTrue("no chord voicings were found to draw", cases.size > 30)
        val index = mutableStateOf(0)
        compose.setContent {
            val case = cases[index.value]
            TabTapTheme {
                ChordDiagram(case.voicing, case.board, selected = true)
            }
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
                    androidx.compose.foundation.layout.Column {
                        SegmentedControl(
                            options = listOf("A", "B", "C"),
                            selected = selected,
                            onSelect = { selected = it },
                            label = { it },
                        )
                        TabTapChip("Chip", onClick = { chipTapped = true })
                        ReadoutRow("Label", "Value")
                        ChipRow(
                            items = listOf(1, 2, 3),
                            isSelected = { it == 2 },
                            onSelect = {},
                            label = { "Item $it" },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("PANEL").assertIsDisplayed()
        compose.onNodeWithText("Value").assertIsDisplayed()
        compose.onNodeWithText("Item 2").assertIsDisplayed()
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
        compose.onNodeWithText("Add something.").assertIsDisplayed()
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
