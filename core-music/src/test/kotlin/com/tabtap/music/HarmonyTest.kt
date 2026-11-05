package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyTest {

    private val eMinor = Key.of("E", ScaleType.NATURAL_MINOR)!!
    private val cMajor = Key.of("C", ScaleType.MAJOR)!!
    private val aMinorPent = Key.of("A", ScaleType.MINOR_PENTATONIC)!!

    private fun n(name: String) = Pitch.parse(name)!!

    // --- The central claim: diatonic harmony is not a fixed transposition. ---

    @Test fun diatonicThirdsAlternateMajorAndMinorAsTheKeyRequires() {
        // Harmonising the E natural-minor scale a third above must give
        // E->G (3), F#->A (3), G->B (4), A->C (3), B->D (3), C->E (4), D->F# (4).
        val scale = listOf("E3", "F#3", "G3", "A3", "B3", "C4", "D4").map { n(it) }
        val expected = listOf("G3", "A3", "B3", "C4", "D4", "E4", "F#4").map { n(it) }
        val got = scale.map { HarmonyGenerator.diatonicTranspose(it, eMinor, 2) }
        assertEquals(expected, got)

        val intervals = scale.indices.map { got[it] - scale[it] }
        assertEquals(listOf(3, 3, 4, 3, 3, 4, 4), intervals)
        // Proof that this is genuinely not a constant transposition.
        assertTrue("diatonic harmony collapsed to a fixed interval", intervals.distinct().size > 1)
    }

    @Test fun diatonicSixthsAlternateInCMajor() {
        val scale = listOf("C4", "D4", "E4", "F4", "G4", "A4", "B4").map { n(it) }
        val expected = listOf("A4", "B4", "C5", "D5", "E5", "F5", "G5").map { n(it) }
        assertEquals(expected, scale.map { HarmonyGenerator.diatonicTranspose(it, cMajor, 5) })
    }

    @Test fun diatonicThirdBelowDescends() {
        val scale = listOf("E4", "F#4", "G4", "A4", "B4").map { n(it) }
        val expected = listOf("C4", "D4", "E4", "F#4", "G4").map { n(it) }
        assertEquals(expected, scale.map { HarmonyGenerator.diatonicTranspose(it, eMinor, -2) })
    }

    @Test fun diatonicHarmonyStaysInKey() {
        for (steps in listOf(-5, -4, -2, 2, 3, 4, 5)) {
            for (scaleNote in eMinor.pitchClasses) {
                for (oct in 2..5) {
                    val pitch = scaleNote + (oct + 1) * 12
                    if (!Pitch.isValid(pitch)) continue
                    val h = HarmonyGenerator.diatonicTranspose(pitch, eMinor, steps)
                    assertTrue(
                        "steps=$steps ${Pitch.fullName(pitch)} -> ${Pitch.fullName(h)} left E minor",
                        eMinor.contains(h),
                    )
                }
            }
        }
    }

    @Test fun pentatonicHarmonyMovesByPentatonicSteps() {
        // A minor pentatonic: A C D E G. One step above A is C, above C is D.
        assertEquals(n("C4"), HarmonyGenerator.diatonicTranspose(n("A3"), aMinorPent, 1))
        assertEquals(n("D4"), HarmonyGenerator.diatonicTranspose(n("C4"), aMinorPent, 1))
        // Two steps above A is D.
        assertEquals(n("D4"), HarmonyGenerator.diatonicTranspose(n("A3"), aMinorPent, 2))
        // Five steps is the octave of a five-note scale.
        assertEquals(n("A4"), HarmonyGenerator.diatonicTranspose(n("A3"), aMinorPent, 5))
    }

    @Test fun stepsOfScaleSizeAlwaysGiveAnOctave() {
        for (scale in ScaleType.BUILT_IN) {
            val k = Key.of("D", scale)!!
            for (p in 50..70) {
                if (!k.contains(p)) continue
                assertEquals(
                    "${scale.id} octave at $p",
                    p + 12,
                    HarmonyGenerator.diatonicTranspose(p, k, scale.size),
                )
            }
        }
    }

    @Test fun zeroStepsIsIdentity() {
        assertEquals(n("E3"), HarmonyGenerator.diatonicTranspose(n("E3"), eMinor, 0))
    }

    // --- Chromatic notes ---

    @Test fun chromaticNotesSnapIntoTheKeyByDefault() {
        // D# is the raised seventh of E minor. The default keeps the harmony in key,
        // so a third above lands on F#, the diatonic third above the seventh degree.
        val h = HarmonyGenerator.diatonicTranspose(
            n("D#4"), eMinor, 2, ChromaticHandling.SNAP_TO_SCALE,
        )
        assertEquals(n("F#4"), h)
        assertTrue(eMinor.contains(h))
    }

    @Test fun chromaticNotesCanFollowTheLineInstead() {
        // In parallel mode the note keeps its own alteration, so the harmony moves by
        // exactly the interval the unaltered degree would have received.
        val plainD = HarmonyGenerator.diatonicTranspose(n("D4"), eMinor, 2)
        val sharpD = HarmonyGenerator.diatonicTranspose(
            n("D#4"), eMinor, 2, ChromaticHandling.PARALLEL,
        )
        assertEquals(plainD - n("D4"), sharpD - n("D#4"))
        assertEquals(n("G4"), sharpD)
    }

    // --- Chromatic (parallel) mode and octaves ---

    @Test fun parallelModeTransposesByAFixedInterval() {
        val spec = HarmonySpec.customInterval(7)
        for (p in 40..80) {
            assertEquals(p + 7, HarmonyGenerator.harmonizePitch(p, eMinor, spec))
        }
    }

    @Test fun octaveHarmoniesAreExact() {
        for (p in 40..80) {
            assertEquals(p + 12, HarmonyGenerator.harmonizePitch(p, eMinor, HarmonySpec.OCTAVE_ABOVE))
            assertEquals(p - 12, HarmonyGenerator.harmonizePitch(p, eMinor, HarmonySpec.OCTAVE_BELOW))
        }
    }

    @Test fun octaveShiftStacksOnTopOfTheInterval() {
        val spec = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = 2, octaveShift = 1)
        assertEquals(n("G4"), HarmonyGenerator.harmonizePitch(n("E3"), eMinor, spec))
    }

    @Test fun harmonyPitchesNeverLeaveTheMidiRange() {
        val up = HarmonySpec(HarmonyMode.CHROMATIC, semitones = 0, octaveShift = 4)
        val down = HarmonySpec(HarmonyMode.CHROMATIC, semitones = 0, octaveShift = -4)
        for (p in 0..127) {
            assertTrue(Pitch.isValid(HarmonyGenerator.harmonizePitch(p, eMinor, up)))
            assertTrue(Pitch.isValid(HarmonyGenerator.harmonizePitch(p, eMinor, down)))
        }
    }

    @Test fun harmonySpellingIsKeyCorrect() {
        val s = HarmonyGenerator.harmonizeSpelled(n("D4"), eMinor, HarmonySpec.THIRD_ABOVE)
        assertEquals(NoteLetter.F, s.letter)
        assertEquals(1, s.alter)
        assertEquals(n("F#4"), s.midi)
    }

    // --- Track generation ---

    private fun solo(): Track {
        // An E minor lick: E F# G A B.
        val board = Fretboard(Tunings.STANDARD_E, 24)
        val pitches = listOf("E4", "F#4", "G4", "A4", "B4").map { n(it) }
        val notes = pitches.mapIndexed { i, p ->
            val pos = board.bestPositionFor(p, 12)!!
            MusicNote(
                id = (i + 1).toLong(), pitch = p,
                startTick = i * 480L, durationTicks = 480,
                string = pos.string, fret = pos.fret,
                articulations = if (i == 4) setOf(Articulation.VIBRATO) else emptySet(),
            )
        }
        return Track(1L, "Lead", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24, notes)
    }

    @Test fun generatedTrackPreservesRhythmNoteForNote() {
        val src = solo()
        val r = HarmonyGenerator.generateTrack(src, eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        assertEquals(src.notes.size, r.track.notes.size)
        src.notes.forEachIndexed { i, s ->
            assertEquals(s.startTick, r.track.notes[i].startTick)
            assertEquals(s.durationTicks, r.track.notes[i].durationTicks)
        }
    }

    @Test fun generatedTrackHarmonisesEveryPitch() {
        val src = solo()
        val r = HarmonyGenerator.generateTrack(src, eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        assertEquals(
            listOf("G4", "A4", "B4", "C5", "D5").map { n(it) },
            r.track.notes.map { it.pitch },
        )
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun generatedNotesAreFrettedConsistentlyWithTheirPitch() {
        val src = solo()
        val r = HarmonyGenerator.generateTrack(src, eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        val board = r.track.fretboard
        for (note in r.track.notes) {
            assertNotNull("harmony note lost its fingering", note.string)
            assertEquals(note.pitch, board.pitchAt(note.string!!, note.fret!!))
        }
    }

    @Test fun generatedTrackIsMarkedAsDerived() {
        val r = HarmonyGenerator.generateTrack(solo(), eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        assertEquals(TrackRole.HARMONY, r.track.role)
        assertEquals(1L, r.track.derivedFromTrackId)
        assertEquals(HarmonySpec.THIRD_ABOVE, r.track.harmonySpec)
        assertEquals("Diatonic 3rd above", r.track.name)
    }

    @Test fun expressiveArticulationsTransferButFingeringOnesDoNot() {
        val src = solo().let { t ->
            t.copy(notes = t.notes.map { it.withArticulation(Articulation.HAMMER_ON) })
        }
        val r = HarmonyGenerator.generateTrack(src, eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        assertTrue(r.track.notes.last().has(Articulation.VIBRATO))
        assertFalse(r.track.notes.first().has(Articulation.HAMMER_ON))
    }

    @Test fun noteIdsAreUniqueAndDoNotCollideWithTheSource() {
        val src = solo()
        val r = HarmonyGenerator.generateTrack(src, eMinor, HarmonySpec.THIRD_ABOVE, 2L, 100L)
        val ids = r.track.notes.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.none { it in src.notes.map { s -> s.id } })
    }

    @Test fun outOfRangeHarmonyIsMovedByOctavesToFitTheFretboard() {
        // A bass has no room three octaves up; the generator should fold it back in.
        val bass = Track(
            1L, "Bass", TrackRole.BASS, InstrumentVoice.BASS_FINGER, Tunings.BASS_STANDARD, 24,
            listOf(MusicNote(1L, n("G2"), 0L, 480)),
        )
        val spec = HarmonySpec(HarmonyMode.CHROMATIC, semitones = 0, octaveShift = 3, fitToFretboard = true)
        val r = HarmonyGenerator.generateTrack(bass, cMajor, spec, 2L, 10L)
        assertTrue(r.warnings.isEmpty())
        assertTrue("harmony note was left unplayable", r.track.notes.all { it.fret != null })
        // Pitch class is preserved even though the octave moved.
        assertEquals(Pitch.pitchClass(n("G2")), Pitch.pitchClass(r.track.notes.first().pitch))
    }

    @Test fun unplayableHarmonyIsReportedRatherThanDropped() {
        val bass = Track(
            1L, "Bass", TrackRole.BASS, InstrumentVoice.BASS_FINGER, Tunings.BASS_STANDARD, 24,
            listOf(MusicNote(1L, n("E1"), 0L, 480)),
        )
        val spec = HarmonySpec(
            HarmonyMode.CHROMATIC, semitones = 0, octaveShift = -1, fitToFretboard = false,
        )
        val r = HarmonyGenerator.generateTrack(bass, cMajor, spec, 2L, 10L)
        assertEquals(1, r.warnings.size)
        // The note still exists with its real pitch, just without a fingering.
        assertEquals(1, r.track.notes.size)
        assertEquals(n("E0"), r.track.notes.first().pitch)
        assertEquals(null, r.track.notes.first().fret)
    }

    // --- Multi-voice generation through the composition ---

    private fun composition(): Composition {
        var c = Composition.newGuitarProject(Tunings.STANDARD_E, 120.0, eMinor)
        val src = solo()
        c = c.updateTrack(1L) { it.copy(notes = src.notes) }
        return c.copy(nextId = 100L)
    }

    @Test fun multipleHarmonyVoicesAreGeneratedAtOnce() {
        val (c, warnings) = HarmonyGenerator.generate(
            composition(), 1L,
            listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.FIFTH_ABOVE, HarmonySpec.OCTAVE_BELOW),
        )
        assertTrue(warnings.isEmpty())
        assertEquals(4, c.tracks.size)
        assertEquals(3, c.tracks.count { it.role == TrackRole.HARMONY })
        // Each voice gets its own colour slot so the views can distinguish them.
        assertEquals(3, c.tracks.filter { it.role == TrackRole.HARMONY }.map { it.colorSlot }.distinct().size)
    }

    @Test fun regeneratingTheSameVoiceReplacesItInsteadOfPilingUp() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        assertEquals(2, c.tracks.size)
    }

    @Test fun regeneratingPreservesMixSettingsTheUserChanged() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val harmonyId = c.tracks.first { it.role == TrackRole.HARMONY }.id
        c = c.updateTrack(harmonyId) { it.copy(volume = 0.33f, muted = true, name = "My harmony") }
        c = HarmonyGenerator.regenerateDerived(c, 1L).first
        val h = c.track(harmonyId)!!
        assertEquals(0.33f, h.volume, 0.0f)
        assertTrue(h.muted)
        assertEquals("My harmony", h.name)
    }

    @Test fun editingTheSourceAndRegeneratingUpdatesTheHarmony() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        // Change the last note of the solo from B4 to C5.
        val lastId = c.track(1L)!!.notes.last().id
        c = c.updateTrack(1L) { t -> t.updateNote(lastId) { it.copy(pitch = n("C5")) } }
        c = HarmonyGenerator.regenerateDerived(c, 1L).first
        val h = c.tracks.first { it.role == TrackRole.HARMONY }
        // A third above C5 in E minor is E5.
        assertEquals(n("E5"), h.notes.last().pitch)
    }

    @Test fun harmonyCanTargetADifferentTuning() {
        val src = solo()
        val r = HarmonyGenerator.generateTrack(
            src, eMinor, HarmonySpec.OCTAVE_BELOW, 2L, 100L,
            targetTuning = Tunings.BASS_STANDARD,
        )
        assertEquals(Tunings.BASS_STANDARD, r.track.tuning)
        val board = r.track.fretboard
        for (note in r.track.notes) {
            assertEquals(note.pitch, board.pitchAt(note.string!!, note.fret!!))
        }
    }

    @Test fun emptySourceProducesNothing() {
        val (c, w) = HarmonyGenerator.generate(Composition.newGuitarProject(), 1L, listOf(HarmonySpec.THIRD_ABOVE))
        assertEquals(1, c.tracks.size)
        assertTrue(w.isEmpty())
    }

    @Test fun harmonyFollowsTheChordUnderneathWhenAsked() {
        // A C natural over an A minor chord: a diatonic third above C in C major is E,
        // which is already a chord tone, so nothing moves.
        val chord = ChordEvent(1L, Chord.of("A", ChordType.MINOR)!!, 0L, 3840)
        val track = Track(
            1L, "Lead", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24,
            listOf(MusicNote(1L, n("C4"), 0L, 480)),
        )
        val r = HarmonyGenerator.generateTrack(
            track, cMajor, HarmonySpec.THIRD_ABOVE, 2L, 10L, chordProgression = listOf(chord),
        )
        assertEquals(n("E4"), r.track.notes.first().pitch)
    }

    @Test fun specLabelsReadNaturally() {
        assertEquals("Diatonic 3rd above", HarmonySpec.THIRD_ABOVE.displayName)
        assertEquals("Diatonic 3rd below", HarmonySpec.THIRD_BELOW.displayName)
        assertEquals("Diatonic 6th above", HarmonySpec.SIXTH_ABOVE.displayName)
        assertEquals("1 octave above", HarmonySpec.OCTAVE_ABOVE.displayName)
        assertEquals("1 octave below", HarmonySpec.OCTAVE_BELOW.displayName)
    }

    // --- Analysis ---

    @Test fun analysisNamesDegreesAndFlagsOutOfKeyNotes() {
        val track = Track(
            1L, "Lead", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24,
            listOf("E4", "G4", "A#4").mapIndexed { i, s -> MusicNote(i + 1L, n(s), i * 480L, 480) },
        )
        val a = CompositionAnalyzer.analyze(track, eMinor)
        assertEquals(listOf("1", "b3", "b5"), a.map { it.degreeLabel })
        assertEquals(listOf(true, true, false), a.map { it.inKey })
        assertEquals(1, CompositionAnalyzer.outOfKeyCount(track, eMinor))
    }

    @Test fun keySuggestionFindsTheObviousKey() {
        val track = Track(
            1L, "Lead", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24,
            listOf("E3", "G3", "A3", "B3", "D4", "E4").mapIndexed { i, s -> MusicNote(i + 1L, n(s), i * 480L, 480) },
        )
        val best = CompositionAnalyzer.suggestKeys(track).first().first
        assertEquals(4, best.tonicPitchClass)
        assertEquals(ScaleType.NATURAL_MINOR, best.scale)
    }
}
