package com.tabtap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.audio.Sequencer
import com.tabtap.music.Composition
import com.tabtap.music.Key
import com.tabtap.music.ScaleType
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import com.tabtap.music.notation.ScoreEngraver
import com.tabtap.music.notation.TabEngraver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tapping a rhythm out, and what the tab and the score then show.
 *
 * The reported fault was that after tapping, only the first bar survived, and both the
 * tab and the score showed one bar with every note piled on top of the others. All of
 * that is one cause: the click track was only ever scheduled to the end of the music,
 * and while tapping there is no music - the notes are sounded by the taps - so the
 * schedule was one bar long. Playback ended after that bar, the playhead froze, and
 * every tap after it recorded the same tick. Notes on the same tick are one chord, in
 * bar one.
 */
@RunWith(AndroidJUnit4::class)
class TapRhythmTest {

    private fun project() = Composition.newGuitarProject(
        Tunings.STANDARD_E, 120.0, Key.of("E", ScaleType.NATURAL_MINOR)!!, TimeSignature.FOUR_FOUR,
    )

    @Test fun theClickKeepsGoingWhenThereIsNoMusicToPlay() {
        val empty = project().let { it.copy(tracks = emptyList()) }
        val bars = 32
        val schedule = Sequencer.build(
            composition = empty,
            sampleRate = 48_000,
            metronome = true,
            metronomeLevel = 0.7f,
            minimumEndTick = bars.toLong() * TimeSignature.FOUR_FOUR.barTicks,
        )

        // Four clicks a bar, for as many bars as were asked for.
        val clicks = schedule.events.count { it is com.tabtap.app.audio.ScheduledEvent.Click }
        assertTrue("only $clicks clicks for $bars bars", clicks >= bars * 4)

        // And the schedule is long enough to hold them, so playback does not end early.
        val expectedSeconds = Ticks.toSeconds(
            bars.toLong() * TimeSignature.FOUR_FOUR.barTicks, 120.0,
        )
        val actualSeconds = schedule.lengthFrames / 48_000.0
        assertTrue(
            "the click track is ${actualSeconds}s long, not ${expectedSeconds}s",
            actualSeconds >= expectedSeconds,
        )
    }

    @Test fun aRhythmSpreadOverThreeBarsStaysInThreeBars() {
        val bar = TimeSignature.FOUR_FOUR.barTicks
        var c = project()
        val trackId = c.activeTrackId!!
        // Eight notes, entered in order on the fretboard.
        repeat(8) { c = com.tabtap.app.editing.CompositionEditor.appendNoteAtPosition(c, trackId, 2, it) }

        // Tapped onsets that really do span three bars.
        val onsets = (0 until 8).map { it.toLong() * (bar * 3 / 8) }
        c = com.tabtap.app.editing.CompositionEditor.applyTappedRhythm(
            c, trackId, onsets, com.tabtap.music.NoteValue.EIGHTH.ticks,
        )

        val notes = c.track(trackId)!!.notes.sortedBy { it.startTick }
        assertEquals(8, notes.size)
        assertEquals(
            "the taps collapsed onto one tick",
            8, notes.map { it.startTick }.distinct().size,
        )
        assertTrue("the music is only ${c.barCount} bars", c.barCount >= 3)

        // The tab and the score must then lay those three bars out side by side, each
        // with its own notes - not one bar with everything in it.
        val tab = TabEngraver.engrave(
            c.track(trackId)!!, TimeSignature.FOUR_FOUR, systemWidth = 200.0,
        )
        assertEquals("the tab shows the wrong number of bars", 3, tab.measureCount)
        val tabXs = tab.systems.flatMap { s -> s.measures.flatMap { m -> m.stacks.map { m.index to it.x } } }
        assertEquals("tab notes are stacked on top of each other", 8, tabXs.distinct().size)

        val score = ScoreEngraver.engrave(
            c.track(trackId)!!, c.key, TimeSignature.FOUR_FOUR, systemWidth = 200.0,
        )
        assertEquals("the score shows the wrong number of bars", 3, score.measureCount)
    }

    /**
     * The whole flow through the view model, with a transport that is not advancing.
     *
     * This is the reported fault reproduced: eight taps, a playhead that never moves,
     * and a phrase that has to come out as eight separate notes rather than one chord.
     */
    @Test fun eightTapsBecomeEightNotesEvenIfTheTransportIsStuck() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val database = com.tabtap.app.data.TabTapDatabase.inMemory(context)
        val model = StudioViewModel(
            context,
            com.tabtap.app.data.ProjectRepository(database.projects()),
            com.tabtap.app.audio.AudioEngine(),
        )
        var now = 10_000L
        model.clockMs = { now }

        // Notes in, in tap order.
        repeat(8) { model.tapFret(2, it) }
        assertEquals(8, model.state.value.activeTrack!!.notes.size)

        model.startRhythmRecording()
        assertTrue("the metronome should be switched on for you", model.state.value.metronomeEnabled)

        // Taps during the count-in are not part of the rhythm.
        now += 500
        model.recordRhythmTap()
        assertEquals("a tap during the count-in was recorded", 0, model.state.value.recording.onsets.size)

        // Then eight taps, a quarter note apart at 120 BPM: 500ms each.
        now += 2_000
        repeat(8) {
            now += 500
            model.recordRhythmTap()
        }
        val onsets = model.state.value.recording.onsets
        assertEquals(8, onsets.size)
        assertEquals("the taps all landed on the same tick", 8, onsets.distinct().size)

        model.commitRhythmToNotes()
        val notes = model.state.value.activeTrack!!.notes
        assertEquals(8, notes.size)
        assertTrue(
            "the phrase collapsed into bar one: ${notes.map { it.startTick }}",
            notes.map { it.startTick }.distinct().size >= 7,
        )
        assertTrue("the music is only ${model.state.value.composition.barCount} bars",
            model.state.value.composition.barCount >= 2)
    }
}
