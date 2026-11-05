package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalTest {

    @Test fun qualityAndNumberOfCommonIntervals() {
        assertEquals("P1", Interval.UNISON.shortName)
        assertEquals("m3", Interval.MINOR_THIRD.shortName)
        assertEquals("M3", Interval.MAJOR_THIRD.shortName)
        assertEquals("P4", Interval.PERFECT_FOURTH.shortName)
        assertEquals("P5", Interval.PERFECT_FIFTH.shortName)
        assertEquals("m6", Interval.MINOR_SIXTH.shortName)
        assertEquals("M7", Interval.MAJOR_SEVENTH.shortName)
        assertEquals("P8", Interval.OCTAVE.shortName)
    }

    @Test fun tritoneSpellsAsAugmentedFourthOrDiminishedFifth() {
        assertEquals("A4", Interval(3, 6).shortName)
        assertEquals("d5", Interval(4, 6).shortName)
    }

    @Test fun augmentedSecondIsDistinctFromMinorThird() {
        val augSecond = Interval(1, 3)
        val minThird = Interval(2, 3)
        assertEquals("A2", augSecond.shortName)
        assertEquals("m3", minThird.shortName)
        assertEquals(augSecond.semitones, minThird.semitones)
    }

    @Test fun betweenSpelledPitchesNamesTheWrittenInterval() {
        val c = SpelledPitch(NoteLetter.C, 0, 4)
        val eFlat = SpelledPitch(NoteLetter.E, -1, 4)
        val dSharp = SpelledPitch(NoteLetter.D, 1, 4)
        assertEquals("m3", Interval.between(c, eFlat).shortName)
        assertEquals("A2", Interval.between(c, dSharp).shortName)
    }

    @Test fun fullNamesReadNaturally() {
        assertEquals("minor third", Interval.MINOR_THIRD.fullName)
        assertEquals("perfect fifth", Interval.PERFECT_FIFTH.fullName)
    }

    @Test fun labelsAreRelativeToTheRoot() {
        // A root of E (pitch class 4).
        assertEquals("R", IntervalLabels.label(Pitch.parse("E3")!!, 4))
        assertEquals("b3", IntervalLabels.label(Pitch.parse("G3")!!, 4))
        assertEquals("5", IntervalLabels.label(Pitch.parse("B3")!!, 4))
        assertEquals("b7", IntervalLabels.label(Pitch.parse("D4")!!, 4))
    }

    @Test fun ninthIsNamedAsACompoundInterval() {
        assertEquals("M9", Interval(8, 14).shortName)
        assertEquals(9, Interval(8, 14).number)
    }
}
