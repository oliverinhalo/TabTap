package com.tabtap.app

import com.tabtap.app.audio.LoopRegion
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.io.ChordRenderer
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.NoteValue
import com.tabtap.music.StrumPattern
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chord lengths, chord rearranging, and looping.
 *
 * The chord-length tests exist because chord length used to be chosen from note values,
 * which is only right in 4/4: a "whole note" chord lasts exactly twice the bar in 2/4 and
 * one and a third bars in 3/4 or 6/8. Chords are counted in bars and beats, and these
 * pin that down for every metre the app offers.
 */
class ChordAndLoopTest {

    private fun project(ts: TimeSignature) =
        Composition.newGuitarProject(Tunings.STANDARD_E, 120.0, com.tabtap.music.Key.C_MAJOR, ts)

    private fun chord(name: String) = Chord.of(name, ChordType.MAJOR)!!

    // ---------------------------------------------------------------- lengths

    @Test fun aOneBarChordLastsExactlyOneBarInEveryMetre() {
        for (ts in TimeSignature.PRESETS) {
            val options = CompositionEditor.chordLengthOptions(ts)
            val oneBar = options.firstOrNull { it.label == "1 bar" }
            assertNotNull("$ts offers no one-bar length", oneBar)
            assertEquals("$ts: a bar is not a bar", ts.barTicks, oneBar!!.ticks)

            // And adding a chord at that length really occupies one bar.
            val composition = CompositionEditor.appendChord(
                project(ts), chord("C"), oneBar.ticks,
            )
            assertEquals(ts.barTicks, composition.chordProgression.single().durationTicks)
            assertEquals(ts.barTicks.toLong(), composition.chordProgression.single().endTick)
        }
    }

    @Test fun aWholeNoteIsNotABarOutsideFourFour() {
        // The old model's failure, kept as a test so it cannot come back.
        assertEquals(3840, NoteValue.WHOLE.ticks)
        assertEquals(1920, TimeSignature.TWO_FOUR.barTicks)
        assertEquals(2880, TimeSignature.THREE_FOUR.barTicks)
        assertEquals(2880, TimeSignature.SIX_EIGHT.barTicks)
        // In 2/4 a whole note is exactly two bars, which is why chords came out twice
        // as long as they should have.
        assertEquals(
            TimeSignature.TWO_FOUR.barTicks * 2,
            NoteValue.WHOLE.ticks,
        )
        // The bar-based length does not have that problem.
        for (ts in listOf(TimeSignature.TWO_FOUR, TimeSignature.THREE_FOUR, TimeSignature.SIX_EIGHT)) {
            val oneBar = CompositionEditor.chordLengthOptions(ts).first { it.label == "1 bar" }
            assertTrue("$ts", oneBar.ticks != NoteValue.WHOLE.ticks || ts == TimeSignature.FOUR_FOUR)
            assertEquals(ts.barTicks, oneBar.ticks)
        }
    }

    @Test fun lengthOptionsAreOrderedAndDistinctForEveryMetre() {
        for (ts in TimeSignature.PRESETS) {
            val options = CompositionEditor.chordLengthOptions(ts)
            assertTrue("$ts has too few options", options.size >= 4)
            assertEquals("$ts has duplicates", options.size, options.map { it.ticks }.distinct().size)
            assertEquals(
                "$ts is not ordered shortest first",
                options.map { it.ticks }.sorted(),
                options.map { it.ticks },
            )
            assertTrue("$ts has a zero-length option", options.all { it.ticks > 0 })
        }
    }

    @Test fun beatsAreFeltBeatsSoCompoundTimeCountsInThrees() {
        // A beat in 6/8 is a dotted quarter, not an eighth.
        val options = CompositionEditor.chordLengthOptions(TimeSignature.SIX_EIGHT)
        assertEquals(1440, options.first { it.label == "1 beat" }.ticks)
        assertEquals(2880, options.first { it.label == "1 bar" }.ticks)
        // And in 4/4 a beat is a quarter.
        assertEquals(
            960,
            CompositionEditor.chordLengthOptions(TimeSignature.FOUR_FOUR)
                .first { it.label == "1 beat" }.ticks,
        )
    }

    @Test fun multiBarLengthsAreOfferedAndWork() {
        val ts = TimeSignature.FOUR_FOUR
        val options = CompositionEditor.chordLengthOptions(ts)
        assertEquals(ts.barTicks * 2, options.first { it.label == "2 bars" }.ticks)
        assertEquals(ts.barTicks * 4, options.first { it.label == "4 bars" }.ticks)
        assertEquals(ts.barTicks * 8, options.first { it.label == "8 bars" }.ticks)

        var c = CompositionEditor.appendChord(project(ts), chord("C"), ts.barTicks * 2)
        c = CompositionEditor.appendChord(c, chord("G"), ts.barTicks * 2)
        assertEquals(listOf(0L, 7680L), c.chordProgression.map { it.startTick })
        assertEquals(15360L, c.endTick)
    }

