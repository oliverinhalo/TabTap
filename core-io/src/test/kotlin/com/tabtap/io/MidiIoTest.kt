package com.tabtap.io

import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ChordEvent
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.HarmonyGenerator
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiIoTest {

    private fun n(s: String) = Pitch.parse(s)!!

    /** An E minor solo, four eighths plus a half note, fretted on a standard guitar. */
    private fun soloComposition(): Composition {
        val key = Key.of("E", ScaleType.NATURAL_MINOR)!!
        var c = Composition.newGuitarProject(Tunings.STANDARD_E, 132.0, key)
        val board = Fretboard(Tunings.STANDARD_E, 24)
        val pitches = listOf("E4", "F#4", "G4", "A4", "B4")
        val notes = pitches.mapIndexed { i, name ->
            val p = n(name)
            val pos = board.bestPositionFor(p, 12)!!
            MusicNote(
                id = (i + 1).toLong(), pitch = p, startTick = i * 480L,
                durationTicks = if (i == 4) 1920 else 480,
                velocity = 90 + i, string = pos.string, fret = pos.fret,
            )
        }
        c = c.updateTrack(1L) { it.copy(notes = notes) }
        return c.copy(nextId = 50L)
    }

    // --- Variable-length quantities, the foundation of SMF timing ---

    @Test fun variableLengthQuantitiesMatchTheSpecExamples() {
        // These values and encodings are taken straight from the SMF 1.0 specification.
        fun encode(v: Long): List<Int> {
            val out = java.io.ByteArrayOutputStream()
            MidiWriter.writeVarLen(out, v)
            return out.toByteArray().map { it.toInt() and 0xFF }
        }
        assertEquals(listOf(0x00), encode(0))
        assertEquals(listOf(0x40), encode(0x40))
        assertEquals(listOf(0x7F), encode(0x7F))
        assertEquals(listOf(0x81, 0x00), encode(0x80))
        assertEquals(listOf(0xC0, 0x00), encode(0x2000))
        assertEquals(listOf(0xFF, 0x7F), encode(0x3FFF))
        assertEquals(listOf(0x81, 0x80, 0x00), encode(0x4000))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0x7F), encode(0x0FFFFFFF))
    }

    // --- File structure ---

    @Test fun exportedFileHasAValidHeader() {
        val bytes = MidiExporter.export(soloComposition())
        assertEquals("MThd", String(bytes, 0, 4, Charsets.US_ASCII))
        // Header length is always 6.
        assertEquals(6, bytes[7].toInt())
        // Format 1.
        assertEquals(1, bytes[9].toInt())
        // Division is the project's tick resolution.
        assertEquals(Ticks.PPQ, ((bytes[12].toInt() and 0xFF) shl 8) or (bytes[13].toInt() and 0xFF))
    }

    @Test fun exportedFileEndsEveryTrackProperly() {
        val bytes = MidiExporter.export(soloComposition())
        // Every MTrk chunk must finish with the end-of-track meta event.
        var i = 14
        var chunks = 0
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val len = ((bytes[i + 4].toInt() and 0xFF) shl 24) or ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                ((bytes[i + 6].toInt() and 0xFF) shl 8) or (bytes[i + 7].toInt() and 0xFF)
            assertEquals("MTrk", id)
            val end = i + 8 + len
            assertEquals(0xFF, bytes[end - 3].toInt() and 0xFF)
            assertEquals(0x2F, bytes[end - 2].toInt() and 0xFF)
            assertEquals(0x00, bytes[end - 1].toInt() and 0xFF)
            i = end
            chunks++
        }
        assertEquals(bytes.size, i)
        assertTrue(chunks >= 2)
    }

    @Test fun type0MergesToOneTrackButKeepsChannels() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val bytes = MidiExporter.export(c, MidiExportOptions(format = MidiFormat.TYPE_0))
        val back = MidiReader.read(bytes)
        assertEquals(MidiFormat.TYPE_0, back.format)
        assertEquals(1, back.tracks.size)
        val channels = back.tracks.first().events
            .filterIsInstance<MidiEvent.NoteOn>().map { it.channel }.distinct()
        assertEquals("the two parts should stay on separate channels", 2, channels.size)
    }

    @Test fun type1WritesOneTrackPerPartPlusAConductor() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(
            c, 1L, listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.OCTAVE_BELOW),
        ).first
        val back = MidiReader.read(MidiExporter.export(c))
        assertEquals(MidiFormat.TYPE_1, back.format)
        // Conductor plus three parts.
        assertEquals(4, back.tracks.size)
        assertEquals("Conductor", back.tracks.first().name)
        assertEquals(
            listOf("Lead", "Diatonic 3rd above", "1 octave below"),
            back.tracks.drop(1).map { it.name },
        )
    }

    // --- Round trips ---

    @Test fun everyNoteSurvivesARoundTripWithExactTiming() {
        val c = soloComposition()
        val back = MidiReader.read(MidiExporter.export(c))
        val track = back.tracks.first { it.name == "Lead" }
        val ons = track.events.filterIsInstance<MidiEvent.NoteOn>()
        val offs = track.events.filterIsInstance<MidiEvent.NoteOff>()
        val source = c.track(1L)!!.notes
        assertEquals(source.size, ons.size)
        assertEquals(source.size, offs.size)
        source.forEachIndexed { i, note ->
            assertEquals("pitch $i", note.pitch, ons[i].pitch)
            assertEquals("start $i", note.startTick, ons[i].tick)
            assertEquals("velocity $i", note.velocity, ons[i].velocity)
            val off = offs.first { it.pitch == note.pitch && it.tick >= ons[i].tick }
            assertEquals("duration $i", note.startTick + note.durationTicks, off.tick)
        }
    }

    @Test fun tempoAndMetreAndKeySurviveARoundTrip() {
        val c = soloComposition().copy(
            timeSignature = TimeSignature.SEVEN_EIGHT,
            tempoMap = TempoMap(
                listOf(
                    com.tabtap.music.TempoMarking(0, 132.0),
                    com.tabtap.music.TempoMarking(3360, 96.0),
                ),
            ),
        )
        val conductor = MidiReader.read(MidiExporter.export(c)).tracks.first()
        val tempos = conductor.events.filterIsInstance<MidiEvent.Tempo>()
        assertEquals(2, tempos.size)
        assertEquals(132.0, tempos[0].bpm, 0.05)
        assertEquals(96.0, tempos[1].bpm, 0.05)
        assertEquals(3360L, tempos[1].tick)

        val ts = conductor.events.filterIsInstance<MidiEvent.TimeSignatureEvent>().first()
        assertEquals(7, ts.numerator)
        assertEquals(8, ts.denominator)

        val key = conductor.events.filterIsInstance<MidiEvent.KeySignatureEvent>().first()
        assertEquals(1, key.fifths)
        assertTrue(key.minor)
    }

    @Test fun programAndMixSettingsAreWritten() {
        val c = soloComposition().updateTrack(1L) {
            it.copy(voice = InstrumentVoice.ELECTRIC_DISTORTION, volume = 0.5f, pan = -1f)
        }
        val track = MidiReader.read(MidiExporter.export(c)).tracks.first { it.name == "Lead" }
        assertEquals(
            InstrumentVoice.ELECTRIC_DISTORTION.gmProgram,
            track.events.filterIsInstance<MidiEvent.ProgramChange>().first().program,
        )
        val cc = track.events.filterIsInstance<MidiEvent.ControlChange>()
        assertEquals(64, cc.first { it.controller == 7 }.value)
        assertEquals(0, cc.first { it.controller == 10 }.value)
    }

    @Test fun aFullHarmonisedArrangementExportsAsSeparateTracks() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(
            c, 1L, listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.SIXTH_ABOVE),
        ).first
        val back = MidiReader.read(MidiExporter.export(c))
        val parts = back.tracks.drop(1)
        assertEquals(3, parts.size)
        // Each part has its own channel.
        val channels = parts.map { p -> p.events.filterIsInstance<MidiEvent.NoteOn>().first().channel }
        assertEquals(3, channels.distinct().size)
        // All three parts share the same onsets, because harmony preserves the rhythm.
        val onsets = parts.map { p -> p.events.filterIsInstance<MidiEvent.NoteOn>().map { it.tick } }
        assertEquals(onsets[0], onsets[1])
        assertEquals(onsets[0], onsets[2])
        // And they sound different pitches.
        val pitches = parts.map { p -> p.events.filterIsInstance<MidiEvent.NoteOn>().map { it.pitch } }
        assertTrue(pitches[0] != pitches[1])
        assertTrue(pitches[1] != pitches[2])
    }

    @Test fun mutedTracksCanBeExcluded() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val harmonyId = c.tracks.last().id
        c = c.updateTrack(harmonyId) { it.copy(muted = true) }
        val kept = MidiReader.read(
            MidiExporter.export(c, MidiExportOptions(respectMute = true)),
        ).tracks.drop(1)
        assertEquals(1, kept.size)
        assertEquals("Lead", kept.first().name)
    }

    @Test fun onlySelectedTracksCanBeExported() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val harmonyId = c.tracks.last().id
        val back = MidiReader.read(
            MidiExporter.export(c, MidiExportOptions(trackIds = setOf(harmonyId))),
        )
        assertEquals(2, back.tracks.size)
        assertEquals("Diatonic 3rd above", back.tracks[1].name)
    }

    @Test fun articulationsChangeVelocityAndLength() {
        val base = soloComposition()
        val staccato = base.updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.STACCATO) }
        }
        val plainOff = MidiReader.read(MidiExporter.export(base))
            .tracks.first { it.name == "Lead" }
            .events.filterIsInstance<MidiEvent.NoteOff>().first()
        val shortOff = MidiReader.read(MidiExporter.export(staccato))
            .tracks.first { it.name == "Lead" }
            .events.filterIsInstance<MidiEvent.NoteOff>().first()
        assertTrue("staccato should shorten the note", shortOff.tick < plainOff.tick)

        val accented = base.updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.ACCENT) }
        }
        val loud = MidiReader.read(MidiExporter.export(accented))
            .tracks.first { it.name == "Lead" }
            .events.filterIsInstance<MidiEvent.NoteOn>().first()
        assertTrue("accent should raise velocity", loud.velocity > 90)
        assertTrue(loud.velocity <= 127)
    }

    @Test fun bendsBecomePitchBendRamps() {
        val c = soloComposition().updateTrack(1L) { t ->
            t.updateNote(1L) { it.withArticulation(Articulation.BEND).copy(bendSemitones = 2f) }
        }
        val bends = MidiReader.read(MidiExporter.export(c))
            .tracks.first { it.name == "Lead" }
            .events.filterIsInstance<MidiEvent.PitchBend>()
        assertTrue(bends.size > 4)
        // The ramp starts centred, reaches the top of the bend range and returns.
        assertEquals(0, bends.first().value)
        assertEquals(8191, bends.maxOf { it.value })
        assertEquals(0, bends.last().value)
    }

    @Test fun pitchBendRoundTripsThroughFourteenBitEncoding() {
        for (v in listOf(-8192, -4096, -1, 0, 1, 4096, 8191)) {
            val seq = MidiSequence(
                MidiFormat.TYPE_1, Ticks.PPQ,
                listOf(MidiTrack("T", listOf(MidiEvent.PitchBend(0L, 0, v)))),
            )
            val back = MidiReader.read(MidiWriter.write(seq))
            assertEquals(v, back.tracks.first().events.filterIsInstance<MidiEvent.PitchBend>().first().value)
        }
    }

    @Test fun metronomeTrackClicksOnEveryPulse() {
        val c = soloComposition().copy(timeSignature = TimeSignature.SIX_EIGHT)
        val click = MidiReader.read(
            MidiExporter.export(c, MidiExportOptions(includeMetronome = true)),
        ).tracks.first { it.name == "Metronome" }
        val ons = click.events.filterIsInstance<MidiEvent.NoteOn>()
        assertTrue(ons.isNotEmpty())
        // 6/8 is felt in dotted quarters, so clicks are 1440 ticks apart.
        assertEquals(listOf(1440L), ons.map { it.tick }.zipWithNext { a, b -> b - a }.distinct())
        // Clicks are on the percussion channel and the downbeat is accented.
        assertTrue(ons.all { it.channel == 9 })
        assertTrue(ons.first().velocity > ons[1].velocity)
    }

    @Test fun chordProgressionsExportAsASoundingTrack() {
        val c = soloComposition().withChordProgression(
            listOf(
                ChordEvent(60L, Chord.of("E", ChordType.MINOR)!!, 0L, 1920),
                ChordEvent(61L, Chord.of("C", ChordType.MAJOR)!!, 1920L, 1920),
            ),
        )
        val chords = MidiReader.read(MidiExporter.export(c)).tracks.first { it.name == "Chords" }
        val ons = chords.events.filterIsInstance<MidiEvent.NoteOn>()
        assertTrue("expected a chord voicing to sound", ons.size >= 6)
        // Chord symbols are written as markers so a DAW shows them.
        assertEquals(
            listOf("Em", "C"),
            chords.events.filterIsInstance<MidiEvent.Marker>().map { it.text },
        )
        // The first chord really sounds an E minor triad.
        val firstChordClasses = ons.filter { it.tick < 1920 }.map { Pitch.pitchClass(it.pitch) }.toSet()
        assertEquals(setOf(4, 7, 11), firstChordClasses)
    }

    @Test fun noteOffPrecedesNoteOnAtTheSameTickSoRepeatsRetrigger() {
        // Two identical pitches back to back must not cut each other off.
        var c = Composition.newGuitarProject()
        c = c.updateTrack(1L) {
            it.copy(
                notes = listOf(
                    MusicNote(1L, n("E4"), 0L, 480, string = 5, fret = 0),
                    MusicNote(2L, n("E4"), 480L, 480, string = 5, fret = 0),
                ),
            )
        }
        val events = MidiReader.read(MidiExporter.export(c))
            .tracks.first { it.name == "Lead" }.events
            .filter { it is MidiEvent.NoteOn || it is MidiEvent.NoteOff }
            .filter { it.tick == 480L }
        assertEquals(2, events.size)
        assertTrue("note-off must come first at a shared tick", events.first() is MidiEvent.NoteOff)
    }

    // --- Import ---

    @Test fun exportThenImportPreservesTheMusic() {
        var c = soloComposition()
        c = HarmonyGenerator.generate(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        val result = MidiImporter.import(MidiExporter.export(c))
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        assertEquals(132.0, result.composition.bpm, 0.5)
        assertEquals(TimeSignature.FOUR_FOUR, result.composition.timeSignature)
        assertEquals(4, result.composition.key.tonicPitchClass)
        assertEquals(2, result.composition.tracks.size)
        for ((original, imported) in c.tracks.zip(result.composition.tracks)) {
            assertEquals(original.name, imported.name)
            assertEquals(original.notes.map { it.pitch }, imported.notes.map { it.pitch })
            assertEquals(original.notes.map { it.startTick }, imported.notes.map { it.startTick })
            assertEquals(original.notes.map { it.durationTicks }, imported.notes.map { it.durationTicks })
            assertEquals(original.notes.map { it.velocity }, imported.notes.map { it.velocity })
        }
    }

    @Test fun importedNotesAreFrettedConsistentlyWithTheirPitch() {
        val result = MidiImporter.import(MidiExporter.export(soloComposition()))
        val track = result.composition.tracks.first()
        val board = track.fretboard
        for (note in track.notes) {
            assertNotNull(note.string)
            assertEquals(note.pitch, board.pitchAt(note.string!!, note.fret!!))
        }
        assertTrue(result.composition.validate().isEmpty())
    }

    @Test fun importRescalesADifferentTickResolution() {
        // A file at 480 ticks per quarter must come in at the project's 960.
        val seq = MidiSequence(
            MidiFormat.TYPE_1, 480,
            listOf(
                MidiTrack(
                    "Part",
                    listOf(
                        MidiEvent.TrackName(0L, "Part"),
                        MidiEvent.NoteOn(0L, 0, 64, 90),
                        MidiEvent.NoteOff(480L, 0, 64),
                        MidiEvent.NoteOn(480L, 0, 67, 90),
                        MidiEvent.NoteOff(960L, 0, 67),
                    ),
                ),
            ),
        )
        val result = MidiImporter.import(MidiWriter.write(seq))
        val notes = result.composition.tracks.first().notes
        assertEquals(listOf(0L, 960L), notes.map { it.startTick })
        assertEquals(listOf(960, 960), notes.map { it.durationTicks })
    }

    @Test fun importTreatsZeroVelocityNoteOnAsNoteOff() {
        // Many files end notes with a note-on of velocity zero rather than a note-off.
        val seq = MidiSequence(
            MidiFormat.TYPE_1, Ticks.PPQ,
            listOf(
                MidiTrack(
                    "Part",
                    listOf(
                        MidiEvent.NoteOn(0L, 0, 64, 90),
                        MidiEvent.NoteOn(960L, 0, 64, 0),
                    ),
                ),
            ),
        )
        val notes = MidiImporter.import(MidiWriter.write(seq)).composition.tracks.first().notes
        assertEquals(1, notes.size)
        assertEquals(960, notes.first().durationTicks)
    }

    @Test fun importReportsStuckNotesRatherThanHanging() {
        val seq = MidiSequence(
            MidiFormat.TYPE_1, Ticks.PPQ,
            listOf(
                MidiTrack(
                    "Part",
                    listOf(
                        MidiEvent.NoteOn(0L, 0, 64, 90),
                        MidiEvent.NoteOff(960L, 0, 64),
                        MidiEvent.NoteOn(960L, 0, 67, 90),
                    ),
                ),
            ),
        )
        val result = MidiImporter.import(MidiWriter.write(seq))
        assertEquals(1, result.composition.tracks.first().notes.size)
        assertTrue(result.warnings.any { it.contains("no note-off") })
    }

    @Test fun importSplitsAFormatZeroFileByChannel() {
        val seq = MidiSequence(
            MidiFormat.TYPE_0, Ticks.PPQ,
            listOf(
                MidiTrack(
                    "All",
                    listOf(
                        MidiEvent.NoteOn(0L, 0, 64, 90), MidiEvent.NoteOff(480L, 0, 64),
                        MidiEvent.NoteOn(0L, 1, 67, 90), MidiEvent.NoteOff(480L, 1, 67),
                    ),
                ),
            ),
        )
        val result = MidiImporter.import(MidiWriter.write(seq))
        assertEquals(2, result.composition.tracks.size)
    }

    @Test fun importOutOfRangeNotesWarnsAndKeepsThePitch() {
        val seq = MidiSequence(
            MidiFormat.TYPE_1, Ticks.PPQ,
            listOf(
                MidiTrack(
                    "Low",
                    listOf(MidiEvent.NoteOn(0L, 0, 20, 90), MidiEvent.NoteOff(480L, 0, 20)),
                ),
            ),
        )
        val result = MidiImporter.import(MidiWriter.write(seq), Tunings.STANDARD_E)
        assertTrue(result.warnings.any { it.contains("outside the range") })
        assertEquals(20, result.composition.tracks.first().notes.first().pitch)
    }

    @Test fun importRejectsRubbishWithAClearMessage() {
        val bad = "This is not a MIDI file".toByteArray()
        val failure = runCatching { MidiImporter.import(bad) }.exceptionOrNull()
        assertTrue(failure is MidiParseException)
        assertTrue(failure!!.message!!.contains("Not a MIDI file"))
    }

    @Test fun importHandlesTruncatedFilesWithoutCrashing() {
        val good = MidiExporter.export(soloComposition())
        for (cut in listOf(4, 10, 20, 40, good.size / 2, good.size - 3)) {
            val failure = runCatching { MidiImporter.import(good.copyOfRange(0, cut)) }
            assertTrue(
                "truncating to $cut should fail cleanly",
                failure.isFailure || failure.getOrNull() != null,
            )
            failure.exceptionOrNull()?.let {
                assertTrue("unexpected exception type: $it", it is MidiParseException)
            }
        }
    }

    @Test fun readerUnderstandsRunningStatus() {
        // Hand-built track using running status for the second note-on.
        val body = byteArrayOf(
            0x00, 0x90.toByte(), 0x40, 0x64,         // note on E4
            0x60, 0x40, 0x00,                        // running status: note on E4 velocity 0
            0x00, 0xFF.toByte(), 0x2F, 0x00,         // end of track
        )
        val out = java.io.ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1))
        out.write(byteArrayOf((Ticks.PPQ shr 8).toByte(), (Ticks.PPQ and 0xFF).toByte()))
        out.write("MTrk".toByteArray())
        out.write(byteArrayOf(0, 0, 0, body.size.toByte()))
        out.write(body)
        val seq = MidiReader.read(out.toByteArray())
        val events = seq.tracks.first().events
        assertEquals(1, events.filterIsInstance<MidiEvent.NoteOn>().size)
        assertEquals(1, events.filterIsInstance<MidiEvent.NoteOff>().size)
        assertEquals(96L, events.filterIsInstance<MidiEvent.NoteOff>().first().tick)
    }

    @Test fun emptyCompositionStillExportsAValidFile() {
        val c = Composition.newGuitarProject()
        val back = MidiReader.read(MidiExporter.export(c))
        assertEquals(2, back.tracks.size)
        assertTrue(back.tracks[1].events.filterIsInstance<MidiEvent.NoteOn>().isEmpty())
    }

    @Test fun exportHandlesTenTracksWithoutChannelCollisionOnPercussion() {
        var c = Composition.newGuitarProject()
        c = HarmonyGenerator.generate(
            c.updateTrack(1L) { it.copy(notes = soloComposition().track(1L)!!.notes) },
            1L,
            (1..11).map { HarmonySpec(com.tabtap.music.HarmonyMode.CHROMATIC, semitones = it) },
        ).first
        val back = MidiReader.read(MidiExporter.export(c))
        val channels = back.tracks.drop(1)
            .mapNotNull { t -> t.events.filterIsInstance<MidiEvent.NoteOn>().firstOrNull()?.channel }
        // The General MIDI drum channel is never used for a pitched part.
        assertTrue("channel 9 must stay reserved", channels.none { it == 9 })
        assertEquals(channels.size, channels.distinct().size)
    }
}
