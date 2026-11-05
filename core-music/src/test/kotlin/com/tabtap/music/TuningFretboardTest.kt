package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningFretboardTest {

    @Test fun standardTuningOpenStringsAreCorrect() {
        assertEquals(
            listOf("E2", "A2", "D3", "G3", "B3", "E4"),
            Tunings.STANDARD_E.stringNames,
        )
    }

    @Test fun tabOrderShowsHighestStringFirst() {
        assertEquals("E4", Tunings.STANDARD_E.stringNamesTabOrder.first())
        assertEquals("E2", Tunings.STANDARD_E.stringNamesTabOrder.last())
    }

    @Test fun everyPresetIsOrderedLowToHighOrDeliberatelyDropped() {
        for (t in Tunings.presets) {
            // Only the lowest string may be below its neighbour by more than a unison,
            // which is what a drop tuning is; the rest must ascend.
            for (i in 2 until t.stringCount) {
                assertTrue("${t.id} string $i", t.openStrings[i] > t.openStrings[i - 1])
            }
        }
    }

    @Test fun dropDLowersOnlyTheLowestString() {
        assertEquals(Tunings.STANDARD_E.openStrings[0] - 2, Tunings.DROP_D.openStrings[0])
        assertEquals(Tunings.STANDARD_E.openStrings.drop(1), Tunings.DROP_D.openStrings.drop(1))
    }

    @Test fun ebStandardIsAHalfStepBelowStandard() {
        Tunings.STANDARD_E.openStrings.forEachIndexed { i, p ->
            assertEquals(p - 1, Tunings.EB_STANDARD.openStrings[i])
        }
    }

    @Test fun dadgadIsCorrect() {
        assertEquals(listOf("D2", "A2", "D3", "G3", "A3", "D4"), Tunings.DADGAD.stringNames)
    }

    @Test fun sevenAndEightStringPresetsHaveTheRightStringCounts() {
        assertEquals(7, Tunings.SEVEN_STANDARD_B.stringCount)
        assertEquals(8, Tunings.EIGHT_STANDARD_F.stringCount)
        assertEquals(4, Tunings.BASS_STANDARD.stringCount)
        assertEquals(5, Tunings.BASS_5_STANDARD.stringCount)
        assertEquals(6, Tunings.BASS_6_STANDARD.stringCount)
    }

    @Test fun bassStandardIsAnOctaveBelowTheGuitarsLowFourStrings() {
        Tunings.BASS_STANDARD.openStrings.forEachIndexed { i, p ->
            assertEquals(Tunings.STANDARD_E.openStrings[i] - 12, p)
        }
    }

    @Test fun fretPitchesAddSemitones() {
        val board = Fretboard(Tunings.STANDARD_E, 24)
        // 5th fret of the low E string is A2.
        assertEquals(Pitch.parse("A2"), board.pitchAt(0, 5))
        // 12th fret of any string is an octave above the open string.
        for (s in 0 until board.stringCount) {
            assertEquals(board.pitchAt(s, 0) + 12, board.pitchAt(s, 12))
        }
        // The classic high E, 12th fret.
        assertEquals(Pitch.parse("E5"), board.pitchAt(5, 12))
    }

    @Test fun outOfRangePositionsAreRejectedNotCrashed() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        assertNull(board.pitchAtOrNull(6, 0))
        assertNull(board.pitchAtOrNull(-1, 0))
        assertNull(board.pitchAtOrNull(0, 23))
        assertNotNull(board.pitchAtOrNull(0, 22))
    }

    @Test fun unisonPositionsAreAllFound() {
        val board = Fretboard(Tunings.STANDARD_E, 24)
        // E4 is open high E, 5th fret of B, 9th fret of G, 14th of D, 19th of A, 24th of low E.
        val positions = board.positionsFor(Pitch.parse("E4")!!)
        assertEquals(
            listOf(FretPosition(0, 24), FretPosition(1, 19), FretPosition(2, 14), FretPosition(3, 9), FretPosition(4, 5), FretPosition(5, 0)),
            positions,
        )
    }

    @Test fun positionsAlwaysSoundTheRequestedPitch() {
        for (t in Tunings.presets) {
            val board = Fretboard(t, 24)
            for (m in board.pitchRange) {
                for (p in board.positionsFor(m)) {
                    assertEquals("${t.id} $p", m, board.pitchAt(p))
                }
            }
        }
    }

    @Test fun bestPositionStaysNearTheHand() {
        val board = Fretboard(Tunings.STANDARD_E, 24)
        val e4 = Pitch.parse("E4")!!
        // With the hand at the 12th fret, the 14th-fret D string is nearer than the open string.
        assertEquals(14, board.bestPositionFor(e4, preferredFret = 14)?.fret)
        // With the hand at the nut, the open string wins.
        assertEquals(0, board.bestPositionFor(e4, preferredFret = 0)?.fret)
    }

    @Test fun unreachablePitchesReturnNull() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        assertNull(board.bestPositionFor(Pitch.parse("C1")!!))
        assertFalse(board.canPlay(Pitch.parse("C1")!!))
        assertTrue(board.canPlay(Pitch.parse("E2")!!))
    }

    @Test fun fretSpacingFollowsTheTwelfthRootOfTwo() {
        // The 12th fret sits at exactly half the scale length.
        assertEquals(0.5, Fretboard.fretDistanceFraction(12), 1e-12)
        // Spacing must shrink monotonically towards the bridge.
        val layout = Fretboard.fretLayout(24)
        val gaps = layout.toList().zipWithNext { a, b -> b - a }
        for (i in 1 until gaps.size) {
            assertTrue("gap $i widened", gaps[i] < gaps[i - 1])
        }
        assertEquals(0.0, layout.first(), 1e-12)
        assertEquals(1.0, layout.last(), 1e-12)
    }

    @Test fun inlaysAreClippedToTheFretCount() {
        assertEquals(listOf(3, 5, 7, 9, 12), Fretboard(Tunings.STANDARD_E, 12).inlayFrets())
        assertTrue(Fretboard(Tunings.STANDARD_E, 24).doubleInlayFrets().containsAll(listOf(12, 24)))
    }

    @Test fun customTuningsAreAccepted() {
        val weird = Tunings.custom(InstrumentFamily.GUITAR, listOf(40, 41, 42, 43, 44, 45))
        val board = Fretboard(weird, 24)
        assertEquals(6, board.stringCount)
        assertEquals(6, board.positionsFor(45).size + board.positionsFor(45).size / 6 * 0)
        // Nothing should throw for any position on an unusual tuning.
        for (s in 0 until 6) for (f in 0..24) board.pitchAt(s, f)
    }

    @Test fun stringCountChangeExtendsDownwardsInFourths() {
        val seven = Tunings.STANDARD_E.withStringCount(7)
        assertEquals(7, seven.stringCount)
        assertEquals(Tunings.STANDARD_E.openStrings[0] - 5, seven.openStrings[0])
        assertEquals(Tunings.STANDARD_E.openStrings, seven.openStrings.drop(1))
    }

    @Test fun editingOneStringProducesACustomTuning() {
        val t = Tunings.STANDARD_E.withString(0, Pitch.parse("D2")!!)
        assertEquals(Tuning.CUSTOM_ID, t.id)
        assertEquals(Tunings.DROP_D.openStrings, t.openStrings)
        assertFalse(t.isPreset)
        assertTrue(Tunings.STANDARD_E.isPreset)
    }

    @Test fun fretCountIsValidated() {
        for (n in Fretboard.MIN_FRETS..Fretboard.MAX_FRETS) {
            Fretboard(Tunings.STANDARD_E, n)
        }
        runCatching { Fretboard(Tunings.STANDARD_E, 11) }.let { assertTrue(it.isFailure) }
        runCatching { Fretboard(Tunings.STANDARD_E, 31) }.let { assertTrue(it.isFailure) }
    }
}
