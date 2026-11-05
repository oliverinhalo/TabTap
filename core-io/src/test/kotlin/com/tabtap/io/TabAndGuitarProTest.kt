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
import com.tabtap.music.StrumPattern
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabAndGuitarProTest {

    private fun n(s: String) = Pitch.parse(s)!!

    private fun composition(tuning: com.tabtap.music.Tuning = Tunings.STANDARD_E): Composition {
        var c = Composition.newGuitarProject(tuning, 120.0, Key.of("E", ScaleType.NATURAL_MINOR)!!)
        val board = Fretboard(tuning, 24)
        val notes = listOf("E3", "G3", "A3", "B3", "D4", "E4").mapIndexed { i, name ->
            val p = n(name)
            val pos = board.bestPositionFor(p, 5)
            MusicNote(i + 1L, p, i * 480L, 480, string = pos?.string, fret = pos?.fret)
        }
        c = c.updateTrack(1L) { it.copy(notes = notes) }
        return c.copy(nextId = 100L)
    }

    // --- ASCII tab ---

    @Test fun asciiTabCarriesTheProjectHeader() {
        val text = CompositionExporter.asciiTab(composition(), "My Riff")
        assertTrue(text.startsWith("My Riff"))
        assertTrue(text.contains("Key: E Natural Minor"))
        assertTrue(text.contains("Tempo: 120 BPM"))
        assertTrue(text.contains("Time: 4/4"))
        assertTrue(text.contains("Standard E: E2 A2 D3 G3 B3 E4"))
    }

    @Test fun asciiTabHasOneStaffPerTrack() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val text = CompositionExporter.asciiTab(c, "Harmonised")
        assertTrue(text.contains("Lead"))
        assertTrue(text.contains("Diatonic 3rd above"))
        // Six tab lines per track, twice over.
        assertEquals(12, text.lines().count { it.contains('|') && it.firstOrNull()?.isLetter() == true })
    }

    @Test fun asciiTabListsTheChordProgression() {
        val c = composition().withChordProgression(
            listOf(
                ChordEvent(200L, Chord.of("E", ChordType.MINOR)!!, 0L, 1920),
                ChordEvent(201L, Chord.of("C", ChordType.MAJOR)!!, 1920L, 1920),
            ),
        )
        assertTrue(CompositionExporter.asciiTab(c, "x").contains("Chords: Em | C"))
    }

    @Test fun asciiTabAdaptsToStringCount() {
        for (tuning in listOf(Tunings.BASS_STANDARD, Tunings.SEVEN_STANDARD_B, Tunings.EIGHT_STANDARD_F)) {
            val text = CompositionExporter.asciiTab(composition(tuning), "x")
            val staffLines = text.lines().count { it.contains('|') && it.firstOrNull()?.isLetter() == true }
            assertEquals(tuning.id, tuning.stringCount, staffLines)
        }
    }

    // --- File naming ---

    @Test fun fileNamesAreSafeAndCarryTheRightExtension() {
        assertEquals("My Solo.mid", CompositionExporter.fileName("My Solo", ExportFormat.MIDI_TYPE_1))
        assertEquals("My Solo (type 0).mid", CompositionExporter.fileName("My Solo", ExportFormat.MIDI_TYPE_0))
        assertEquals("My Solo.musicxml", CompositionExporter.fileName("My Solo", ExportFormat.MUSICXML))
        assertEquals("My Solo.gp5", CompositionExporter.fileName("My Solo", ExportFormat.GUITAR_PRO_5))
        assertEquals("Riff 1 draft.txt", CompositionExporter.fileName("Riff/1: *draft*", ExportFormat.ASCII_TAB))
        assertEquals("TabTap project.pdf", CompositionExporter.fileName("  ", ExportFormat.PDF))
    }

    @Test fun everyNonPdfFormatProducesBytes() {
        val c = composition()
        for (format in ExportFormat.PURE_KOTLIN) {
            val bytes = CompositionExporter.export(c, format, "Test")
            assertTrue("${format.id} produced nothing", bytes.size > 64)
        }
        assertTrue(
            runCatching { CompositionExporter.export(c, ExportFormat.PDF) }.isFailure,
        )
    }

    @Test fun importFormatsAreRecognisedByExtension() {
        assertEquals(ImportFormat.MIDI, ImportFormat.forFileName("song.mid"))
        assertEquals(ImportFormat.MIDI, ImportFormat.forFileName("SONG.MIDI"))
        assertEquals(ImportFormat.MUSICXML, ImportFormat.forFileName("score.musicxml"))
        assertEquals(ImportFormat.MUSICXML, ImportFormat.forFileName("score.xml"))
        assertEquals(null, ImportFormat.forFileName("notes.txt"))
    }

    // --- Guitar Pro 5 ---

    @Test fun guitarProFileStartsWithTheVersionString() {
        val bytes = GuitarPro5Exporter.export(composition(), "Riff")
        // A Pascal string: a length byte, then the text, padded to 31 bytes.
        assertEquals(24, bytes[0].toInt())
        assertEquals("FICHIER GUITAR PRO v5.10", String(bytes, 1, 24, Charsets.ISO_8859_1))
        assertTrue(bytes.size > 1000)
    }

    @Test fun guitarProFileContainsTheTitleAndTrackName() {
        val bytes = GuitarPro5Exporter.export(composition(), "Riff Title")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("Riff Title"))
        assertTrue(text.contains("Lead"))
        assertTrue(text.contains("TabTap"))
    }

    @Test fun guitarProFileRecordsTheTuningAndFretCount() {
        val bytes = GuitarPro5Exporter.export(composition(Tunings.DROP_C), "x")
        // Drop C's lowest string is C2, MIDI 36, written as a little-endian int32.
        val target = byteArrayOf(36, 0, 0, 0, 43, 0, 0, 0)   // C2 then G2
        assertTrue("tuning not found in the file", indexOf(bytes, target) >= 0)
    }

    @Test fun guitarProExportHandlesEveryTuningAndStringCount() {
        for (tuning in Tunings.presets) {
            val bytes = GuitarPro5Exporter.export(composition(tuning), tuning.displayName)
            assertTrue("${tuning.id} produced too little data", bytes.size > 800)
        }
    }

    @Test fun guitarProExportHandlesMultipleTracks() {
        var c = composition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.OCTAVE_BELOW)).first
        val bytes = GuitarPro5Exporter.export(c, "Harmonised")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("Diatonic 3rd above"))
        assertTrue(text.contains("1 octave below"))
    }

    @Test fun guitarProExportHandlesTechniquesAndEmptyProjects() {
        val withTechniques = composition().updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.BEND).copy(bendSemitones = 2f) }
                .updateNote(2L) { it.withArticulation(Articulation.PALM_MUTE) }
                .updateNote(3L) { it.withArticulation(Articulation.DEAD_NOTE) }
                .updateNote(4L) { it.withArticulation(Articulation.HARMONIC) }
        }
        assertTrue(GuitarPro5Exporter.export(withTechniques, "x").size > 1000)
        assertTrue(GuitarPro5Exporter.export(Composition.newGuitarProject(), "empty").size > 800)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // --- Chord rendering, shared by playback and export ---

    @Test fun blockChordsSoundTogether() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        val event = ChordEvent(1L, Chord.of("E", ChordType.MINOR)!!, 0L, 1920, strum = StrumPattern.BLOCK)
        val notes = ChordRenderer.render(event, board)
        assertTrue(notes.size >= 3)
        assertEquals(1, notes.map { it.startTick }.distinct().size)
        assertEquals(setOf(4, 7, 11), notes.map { Pitch.pitchClass(it.pitch) }.toSet())
    }

    @Test fun strummedChordsSpreadAcrossTheStrings() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        val down = ChordRenderer.render(
            ChordEvent(1L, Chord.of("G", ChordType.MAJOR)!!, 0L, 1920, strum = StrumPattern.STRUM_DOWN),
            board,
        )
        val starts = down.map { it.startTick }
        assertEquals(starts.sorted(), starts)
        assertTrue("a strum should be spread in time", starts.distinct().size > 1)
        // A down strum starts on the lowest string.
        assertEquals(down.minByOrNull { it.startTick }!!.pitch, down.minOf { it.pitch })

        val up = ChordRenderer.render(
            ChordEvent(1L, Chord.of("G", ChordType.MAJOR)!!, 0L, 1920, strum = StrumPattern.STRUM_UP),
            board,
        )
        assertEquals(up.minByOrNull { it.startTick }!!.pitch, up.maxOf { it.pitch })
    }

    @Test fun arpeggiosPlayOneNoteAtATimeAcrossTheWholeDuration() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        val notes = ChordRenderer.render(
            ChordEvent(1L, Chord.of("C", ChordType.MAJOR)!!, 0L, 1920, strum = StrumPattern.ARPEGGIO_UP),
            board,
        )
        assertEquals(notes.size, notes.map { it.startTick }.distinct().size)
        val last = notes.maxByOrNull { it.startTick }!!
        assertTrue(last.startTick + last.durationTicks <= 1920)
        // Ascending.
        assertEquals(notes.map { it.pitch }.sorted(), notes.map { it.pitch })
        assertNotEquals(
            notes.map { it.pitch },
            ChordRenderer.render(
                ChordEvent(1L, Chord.of("C", ChordType.MAJOR)!!, 0L, 1920, strum = StrumPattern.ARPEGGIO_DOWN),
                board,
            ).map { it.pitch },
        )
    }

    @Test fun renderingUsesAnExplicitVoicingWhenOneIsSet() {
        val board = Fretboard(Tunings.STANDARD_E, 22)
        val voicing = com.tabtap.music.ChordVoicing(listOf(null, null, 0, 2, 3, 2))
        val notes = ChordRenderer.render(
            ChordEvent(1L, Chord.of("D", ChordType.MAJOR)!!, 0L, 1920, voicing = voicing),
            board,
        )
        assertEquals(4, notes.size)
        assertEquals(listOf(n("D3"), n("A3"), n("D4"), n("F#4")), notes.map { it.pitch })
    }

    @Test fun renderingAnUnplayableChordReturnsNothingRatherThanCrashing() {
        val tiny = Fretboard(com.tabtap.music.Tunings.custom(com.tabtap.music.InstrumentFamily.GUITAR, listOf(40)), 12)
        val notes = ChordRenderer.render(ChordEvent(1L, Chord.of("C", ChordType.MAJOR9)!!, 0L, 960), tiny)
        assertTrue(notes.isEmpty())
    }
}
