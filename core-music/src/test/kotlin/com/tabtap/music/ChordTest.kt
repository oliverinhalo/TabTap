package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordTest {

    private fun chord(root: String, type: ChordType) = Chord.of(root, type)!!

    @Test fun triadIntervalsAreCorrect() {
        assertEquals(listOf(0, 4, 7), ChordType.MAJOR.semitones)
        assertEquals(listOf(0, 3, 7), ChordType.MINOR.semitones)
        assertEquals(listOf(0, 3, 6), ChordType.DIMINISHED.semitones)
        assertEquals(listOf(0, 4, 8), ChordType.AUGMENTED.semitones)
        assertEquals(listOf(0, 7), ChordType.POWER.semitones)
    }

    @Test fun seventhChordIntervalsAreCorrect() {
        assertEquals(listOf(0, 4, 7, 11), ChordType.MAJOR7.semitones)
        assertEquals(listOf(0, 3, 7, 10), ChordType.MINOR7.semitones)
        assertEquals(listOf(0, 4, 7, 10), ChordType.DOMINANT7.semitones)
        assertEquals(listOf(0, 3, 6, 10), ChordType.HALF_DIMINISHED.semitones)
        assertEquals(listOf(0, 3, 6, 9), ChordType.DIMINISHED7.semitones)
    }

    @Test fun chordTonesAreSpelledCorrectly() {
        assertEquals("C E G", chord("C", ChordType.MAJOR).toneNames)
        assertEquals("A C E", chord("A", ChordType.MINOR).toneNames)
        assertEquals("A C E G", chord("A", ChordType.MINOR7).toneNames)
        assertEquals("G B D F", chord("G", ChordType.DOMINANT7).toneNames)
        assertEquals("F# A# C# E", chord("F#", ChordType.DOMINANT7).toneNames)
        assertEquals("F# A# C# E#", chord("F#", ChordType.MAJOR7).toneNames)
        // A diminished fifth must be spelled as a flattened fifth, not a sharpened fourth.
        assertEquals("B D F", chord("B", ChordType.DIMINISHED).toneNames)
        assertEquals("C Eb Gb", chord("C", ChordType.DIMINISHED).toneNames)
        // A suspended fourth keeps the fourth letter.
        assertEquals("D G A", chord("D", ChordType.SUS4).toneNames)
        assertEquals("D E A", chord("D", ChordType.SUS2).toneNames)
    }

    @Test fun ninthChordsSpellTheNinthAnOctaveUp() {
        val cmaj9 = chord("C", ChordType.MAJOR9)
        assertEquals("C E G B D", cmaj9.toneNames)
        // The ninth really is a ninth above the root, not a second.
        assertEquals(14, cmaj9.spelledTones.last().midi - cmaj9.spelledTones.first().midi)
    }

    @Test fun chordSymbolsReadConventionally() {
        assertEquals("C", chord("C", ChordType.MAJOR).symbol)
        assertEquals("Am", chord("A", ChordType.MINOR).symbol)
        assertEquals("G7", chord("G", ChordType.DOMINANT7).symbol)
        assertEquals("Fmaj7", chord("F", ChordType.MAJOR7).symbol)
        assertEquals("E5", chord("E", ChordType.POWER).symbol)
        assertEquals("Bbm7", chord("Bb", ChordType.MINOR7).symbol)
    }

    @Test fun inversionsProduceSlashChords() {
        val c = chord("C", ChordType.MAJOR)
        assertEquals("C/E", c.inversion(1).symbol)
        assertEquals("C/G", c.inversion(2).symbol)
        assertEquals("C", c.inversion(0).symbol)
    }

    @Test fun membershipIgnoresOctave() {
        val am = chord("A", ChordType.MINOR)
        assertTrue(am.contains(Pitch.parse("A2")!!))
        assertTrue(am.contains(Pitch.parse("C5")!!))
        assertTrue(am.contains(Pitch.parse("E3")!!))
        assertFalse(am.contains(Pitch.parse("B3")!!))
    }

    @Test fun degreeLookupIdentifiesChordTones() {
        val g7 = chord("G", ChordType.DOMINANT7)
        assertEquals(0, g7.degreeOf(Pitch.parse("G3")!!))
        assertEquals(1, g7.degreeOf(Pitch.parse("B3")!!))
        assertEquals(2, g7.degreeOf(Pitch.parse("D4")!!))
        assertEquals(3, g7.degreeOf(Pitch.parse("F4")!!))
        assertEquals(null, g7.degreeOf(Pitch.parse("A3")!!))
    }

    @Test fun customChordsCanBeBuilt() {
        val t = ChordType.custom("My chord", listOf(4, 7, 10, 14))
        assertEquals(listOf(0, 4, 7, 10, 14), t.semitones)
        assertEquals(5, t.size)
    }

    // --- Voicings ---

    private val guitar = Fretboard(Tunings.STANDARD_E, 22)

    @Test fun voicingsSoundTheRightPitchClasses() {
        for (type in ChordType.BUILT_IN) {
            for (rootName in listOf("C", "A", "E", "G", "Bb")) {
                val c = chord(rootName, type)
                for (v in ChordVoicer.voicings(c, guitar, maxResults = 4)) {
                    val classes = v.pitches(guitar).map { Pitch.pitchClass(it) }.toSet()
                    assertTrue(
                        "${c.symbol} voicing ${v.tabString()} contains a foreign note",
                        classes.all { it in c.pitchClasses },
                    )
                }
            }
        }
    }

    @Test fun voicingsStayWithinReach() {
        for (type in listOf(ChordType.MAJOR, ChordType.MINOR, ChordType.DOMINANT7, ChordType.MINOR7)) {
            for (rootName in listOf("C", "D", "E", "F", "G", "A", "B")) {
                for (v in ChordVoicer.voicings(chord(rootName, type), guitar, maxResults = 6)) {
                    assertTrue("span ${v.span} too wide: ${v.tabString()}", v.span <= ChordVoicer.MAX_SPAN)
                    assertTrue(v.frets.size == guitar.stringCount)
                }
            }
        }
    }

    @Test fun commonOpenChordsAreFound() {
        // The standard open shapes must appear among the suggestions, lowest string first.
        fun has(c: Chord, frets: List<Int?>) =
            ChordVoicer.voicings(c, guitar, maxResults = 40).any { it.frets == frets }

        assertTrue("E major 022100", has(chord("E", ChordType.MAJOR), listOf(0, 2, 2, 1, 0, 0)))
        assertTrue("A minor x02210", has(chord("A", ChordType.MINOR), listOf(null, 0, 2, 2, 1, 0)))
        assertTrue("G major 320003", has(chord("G", ChordType.MAJOR), listOf(3, 2, 0, 0, 0, 3)))
        assertTrue("D major xx0232", has(chord("D", ChordType.MAJOR), listOf(null, null, 0, 2, 3, 2)))
        assertTrue("C major x32010", has(chord("C", ChordType.MAJOR), listOf(null, 3, 2, 0, 1, 0)))
        assertTrue("E7 020100", has(chord("E", ChordType.DOMINANT7), listOf(0, 2, 0, 1, 0, 0)))
    }

    @Test fun theBestVoicingPutsTheRootInTheBass() {
        for (rootName in listOf("C", "D", "E", "F", "G", "A")) {
            val c = chord(rootName, ChordType.MAJOR)
            val best = ChordVoicer.bestVoicing(c, guitar)
            assertNotNull(best)
            val bass = Pitch.pitchClass(best!!.pitches(guitar).first())
            assertEquals("${c.symbol} best voicing ${best.tabString()}", c.rootPitchClass, bass)
        }
    }

    @Test fun inversionsAreHonouredWhenRequested() {
        val cOverG = chord("C", ChordType.MAJOR).inversion(2)
        val v = ChordVoicer.voicings(cOverG, guitar, maxResults = 6, requireRootInBass = true)
        assertTrue(v.isNotEmpty())
        for (voicing in v) {
            assertEquals(7, Pitch.pitchClass(voicing.pitches(guitar).first()))
        }
    }

    @Test fun powerChordsAreFoundOnTheLowStrings() {
        val e5 = ChordVoicer.voicings(chord("E", ChordType.POWER), guitar, maxResults = 20, minStrings = 2)
        assertTrue(e5.any { it.frets.take(2) == listOf(0, 2) })
    }

    @Test fun voicingsWorkOnSevenAndEightStringGuitars() {
        for (tuning in listOf(Tunings.SEVEN_STANDARD_B, Tunings.EIGHT_STANDARD_F)) {
            val board = Fretboard(tuning, 24)
            val v = ChordVoicer.voicings(chord("B", ChordType.MINOR), board, maxResults = 3)
            assertTrue("${tuning.id} found no Bm voicing", v.isNotEmpty())
            assertEquals(tuning.stringCount, v.first().frets.size)
        }
    }

    @Test fun voicingsWorkOnBassWithFewerRequiredStrings() {
        val board = Fretboard(Tunings.BASS_STANDARD, 24)
        val v = ChordVoicer.voicings(chord("E", ChordType.POWER), board, maxResults = 5, minStrings = 2)
        assertTrue(v.isNotEmpty())
        assertTrue(v.first().pitches(board).size >= 2)
    }

    @Test fun voicingsWorkInUnusualTunings() {
        for (tuning in listOf(Tunings.DADGAD, Tunings.OPEN_G, Tunings.DROP_C, Tunings.BASS_6_STANDARD)) {
            val board = Fretboard(tuning, 22)
            val v = ChordVoicer.voicings(chord("D", ChordType.MAJOR), board, maxResults = 3, minStrings = 3)
            assertTrue("${tuning.id} found no D voicing", v.isNotEmpty())
        }
    }

    @Test fun openGTuningHasATrivialGMajorShape() {
        val board = Fretboard(Tunings.OPEN_G, 22)
        val v = ChordVoicer.voicings(chord("G", ChordType.MAJOR), board, maxResults = 40)
        // Strumming the open strings of Open G sounds a G major chord.
        assertTrue(v.any { it.frets.drop(1).all { f -> f == 0 } })
    }

    @Test fun difficultyRanksEasyShapesBelowHardOnes() {
        val easy = ChordVoicing(listOf(0, 2, 2, 1, 0, 0))
        val hard = ChordVoicing(listOf(8, 10, 10, 9, 8, 8))
        assertTrue(ChordVoicer.difficulty(easy) < ChordVoicer.difficulty(hard))
    }

    @Test fun barreIsDetectedOnlyWhenAdjacentStringsShareTheLowestFret() {
        // The movable E shape at the 5th fret is barred across the top strings.
        assertTrue(ChordVoicing(listOf(5, 7, 7, 6, 5, 5)).isBarre)
        // So is the movable F shape at the 1st fret.
        assertTrue(ChordVoicing(listOf(1, 3, 3, 2, 1, 1)).isBarre)
        // Open D has two notes at the 2nd fret, but a string apart: two fingers, no barre.
        assertFalse(ChordVoicing(listOf(null, null, 0, 2, 3, 2)).isBarre)
        assertFalse(ChordVoicing(listOf(0, 2, 2, 1, 0, 0)).isBarre)
    }

    @Test fun tabStringShowsHighestStringFirst() {
        assertEquals("0 1 2 2 0 x", ChordVoicing(listOf(null, 0, 2, 2, 1, 0)).tabString())
    }

    @Test fun nearbyVoicingsAreOfferedForSmoothProgressions() {
        val high = ChordVoicer.bestVoicing(chord("C", ChordType.MAJOR), guitar, nearFret = 10)
        assertNotNull(high)
        assertTrue("expected a voicing near fret 10, got ${high!!.tabString()}", high.position >= 5)
    }

    @Test fun unplayableChordsReturnNothingRatherThanCrashing() {
        // A one-string instrument cannot sound a triad.
        val tiny = Fretboard(Tunings.custom(InstrumentFamily.GUITAR, listOf(40)), 12)
        assertEquals(emptyList<ChordVoicing>(), ChordVoicer.voicings(chord("C", ChordType.MAJOR), tiny, minStrings = 3))
    }

    // --- Diatonic chords ---

    @Test fun cMajorDiatonicTriads() {
        assertEquals(
            listOf("C", "Dm", "Em", "F", "G", "Am", "Bdim"),
            DiatonicChords.triads(Key.of("C", ScaleType.MAJOR)!!).map { it.symbol },
        )
    }

    @Test fun eMinorDiatonicTriads() {
        assertEquals(
            listOf("Em", "F#dim", "G", "Am", "Bm", "C", "D"),
            DiatonicChords.triads(Key.of("E", ScaleType.NATURAL_MINOR)!!).map { it.symbol },
        )
    }

    @Test fun cMajorDiatonicSevenths() {
        assertEquals(
            listOf("Cmaj7", "Dm7", "Em7", "Fmaj7", "G7", "Am7", "Bm7b5"),
            DiatonicChords.sevenths(Key.of("C", ScaleType.MAJOR)!!).map { it.symbol },
        )
    }

    @Test fun harmonicMinorHasADominantFifthAndADiminishedSeventh() {
        val chords = DiatonicChords.triads(Key.of("A", ScaleType.HARMONIC_MINOR)!!)
        assertEquals("E", chords[4].symbol)
        assertEquals("G#dim", chords[6].symbol)
        assertEquals("E7", DiatonicChords.sevenths(Key.of("A", ScaleType.HARMONIC_MINOR)!!)[4].symbol)
    }

    @Test fun romanNumeralsFollowTheQualities() {
        assertEquals(
            listOf("I", "ii", "iii", "IV", "V", "vi", "vii°"),
            DiatonicChords.romanNumerals(Key.of("C", ScaleType.MAJOR)!!),
        )
    }

    @Test fun pentatonicKeysBorrowTheirParentsDiatonicChords() {
        assertEquals(
            DiatonicChords.triads(Key.of("E", ScaleType.NATURAL_MINOR)!!).map { it.symbol },
            DiatonicChords.triads(Key.of("E", ScaleType.MINOR_PENTATONIC)!!).map { it.symbol },
        )
    }

    @Test fun everyDiatonicChordIsPlayableOnAStandardGuitar() {
        for (scale in listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR)) {
            for (tonic in listOf("C", "G", "D", "A", "E", "F", "Bb", "Eb")) {
                for (c in DiatonicChords.triads(Key.of(tonic, scale)!!)) {
                    assertNotNull("${c.symbol} unplayable", ChordVoicer.bestVoicing(c, guitar))
                }
            }
        }
    }

    // --- Recognition ---

    @Test fun chordsAreRecognisedFromTheirPitches() {
        fun pitches(vararg names: String) = names.map { Pitch.parse(it)!! }
        assertEquals("C", ChordIdentifier.identify(pitches("C3", "E3", "G3"))?.symbol)
        assertEquals("Am", ChordIdentifier.identify(pitches("A2", "C3", "E3"))?.symbol)
        assertEquals("G7", ChordIdentifier.identify(pitches("G2", "B2", "D3", "F3"))?.symbol)
        assertEquals("E5", ChordIdentifier.identify(pitches("E2", "B2", "E3"))?.symbol)
    }

    @Test fun inversionsAreRecognisedAsSlashChords() {
        val id = ChordIdentifier.identify(listOf("E3", "G3", "C4").map { Pitch.parse(it)!! })
        assertEquals("C/E", id?.symbol)
    }

    @Test fun recognitionDeclinesOnTooLittleInformation() {
        assertEquals(null, ChordIdentifier.identify(emptyList()))
        assertEquals(null, ChordIdentifier.identify(listOf(60)))
        assertEquals(null, ChordIdentifier.identify(listOf(60, 72)))
    }

    @Test fun recognisingAVoicingRecoversItsChord() {
        for (rootName in listOf("C", "D", "E", "F", "G", "A")) {
            for (type in listOf(ChordType.MAJOR, ChordType.MINOR, ChordType.DOMINANT7)) {
                val c = chord(rootName, type)
                val v = ChordVoicer.bestVoicing(c, guitar)!!
                val back = ChordIdentifier.identify(v.pitches(guitar))
                assertEquals("${c.symbol} -> ${v.tabString()}", c.rootPitchClass, back?.rootPitchClass)
                assertEquals(c.type.id, back?.type?.id)
            }
        }
    }
}
