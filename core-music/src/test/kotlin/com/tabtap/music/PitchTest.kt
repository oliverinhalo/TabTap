package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchTest {

    @Test fun middleCIsMidi60() {
        assertEquals(60, Pitch.parse("C4"))
        assertEquals("C4", Pitch.fullName(60))
        assertEquals(4, Pitch.octave(60))
        assertEquals(0, Pitch.pitchClass(60))
    }

    @Test fun a440IsMidi69() {
        assertEquals(440.0, Pitch.frequency(69), 1e-9)
        assertEquals(69, Pitch.fromFrequency(440.0))
    }

    @Test fun standardGuitarStringFrequencies() {
        // Published equal-tempered frequencies for standard tuning, to 0.01 Hz.
        assertEquals(82.41, Pitch.frequency(Pitch.parse("E2")!!), 0.01)
        assertEquals(110.00, Pitch.frequency(Pitch.parse("A2")!!), 0.01)
        assertEquals(146.83, Pitch.frequency(Pitch.parse("D3")!!), 0.01)
        assertEquals(196.00, Pitch.frequency(Pitch.parse("G3")!!), 0.01)
        assertEquals(246.94, Pitch.frequency(Pitch.parse("B3")!!), 0.01)
        assertEquals(329.63, Pitch.frequency(Pitch.parse("E4")!!), 0.01)
    }

    @Test fun bassBStringIsBelow31Hz() {
        assertEquals(30.87, Pitch.frequency(Pitch.parse("B0")!!), 0.01)
    }

    @Test fun octaveDoublesFrequency() {
        for (m in 12..115) {
            assertEquals(Pitch.frequency(m) * 2.0, Pitch.frequency(m + 12), 1e-9)
        }
    }

    @Test fun frequencyRoundTripsForEveryMidiNote() {
        for (m in 0..127) {
            assertEquals(m, Pitch.fromFrequency(Pitch.frequency(m)))
        }
    }

    @Test fun centsDeviationIsZeroAtEqualTemperament() {
        for (m in 20..100) {
            assertTrue(abs(Pitch.centsFromNearest(Pitch.frequency(m))) < 1e-6)
        }
    }

    @Test fun parsesAccidentalsAndNegativeOctaves() {
        assertEquals(66, Pitch.parse("F#4"))
        assertEquals(66, Pitch.parse("Gb4"))
        assertEquals(0, Pitch.parse("C-1"))
        assertEquals(127, Pitch.parse("G9"))
        assertEquals(62, Pitch.parse("C##4"))
        assertEquals(60, Pitch.parse("Dbb4"))
    }

    @Test fun rejectsNonsense() {
        assertNull(Pitch.parse(""))
        assertNull(Pitch.parse("H4"))
        assertNull(Pitch.parse("Cx"))
        assertNull(Pitch.parse("C99"))
    }

    @Test fun flatAndSharpSpellingsAreOffered() {
        assertEquals("F#", Pitch.noteName(66, preferFlats = false))
        assertEquals("Gb", Pitch.noteName(66, preferFlats = true))
    }

    @Test fun spelledPitchRoundTrips() {
        val fSharp3 = SpelledPitch(NoteLetter.F, 1, 3)
        assertEquals(54, fSharp3.midi)
        assertEquals("F#3", fSharp3.toString())
        val gFlat3 = SpelledPitch(NoteLetter.G, -1, 3)
        assertEquals(54, gFlat3.midi)
        // Enharmonic but distinct on the staff.
        assertTrue(fSharp3.diatonicIndex != gFlat3.diatonicIndex)
    }

    @Test fun diatonicIndexIsContinuousAcrossOctaves() {
        assertEquals(1, SpelledPitch(NoteLetter.C, 0, 1).diatonicIndex - SpelledPitch(NoteLetter.B, 0, 0).diatonicIndex)
    }

    @Test fun clampKeepsNotesInMidiRange() {
        assertEquals(0, Pitch.clamp(-5))
        assertEquals(127, Pitch.clamp(900))
    }
}
