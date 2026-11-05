package com.tabtap.music

import com.tabtap.music.notation.AccidentalGlyph
import com.tabtap.music.notation.ScoreEngraver
import com.tabtap.music.notation.StemDirection
import com.tabtap.music.notation.TabEngraver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotationTest {

    private val cMajor = Key.of("C", ScaleType.MAJOR)!!
    private val eMinor = Key.of("E", ScaleType.NATURAL_MINOR)!!
    private val ebMajor = Key.of("Eb", ScaleType.MAJOR)!!

    private fun n(name: String) = Pitch.parse(name)!!

    private fun track(
        vararg notes: Triple<String, Long, Int>,
        tuning: Tuning = Tunings.STANDARD_E,
        clef: ClefType = ClefType.TREBLE_8VB,
    ): Track {
        val board = Fretboard(tuning, 24)
        val list = notes.mapIndexed { i, (name, start, dur) ->
            val p = n(name)
            val pos = board.bestPositionFor(p, 7)
            MusicNote(i + 1L, p, start, dur, string = pos?.string, fret = pos?.fret)
        }
        return Track(1L, "T", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, tuning, 24, list, clef = clef)
    }

    // --- Staff placement ---

    @Test fun trebleClefPlacesMiddleBOnTheMiddleLine() {
        // A guitar track is written an octave above where it sounds, so sounding B3
        // is written as B4 and sits on the middle line.
        val s = ScoreEngraver.engrave(track(Triple("B3", 0L, 3840)), cMajor, TimeSignature.FOUR_FOUR)
        val head = s.systems.first().measures.first().chords.first().noteheads.first()
        assertEquals(0, head.staffStep)
        assertEquals(n("B3"), head.soundingPitch)
        assertEquals(n("B4"), head.writtenPitch.midi)
    }

    @Test fun nonTransposingTrebleClefPlacesB4OnTheMiddleLine() {
        val t = track(Triple("B4", 0L, 3840), clef = ClefType.TREBLE)
        val s = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(0, s.systems.first().measures.first().chords.first().noteheads.first().staffStep)
    }

    @Test fun bassClefPlacesD3OnTheMiddleLine() {
        // Bass guitar is also written an octave up, so sounding D2 is written D3.
        val t = track(Triple("D2", 0L, 3840), tuning = Tunings.BASS_STANDARD, clef = ClefType.BASS_8VB)
        val s = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(0, s.systems.first().measures.first().chords.first().noteheads.first().staffStep)
    }

    @Test fun stepsIncreaseByOnePerDiatonicStep() {
        val t = track(
            Triple("C4", 0L, 480), Triple("D4", 480L, 480), Triple("E4", 960L, 480),
            Triple("F4", 1440L, 480), Triple("G4", 1920L, 480),
        )
        val chords = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords
        val steps = chords.map { it.noteheads.first().staffStep }
        assertEquals(steps.sorted(), steps)
        assertEquals(listOf(1, 1, 1, 1), steps.zipWithNext { a, b -> b - a })
    }

    @Test fun enharmonicsSitOnDifferentStaffPositions() {
        val fSharp = ScoreEngraver.engrave(track(Triple("F#3", 0L, 3840)), eMinor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords.first().noteheads.first()
        val gFlat = ScoreEngraver.engrave(track(Triple("F#3", 0L, 3840)), ebMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords.first().noteheads.first()
        assertEquals(NoteLetter.F, fSharp.writtenPitch.letter)
        assertEquals(NoteLetter.G, gFlat.writtenPitch.letter)
        assertTrue(fSharp.staffStep != gFlat.staffStep)
        // Both still sound the same pitch.
        assertEquals(fSharp.soundingPitch, gFlat.soundingPitch)
    }

    @Test fun ledgerLinesAreReportedOutsideTheStaff() {
        // High notes well above the staff need ledger lines.
        val s = ScoreEngraver.engrave(track(Triple("E6", 0L, 3840)), cMajor, TimeSignature.FOUR_FOUR)
        val chord = s.systems.first().measures.first().chords.first()
        assertTrue(chord.ledgerStepsAbove.isNotEmpty())
        assertTrue(chord.ledgerStepsAbove.all { it % 2 == 0 })
        // A note inside the staff needs none.
        val inside = ScoreEngraver.engrave(track(Triple("B3", 0L, 3840)), cMajor, TimeSignature.FOUR_FOUR)
        assertTrue(inside.systems.first().measures.first().chords.first().ledgerStepsAbove.isEmpty())
    }

    // --- Accidentals ---

    @Test fun keySignatureAccidentalsAreNotRepeatedOnTheNote() {
        // F# is in the key of E minor, so it needs no accidental sign.
        val s = ScoreEngraver.engrave(track(Triple("F#3", 0L, 3840)), eMinor, TimeSignature.FOUR_FOUR)
        assertNull(s.systems.first().measures.first().chords.first().noteheads.first().accidental)
        assertEquals(1, s.systems.first().keySignatureFifths)
    }

    @Test fun outOfKeyNotesGetAnAccidental() {
        val s = ScoreEngraver.engrave(track(Triple("F3", 0L, 3840)), eMinor, TimeSignature.FOUR_FOUR)
        // F natural in E minor must be cancelled with a natural sign.
        assertEquals(
            AccidentalGlyph.NATURAL,
            s.systems.first().measures.first().chords.first().noteheads.first().accidental,
        )
    }

    @Test fun anAccidentalPersistsForTheRestOfTheBarThenReappears() {
        val t = track(
            Triple("F3", 0L, 960), Triple("F3", 960L, 960), Triple("G3", 1920L, 960),
            Triple("F3", 2880L, 960),
            // Next bar.
            Triple("F3", 3840L, 960),
        )
        val m = ScoreEngraver.engrave(t, eMinor, TimeSignature.FOUR_FOUR).systems.first().measures
        val bar1 = m[0].chords.map { it.noteheads.first().accidental }
        assertEquals(AccidentalGlyph.NATURAL, bar1[0])
        assertNull("accidental repeated within the bar", bar1[1])
        assertNull(bar1[3])
        // A new bar resets the state, so the natural is written again.
        assertEquals(AccidentalGlyph.NATURAL, m[1].chords.first().noteheads.first().accidental)
    }

    @Test fun accidentalsAreTrackedPerOctave() {
        val t = track(Triple("F3", 0L, 1920), Triple("F4", 1920L, 1920))
        val chords = ScoreEngraver.engrave(t, eMinor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords
        // Both need their own natural: the bar accidental applies to one octave.
        assertEquals(AccidentalGlyph.NATURAL, chords[0].noteheads.first().accidental)
        assertEquals(AccidentalGlyph.NATURAL, chords[1].noteheads.first().accidental)
    }

    @Test fun flatKeysWriteFlats() {
        val s = ScoreEngraver.engrave(track(Triple("Bb3", 0L, 3840)), ebMajor, TimeSignature.FOUR_FOUR)
        val head = s.systems.first().measures.first().chords.first().noteheads.first()
        assertEquals(NoteLetter.B, head.writtenPitch.letter)
        assertEquals(-1, head.writtenPitch.alter)
        assertNull(head.accidental)
        assertEquals(-3, s.systems.first().keySignatureFifths)
    }

    // --- Rhythm, rests and ties ---

    @Test fun writtenValuesMatchDurations() {
        val t = track(
            Triple("E4", 0L, 1920), Triple("E4", 1920L, 960),
            Triple("E4", 2880L, 480), Triple("E4", 3360L, 480),
        )
        val chords = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords
        assertEquals(listOf(1, 2, 3, 3), chords.map { it.value.divisionLevel })
        assertEquals(0, chords[0].value.dots)
    }

    @Test fun gapsBecomeRestsThatFillTheBarExactly() {
        val t = track(Triple("E4", 0L, 960), Triple("E4", 2880L, 960))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        val sounded = m.chords.sumOf { it.durationTicks }
        val rested = m.rests.sumOf { it.durationTicks }
        assertEquals(TimeSignature.FOUR_FOUR.barTicks, sounded + rested)
    }

    @Test fun anEmptyBarGetsAWholeBarRest() {
        val t = track(Triple("E4", 3840L, 3840))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures
        assertEquals(2, m.size)
        assertTrue(m[0].chords.isEmpty())
        assertEquals(1, m[0].rests.size)
        assertEquals(TimeSignature.FOUR_FOUR.barTicks, m[0].rests.first().durationTicks)
    }

    @Test fun restsFillEveryBarOfEveryTimeSignature() {
        for (ts in TimeSignature.PRESETS) {
            val t = track(Triple("E4", 0L, 240))
            val m = ScoreEngraver.engrave(t, cMajor, ts).systems.first().measures.first()
            assertEquals(
                "$ts did not fill the bar",
                ts.barTicks,
                m.chords.sumOf { it.durationTicks } + m.rests.sumOf { it.durationTicks },
            )
        }
    }

    @Test fun aNoteCrossingTheBarLineIsSplitAndTied() {
        // A note starting on beat 4 and lasting two beats must become two tied notes.
        val t = track(Triple("E4", 2880L, 1920))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures
        assertEquals(2, m.size)
        val first = m[0].chords.last()
        val second = m[1].chords.first()
        assertEquals(960, first.durationTicks)
        assertEquals(960, second.durationTicks)
        assertTrue("tie not started", first.noteheads.first().tieToNext)
        assertTrue("tie not continued", second.noteheads.first().tieFromPrevious)
        // The two fragments belong to the same underlying note.
        assertEquals(first.noteheads.first().noteId, second.noteheads.first().noteId)
        // A tied continuation does not repeat the accidental.
        assertNull(second.noteheads.first().accidental)
    }

    @Test fun tiedFragmentsSumToTheOriginalDuration() {
        val t = track(Triple("E4", 1440L, 5760))
        val score = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
        val total = score.systems.flatMap { it.measures }.flatMap { it.chords }.sumOf { it.durationTicks }
        assertEquals(5760, total)
    }

    // --- Chords, stems and seconds ---

    @Test fun simultaneousNotesFormOneStack() {
        val t = track(Triple("E3", 0L, 1920), Triple("G3", 0L, 1920), Triple("B3", 0L, 1920))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertEquals(1, m.chords.size)
        assertEquals(3, m.chords.first().noteheads.size)
        // Noteheads are ordered low to high.
        val steps = m.chords.first().noteheads.map { it.staffStep }
        assertEquals(steps.sorted(), steps)
    }

    @Test fun stemsPointAwayFromTheMiddleLine() {
        val high = ScoreEngraver.engrave(track(Triple("E5", 0L, 960)), cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(StemDirection.DOWN, high.systems.first().measures.first().chords.first().stem)
        val low = ScoreEngraver.engrave(track(Triple("E2", 0L, 960)), cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(StemDirection.UP, low.systems.first().measures.first().chords.first().stem)
    }

    @Test fun wholeNotesHaveNoStem() {
        val s = ScoreEngraver.engrave(track(Triple("E4", 0L, 3840)), cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(StemDirection.NONE, s.systems.first().measures.first().chords.first().stem)
    }

    @Test fun notesASecondApartAreOffsetSoTheyDoNotCollide() {
        val t = track(Triple("E4", 0L, 960), Triple("F4", 0L, 960))
        val heads = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords.first().noteheads
        assertEquals(2, heads.size)
        assertEquals(1, heads.count { it.shifted })
    }

    @Test fun athirdApartNeedsNoOffset() {
        val t = track(Triple("E4", 0L, 960), Triple("G4", 0L, 960))
        val heads = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().chords.first().noteheads
        assertEquals(0, heads.count { it.shifted })
    }

    // --- Beaming ---

    @Test fun eighthsWithinABeatAreBeamedTogether() {
        val t = track(
            Triple("E4", 0L, 480), Triple("F4", 480L, 480),
            Triple("G4", 960L, 480), Triple("A4", 1440L, 480),
        )
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        val primary = m.beams.filter { it.level == 1 }
        // Two beats of eighths: two separate beams of two, not one beam of four.
        assertEquals(2, primary.size)
        assertEquals(listOf(0, 1), primary[0].chordIndices)
        assertEquals(listOf(2, 3), primary[1].chordIndices)
    }

    @Test fun quarterNotesAreNotBeamed() {
        val t = track(
            Triple("E4", 0L, 960), Triple("F4", 960L, 960),
            Triple("G4", 1920L, 960), Triple("A4", 2880L, 960),
        )
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertTrue(m.beams.isEmpty())
    }

    @Test fun sixteenthsGetASecondBeam() {
        val t = track(
            Triple("E4", 0L, 240), Triple("F4", 240L, 240),
            Triple("G4", 480L, 240), Triple("A4", 720L, 240),
        )
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertEquals(1, m.beams.count { it.level == 1 })
        assertEquals(1, m.beams.count { it.level == 2 })
        assertEquals(listOf(0, 1, 2, 3), m.beams.first { it.level == 2 }.chordIndices)
    }

    @Test fun aLoneSixteenthInABeamGetsAPartialBeam() {
        // Dotted eighth plus sixteenth: the sixteenth carries a partial second beam.
        val t = track(Triple("E4", 0L, 720), Triple("F4", 720L, 240))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        val secondary = m.beams.filter { it.level == 2 }
        assertEquals(1, secondary.size)
        assertTrue(secondary.first().partial)
        assertEquals(listOf(1), secondary.first().chordIndices)
    }

    @Test fun beamsDoNotCrossARest() {
        val t = track(Triple("E4", 0L, 480), Triple("F4", 1440L, 480))
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertTrue(m.beams.isEmpty())
    }

    @Test fun compoundTimeBeamsInDottedQuarterGroups() {
        // Six eighths in 6/8 beam as two groups of three.
        val notes = (0 until 6).map { Triple("E4", it * 480L, 480) }.toTypedArray()
        val m = ScoreEngraver.engrave(track(*notes), cMajor, TimeSignature.SIX_EIGHT)
            .systems.first().measures.first()
        val primary = m.beams.filter { it.level == 1 }
        assertEquals(2, primary.size)
        assertEquals(listOf(0, 1, 2), primary[0].chordIndices)
        assertEquals(listOf(3, 4, 5), primary[1].chordIndices)
    }

    @Test fun sevenEightBeamsAsTwoTwoThree() {
        val notes = (0 until 7).map { Triple("E4", it * 480L, 480) }.toTypedArray()
        val m = ScoreEngraver.engrave(track(*notes), cMajor, TimeSignature.SEVEN_EIGHT)
            .systems.first().measures.first()
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5, 6)),
            m.beams.filter { it.level == 1 }.map { it.chordIndices },
        )
    }

    @Test fun tripletsAreBracketed() {
        val t = track(
            Triple("E4", 0L, 320), Triple("F4", 320L, 320), Triple("G4", 640L, 320),
        )
        val m = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertEquals(1, m.tuplets.size)
        assertEquals(3, m.tuplets.first().numerator)
        assertEquals(listOf(0, 1, 2), m.tuplets.first().chordIndices)
    }

    // --- Layout ---

    @Test fun measuresAreOrderedAndContiguous() {
        val notes = (0 until 12).map { Triple("E4", it * 960L, 960) }.toTypedArray()
        val score = ScoreEngraver.engrave(track(*notes), cMajor, TimeSignature.FOUR_FOUR)
        val measures = score.systems.flatMap { it.measures }
        assertEquals(3, measures.size)
        measures.forEachIndexed { i, m ->
            assertEquals(i, m.index)
            assertEquals(i * 3840L, m.startTick)
        }
    }

    @Test fun systemsRespectTheRequestedWidth() {
        val notes = (0 until 64).map { Triple("E4", it * 960L, 960) }.toTypedArray()
        val score = ScoreEngraver.engrave(track(*notes), cMajor, TimeSignature.FOUR_FOUR, systemWidth = 40.0)
        assertTrue(score.systems.size > 1)
        for (s in score.systems) {
            // A single measure wider than the system gets its own line rather than clipping.
            if (s.measures.size > 1) assertTrue("system ${s.index} is ${s.width} wide", s.width <= 40.0)
        }
        assertEquals(16, score.measureCount)
    }

    @Test fun positionsWithinAMeasureAscend() {
        val notes = (0 until 8).map { Triple("E4", it * 480L, 480) }.toTypedArray()
        val m = ScoreEngraver.engrave(track(*notes), cMajor, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first()
        val xs = m.chords.map { it.x }
        assertEquals(xs.sorted(), xs)
        assertTrue(xs.first() >= m.headerWidth)
        assertTrue(xs.last() < m.width)
    }

    @Test fun longerNotesGetMoreRoomButNotProportionally() {
        val quarter = ScoreEngraver.spacingFor(960)
        val whole = ScoreEngraver.spacingFor(3840)
        val eighth = ScoreEngraver.spacingFor(480)
        assertTrue(whole > quarter)
        assertTrue(quarter > eighth)
        assertTrue("spacing should be compressed, not linear", whole < quarter * 4)
    }

    @Test fun firstMeasureCarriesTheClefKeyAndMetre() {
        val score = ScoreEngraver.engrave(track(Triple("E4", 0L, 3840)), eMinor, TimeSignature.FIVE_FOUR)
        val first = score.systems.first().measures.first()
        assertEquals(1, first.keySignatureFifths)
        assertTrue(first.printTimeSignature)
        assertEquals(TimeSignature.FIVE_FOUR, first.timeSignature)
        assertNotNull(first.tempoText)
    }

    @Test fun tempoTextIsPrintedAtTheStartAndAtChanges() {
        val score = ScoreEngraver.engrave(
            track(Triple("E4", 0L, 7680)), cMajor, TimeSignature.FOUR_FOUR,
            tempoMap = TempoMap(listOf(TempoMarking(0, 92.0), TempoMarking(3840, 140.0))),
        )
        val measures = score.systems.flatMap { it.measures }
        assertEquals("♩ = 92", measures[0].tempoText)
        assertEquals("♩ = 140", measures[1].tempoText)
    }

    @Test fun notesCanBeLocatedByIdForEditing() {
        val t = track(Triple("E4", 0L, 960), Triple("G4", 960L, 960))
        val score = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
        val found = score.locate(2L)
        assertNotNull(found)
        assertEquals(960L, found!!.second.startTick)
        assertNull(score.locate(999L))
    }

    @Test fun anEmptyTrackStillEngravesABar() {
        val t = Track(1L, "T", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24)
        val score = ScoreEngraver.engrave(t, cMajor, TimeSignature.FOUR_FOUR)
        assertEquals(1, score.measureCount)
        assertTrue(score.systems.first().measures.first().chords.isEmpty())
    }

    @Test fun chordSymbolsArePrintedAboveTheirStack() {
        val progression = listOf(
            ChordEvent(1L, Chord.of("C", ChordType.MAJOR)!!, 0L, 1920),
            ChordEvent(2L, Chord.of("G", ChordType.DOMINANT7)!!, 1920L, 1920),
        )
        val t = track(Triple("E4", 0L, 1920), Triple("D4", 1920L, 1920))
        val m = ScoreEngraver.engrave(
            t, cMajor, TimeSignature.FOUR_FOUR, chordProgression = progression,
        ).systems.first().measures.first()
        assertEquals("C", m.chords[0].chordSymbol)
        assertEquals("G7", m.chords[1].chordSymbol)
    }

    // --- Tab ---

    @Test fun tabLinesAreNumberedInPlayerOrder() {
        val t = track(Triple("E2", 0L, 960), Triple("E4", 960L, 960))
        val layout = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
        assertEquals(6, layout.stringCount)
        assertEquals(listOf("E", "B", "G", "D", "A", "E"), layout.stringLabels)
        val low = layout.systems.first().measures.first().stacks[0].cells.first()
        // Low E is string 0 internally but the bottom line of the tab.
        assertEquals(0, low.string)
        assertEquals(5, low.line)
    }

    @Test fun tabFretNumbersMatchTheirPitches() {
        val t = track(
            Triple("A2", 0L, 480), Triple("C3", 480L, 480),
            Triple("E3", 960L, 480), Triple("G3", 1440L, 480),
        )
        val layout = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
        val board = t.fretboard
        for (stack in layout.systems.first().measures.first().stacks) {
            for (cell in stack.cells) {
                val note = t.note(cell.noteId)!!
                assertEquals(note.pitch, board.pitchAt(cell.string, cell.fret))
            }
        }
    }

    @Test fun tabAndScoreDescribeTheSameNotes() {
        val t = track(
            Triple("E3", 0L, 480), Triple("G3", 480L, 480), Triple("B3", 960L, 480),
            Triple("E4", 1440L, 480), Triple("G4", 1920L, 1920),
        )
        val score = ScoreEngraver.engrave(t, eMinor, TimeSignature.FOUR_FOUR)
        val tab = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
        val scoreIds = score.systems.flatMap { it.measures }.flatMap { it.chords }
            .flatMap { it.noteheads }.map { it.noteId }.distinct().sorted()
        val tabIds = tab.systems.flatMap { it.measures }.flatMap { it.stacks }
            .flatMap { it.cells }.map { it.noteId }.distinct().sorted()
        assertEquals(scoreIds, tabIds)
        assertEquals(t.notes.map { it.id }.sorted(), tabIds)
    }

    @Test fun tabStacksChordsVertically() {
        val t = track(Triple("E3", 0L, 1920), Triple("B3", 0L, 1920), Triple("E4", 0L, 1920))
        val m = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertEquals(1, m.stacks.size)
        assertEquals(3, m.stacks.first().cells.size)
        // Cells are listed from the top line downwards, as drawn.
        val lines = m.stacks.first().cells.map { it.line }
        assertEquals(lines.sorted(), lines)
    }

    @Test fun tabCarriesTechniqueMarkings() {
        val base = track(Triple("E4", 0L, 480), Triple("F#4", 480L, 480))
        val t = base.copy(
            notes = listOf(
                base.notes[0].withArticulation(Articulation.HAMMER_ON),
                base.notes[1].withArticulation(Articulation.VIBRATO),
            ),
        )
        val m = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR).systems.first().measures.first()
        assertEquals("h", m.stacks[0].cells.first().connectorToNext)
        assertTrue(m.stacks[1].cells.first().articulations.contains(Articulation.VIBRATO))
    }

    @Test fun deadNotesAreMarked() {
        val base = track(Triple("E4", 0L, 480))
        val t = base.copy(notes = listOf(base.notes[0].withArticulation(Articulation.DEAD_NOTE)))
        val cell = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
            .systems.first().measures.first().stacks.first().cells.first()
        assertTrue(cell.dead)
    }

    @Test fun tabWorksForEveryStringCount() {
        for (tuning in Tunings.presets) {
            val board = Fretboard(tuning, 24)
            val notes = (0 until tuning.stringCount).map { s ->
                MusicNote(s + 1L, board.pitchAt(s, 5), s * 480L, 480, string = s, fret = 5)
            }
            val t = Track(
                1L, "T", TrackRole.LEAD, InstrumentVoice.defaultFor(tuning.family),
                tuning, 24, notes,
            )
            val layout = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
            assertEquals(tuning.id, tuning.stringCount, layout.stringCount)
            assertEquals(tuning.stringCount, layout.stringLabels.size)
            val cells = layout.systems.flatMap { it.measures }.flatMap { it.stacks }.flatMap { it.cells }
            assertEquals(tuning.stringCount, cells.size)
            assertTrue(cells.all { it.line in 0 until tuning.stringCount })
        }
    }

    @Test fun unplayableNotesAreReportedNotDropped() {
        // A note with no fingering (left over from a tuning change) is flagged.
        val t = Track(
            1L, "T", TrackRole.LEAD, InstrumentVoice.ELECTRIC_CLEAN, Tunings.STANDARD_E, 24,
            listOf(MusicNote(1L, n("C1"), 0L, 480)),
        )
        assertEquals(1, TabEngraver.unplaceable(t).size)
        val layout = TabEngraver.engrave(t, TimeSignature.FOUR_FOUR)
        assertTrue(layout.systems.flatMap { it.measures }.flatMap { it.stacks }.isEmpty())
    }

    @Test fun asciiTabIsReadableAndHasOneLinePerString() {
        val t = track(
            Triple("E3", 0L, 480), Triple("G3", 480L, 480),
            Triple("A3", 960L, 480), Triple("B3", 1440L, 480),
        )
        val ascii = TabEngraver.toAsciiTab(t, TimeSignature.FOUR_FOUR)
        val lines = ascii.trim().lines().filter { it.isNotBlank() }
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.contains('|') })
        assertTrue(lines.first().startsWith("E"))
        // Every fret number used appears somewhere in the output.
        for (note in t.notes) {
            assertTrue("fret ${note.fret} missing", ascii.contains(note.fret!!.toString()))
        }
    }
}