    @Test fun doublingAndHalvingAChordWorksAndIsBounded() {
        val ts = TimeSignature.FOUR_FOUR
        var c = CompositionEditor.appendChord(project(ts), chord("C"), ts.barTicks)
        val id = c.chordProgression.single().id

        c = CompositionEditor.doubleChordLength(c, id)
        assertEquals("doubling should double it", ts.barTicks * 2, c.chordProgression.single().durationTicks)
        c = CompositionEditor.halveChordLength(c, id)
        assertEquals(ts.barTicks, c.chordProgression.single().durationTicks)

        // Doubling cannot run away.
        repeat(12) { c = CompositionEditor.doubleChordLength(c, id) }
        assertEquals(ts.barTicks * 16, c.chordProgression.single().durationTicks)
        // Nor can halving reach zero.
        repeat(20) { c = CompositionEditor.halveChordLength(c, id) }
        assertTrue(c.chordProgression.single().durationTicks >= Ticks.SIXTEENTH)
    }

    @Test fun changingAChordLengthReflowsTheRest() {
        val ts = TimeSignature.FOUR_FOUR
        var c = project(ts)
        for (name in listOf("C", "G", "Am", "F")) {
            c = CompositionEditor.appendChord(c, Chord.of(name.take(1), ChordType.MAJOR)!!, ts.barTicks)
        }
        val first = c.chordProgression.first().id
        c = CompositionEditor.doubleChordLength(c, first)
        // The first chord is now two bars and everything after it has moved along.
        assertEquals(listOf(0L, 7680L, 11520L, 15360L), c.chordProgression.map { it.startTick })
        // No gaps and no overlaps.
        for (pair in c.chordProgression.zipWithNext()) {
            assertEquals(pair.first.endTick, pair.second.startTick)
        }
    }

    @Test fun describeLengthReadsNaturally() {
        val ts = TimeSignature.FOUR_FOUR
        assertEquals("1 bar", CompositionEditor.describeChordLength(ts.barTicks, ts))
        assertEquals("2 bars", CompositionEditor.describeChordLength(ts.barTicks * 2, ts))
        assertEquals("1 beat", CompositionEditor.describeChordLength(960, ts))
        assertEquals("2 beats", CompositionEditor.describeChordLength(1920, ts))
        assertEquals("3 beats", CompositionEditor.describeChordLength(2880, ts))
        assertEquals("16 bars", CompositionEditor.describeChordLength(ts.barTicks * 16, ts))
    }

    @Test fun aLongChordSoundsForItsWholeLength() {
        // The length has to reach the audio, not just the list.
        val ts = TimeSignature.FOUR_FOUR
        val c = CompositionEditor.appendChord(
            project(ts), chord("C"), ts.barTicks * 2, StrumPattern.BLOCK,
        )
        val event = c.chordProgression.single()
        val notes = ChordRenderer.render(event, c.track(1L)!!.fretboard)
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.all { it.durationTicks == ts.barTicks * 2 })

