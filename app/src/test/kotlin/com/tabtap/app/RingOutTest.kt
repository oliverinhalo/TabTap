package com.tabtap.app

import com.tabtap.app.audio.ScheduledEvent
import com.tabtap.app.audio.Sequencer
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.Articulation
import com.tabtap.music.Composition
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.Ticks
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long notes sound for.
 *
 * A plucked string rings on past its written length until that string is played again —
 * lifting a finger does not silence it, but fretting it does. Ending every note at its
 * written length is what made playback sound clipped, so this pins the behaviour down.
 */
class RingOutTest {

    private val sampleRate = 48_000
    private val bpm = 120.0

    private fun project(): Composition =
        Composition.newGuitarProject(Tunings.STANDARD_E, bpm)

    /** Sounding length in frames of the note that starts at [startTick]. */
    private fun soundingFrames(composition: Composition, startTick: Long): Long {
        val schedule = Sequencer.build(composition, sampleRate)
        val on = schedule.events.filterIsInstance<ScheduledEvent.NoteOn>()
            .first { schedule.tickAtFrame(it.frame) in (startTick - 2)..(startTick + 2) }
        val off = schedule.events.filterIsInstance<ScheduledEvent.NoteOff>()
            .first { it.key == on.key }
        return off.frame - on.frame
    }

    @Test fun aNoteRingsPastItsWrittenLength() {
        // One short note on its own: it should ring far longer than its written eighth.
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.EIGHTH.ticks)
        val written = Ticks.toFrames(NoteValue.EIGHTH.ticks.toLong(), bpm, sampleRate)
        val actual = soundingFrames(c, 0L)
        assertTrue(
            "a lone note should ring on, not stop at its written length " +
                "($actual frames vs $written)",
            actual > written * 3,
        )
    }

    @Test fun aNoteIsStoppedByTheNextNoteOnTheSameString() {
        // Two notes on the low E string, a quarter apart. The first must stop when the
        // second is played, because it is the same string.
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.EIGHTH.ticks)
        c = CompositionEditor.addNoteAt(c, 1L, 0, 5, Ticks.QUARTER.toLong(), NoteValue.EIGHTH.ticks)
        val expected = Ticks.toFrames(Ticks.QUARTER.toLong(), bpm, sampleRate)
        assertEquals(expected, soundingFrames(c, 0L))
    }

    @Test fun aNoteOnAnotherStringDoesNotStopIt() {
        // The same two notes, but on different strings: both ring on together, which is
        // how a guitar actually sounds and is the point of modelling it this way.
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.EIGHTH.ticks)
        c = CompositionEditor.addNoteAt(c, 1L, 1, 3, Ticks.QUARTER.toLong(), NoteValue.EIGHTH.ticks)
        val quarter = Ticks.toFrames(Ticks.QUARTER.toLong(), bpm, sampleRate)
        assertTrue(
            "a note on a different string should not cut this one off",
            soundingFrames(c, 0L) > quarter,
        )
    }

    @Test fun ringingOutIsCappedSoNothingDronesForEver() {
        // A single note in a long piece must not ring for the whole thing.
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.EIGHTH.ticks)
        c = CompositionEditor.addNoteAt(
            c, 1L, 5, 0, Ticks.PPQ.toLong() * 64, NoteValue.EIGHTH.ticks,
        )
        val frames = soundingFrames(c, 0L)
        val seconds = frames.toDouble() / sampleRate
        assertTrue("a note rang for $seconds s", seconds in 1.0..4.0)
    }

    @Test fun dampedNotesKeepTheirWrittenLength() {
        // Damping is the whole point of palm muting, staccato and dead notes: they must
        // not ring on, or the technique does nothing.
        for (articulation in listOf(
            Articulation.PALM_MUTE, Articulation.STACCATO, Articulation.DEAD_NOTE,
        )) {
            var c = project()
            c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.QUARTER.ticks)
            val id = c.track(1L)!!.notes.single().id
            c = CompositionEditor.toggleArticulation(c, 1L, setOf(id), articulation)
            val written = Ticks.toFrames(NoteValue.QUARTER.ticks.toLong(), bpm, sampleRate)
            val actual = soundingFrames(c, 0L)
            assertTrue(
                "$articulation should not ring on ($actual vs $written)",
                actual <= written + 1,
            )
        }
    }

    @Test fun aChordRingsAsAWhole() {
        // Six notes struck together, each on its own string, all ring on.
        var c = project()
        for (string in 0 until 6) {
            c = CompositionEditor.addNoteAt(c, 1L, string, 0, 0L, NoteValue.EIGHTH.ticks)
        }
        val schedule = Sequencer.build(c, sampleRate)
        val ons = schedule.events.filterIsInstance<ScheduledEvent.NoteOn>()
        assertEquals(6, ons.size)
        val eighth = Ticks.toFrames(NoteValue.EIGHTH.ticks.toLong(), bpm, sampleRate)
        for (on in ons) {
            val off = schedule.events.filterIsInstance<ScheduledEvent.NoteOff>()
                .first { it.key == on.key }
            assertTrue("a chord tone was cut short", off.frame - on.frame > eighth * 2)
        }
    }

    @Test fun everyNoteStillGetsExactlyOneNoteOff() {
        // Whatever the ring-out does, the books must balance or notes stick on.
        var c = project()
        for (i in 0 until 40) {
            c = CompositionEditor.addNoteAt(
                c, 1L, i % 6, (i * 2) % 12, i * 240L, NoteValue.SIXTEENTH.ticks,
            )
        }
        val schedule = Sequencer.build(c, sampleRate, metronome = true)
        val ons = schedule.events.filterIsInstance<ScheduledEvent.NoteOn>()
        val offs = schedule.events.filterIsInstance<ScheduledEvent.NoteOff>()
        assertEquals(40, ons.size)
        assertEquals(ons.map { it.key }.sorted(), offs.map { it.key }.sorted())
        // And no note-off lands before its note-on.
        for (on in ons) {
            val off = offs.first { it.key == on.key }
            assertTrue("a note-off preceded its note-on", off.frame >= on.frame)
        }
    }

    @Test fun ringingOutDoesNotDependOnTempo() {
        // The cap is in seconds, because a string's decay does not know the tempo.
        fun secondsAt(tempo: Double): Double {
            var c = Composition.newGuitarProject(Tunings.STANDARD_E, tempo)
            c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.EIGHTH.ticks)
            val schedule = Sequencer.build(c, sampleRate)
            val on = schedule.events.filterIsInstance<ScheduledEvent.NoteOn>().first()
            val off = schedule.events.filterIsInstance<ScheduledEvent.NoteOff>()
                .first { it.key == on.key }
            return (off.frame - on.frame).toDouble() / sampleRate
        }
        val slow = secondsAt(60.0)
        val fast = secondsAt(200.0)
        assertEquals("the ring-out moved with the tempo", slow, fast, 0.25)
    }

    @Test fun theLetRingTechniqueStillExtendsBeyondTheCap() {
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 0L, NoteValue.QUARTER.ticks)
        val id = c.track(1L)!!.notes.single().id
        val plain = soundingFrames(c, 0L)
        c = CompositionEditor.toggleArticulation(c, 1L, setOf(id), Articulation.LET_RING)
        assertTrue("let ring should extend the note", soundingFrames(c, 0L) > plain)
    }
}
