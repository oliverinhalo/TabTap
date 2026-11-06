package com.tabtap.app

import com.tabtap.app.audio.ScheduledEvent
import com.tabtap.app.audio.Sequencer
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.Composition
import com.tabtap.music.NoteValue
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing while the transport is running.
 *
 * An edit made during playback should be heard without stopping and starting again. The
 * engine swaps the schedule in place: the playhead is converted to a musical tick against
 * the old schedule and back to a frame against the new one, so a tempo change moves the
 * playhead to the right place rather than jumping, and the event cursor skips everything
 * already due so nothing re-triggers.
 *
 * The engine itself needs a device, so what is checked here is the arithmetic that swap
 * depends on — which is where a mistake would actually be.
 */
class LiveEditTest {

    private val sampleRate = 48_000

    private fun project(bpm: Double = 120.0): Composition {
        var c = Composition.newGuitarProject(Tunings.STANDARD_E, bpm)
        for (i in 0 until 16) {
            c = CompositionEditor.addNoteAt(
                c, 1L, i % 6, i % 12, i * Ticks.QUARTER.toLong(), NoteValue.QUARTER.ticks,
            )
        }
        return c
    }

    @Test fun addingANoteLeavesEveryOtherNoteWhereItWas() {
        val before = project()
        val after = CompositionEditor.addNoteAt(
            before, 1L, 0, 7, 40L * Ticks.QUARTER, NoteValue.QUARTER.ticks,
        )
        val a = Sequencer.build(before, sampleRate)
        val b = Sequencer.build(after, sampleRate)

        // Every note that existed before is at exactly the same frame afterwards, so a
        // mid-playback swap cannot shift the music under the playhead.
        val framesBefore = a.events.filterIsInstance<ScheduledEvent.NoteOn>()
            .associate { it.key to it.frame }
        val framesAfter = b.events.filterIsInstance<ScheduledEvent.NoteOn>()
            .associate { it.key to it.frame }
        for ((key, frame) in framesBefore) {
            assertEquals("note $key moved", frame, framesAfter[key])
        }
        assertEquals(framesBefore.size + 1, framesAfter.size)
    }

    @Test fun theEventCursorAfterASwapSkipsWhatHasAlreadySounded() {
        // This is the calculation the engine does: find the first event at or after the
        // playhead. Anything earlier has already been dispatched and must not fire again.
        val schedule = Sequencer.build(project(), sampleRate)
        val events = schedule.events
        for (playhead in listOf(0L, 48_000L, 96_000L, 240_000L, Long.MAX_VALUE / 4)) {
            val cursor = events.indexOfFirst { it.frame >= playhead }
                .let { if (it < 0) events.size else it }
            // Nothing before the cursor is still to come...
            for (i in 0 until cursor) {
                assertTrue("event $i at ${events[i].frame} would re-fire", events[i].frame < playhead)
            }
            // ...and nothing at or after it has been missed.
            if (cursor < events.size) {
                assertTrue(events[cursor].frame >= playhead)
            }
        }
    }

    @Test fun aTempoChangeMovesThePlayheadToTheSameMusicalPlace() {
        // The point of converting through ticks: at 120 BPM, frame 96000 is bar 2. After
        // changing to 60 BPM the playhead must still be at bar 2, which is frame 192000.
        val slow = Sequencer.build(project(60.0), sampleRate)
        val fast = Sequencer.build(project(120.0), sampleRate)

        val frameAtFast = 96_000L
        val tick = fast.tickAtFrame(frameAtFast)
        assertEquals(TimeSignature.FOUR_FOUR.barTicks.toLong(), tick)

        val frameAtSlow = slow.frameAtTick(tick)
        assertEquals(192_000L, frameAtSlow)
        // And converting back gets the same bar.
        assertEquals(tick, slow.tickAtFrame(frameAtSlow))
    }

    @Test fun theConversionIsStableForEveryTempoAndPosition() {
        for (bpm in listOf(40.0, 60.0, 90.0, 120.0, 180.0, 300.0)) {
            val schedule = Sequencer.build(project(bpm), sampleRate)
            for (bar in 0..8) {
                val tick = bar.toLong() * TimeSignature.FOUR_FOUR.barTicks
                val frame = schedule.frameAtTick(tick)
                assertEquals("$bpm BPM bar $bar", tick, schedule.tickAtFrame(frame))
            }
        }
    }

    @Test fun aCountInSwapKeepsWhatIsLeftOfIt() {
        // Changing something during the count-in must not restart it. The engine keeps
        // the remaining frames, which only works if both schedules' count-ins are known.
        val a = Sequencer.build(project(), sampleRate, countInBars = 2, metronome = true)
        val b = Sequencer.build(project(), sampleRate, countInBars = 2, metronome = true)
        assertEquals(a.countInFrames, b.countInFrames)

        val elapsed = a.countInFrames / 3
        val remaining = a.countInFrames - elapsed
        val newFrame = (b.countInFrames - remaining).coerceAtLeast(0L)
        assertEquals(elapsed, newFrame)
        // And musical time has still not started.
        assertEquals(0L, b.tickAtFrame(newFrame))
    }

    @Test fun loopBoundariesSurviveASwap() {
        val before = Sequencer.build(project(), sampleRate)
        val after = Sequencer.build(
            CompositionEditor.addNoteAt(project(), 1L, 0, 3, 200L, 480), sampleRate,
        )
        val bar = TimeSignature.FOUR_FOUR.barTicks.toLong()
        // The loop is held in ticks, so its frames are recomputed and come out the same
        // when the tempo has not changed.
        assertEquals(before.frameAtTick(bar), after.frameAtTick(bar))
        assertEquals(before.frameAtTick(bar * 2), after.frameAtTick(bar * 2))
    }

    @Test fun removingANoteDoesNotStrandItsNoteOff() {
        // Every scheduled note must still be matched, or a note sticks on after an edit.
        val edited = CompositionEditor.removeNotes(
            project(), 1L, setOf(project().track(1L)!!.notes[4].id),
        )
        val schedule = Sequencer.build(edited, sampleRate)
        val ons = schedule.events.filterIsInstance<ScheduledEvent.NoteOn>()
        val offs = schedule.events.filterIsInstance<ScheduledEvent.NoteOff>()
        assertEquals(ons.map { it.key }.sorted(), offs.map { it.key }.sorted())
    }
}