        val schedule = com.tabtap.app.audio.Sequencer.build(c, 48_000)
        val ons = schedule.events
            .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
        val offs = schedule.events
            .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOff>()
        assertTrue(ons.isNotEmpty())
        // Two bars at 120 BPM is four seconds, which is 192000 frames.
        assertEquals(192_000L, offs.maxOf { it.frame })
    }

    // ---------------------------------------------------------------- rearranging

    @Test fun chordsCanBeReorderedAfterBeingAdded() {
        val ts = TimeSignature.FOUR_FOUR
        var c = project(ts)
        for (name in listOf("C", "G", "D")) {
            c = CompositionEditor.appendChord(c, chord(name), ts.barTicks)
        }
        val last = c.chordProgression.last().id

        c = CompositionEditor.nudgeChord(c, last, -1)
        assertEquals(listOf("C", "D", "G"), c.chordProgression.map { it.chord.symbol })
        c = CompositionEditor.nudgeChord(c, last, -1)
        assertEquals(listOf("D", "C", "G"), c.chordProgression.map { it.chord.symbol })
        // Moving past the start does nothing harmful.
        c = CompositionEditor.nudgeChord(c, last, -1)
        assertEquals(listOf("D", "C", "G"), c.chordProgression.map { it.chord.symbol })
        // And the timings follow the order.
        assertEquals(listOf(0L, 3840L, 7680L), c.chordProgression.map { it.startTick })
    }

    @Test fun aChordCanBeReplacedInPlace() {
        val ts = TimeSignature.FOUR_FOUR
        var c = project(ts)
        for (name in listOf("C", "G", "D")) {
            c = CompositionEditor.appendChord(c, chord(name), ts.barTicks)
        }
        val middle = c.chordProgression[1].id
        c = CompositionEditor.replaceChord(
            c, middle, Chord.of("A", ChordType.MINOR)!!, ts.barTicks * 2,
            StrumPattern.ARPEGGIO_UP, null,
        )
        assertEquals(listOf("C", "Am", "D"), c.chordProgression.map { it.chord.symbol })
        assertEquals(ts.barTicks * 2, c.chordProgression[1].durationTicks)
        assertEquals(StrumPattern.ARPEGGIO_UP, c.chordProgression[1].strum)
        // A voicing was found for the new chord.
        assertNotNull(c.chordProgression[1].voicing)
        // And the progression stayed packed.
        assertEquals(listOf(0L, 3840L, 11520L), c.chordProgression.map { it.startTick })
    }

    @Test fun aChordCanBeRepeated() {
        val ts = TimeSignature.FOUR_FOUR
        var c = project(ts)
        c = CompositionEditor.appendChord(c, chord("C"), ts.barTicks)
        c = CompositionEditor.appendChord(c, chord("G"), ts.barTicks)
        val first = c.chordProgression.first().id
        c = CompositionEditor.duplicateChord(c, first)
        assertEquals(listOf("C", "C", "G"), c.chordProgression.map { it.chord.symbol })
        assertEquals(3, c.chordProgression.map { it.id }.distinct().size)
        assertEquals(listOf(0L, 3840L, 7680L), c.chordProgression.map { it.startTick })
    }

    @Test fun rearrangingKeepsTheCompositionValid() {
        val ts = TimeSignature.FIVE_FOUR
        var c = project(ts)
        for (name in listOf("C", "G", "D", "A", "E")) {
            c = CompositionEditor.appendChord(c, chord(name), ts.barTicks)
        }
        val ids = c.chordProgression.map { it.id }
        c = CompositionEditor.nudgeChord(c, ids.last(), -2)
        c = CompositionEditor.doubleChordLength(c, ids.first())
        c = CompositionEditor.duplicateChord(c, ids[2])
        c = CompositionEditor.removeChord(c, ids[1])
        assertEquals(emptyList<String>(), c.validate())
        for (pair in c.chordProgression.zipWithNext()) {
            assertEquals(pair.first.endTick, pair.second.startTick)
        }
    }

    // ---------------------------------------------------------------- note lengths

    @Test fun aNotesLengthCanBeChangedAfterTapping() {
        // The "I cannot set the rhythm after tapping" complaint: a tapped note's length
        // must be changeable directly, one note at a time.
        var c = project(TimeSignature.FOUR_FOUR)
        for (fret in listOf(0, 3, 5)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, 0, fret)
        }
        val notes = c.track(1L)!!.notes
        assertTrue(notes.all { it.durationTicks == NoteValue.QUARTER.ticks })

        // Halving and doubling one note leaves the others alone.
        val middle = notes[1].id
        c = CompositionEditor.setNoteDuration(c, 1L, middle, NoteValue.EIGHTH.ticks)
        assertEquals(
            listOf(NoteValue.QUARTER.ticks, NoteValue.EIGHTH.ticks, NoteValue.QUARTER.ticks),
            c.track(1L)!!.notes.map { it.durationTicks },
        )
        // And assigning an even rhythm afterwards resets all of them.
        c = CompositionEditor.applyEvenRhythm(c, 1L, NoteValue.SIXTEENTH)
        assertTrue(c.track(1L)!!.notes.all { it.durationTicks == NoteValue.SIXTEENTH.ticks })
        assertEquals(listOf(0L, 240L, 480L), c.track(1L)!!.notes.map { it.startTick })
    }

    // ---------------------------------------------------------------- looping

    @Test fun loopRegionsAreValidAndBarAligned() {
        val ts = TimeSignature.FOUR_FOUR
        val bar = ts.barTicks.toLong()
        // A whole-section loop covers every bar containing music, rounded out.
        var c = project(ts)
        for (i in 0 until 10) {
            c = CompositionEditor.addNoteAt(c, 1L, 0, 0, i * 960L, 960)
        }
        // Ten quarter notes is two and a half bars, so the loop should be three bars.
        val end = c.endTick
        val rounded = ((end + bar - 1) / bar) * bar
        assertEquals(3 * bar, rounded)
        val region = LoopRegion(0L, rounded)
        assertTrue(region.isValid)
        assertEquals(0L, region.startTick % bar)
        assertEquals(0L, region.endTick % bar)
    }

    @Test fun aLoopRegionAlwaysCoversAtLeastOneBar() {
        // An empty project must still produce a loopable region rather than a zero-length
        // one, which would make the engine spin.
        val region = LoopRegion(0L, TimeSignature.FOUR_FOUR.barTicks.toLong())
        assertTrue(region.isValid)
        assertTrue(LoopRegion(0L, 0L).isValid.not())
        assertTrue(LoopRegion(960L, 480L).isValid.not())
    }

    @Test fun theScheduleIsNotTruncatedAtTheLoopEnd() {
        // The loop is applied by wrapping frames, so the schedule has to cover the music
        // past the loop end; otherwise widening the loop while playing would find
        // nothing there.
        val ts = TimeSignature.FOUR_FOUR
        var c = project(ts)
        for (i in 0 until 16) {
            c = CompositionEditor.addNoteAt(c, 1L, 0, 0, i * 960L, 960)
        }
        val schedule = com.tabtap.app.audio.Sequencer.build(
            c, 48_000, fromTick = 0L, toTick = null,
        )
        assertEquals(
            16,
            schedule.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>().size,
        )
        // And frames for a tick well past a one-bar loop are still resolvable.
        assertTrue(schedule.frameAtTick(ts.barTicks.toLong() * 3) > 0)
    }
}
