package com.tabtap.app

import com.tabtap.app.editing.CompositionEditor
import com.tabtap.app.editing.EditStack
import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.GridFeel
import com.tabtap.music.GridResolution
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.Quantize
import com.tabtap.music.ScaleType
import com.tabtap.music.StrumPattern
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.TrackRole
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {

    private fun n(s: String) = Pitch.parse(s)!!

    private fun project(): Composition =
        Composition.newGuitarProject(Tunings.STANDARD_E, 120.0, Key.of("E", ScaleType.NATURAL_MINOR)!!)

    /** Tap a sequence of positions onto the fretboard, as the user would. */
    private fun tap(composition: Composition, vararg positions: Pair<Int, Int>): Composition =
        positions.fold(composition) { acc, (string, fret) ->
            CompositionEditor.appendNoteAtPosition(acc, 1L, string, fret)
        }

    // ---------------------------------------------------------------- note entry

    @Test fun tappingRecordsNotesInTheOrderTapped() {
        // Low E string: frets 0, 3, 5 — E2, G2, A2.
        val c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        val notes = c.track(1L)!!.notes
        assertEquals(3, notes.size)
        assertEquals(listOf(n("E2"), n("G2"), n("A2")), notes.map { it.pitch })
        assertEquals(listOf(0L, 960L, 1920L), notes.map { it.startTick })
    }

    @Test fun tappingTheSamePositionTwiceMakesTwoNotes() {
        val c = tap(project(), 2 to 7, 2 to 7, 2 to 7)
        val notes = c.track(1L)!!.notes
        assertEquals(3, notes.size)
        assertEquals(1, notes.map { it.pitch }.distinct().size)
        assertEquals(3, notes.map { it.startTick }.distinct().size)
        assertEquals(3, notes.map { it.id }.distinct().size)
    }

    @Test fun everyTappedNoteSoundsItsPosition() {
        var c = project()
        val board = c.track(1L)!!.fretboard
        for (string in 0 until board.stringCount) {
            for (fret in listOf(0, 1, 5, 12, 24)) {
                c = CompositionEditor.appendNoteAtPosition(c, 1L, string, fret)
            }
        }
        for (note in c.track(1L)!!.notes) {
            assertEquals(board.pitchAt(note.string!!, note.fret!!), note.pitch)
        }
        assertEquals(emptyList<String>(), c.validate())
    }

    @Test fun anImpossiblePositionIsRejectedRatherThanCrashing() {
        val c = project()
        assertEquals(c, CompositionEditor.appendNoteAtPosition(c, 1L, 9, 3))
        assertEquals(c, CompositionEditor.appendNoteAtPosition(c, 1L, 0, 99))
        assertEquals(c, CompositionEditor.appendNoteAtPosition(c, 99L, 0, 3))
    }

    @Test fun stackingBuildsAChordAtTheSamePosition() {
        var c = tap(project(), 0 to 0)
        c = CompositionEditor.stackNoteAtPosition(c, 1L, 1, 2)
        c = CompositionEditor.stackNoteAtPosition(c, 1L, 2, 2)
        val notes = c.track(1L)!!.notes
        assertEquals(3, notes.size)
        assertEquals(1, notes.map { it.startTick }.distinct().size)
        // E2, B2, E3 — an E power chord.
        assertEquals(listOf(n("E2"), n("B2"), n("E3")), notes.map { it.pitch }.sorted())
    }

    @Test fun removingTheLastNoteRemovesAWholeChord() {
        var c = tap(project(), 0 to 0)
        c = CompositionEditor.stackNoteAtPosition(c, 1L, 1, 2)
        c = CompositionEditor.stackNoteAtPosition(c, 1L, 2, 2)
        c = CompositionEditor.removeLastNote(c, 1L)
        assertEquals(0, c.track(1L)!!.notes.size)
    }

    @Test fun notesAreAddedAtExplicitPositionsForTheTabEditor() {
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 5, startTick = 1920L, durationTicks = 480)
        val note = c.track(1L)!!.notes.single()
        assertEquals(1920L, note.startTick)
        assertEquals(480, note.durationTicks)
        assertEquals(n("A2"), note.pitch)
    }

    @Test fun notesAddedByPitchGetAPlayableFingering() {
        var c = project()
        c = CompositionEditor.addNoteByPitch(c, 1L, n("C4"), 0L, 480)
        val note = c.track(1L)!!.notes.single()
        assertNotNull(note.fret)
        assertEquals(note.pitch, c.track(1L)!!.fretboard.pitchAt(note.string!!, note.fret!!))
    }

    @Test fun outOfRangePitchesAreRefused() {
        val c = project()
        assertEquals(c, CompositionEditor.addNoteByPitch(c, 1L, 200, 0L, 480))
        assertEquals(c, CompositionEditor.addNoteByPitch(c, 1L, -5, 0L, 480))
    }

    // ---------------------------------------------------------------- note editing

    @Test fun movingANoteChangesItsPitch() {
        var c = tap(project(), 0 to 0)
        val id = c.track(1L)!!.notes.single().id
        c = CompositionEditor.moveNoteToPosition(c, 1L, id, 0, 5)
        assertEquals(n("A2"), c.track(1L)!!.notes.single().pitch)
    }

    @Test fun transposingANoteKeepsItPlayable() {
        var c = tap(project(), 5 to 0)
        val id = c.track(1L)!!.notes.single().id
        for (semitones in listOf(1, 2, 5, 12, -1, -12)) {
            val moved = CompositionEditor.transposeNote(c, 1L, id, semitones)
            val note = moved.track(1L)!!.notes.single()
            assertEquals(n("E4") + semitones, note.pitch)
            assertNotNull("transposing by $semitones lost the fingering", note.fret)
            assertEquals(note.pitch, moved.track(1L)!!.fretboard.pitchAt(note.string!!, note.fret!!))
        }
    }

    @Test fun transposingPastTheMidiRangeIsRefused() {
        var c = project()
        c = CompositionEditor.addNoteByPitch(c, 1L, 126, 0L, 480)
        val id = c.track(1L)!!.notes.single().id
        assertEquals(c, CompositionEditor.transposeNote(c, 1L, id, 12))
    }

    @Test fun durationsAreClampedAboveZero() {
        var c = tap(project(), 0 to 0)
        val id = c.track(1L)!!.notes.single().id
        c = CompositionEditor.setNoteDuration(c, 1L, id, -50)
        assertTrue(c.track(1L)!!.notes.single().durationTicks >= 1)
    }

    @Test fun articulationsToggleAcrossASelection() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        val ids = c.track(1L)!!.notes.map { it.id }.toSet()
        c = CompositionEditor.toggleArticulation(c, 1L, ids, Articulation.PALM_MUTE)
        assertTrue(c.track(1L)!!.notes.all { it.has(Articulation.PALM_MUTE) })
        // Toggling again, when all of them have it, removes it.
        c = CompositionEditor.toggleArticulation(c, 1L, ids, Articulation.PALM_MUTE)
        assertTrue(c.track(1L)!!.notes.none { it.has(Articulation.PALM_MUTE) })
    }

    @Test fun togglingOnAMixedSelectionTurnsItOnForAll() {
        var c = tap(project(), 0 to 0, 0 to 3)
        val ids = c.track(1L)!!.notes.map { it.id }.toSet()
        val first = c.track(1L)!!.notes.first().id
        c = CompositionEditor.toggleArticulation(c, 1L, setOf(first), Articulation.VIBRATO)
        c = CompositionEditor.toggleArticulation(c, 1L, ids, Articulation.VIBRATO)
        assertTrue(c.track(1L)!!.notes.all { it.has(Articulation.VIBRATO) })
    }

    @Test fun bendsSetTheArticulationAndTheAmount() {
        var c = tap(project(), 0 to 0)
        val ids = setOf(c.track(1L)!!.notes.single().id)
        c = CompositionEditor.setBend(c, 1L, ids, 2f)
        assertTrue(c.track(1L)!!.notes.single().has(Articulation.BEND))
        assertEquals(2f, c.track(1L)!!.notes.single().bendSemitones, 0f)
        c = CompositionEditor.setBend(c, 1L, ids, 0f)
        assertFalse(c.track(1L)!!.notes.single().has(Articulation.BEND))
    }

    @Test fun refingeringCyclesThroughThePositionsForTheSamePitch() {
        var c = project()
        c = CompositionEditor.addNoteByPitch(c, 1L, n("E4"), 0L, 480)
        val id = c.track(1L)!!.notes.single().id
        val seen = HashSet<Pair<Int, Int>>()
        repeat(7) {
            val note = c.track(1L)!!.notes.single()
            seen += note.string!! to note.fret!!
            // The pitch must never change when only the fingering does.
            assertEquals(n("E4"), note.pitch)
            c = CompositionEditor.refingerNote(c, 1L, id)
        }
        assertTrue("refingering never moved the note", seen.size > 1)
    }

    // ---------------------------------------------------------------- copy and paste

    @Test fun copyAndPastePreservesRelativeTiming() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        val ids = c.track(1L)!!.notes.map { it.id }.toSet()
        val clipboard = CompositionEditor.copy(c, 1L, ids)
        c = CompositionEditor.paste(c, 1L, clipboard, atTick = 3840L)
        val notes = c.track(1L)!!.notes
        assertEquals(6, notes.size)
        val pasted = notes.filter { it.startTick >= 3840L }
        assertEquals(listOf(3840L, 4800L, 5760L), pasted.map { it.startTick })
        assertEquals(listOf(n("E2"), n("G2"), n("A2")), pasted.map { it.pitch })
        // Pasted notes get fresh ids.
        assertEquals(6, notes.map { it.id }.distinct().size)
    }

    @Test fun pastingOntoABassTrackRefretsThePitches() {
        var c = tap(project(), 0 to 0, 0 to 5)
        val clipboard = CompositionEditor.copy(c, 1L, c.track(1L)!!.notes.map { it.id }.toSet())
        c = CompositionEditor.addTrack(c, "Bass", TrackRole.BASS, Tunings.BASS_STANDARD)
        val bassId = c.tracks.last().id
        c = CompositionEditor.paste(c, bassId, clipboard, 0L)
        val bass = c.track(bassId)!!
        assertEquals(2, bass.notes.size)
        for (note in bass.notes) {
            assertEquals(bass.fretboard.pitchAt(note.string!!, note.fret!!), note.pitch)
        }
        assertEquals(emptyList<String>(), c.validate())
    }

    @Test fun duplicatingASelectionPlacesItAfterItself() {
        var c = tap(project(), 0 to 0, 0 to 3)
        val ids = c.track(1L)!!.notes.map { it.id }.toSet()
        c = CompositionEditor.duplicateSelection(c, 1L, ids)
        assertEquals(4, c.track(1L)!!.notes.size)
        assertEquals(listOf(0L, 960L, 1920L, 2880L), c.track(1L)!!.notes.map { it.startTick })
    }

    @Test fun pastingAnEmptyClipboardDoesNothing() {
        val c = tap(project(), 0 to 0)
        assertEquals(c, CompositionEditor.paste(c, 1L, CompositionEditor.copy(c, 1L, emptySet()), 0L))
    }

    // ---------------------------------------------------------------- rhythm

    @Test fun evenRhythmKeepsThePitchesAndTheirOrder() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5, 0 to 7)
        val before = c.track(1L)!!.notes.map { it.pitch }
        c = CompositionEditor.applyEvenRhythm(c, 1L, NoteValue.EIGHTH)
        val after = c.track(1L)!!
        assertEquals(before, after.notes.map { it.pitch })
        assertEquals(listOf(0L, 480L, 960L, 1440L), after.notes.map { it.startTick })
        assertTrue(after.notes.all { it.durationTicks == 480 })
    }

    @Test fun evenRhythmTreatsAChordAsOneStep() {
        var c = tap(project(), 0 to 0)
        c = CompositionEditor.stackNoteAtPosition(c, 1L, 1, 2)
        c = CompositionEditor.appendNoteAtPosition(c, 1L, 0, 3)
        c = CompositionEditor.applyEvenRhythm(c, 1L, NoteValue.QUARTER)
        val notes = c.track(1L)!!.notes
        assertEquals(3, notes.size)
        // Two notes at tick 0, one at tick 960 — the chord stayed a chord.
        assertEquals(2, notes.count { it.startTick == 0L })
        assertEquals(1, notes.count { it.startTick == 960L })
    }

    @Test fun aTappedRhythmIsAppliedToTheExistingNotes() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        // A dotted-eighth, sixteenth, quarter pattern.
        val onsets = listOf(0L, 720L, 960L)
        c = CompositionEditor.applyTappedRhythm(c, 1L, onsets, tailTicks = 960)
        val notes = c.track(1L)!!.notes
        assertEquals(listOf(0L, 720L, 960L), notes.map { it.startTick })
        assertEquals(listOf(720, 240, 960), notes.map { it.durationTicks })
        assertEquals(listOf(n("E2"), n("G2"), n("A2")), notes.map { it.pitch })
    }

    @Test fun aShortRhythmRepeatsOverALongerMelody() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5, 0 to 7)
        // Two taps for four notes: the pattern should cycle.
        c = CompositionEditor.applyTappedRhythm(c, 1L, listOf(0L, 480L), tailTicks = 480)
        val starts = c.track(1L)!!.notes.map { it.startTick }
        assertEquals(4, starts.size)
        assertEquals(starts.sorted(), starts)
        assertEquals(4, starts.distinct().size)
    }

    @Test fun quantiseSnapsToTheGridWithoutBreakingChords() {
        var c = project()
        // Two notes a few ticks apart, as a strummed chord would be.
        c = CompositionEditor.addNoteAt(c, 1L, 0, 0, 7L, 480)
        c = CompositionEditor.addNoteAt(c, 1L, 1, 2, 7L, 480)
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 487L, 480)
        c = CompositionEditor.quantize(c, 1L, Quantize(GridResolution.SIXTEENTH))
        val notes = c.track(1L)!!.notes
        assertEquals(listOf(0L, 0L, 480L), notes.map { it.startTick }.sorted())
    }

    @Test fun quantiseCanBeLimitedToASelection() {
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 0, 7L, 480)
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 487L, 480)
        val firstId = c.track(1L)!!.notes.first().id
        c = CompositionEditor.quantize(c, 1L, Quantize(GridResolution.SIXTEENTH), setOf(firstId))
        val notes = c.track(1L)!!.notes.sortedBy { it.startTick }
        assertEquals(0L, notes[0].startTick)
        assertEquals("the unselected note should not have moved", 487L, notes[1].startTick)
    }

    @Test fun swingQuantisationDelaysTheOffBeats() {
        var c = project()
        for (i in 0 until 4) {
            c = CompositionEditor.addNoteAt(c, 1L, 0, i, i * 480L, 480)
        }
        c = CompositionEditor.quantize(
            c, 1L,
            Quantize(GridResolution.EIGHTH, GridFeel.SWING, swingRatio = 2.0 / 3.0),
        )
        val starts = c.track(1L)!!.notes.map { it.startTick }
        assertEquals(0L, starts[0])
        assertEquals(640L, starts[1])
        assertEquals(960L, starts[2])
    }

    @Test fun humaniseIsSmallAndRepeatable() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5, 0 to 7)
        val a = CompositionEditor.humanize(c, 1L, 40)
        val b = CompositionEditor.humanize(c, 1L, 40)
        assertEquals(a.track(1L)!!.notes.map { it.startTick }, b.track(1L)!!.notes.map { it.startTick })
        val original = c.track(1L)!!.notes.map { it.startTick }
        a.track(1L)!!.notes.forEachIndexed { i, note ->
            assertTrue(kotlin.math.abs(note.startTick - original[i]) <= 40)
        }
    }

    @Test fun legatoRemovesGapsBetweenNotes() {
        var c = project()
        c = CompositionEditor.addNoteAt(c, 1L, 0, 0, 0L, 240)
        c = CompositionEditor.addNoteAt(c, 1L, 0, 3, 960L, 240)
        c = CompositionEditor.addNoteAt(c, 1L, 0, 5, 1920L, 240)
        c = CompositionEditor.makeLegato(c, 1L)
        val notes = c.track(1L)!!.notes
        assertEquals(960, notes[0].durationTicks)
        assertEquals(960, notes[1].durationTicks)
        assertEquals("the last note keeps its own length", 240, notes[2].durationTicks)
    }

    // ---------------------------------------------------------------- key and tuning

    @Test fun changingTheKeyDoesNotMoveAnyNote() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        val before = c.track(1L)!!.notes
        c = CompositionEditor.setKey(c, Key.of("Bb", ScaleType.MAJOR)!!)
        assertEquals(before, c.track(1L)!!.notes)
        assertEquals("Bb maj", c.key.shortName)
    }

    @Test fun transposingToAKeyMovesEveryNoteByTheShorterInterval() {
        var c = tap(project(), 0 to 0, 0 to 3, 0 to 5)
        val before = c.track(1L)!!.notes.map { it.pitch }
        // E minor to F minor is up one semitone, not down eleven.
        c = CompositionEditor.transposeToKey(c, Key.of("F", ScaleType.NATURAL_MINOR)!!)
        assertEquals(before.map { it + 1 }, c.track(1L)!!.notes.map { it.pitch })
    }

    @Test fun changingTuningKeepsPitchesAndRefretsThem() {
        var c = tap(project(), 0 to 3, 0 to 5, 1 to 2)
        val before = c.track(1L)!!.notes.map { it.pitch }
        c = CompositionEditor.setTuning(c, 1L, Tunings.DROP_C)
        val after = c.track(1L)!!
        assertEquals("retuning must not change the music", before, after.notes.map { it.pitch })
        assertEquals(Tunings.DROP_C, after.tuning)
        for (note in after.notes) {
            if (note.fret != null) {
                assertEquals(note.pitch, after.fretboard.pitchAt(note.string!!, note.fret!!))
            }
        }
        assertEquals(emptyList<String>(), c.validate())
    }

    @Test fun switchingToBassKeepsTheProjectAndThePitches() {
        var c = tap(project(), 2 to 0, 2 to 2, 2 to 4)
        val before = c.track(1L)!!.notes.map { it.pitch }
        c = CompositionEditor.switchInstrumentFamily(c, 1L, InstrumentFamily.BASS)
        val track = c.track(1L)!!
        assertEquals(InstrumentFamily.BASS, track.tuning.family)
        assertEquals(before, track.notes.map { it.pitch })
        assertEquals(InstrumentVoice.BASS_FINGER, track.voice)
        assertEquals(com.tabtap.music.ClefType.BASS_8VB, track.clef)
        // And back again, still intact.
        c = CompositionEditor.switchInstrumentFamily(c, 1L, InstrumentFamily.GUITAR)
        assertEquals(before, c.track(1L)!!.notes.map { it.pitch })
    }

    @Test fun notesTooLowForTheNewTuningKeepTheirPitch() {
        var c = project()
        c = CompositionEditor.addNoteByPitch(c, 1L, n("E2"), 0L, 480)
        // A 4-string bass starts at E1, so E2 is fine; a tuning *up* would not fit.
        c = CompositionEditor.setTuning(c, 1L, Tunings.custom(InstrumentFamily.GUITAR, listOf(70, 72, 74, 76, 78, 80)))
        val note = c.track(1L)!!.notes.single()
        assertEquals("the pitch must survive even when unplayable", n("E2"), note.pitch)
        assertNull(note.fret)
        assertEquals(1, c.track(1L)!!.unplayableNotes().size)
        // And the composition is still structurally valid.
        assertEquals(emptyList<String>(), c.validate())
    }

    @Test fun changingStringCountExtendsTheTuningDownwards() {
        var c = tap(project(), 5 to 0)
        c = CompositionEditor.setTuning(c, 1L, c.track(1L)!!.tuning.withStringCount(7))
        assertEquals(7, c.track(1L)!!.tuning.stringCount)
        assertEquals(n("E4"), c.track(1L)!!.notes.single().pitch)
    }

    // ---------------------------------------------------------------- tracks

    @Test fun soloSilencesTheOthers() {
        var c = tap(project(), 0 to 0)
        c = CompositionEditor.addTrack(c)
        val second = c.tracks.last().id
        c = CompositionEditor.setTrackSoloed(c, second, true)
        assertEquals(listOf(second), c.audibleTracks.map { it.id })
        c = CompositionEditor.clearSolos(c)
        assertEquals(2, c.audibleTracks.size)
    }

    @Test fun mutingRemovesATrackFromTheMix() {
        var c = tap(project(), 0 to 0)
        c = CompositionEditor.setTrackMuted(c, 1L, true)
        assertTrue(c.audibleTracks.isEmpty())
    }

    @Test fun theLastTrackCannotBeRemoved() {
        val c = project()
        assertEquals(c, CompositionEditor.removeTrack(c, 1L))
    }

    @Test fun removingATrackUnlinksItsHarmonies() {
        var c = tap(project(), 0 to 0, 0 to 3)
        c = CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        c = CompositionEditor.addTrack(c)
        c = CompositionEditor.removeTrack(c, 1L)
        assertTrue(c.tracks.none { it.derivedFromTrackId == 1L })
        assertNotNull(c.activeTrackId)
    }

    // ---------------------------------------------------------------- chords

    @Test fun chordsArePackedEndToEnd() {
        var c = project()
        val chords = listOf("E", "C", "G", "D").mapNotNull { Chord.of(it, ChordType.MAJOR) }
        for (chord in chords) {
            c = CompositionEditor.appendChord(c, chord, TimeSignature.FOUR_FOUR.barTicks)
        }
        assertEquals(4, c.chordProgression.size)
        assertEquals(listOf(0L, 3840L, 7680L, 11520L), c.chordProgression.map { it.startTick })
        assertEquals(listOf("E", "C", "G", "D"), c.chordProgression.map { it.chord.symbol })
    }

    @Test fun appendedChordsGetAPlayableVoicing() {
        var c = project()
        c = CompositionEditor.appendChord(c, Chord.of("A", ChordType.MINOR)!!, 3840)
        val voicing = c.chordProgression.single().voicing
        assertNotNull(voicing)
        val board = c.track(1L)!!.fretboard
        val classes = voicing!!.pitches(board).map { Pitch.pitchClass(it) }.toSet()
        assertEquals(setOf(9, 0, 4), classes)
    }

    @Test fun removingAChordClosesTheGap() {
        var c = project()
        for (name in listOf("E", "C", "G")) {
            c = CompositionEditor.appendChord(c, Chord.of(name, ChordType.MAJOR)!!, 1920)
        }
        val middle = c.chordProgression[1].id
        c = CompositionEditor.removeChord(c, middle)
        assertEquals(listOf(0L, 1920L), c.chordProgression.map { it.startTick })
        assertEquals(listOf("E", "G"), c.chordProgression.map { it.chord.symbol })
    }

    @Test fun reorderingChordsReflowsTheTiming() {
        var c = project()
        for (name in listOf("E", "C", "G")) {
            c = CompositionEditor.appendChord(c, Chord.of(name, ChordType.MAJOR)!!, 1920)
        }
        val last = c.chordProgression.last().id
        c = CompositionEditor.moveChord(c, last, 0)
        assertEquals(listOf("G", "E", "C"), c.chordProgression.map { it.chord.symbol })
        assertEquals(listOf(0L, 1920L, 3840L), c.chordProgression.map { it.startTick })
    }

    @Test fun chordsCanBeWrittenIntoATrackAsNotes() {
        var c = project()
        c = CompositionEditor.appendChord(c, Chord.of("E", ChordType.MINOR)!!, 1920, StrumPattern.BLOCK)
        c = CompositionEditor.appendChord(c, Chord.of("C", ChordType.MAJOR)!!, 1920, StrumPattern.BLOCK)
        c = CompositionEditor.renderChordsToTrack(c, 1L)
        val notes = c.track(1L)!!.notes
        assertTrue("expected a chord's worth of notes", notes.size >= 6)
        assertEquals(emptyList<String>(), c.validate())
        val first = notes.filter { it.startTick == 0L }.map { Pitch.pitchClass(it.pitch) }.toSet()
        assertEquals(setOf(4, 7, 11), first)
    }

    // ---------------------------------------------------------------- undo stack

    @Test fun undoAndRedoWalkTheHistory() {
        val stack = EditStack(project())
        var c = project()
        assertFalse(stack.canUndo)

        c = tap(c, 0 to 0)
        stack.push(c, "Add note 1")
        c = tap(c, 0 to 3)
        stack.push(c, "Add note 2")
        c = tap(c, 0 to 5)
        stack.push(c, "Add note 3")

        assertEquals(3, stack.current.composition.track(1L)!!.notes.size)
        assertEquals(2, stack.undo()!!.track(1L)!!.notes.size)
        assertEquals(1, stack.undo()!!.track(1L)!!.notes.size)
        assertEquals(0, stack.undo()!!.track(1L)!!.notes.size)
        assertNull(stack.undo())

        assertEquals(1, stack.redo()!!.track(1L)!!.notes.size)
        assertEquals(2, stack.redo()!!.track(1L)!!.notes.size)
        assertEquals(3, stack.redo()!!.track(1L)!!.notes.size)
        assertNull(stack.redo())
    }

    @Test fun aNewEditDiscardsTheRedoBranch() {
        val stack = EditStack(project())
        var c = tap(project(), 0 to 0)
        stack.push(c, "one")
        c = tap(c, 0 to 3)
        stack.push(c, "two")
        stack.undo()
        assertTrue(stack.canRedo)
        stack.push(tap(stack.current.composition, 0 to 7), "three")
        assertFalse(stack.canRedo)
    }

    @Test fun anEditThatChangesNothingTakesNoUndoStep() {
        val stack = EditStack(project())
        val c = tap(project(), 0 to 0)
        stack.push(c, "one")
        val depth = stack.undoDepth
        stack.push(c, "same again")
        assertEquals(depth, stack.undoDepth)
    }

    @Test fun rapidSimilarEditsCoalesceIntoOneStep() {
        var now = 1_000L
        val stack = EditStack(project(), clock = { now })
        var c = project()
        // Dragging a fader: ten changes in a third of a second.
        repeat(10) { i ->
            c = CompositionEditor.setTrackVolume(c, 1L, 0.5f + i * 0.02f)
            stack.push(c, "Set volume", coalesceKey = "volume-1")
            now += 30
        }
        assertEquals("a fader drag should be one undo step", 1, stack.undoDepth)
        // After the window closes a new change starts a new step.
        now += 2_000
        c = CompositionEditor.setTrackVolume(c, 1L, 0.1f)
        stack.push(c, "Set volume", coalesceKey = "volume-1")
        assertEquals(2, stack.undoDepth)
    }

    @Test fun differentCoalesceKeysDoNotMerge() {
        var now = 0L
        val stack = EditStack(project(), clock = { now })
        var c = CompositionEditor.setTrackVolume(project(), 1L, 0.4f)
        stack.push(c, "volume", "volume-1")
        c = CompositionEditor.setTrackPan(c, 1L, 0.5f)
        stack.push(c, "pan", "pan-1")
        assertEquals(2, stack.undoDepth)
    }

    @Test fun theHistoryIsBounded() {
        val stack = EditStack(project(), capacity = 5)
        var c = project()
        repeat(20) {
            c = tap(c, 0 to (it % 12))
            stack.push(c, "note $it")
        }
        assertEquals(5, stack.undoDepth)
        // Unwinding to the end leaves a valid composition, not a crash.
        while (stack.canUndo) assertNotNull(stack.undo())
        assertNotNull(stack.current.composition)
    }

    @Test fun replacingTheCurrentStateIsNotUndoable() {
        val stack = EditStack(project())
        stack.push(tap(project(), 0 to 0), "note")
        val depth = stack.undoDepth
        stack.replaceCurrent(CompositionEditor.setActiveTrack(stack.current.composition, 1L))
        assertEquals(depth, stack.undoDepth)
    }

    @Test fun resetClearsTheHistory() {
        val stack = EditStack(project())
        stack.push(tap(project(), 0 to 0), "note")
        stack.reset(project())
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(0, stack.current.composition.track(1L)!!.notes.size)
    }

    @Test fun everyEditLeavesAValidComposition() {
        // A long, varied sequence of operations: nothing may corrupt the document.
        var c = project()
        c = tap(c, 0 to 0, 1 to 2, 2 to 2, 3 to 1, 4 to 0, 5 to 0)
        val ids = c.track(1L)!!.notes.map { it.id }.toSet()
        val operations: List<(Composition) -> Composition> = listOf(
            { CompositionEditor.applyEvenRhythm(it, 1L, NoteValue.EIGHTH) },
            { CompositionEditor.quantize(it, 1L, Quantize(GridResolution.SIXTEENTH)) },
            { CompositionEditor.humanize(it, 1L, 30) },
            { CompositionEditor.makeLegato(it, 1L) },
            { CompositionEditor.transposeNotes(it, 1L, ids, 2) },
            { CompositionEditor.setTuning(it, 1L, Tunings.DROP_C) },
            { CompositionEditor.setKey(it, Key.of("C", ScaleType.MAJOR)!!) },
            { CompositionEditor.generateHarmonies(it, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first },
            { CompositionEditor.appendChord(it, Chord.of("C", ChordType.MAJOR)!!, 3840) },
            { CompositionEditor.addTrack(it) },
            { CompositionEditor.switchInstrumentFamily(it, 1L, InstrumentFamily.BASS) },
            { CompositionEditor.duplicateSelection(it, 1L, ids) },
            { CompositionEditor.setFretCount(it, 1L, 12) },
            { CompositionEditor.reflowChords(it) },
        )
        for ((index, operation) in operations.withIndex()) {
            c = operation(c)
            assertEquals("operation $index left the composition invalid", emptyList<String>(), c.validate())
        }
    }
}
