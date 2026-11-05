package com.tabtap.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTest {

    @Test fun noteValueDurationsAreExact() {
        assertEquals(3840, NoteValue.WHOLE.ticks)
        assertEquals(1920, NoteValue.HALF.ticks)
        assertEquals(960, NoteValue.QUARTER.ticks)
        assertEquals(480, NoteValue.EIGHTH.ticks)
        assertEquals(240, NoteValue.SIXTEENTH.ticks)
        assertEquals(120, NoteValue.THIRTY_SECOND.ticks)
        assertEquals(60, NoteValue.SIXTY_FOURTH.ticks)
    }

    @Test fun dotsAddHalfAgain() {
        assertEquals(1440, NoteValue.DOTTED_QUARTER.ticks)
        assertEquals(2880, NoteValue.DOTTED_HALF.ticks)
        assertEquals(720, NoteValue.DOTTED_EIGHTH.ticks)
        // Double dot adds a further quarter.
        assertEquals(1680, NoteValue(2, 2).ticks)
    }

    @Test fun tripletsDivideCleanly() {
        assertEquals(320, NoteValue.TRIPLET_EIGHTH.ticks)
        assertEquals(960, NoteValue.TRIPLET_EIGHTH.ticks * 3)
        assertEquals(640, NoteValue.TRIPLET_QUARTER.ticks)
        assertEquals(1920, NoteValue.TRIPLET_QUARTER.ticks * 3)
    }

    @Test fun beamCountsMatchFlagCounts() {
        assertEquals(0, NoteValue.QUARTER.beamCount)
        assertEquals(1, NoteValue.EIGHTH.beamCount)
        assertEquals(2, NoteValue.SIXTEENTH.beamCount)
        assertEquals(3, NoteValue.THIRTY_SECOND.beamCount)
        assertTrue(NoteValue.QUARTER.filled)
        assertTrue(!NoteValue.HALF.filled)
        assertTrue(!NoteValue.WHOLE.hasStem)
    }

    @Test fun arbitraryDurationsDecomposeExactly() {
        for (ticks in listOf(960, 1440, 2400, 3840, 5760, 1020, 7000)) {
            val parts = NoteValue.decompose(ticks)
            assertTrue("$ticks produced nothing", parts.isNotEmpty())
            if (ticks % 60 == 0) {
                assertEquals("decompose($ticks)", ticks, parts.sumOf { it.ticks })
            }
        }
    }

    @Test fun exactValueLookupFindsWrittenDurations() {
        assertEquals(NoteValue.QUARTER.ticks, NoteValue.exactForTicks(960)!!.ticks)
        assertNull(NoteValue.exactForTicks(961))
        assertEquals(960, NoteValue.forTicks(961).ticks)
    }

    @Test fun barLengthsAreCorrectForEachTimeSignature() {
        assertEquals(3840, TimeSignature.FOUR_FOUR.barTicks)
        assertEquals(2880, TimeSignature.THREE_FOUR.barTicks)
        assertEquals(4800, TimeSignature.FIVE_FOUR.barTicks)
        assertEquals(2880, TimeSignature.SIX_EIGHT.barTicks)
        assertEquals(3360, TimeSignature.SEVEN_EIGHT.barTicks)
    }

    @Test fun compoundTimeIsFeltInDottedQuarters() {
        assertTrue(TimeSignature.SIX_EIGHT.isCompound)
        assertEquals(1440, TimeSignature.SIX_EIGHT.pulseTicks)
        assertEquals(2, TimeSignature.SIX_EIGHT.pulsesPerBar)
        assertEquals(4, TimeSignature.TWELVE_EIGHT.pulsesPerBar)
        // 7/8 is irregular, so it is felt in eighths.
        assertTrue(!TimeSignature.SEVEN_EIGHT.isCompound)
        assertEquals(7, TimeSignature.SEVEN_EIGHT.pulsesPerBar)
    }

    @Test fun beamGroupsSumToTheBar() {
        for (ts in TimeSignature.PRESETS) {
            assertEquals(ts.toString(), ts.barTicks, ts.beamGroups.sum())
        }
    }

    @Test fun sevenEightGroupsAsTwoTwoThree() {
        assertEquals(listOf(960, 960, 1440), TimeSignature.SEVEN_EIGHT.beamGroups)
    }

    @Test fun timeSignatureParsingAndValidation() {
        assertEquals(TimeSignature(5, 4), TimeSignature.parse("5/4"))
        assertNull(TimeSignature.parse("5/5"))
        assertNull(TimeSignature.parse("rubbish"))
        runCatching { TimeSignature(0, 4) }.let { assertTrue(it.isFailure) }
    }

    @Test fun ticksConvertToSecondsAtTempo() {
        // At 120 BPM a quarter note lasts half a second.
        assertEquals(0.5, Ticks.toSeconds(Ticks.QUARTER.toLong(), 120.0), 1e-12)
        assertEquals(2.0, Ticks.toSeconds(TimeSignature.FOUR_FOUR.barTicks.toLong(), 120.0), 1e-12)
        assertEquals(Ticks.QUARTER.toLong(), Ticks.fromSeconds(0.5, 120.0))
        // 90 BPM: a bar of 4/4 is 8/3 seconds.
        assertEquals(8.0 / 3.0, Ticks.toSeconds(3840, 90.0), 1e-12)
    }

    @Test fun straightGridsDivideTheBeatEvenly() {
        val q = Quantize(GridResolution.SIXTEENTH, GridFeel.STRAIGHT)
        assertEquals(listOf(0, 240, 480, 720), RhythmEngine.gridOffsetsWithinBeat(q, 960))
    }

    @Test fun tripletGridsDivideTheBeatInThree() {
        val q = Quantize(GridResolution.EIGHTH, GridFeel.TRIPLET)
        assertEquals(listOf(0, 320, 640), RhythmEngine.gridOffsetsWithinBeat(q, 960))
    }

    @Test fun swingDelaysTheOffBeat() {
        val q = Quantize(GridResolution.EIGHTH, GridFeel.SWING, swingRatio = 2.0 / 3.0)
        val offsets = RhythmEngine.gridOffsetsWithinBeat(q, 960)
        assertEquals(0, offsets[0])
        assertEquals(640, offsets[1])
    }

    @Test fun quantiseSnapsToTheNearestGridLine() {
        val q = Quantize(GridResolution.SIXTEENTH, GridFeel.STRAIGHT)
        val ts = TimeSignature.FOUR_FOUR
        assertEquals(240L, RhythmEngine.snap(250L, q, ts))
        assertEquals(240L, RhythmEngine.snap(230L, q, ts))
        assertEquals(960L, RhythmEngine.snap(950L, q, ts))
        assertEquals(0L, RhythmEngine.snap(10L, q, ts))
        // Across a bar line.
        assertEquals(3840L, RhythmEngine.snap(3835L, q, ts))
    }

    @Test fun quantiseStrengthPullsPartway() {
        val q = Quantize(GridResolution.QUARTER, GridFeel.STRAIGHT, strength = 0.5)
        // 100 ticks late: half-strength quantisation leaves it 50 ticks late.
        assertEquals(50L, RhythmEngine.snap(100L, q, TimeSignature.FOUR_FOUR))
    }

    @Test fun quantiseOffLeavesTimingUntouched() {
        assertEquals(1234L, RhythmEngine.snap(1234L, Quantize.OFF, TimeSignature.FOUR_FOUR))
    }

    @Test fun simultaneousNotesStaySimultaneous() {
        val q = Quantize(GridResolution.SIXTEENTH)
        val chord = listOf(503L, 503L, 503L)
        val out = RhythmEngine.quantizeStarts(chord, q, TimeSignature.FOUR_FOUR)
        assertEquals(1, out.distinct().size)
    }

    @Test fun gridPositionsAscendAndCoverTheLength() {
        val positions = RhythmEngine.gridPositions(
            Quantize(GridResolution.EIGHTH), TimeSignature.FOUR_FOUR, 3840L,
        )
        assertEquals(positions.toList(), positions.toList().sorted())
        assertEquals(0L, positions.first())
        assertEquals(3840L, positions.last())
        assertEquals(9, positions.size)
    }

    @Test fun tapTempoAveragesIntervals() {
        // Taps 500 ms apart imply 120 BPM.
        val taps = listOf(0L, 500L, 1000L, 1500L, 2000L)
        assertEquals(120.0, RhythmEngine.tapTempo(taps)!!, 0.001)
        assertNull(RhythmEngine.tapTempo(listOf(0L)))
        // Wild outliers are discarded rather than wrecking the estimate.
        assertEquals(120.0, RhythmEngine.tapTempo(listOf(0L, 500L, 1000L, 90_000L))!!, 0.001)
    }

    @Test fun durationsFromOnsetsFillTheGaps() {
        val onsets = listOf(0L, 480L, 960L, 1920L)
        assertEquals(listOf(480, 480, 960, 240), RhythmEngine.durationsFromOnsets(onsets, 240))
    }

    @Test fun humaniseIsDeterministicAndBounded() {
        val starts = List(32) { it * 480L }
        val a = RhythmEngine.humanize(starts, 20, seed = 7L)
        val b = RhythmEngine.humanize(starts, 20, seed = 7L)
        assertEquals(a, b)
        a.forEachIndexed { i, v -> assertTrue(kotlin.math.abs(v - starts[i]) <= 20) }
        assertTrue(a.all { it >= 0 })
    }

    @Test fun evenRhythmLaysNotesOnAGrid() {
        val r = RhythmEngine.evenRhythm(4, NoteValue.EIGHTH)
        assertEquals(listOf(0L to 480, 480L to 480, 960L to 480, 1440L to 480), r)
    }

    @Test fun barAndOffsetSplitsPositionsCorrectly() {
        assertEquals(0 to 0, RhythmEngine.barAndOffset(0L, TimeSignature.FOUR_FOUR))
        assertEquals(1 to 0, RhythmEngine.barAndOffset(3840L, TimeSignature.FOUR_FOUR))
        assertEquals(2 to 480, RhythmEngine.barAndOffset(8160L, TimeSignature.FOUR_FOUR))
    }

    @Test fun tempoMapIntegratesAcrossChanges() {
        val map = TempoMap(listOf(TempoMarking(0, 120.0), TempoMarking(3840, 60.0)))
        assertEquals(120.0, map.bpmAt(0), 0.0)
        assertEquals(60.0, map.bpmAt(4000), 0.0)
        // One bar at 120 is 2 s; the next bar at 60 is 4 s.
        assertEquals(2.0, map.secondsAt(3840), 1e-9)
        assertEquals(6.0, map.secondsAt(7680), 1e-9)
        assertEquals(3840L, map.tickAtSeconds(2.0))
        assertEquals(7680L, map.tickAtSeconds(6.0))
    }
}
