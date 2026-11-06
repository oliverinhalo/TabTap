package com.tabtap.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.tabtap.app.ui.fretboard.FretboardGeometry
import com.tabtap.music.Fretboard
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fretboard hit testing.
 *
 * Open strings could not be played at all: fret 0's hit zone ran from the board's left
 * edge to fret 0's wire, which sit in the same place, so it was zero pixels wide and no
 * tap could ever land in it. These tests check every position on every preset is
 * reachable, which is the only way to be sure a whole row of notes has not gone missing.
 */
class FretboardHitTest {

    private fun geometry(
        tuning: com.tabtap.music.Tuning = Tunings.STANDARD_E,
        frets: Int = 22,
        width: Float = 900f,
        height: Float = 320f,
        firstFret: Int = 0,
        visibleFrets: Int = 22,
        leftHanded: Boolean = false,
    ) = FretboardGeometry(
        size = Size(width, height),
        stringCount = tuning.stringCount,
        firstFret = firstFret,
        visibleFrets = visibleFrets,
        totalFrets = frets,
        showFretNumbers = true,
        leftHanded = leftHanded,
        labelWidthPx = 30f,
        numberHeightPx = 20f,
        openColumnPx = 34f,
    )

    @Test fun theOpenStringsCanBeTapped() {
        val g = geometry()
        assertTrue("the open-string column is missing", g.showsOpenStrings)
        val column = g.openColumn
        assertNotNull(column)
        assertTrue("the open-string column has no width", column!!.width > 10f)

        for (string in 0 until 6) {
            val hit = g.hitTest(Offset(column.center.x, g.stringY(string)))
            assertEquals("string $string open", string to 0, hit)
        }
    }

    @Test fun everyPositionOnEveryPresetIsReachable() {
        for (tuning in Tunings.presets) {
            val g = geometry(tuning, frets = 24, visibleFrets = 24)
            for (string in 0 until tuning.stringCount) {
                for (fret in 0..24) {
                    val hit = g.hitTest(Offset(g.fretCentreX(fret), g.stringY(string)))
                    assertEquals(
                        "${tuning.id}: string $string fret $fret is unreachable",
                        string to fret,
                        hit,
                    )
                }
            }
        }
    }

    @Test fun theFirstFrettedNoteIsFretOneNotZero() {
        // A tap just past the nut must be fret 1. Before the fix the zone from the edge
        // to the nut was claimed by fret 0 *and* had no width, so fret 1 absorbed it.
        val g = geometry()
        val justPastNut = g.fretWireX(0) + (g.fretWireX(1) - g.fretWireX(0)) * 0.5f
        assertEquals(0 to 1, g.hitTest(Offset(justPastNut, g.stringY(0))))
    }

    @Test fun theOpenColumnDisappearsWhenTheWindowStartsUpTheNeck() {
        val g = geometry(firstFret = 5, visibleFrets = 8)
        assertTrue(!g.showsOpenStrings)
        assertNull(g.openColumn)
        // And the frets in the window are all still reachable.
        for (fret in 5..13) {
            assertEquals(0 to fret, g.hitTest(Offset(g.fretCentreX(fret), g.stringY(0))))
        }
    }

    @Test fun openStringsWorkLeftHanded() {
        val g = geometry(leftHanded = true)
        val column = g.openColumn
        assertNotNull(column)
        // Mirrored: the open column is on the right.
        assertTrue("the open column should be on the right", column!!.left > g.size.width / 2f)
        for (string in 0 until 6) {
            assertEquals(string to 0, g.hitTest(Offset(column.center.x, g.stringY(string))))
        }
        // And the frets ascend leftwards.
        assertTrue(g.fretWireX(1) > g.fretWireX(12))
    }

    @Test fun positionsAndPitchesAgree() {
        // What is tapped must sound what the fretboard says it sounds.
        val board = Fretboard(Tunings.STANDARD_E, 22)
        val g = geometry()
        for (string in 0 until 6) {
            for (fret in 0..22) {
                val (hitString, hitFret) = g.hitTest(Offset(g.fretCentreX(fret), g.stringY(string)))!!
                assertEquals(
                    board.pitchAt(string, fret),
                    board.pitchAt(hitString, hitFret),
                )
            }
        }
        // And the open strings really are the tuning's own pitches.
        for (string in 0 until 6) {
            val (s, f) = g.hitTest(Offset(g.openColumn!!.center.x, g.stringY(string)))!!
            assertEquals(Tunings.STANDARD_E.openStrings[string], board.pitchAt(s, f))
        }
    }

    @Test fun tapsAwayFromAnyStringMissCleanly() {
        val g = geometry()
        // Well above the top string.
        assertNull(g.hitTest(Offset(g.fretCentreX(3), g.board.top - 50f)))
        // In the string-label gutter.
        assertNull(g.hitTest(Offset(2f, g.stringY(0))))
    }

    @Test fun itWorksWhenTheBoardIsVeryShort() {
        // The landscape case: eight strings in 260 pixels.
        val g = geometry(Tunings.EIGHT_STANDARD_F, frets = 24, height = 260f, visibleFrets = 24)
        for (string in 0 until 8) {
            assertEquals(string to 0, g.hitTest(Offset(g.openColumn!!.center.x, g.stringY(string))))
            assertEquals(string to 5, g.hitTest(Offset(g.fretCentreX(5), g.stringY(string))))
        }
    }

    @Test fun markersHaveSensibleSizesEverywhere() {
        val g = geometry()
        for (fret in 0..22) {
            val r = g.markerRadius(fret)
            assertTrue("fret $fret marker radius is $r", r in 3f..40f)
        }
    }
}
