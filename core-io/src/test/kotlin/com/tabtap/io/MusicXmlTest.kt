package com.tabtap.io

import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ChordEvent
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.HarmonyGenerator
import com.tabtap.music.HarmonySpec
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlTest {

    private fun n(s: String) = Pitch.parse(s)!!

    private fun composition(
        tuning: com.tabtap.music.Tuning = Tunings.STANDARD_E,
        key: Key = Key.of("E", ScaleType.NATURAL_MINOR)!!,
        timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    ): Composition {
        var c = Composition.newGuitarProject(tuning, 132.0, key).copy(timeSignature = timeSignature)
        val board = Fretboard(tuning, 24)
        val names = listOf("E4", "F#4", "G4", "A4", "B4", "C5", "B4", "G4")
        val notes = names.mapIndexed { i, name ->
            val p = n(name)
            val pos = board.bestPositionFor(p, 12)
            MusicNote(
                i + 1L, p, i * 480L, 480, 90,
                string = pos?.string, fret = pos?.fret,
            )
        }
        c = c.updateTrack(1L) { it.copy(notes = notes) }
        return c.copy(nextId = 100L)
    }

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply {
            isValidating = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(xml.byteInputStream())

    // --- Export structure ---

    @Test fun exportedDocumentIsWellFormedAndPartwise() {
        val xml = MusicXmlExporter.export(composition(), "My Solo")
        val doc = parse(xml)
        assertEquals("score-partwise", doc.documentElement.tagName)
        assertEquals("4.0", doc.documentElement.getAttribute("version"))
        assertTrue(xml.contains("<work-title>My Solo</work-title>"))
        assertTrue(xml.contains("<software>TabTap</software>"))
    }

    @Test fun divisionsMatchTheProjectResolutionSoDurationsAreExact() {
        val xml = MusicXmlExporter.export(composition())
        assertTrue(xml.contains("<divisions>960</divisions>"))
        // An eighth note at 960 divisions is exactly 480.
        assertTrue(xml.contains("<duration>480</duration>"))
    }

    @Test fun keyAndTimeSignatureAndTempoAreWritten() {
        val xml = MusicXmlExporter.export(
            composition(timeSignature = TimeSignature.FIVE_FOUR),
        )
        assertTrue(xml.contains("<fifths>1</fifths>"))
        assertTrue(xml.contains("<mode>minor</mode>"))
        assertTrue(xml.contains("<beats>5</beats>"))
        assertTrue(xml.contains("<beat-type>4</beat-type>"))
        assertTrue(xml.contains("<per-minute>132</per-minute>"))
    }

    @Test fun guitarPartsUseAnOctaveTransposingClefButSoundingPitches() {
        val xml = MusicXmlExporter.export(composition())
        assertTrue(xml.contains("<sign>G</sign>"))
        assertTrue(xml.contains("<clef-octave-change>-1</clef-octave-change>"))
        // Guitar is not a transposing instrument in MusicXML: the clef carries the
        // octave convention and <pitch> holds the real sounding pitch. Writing a
        // <transpose> as well would make every note sound an octave too low on import.
        assertTrue("guitar parts must not declare a transposition", !xml.contains("<transpose>"))
        // The lowest note of the solo is a sounding E4, written as octave 4.
        assertTrue(xml.contains("<step>E</step>"))
        assertTrue(xml.contains("<octave>4</octave>"))
    }

    @Test fun soundingPitchesSurviveTheClefConvention() {
        // A low open E (E2) must come back as E2, not E3 or E1.
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(notes = listOf(MusicNote(1L, n("E2"), 0L, 960, string = 0, fret = 0)))
        }
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        assertEquals(n("E2"), back.tracks.first().notes.first().pitch)
    }

    @Test fun bassPartsUseTheBassClef() {
        val xml = MusicXmlExporter.export(
            Composition.newBassProject(Tunings.BASS_STANDARD).updateTrack(1L) {
                it.copy(notes = listOf(MusicNote(1L, n("E2"), 0L, 960, string = 0, fret = 0)))
            },
        )
        assertTrue(xml.contains("<sign>F</sign>"))
        assertTrue(xml.contains("<line>4</line>"))
    }

    @Test fun theTuningIsWrittenSoReadersCanShowTablature() {
        val xml = MusicXmlExporter.export(composition(Tunings.DADGAD))
        assertTrue(xml.contains("<staff-details>"))
        assertTrue(xml.contains("<staff-lines>6</staff-lines>"))
        // DADGAD's lowest string is D2.
        assertTrue(xml.contains("<tuning-step>D</tuning-step>"))
        assertTrue(xml.contains("<tuning-octave>2</tuning-octave>"))
    }

    @Test fun eightStringTuningsAreWrittenInFull() {
        val xml = MusicXmlExporter.export(composition(Tunings.EIGHT_STANDARD_F))
        assertTrue(xml.contains("<staff-lines>8</staff-lines>"))
        assertEquals(8, Regex("<staff-tuning line=").findAll(xml).count())
    }

    @Test fun notesCarryStringAndFretNumbers() {
        val xml = MusicXmlExporter.export(composition())
        assertTrue(xml.contains("<technical>"))
        assertTrue(xml.contains("<fret>"))
        assertTrue(xml.contains("<string>"))
    }

    @Test fun beamsAndStemsComeFromTheEngravedLayout() {
        val xml = MusicXmlExporter.export(composition())
        assertTrue(xml.contains("<beam number=\"1\">begin</beam>"))
        assertTrue(xml.contains("<beam number=\"1\">end</beam>"))
        assertTrue(xml.contains("<stem>"))
    }

    @Test fun accidentalsAreWrittenOnlyWhereTheyAreNeeded() {
        // F# belongs to E minor, so it carries no accidental element...
        val inKey = MusicXmlExporter.export(composition())
        // ...but an F natural in E minor does.
        val outOfKey = MusicXmlExporter.export(
            composition().updateTrack(1L) { t -> t.updateNote(2L) { it.copy(pitch = n("F4")) } },
        )
        assertTrue(outOfKey.contains("<accidental>natural</accidental>"))
        assertTrue(
            "an in-key F# should need no accidental",
            Regex("<accidental>").findAll(inKey).count() < Regex("<accidental>").findAll(outOfKey).count(),
        )
    }

    @Test fun tiesAcrossBarLinesAreWrittenAsTieAndTied() {
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(notes = listOf(MusicNote(1L, n("E4"), 2880L, 1920, string = 5, fret = 0)))
        }
        val xml = MusicXmlExporter.export(c)
        assertTrue(xml.contains("<tie type=\"start\"/>"))
        assertTrue(xml.contains("<tie type=\"stop\"/>"))
        assertTrue(xml.contains("<tied type=\"start\"/>"))
        assertTrue(xml.contains("<tied type=\"stop\"/>"))
    }

    @Test fun restsFillTheBars() {
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(notes = listOf(MusicNote(1L, n("E4"), 0L, 480, string = 5, fret = 0)))
        }
        val xml = MusicXmlExporter.export(c)
        assertTrue(xml.contains("<rest/>"))
    }

    @Test fun chordsAreWrittenWithTheChordElement() {
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(
                notes = listOf(
                    MusicNote(1L, n("E3"), 0L, 1920, string = 0, fret = 0),
                    MusicNote(2L, n("B3"), 0L, 1920, string = 1, fret = 2),
                    MusicNote(3L, n("E4"), 0L, 1920, string = 2, fret = 2),
                ),
            )
        }
        val xml = MusicXmlExporter.export(c)
        assertEquals(2, Regex("<chord/>").findAll(xml).count())
    }

    @Test fun techniquesAreWritten() {
        var c = composition()
        c = c.updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.HAMMER_ON) }
                .updateNote(2L) { it.withArticulation(Articulation.VIBRATO) }
                .updateNote(3L) { it.withArticulation(Articulation.HARMONIC) }
                .updateNote(4L) { it.withArticulation(Articulation.BEND).copy(bendSemitones = 2f) }
                .updateNote(5L) { it.withArticulation(Articulation.STACCATO) }
        }
        val xml = MusicXmlExporter.export(c)
        assertTrue(xml.contains("<hammer-on"))
        assertTrue(xml.contains("<wavy-line"))
        assertTrue(xml.contains("<natural/>"))
        assertTrue(xml.contains("<bend-alter>2.0</bend-alter>"))
        assertTrue(xml.contains("<staccato/>"))
    }

    @Test fun chordSymbolsBecomeHarmonyElements() {
        val c = composition().withChordProgression(
            listOf(
                ChordEvent(200L, Chord.of("E", ChordType.MINOR)!!, 0L, 1920),
                ChordEvent(201L, Chord.of("C", ChordType.MAJOR7)!!, 1920L, 1920),
            ),
        )
        val xml = MusicXmlExporter.export(c)
        assertTrue(xml.contains("<root-step>E</root-step>"))
        assertTrue(xml.contains("<kind text=\"m\">minor</kind>"))
        assertTrue(xml.contains("<kind text=\"maj7\">major-seventh</kind>"))
    }

    @Test fun slashChordsWriteABassElement() {
        val c = composition().withChordProgression(
            listOf(ChordEvent(200L, Chord.of("C", ChordType.MAJOR)!!.inversion(2), 0L, 3840)),
        )
        val xml = MusicXmlExporter.export(c)
        assertTrue(xml.contains("<bass-step>G</bass-step>"))
    }

    @Test fun everyTrackBecomesItsOwnPart() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.SIXTH_ABOVE)).first
        val xml = MusicXmlExporter.export(c)
        assertEquals(3, Regex("<score-part id=").findAll(xml).count())
        assertEquals(3, Regex("<part id=").findAll(xml).count())
        assertTrue(xml.contains("<part-name>Diatonic 3rd above</part-name>"))
    }

    @Test fun titlesWithSpecialCharactersAreEscaped() {
        val xml = MusicXmlExporter.export(composition(), "Rock & Roll <demo>")
        assertTrue(xml.contains("Rock &amp; Roll &lt;demo&gt;"))
        // Still parses.
        parse(xml)
    }

    // --- Round trip ---

    @Test fun exportThenImportPreservesPitchesAndTiming() {
        val c = composition()
        val result = MusicXmlImporter.import(MusicXmlExporter.export(c, "Solo"))
        assertEquals("Solo", result.title)
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        val original = c.track(1L)!!.notes
        val imported = result.composition.tracks.first().notes
        assertEquals(original.map { it.pitch }, imported.map { it.pitch })
        assertEquals(original.map { it.startTick }, imported.map { it.startTick })
        assertEquals(original.map { it.durationTicks }, imported.map { it.durationTicks })
    }

    @Test fun roundTripPreservesKeyTempoAndMetre() {
        val c = composition(timeSignature = TimeSignature.SEVEN_EIGHT)
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        assertEquals(132.0, back.bpm, 0.5)
        assertEquals(TimeSignature.SEVEN_EIGHT, back.timeSignature)
        assertEquals(4, back.key.tonicPitchClass)
        assertEquals(ScaleType.NATURAL_MINOR, back.key.scale)
    }

    @Test fun roundTripPreservesTheFingeringTheAuthorChose() {
        // Deliberately pick an awkward position: E4 on the 14th fret of the D string.
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(notes = listOf(MusicNote(1L, n("E4"), 0L, 960, string = 2, fret = 14)))
        }
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        val note = back.tracks.first().notes.first()
        assertEquals(2, note.string)
        assertEquals(14, note.fret)
    }

    @Test fun roundTripPreservesChordsAndMultipleParts() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        c = c.updateTrack(1L) { t ->
            t.withNotes(
                t.notes + listOf(
                    MusicNote(300L, n("B3"), 0L, 480, string = 1, fret = 2),
                    MusicNote(301L, n("G3"), 0L, 480, string = 0, fret = 3),
                ),
            )
        }
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        assertEquals(2, back.tracks.size)
        // The three simultaneous notes at tick 0 come back as a stack.
        assertEquals(3, back.tracks.first().notes.count { it.startTick == 0L })
    }

    @Test fun roundTripRejoinsTiedNotesIntoOneSoundingNote() {
        var c = composition()
        c = c.updateTrack(1L) {
            it.copy(notes = listOf(MusicNote(1L, n("E4"), 2880L, 1920, string = 5, fret = 0)))
        }
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        val notes = back.tracks.first().notes
        assertEquals("the tied fragments should rejoin", 1, notes.size)
        assertEquals(2880L, notes.first().startTick)
        assertEquals(1920, notes.first().durationTicks)
    }

    @Test fun roundTripPreservesTechniques() {
        var c = composition()
        c = c.updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.HAMMER_ON) }
                .updateNote(2L) { it.withArticulation(Articulation.STACCATO) }
        }
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        val notes = back.tracks.first().notes
        assertTrue(notes[0].has(Articulation.HAMMER_ON))
        assertTrue(notes[1].has(Articulation.STACCATO))
    }

    @Test fun roundTripProducesAValidComposition() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val back = MusicXmlImporter.import(MusicXmlExporter.export(c)).composition
        assertEquals(emptyList<String>(), back.validate())
    }

    @Test fun roundTripSurvivesEveryTuningPreset() {
        for (tuning in Tunings.presets) {
            val board = Fretboard(tuning, 24)
            val notes = (0 until tuning.stringCount).map { s ->
                MusicNote(s + 1L, board.pitchAt(s, 5), s * 480L, 480, string = s, fret = 5)
            }
            var c = Composition.newGuitarProject(tuning)
            c = c.updateTrack(1L) { it.copy(notes = notes) }
            val back = MusicXmlImporter.import(
                MusicXmlExporter.export(c), tuning,
            ).composition
            assertEquals(tuning.id, notes.map { it.pitch }, back.tracks.first().notes.map { it.pitch })
        }
    }

    // --- Importing other people's files ---

    @Test fun importsAMinimalHandWrittenDocument() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="3.1">
              <part-list>
                <score-part id="P1"><part-name>Guitar</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>4</divisions>
                    <key><fifths>0</fifths></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                    <clef><sign>G</sign><line>2</line></clef>
                  </attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch>
                    <duration>4</duration><type>quarter</type></note>
                  <note><pitch><step>E</step><octave>4</octave></pitch>
                    <duration>4</duration><type>quarter</type></note>
                  <note><rest/><duration>4</duration><type>quarter</type></note>
                  <note><pitch><step>G</step><octave>4</octave></pitch>
                    <duration>4</duration><type>quarter</type></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()
        val result = MusicXmlImporter.import(xml)
        val notes = result.composition.tracks.first().notes
        assertEquals("Guitar", result.composition.tracks.first().name)
        assertEquals(listOf(n("C4"), n("E4"), n("G4")), notes.map { it.pitch })
        // Divisions of 4 means a quarter note is 4 units, rescaled to 960 ticks.
        assertEquals(listOf(0L, 960L, 2880L), notes.map { it.startTick })
        assertEquals(listOf(960, 960, 960), notes.map { it.durationTicks })
    }

    @Test fun importHonoursBackupForMultiVoiceMusic() {
        val xml = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>G</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                <backup><duration>4</duration></backup>
                <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        val notes = MusicXmlImporter.import(xml).composition.tracks.first().notes
        // Both notes start at tick 0 because of the backup.
        assertEquals(listOf(0L, 0L), notes.map { it.startTick })
    }

    @Test fun importRejectsNonMusicXmlWithAHelpfulMessage() {
        val failure = runCatching {
            MusicXmlImporter.import("<html><body>Not music</body></html>")
        }.exceptionOrNull()
        assertTrue(failure is MusicXmlParseException)
        assertTrue(failure!!.message!!.contains("Not a MusicXML document"))
    }

    @Test fun importExplainsTheTimewiseForm() {
        val failure = runCatching {
            MusicXmlImporter.import("<score-timewise version=\"4.0\"></score-timewise>")
        }.exceptionOrNull()
        assertTrue(failure is MusicXmlParseException)
        assertTrue(failure!!.message!!.contains("timewise"))
    }

    @Test fun importSkipsUnpitchedAndGraceNotesWithoutMistiming() {
        val xml = """
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>G</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><grace/><pitch><step>D</step><octave>4</octave></pitch></note>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                <note><unpitched/><duration>4</duration></note>
                <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        val notes = MusicXmlImporter.import(xml).composition.tracks.first().notes
        assertEquals(listOf(n("C4"), n("E4")), notes.map { it.pitch })
        assertEquals(listOf(0L, 7680L), notes.map { it.startTick })
    }

    @Test fun importIgnoresAnUnreachableExternalDtd() {
        // The doctype must not cause a network fetch or a failure.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN"
              "http://example.invalid/partwise.dtd">
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>G</part-name></score-part></part-list>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()
        assertEquals(1, MusicXmlImporter.import(xml).composition.tracks.first().notes.size)
    }
}
