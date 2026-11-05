package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleKeyTest {

    private fun key(tonic: String, scale: ScaleType) = Key.of(tonic, scale)!!

    @Test fun cMajorHasNoAccidentals() {
        val k = key("C", ScaleType.MAJOR)
        assertEquals(0, k.sharpsInSignature)
        assertEquals(listOf("C", "D", "E", "F", "G", "A", "B"), k.spelledDegrees.map { "${it.letter}${it.accidentalText}" })
    }

    @Test fun eMinorHasOneSharp() {
        val k = key("E", ScaleType.NATURAL_MINOR)
        assertEquals(1, k.sharpsInSignature)
        assertEquals(listOf("E", "F#", "G", "A", "B", "C", "D"), k.spelledDegrees.map { "${it.letter}${it.accidentalText}" })
    }

    @Test fun eFlatMajorHasThreeFlats() {
        assertEquals(-3, key("Eb", ScaleType.MAJOR).sharpsInSignature)
    }

    @Test fun fSharpMajorHasSixSharps() {
        assertEquals(6, key("F#", ScaleType.MAJOR).sharpsInSignature)
    }

    @Test fun bFlatMinorHasFiveFlats() {
        assertEquals(-5, key("Bb", ScaleType.NATURAL_MINOR).sharpsInSignature)
    }

    @Test fun modesBorrowTheirParentMajorSignature() {
        // D Dorian comes from C major: no accidentals.
        assertEquals(0, key("D", ScaleType.DORIAN).sharpsInSignature)
        // G Mixolydian comes from C major.
        assertEquals(0, key("G", ScaleType.MIXOLYDIAN).sharpsInSignature)
        // F Lydian comes from C major.
        assertEquals(0, key("F", ScaleType.LYDIAN).sharpsInSignature)
        // E Phrygian comes from C major.
        assertEquals(0, key("E", ScaleType.PHRYGIAN).sharpsInSignature)
        // B Locrian comes from C major.
        assertEquals(0, key("B", ScaleType.LOCRIAN).sharpsInSignature)
    }

    @Test fun pentatonicAndBluesBorrowTheirParentSignature() {
        assertEquals(1, key("E", ScaleType.MINOR_PENTATONIC).sharpsInSignature)
        assertEquals(1, key("E", ScaleType.BLUES).sharpsInSignature)
        assertEquals(2, key("D", ScaleType.MAJOR_PENTATONIC).sharpsInSignature)
    }

    @Test fun signatureAccidentalLettersFollowTheConventionalOrder() {
        assertEquals(listOf(NoteLetter.F, NoteLetter.C, NoteLetter.G), KeySignature.accidentalLetters(3))
        assertEquals(listOf(NoteLetter.B, NoteLetter.E, NoteLetter.A), KeySignature.accidentalLetters(-3))
    }

    @Test fun scaleMembershipIgnoresOctave() {
        val eMinor = key("E", ScaleType.NATURAL_MINOR)
        assertTrue(eMinor.contains(Pitch.parse("F#2")!!))
        assertTrue(eMinor.contains(Pitch.parse("F#5")!!))
        assertFalse(eMinor.contains(Pitch.parse("F2")!!))
        assertFalse(eMinor.contains(Pitch.parse("D#4")!!))
    }

    @Test fun degreeLabelsUseTheRelativeToMajorConventionGuitaristsRead() {
        // Degrees are labelled against the major scale, so a minor scale reads
        // 1 2 b3 4 5 b6 b7 — the convention used on every scale chart.
        val eMinor = key("E", ScaleType.NATURAL_MINOR)
        assertEquals("1", eMinor.degreeLabel(Pitch.parse("E3")!!))
        assertEquals("2", eMinor.degreeLabel(Pitch.parse("F#3")!!))
        assertEquals("b3", eMinor.degreeLabel(Pitch.parse("G3")!!))
        assertEquals("4", eMinor.degreeLabel(Pitch.parse("A3")!!))
        assertEquals("5", eMinor.degreeLabel(Pitch.parse("B3")!!))
        assertEquals("b6", eMinor.degreeLabel(Pitch.parse("C4")!!))
        assertEquals("b7", eMinor.degreeLabel(Pitch.parse("D4")!!))
    }

    @Test fun majorKeyDegreesAreUnaltered() {
        val g = key("G", ScaleType.MAJOR)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7"),
            listOf("G3", "A3", "B3", "C4", "D4", "E4", "F#4").map { g.degreeLabel(Pitch.parse(it)!!) },
        )
    }

    @Test fun minorPentatonicIsLabelledOneFlatThreeFourFiveFlatSeven() {
        val k = key("A", ScaleType.MINOR_PENTATONIC)
        assertEquals(
            listOf("1", "b3", "4", "5", "b7"),
            listOf("A3", "C4", "D4", "E4", "G4").map { k.degreeLabel(Pitch.parse(it)!!) },
        )
    }

    @Test fun bluesScaleIsLabelledWithAFlatFifth() {
        val k = key("A", ScaleType.BLUES)
        assertEquals(
            listOf("1", "b3", "4", "b5", "5", "b7"),
            listOf("A3", "C4", "D4", "Eb4", "E4", "G4").map { k.degreeLabel(Pitch.parse(it)!!) },
        )
    }

    @Test fun bluesScaleKeepsTheFlattenedFifthAsAFifth() {
        val k = key("A", ScaleType.BLUES)
        // Eb is the b5 of A blues; it must be spelled on the fifth letter, not the fourth.
        val eFlat = k.spelledDegrees[3]
        assertEquals(NoteLetter.E, eFlat.letter)
        assertEquals(-1, eFlat.alter)
    }

    @Test fun spellingInKeyUsesKeyAccidentals() {
        val eMinor = key("E", ScaleType.NATURAL_MINOR)
        val fSharp = eMinor.spell(Pitch.parse("F#3")!!)
        assertEquals(NoteLetter.F, fSharp.letter)
        assertEquals(1, fSharp.alter)

        val eFlatMajor = key("Eb", ScaleType.MAJOR)
        val bFlat = eFlatMajor.spell(Pitch.parse("Bb3")!!)
        assertEquals(NoteLetter.B, bFlat.letter)
        assertEquals(-1, bFlat.alter)
    }

    @Test fun spellingAlwaysRecoversTheSoundingPitch() {
        for (scale in ScaleType.BUILT_IN) {
            for (tonic in listOf("C", "G", "D", "A", "E", "B", "F#", "F", "Bb", "Eb", "Ab")) {
                val k = key(tonic, scale)
                for (m in 40..80) {
                    assertEquals("${k.displayName} spelling $m", m, k.spell(m).midi)
                }
            }
        }
    }

    @Test fun allScalesAreStrictlyAscendingWithinAnOctave() {
        for (s in ScaleType.BUILT_IN) {
            assertEquals(s.semitones, s.semitones.sorted())
            assertTrue(s.id, s.semitones.last() < 12)
            assertEquals(s.id, s.semitones.size, s.semitones.distinct().size)
        }
    }

    @Test fun customScaleIsNormalised() {
        val s = ScaleType.custom("x", "X", listOf(7, 3, 3, 10))
        assertEquals(listOf(0, 3, 7, 10), s.semitones)
    }

    @Test fun degreeSpellingRunsIntoHigherOctaves() {
        val cMajor = key("C", ScaleType.MAJOR)
        // Degree 7 is the octave above the tonic.
        assertEquals(cMajor.tonic.midi + 12, cMajor.degreeSpelling(7).midi)
        // Degree -1 is the leading tone below.
        assertEquals(cMajor.tonic.midi - 1, cMajor.degreeSpelling(-1).midi)
    }
}
