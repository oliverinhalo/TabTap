package com.tabtap.app

import com.tabtap.app.data.CompositionSerializer
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.app.editing.EditStack
import com.tabtap.io.MidiEvent
import com.tabtap.io.MidiExportOptions
import com.tabtap.io.MidiExporter
import com.tabtap.io.MidiFormat
import com.tabtap.io.MidiReader
import com.tabtap.io.MusicXmlExporter
import com.tabtap.io.MusicXmlImporter
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.Key
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.StrumPattern
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.TrackRole
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import com.tabtap.music.notation.ScoreEngraver
import com.tabtap.music.notation.TabEngraver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end workflow this app exists to support, performed through the same
 * operations the user interface calls.
 *
 * This is the acceptance test: create a guitar project in a custom tuning, tap a solo
 * onto the fretboard, give it a rhythm, set the key to E minor, generate a diatonic
 * harmony above it, play the parts together and then the harmony alone, edit the
 * tablature and see the change in the notation, export a valid multi-track MIDI file,
 * and reopen the project with everything intact.
 */
class WorkflowTest {

    private fun n(s: String) = Pitch.parse(s)!!

    /** The custom tuning the player chose: Drop D with the top string raised to F. */
    private val customTuning: Tuning =
        Tunings.DROP_D.withString(5, n("F4"))

    @Test fun theCompleteWorkflow() {
        // ---------------------------------------------------------------- 1 & 2
        // Create a guitar project with a custom tuning.
        var composition = Composition.newGuitarProject(
            tuning = customTuning,
            bpm = 132.0,
            key = Key.of("C", ScaleType.MAJOR)!!,
            timeSignature = TimeSignature.FOUR_FOUR,
        )
        val edits = EditStack(composition)
        val trackId = composition.activeTrackId!!

        assertEquals(6, composition.track(trackId)!!.tuning.stringCount)
        assertEquals(n("D2"), composition.track(trackId)!!.tuning.openStrings.first())
        assertEquals(n("F4"), composition.track(trackId)!!.tuning.openStrings.last())

        // ---------------------------------------------------------------- 3
        // Tap a sequence of notes on the fretboard. An E minor line on the D and G
        // strings: E, F#, G, A, B, A, G, E.
        val board = composition.track(trackId)!!.fretboard
        val taps = listOf(
            2 to 2,   // E3
            2 to 4,   // F#3
            2 to 5,   // G3
            3 to 2,   // A3
            3 to 4,   // B3
            3 to 2,   // A3 again — a repeated position must make a second note
            2 to 5,   // G3
            2 to 2,   // E3
        )
        for ((string, fret) in taps) {
            composition = CompositionEditor.appendNoteAtPosition(
                composition, trackId, string, fret, NoteValue.QUARTER,
            )
            edits.push(composition, "Add note")
        }
        val solo = composition.track(trackId)!!
        assertEquals("every tap should record a note", 8, solo.notes.size)
        assertEquals(
            listOf("E3", "F#3", "G3", "A3", "B3", "A3", "G3", "E3").map { n(it) },
            solo.notes.map { it.pitch },
        )
        // The repeated position really produced two independent notes.
        assertEquals(8, solo.notes.map { it.id }.distinct().size)
        // Every recorded note sounds exactly what its position sounds.
        for (note in solo.notes) {
            assertEquals(note.pitch, board.pitchAt(note.string!!, note.fret!!))
        }

        // ---------------------------------------------------------------- 4
        // Assign a rhythm: eighths, with the last note held.
        composition = CompositionEditor.applyTappedRhythm(
            composition, trackId,
            onsets = listOf(0L, 480L, 960L, 1440L, 1920L, 2400L, 2880L, 3360L),
            tailTicks = Ticks.QUARTER,
        )
        edits.push(composition, "Assign rhythm")
        val rhythmic = composition.track(trackId)!!
        assertEquals(
            listOf(0L, 480L, 960L, 1440L, 1920L, 2400L, 2880L, 3360L),
            rhythmic.notes.map { it.startTick },
        )
        assertTrue(rhythmic.notes.dropLast(1).all { it.durationTicks == 480 })
        // The pitches are untouched by the rhythm change.
        assertEquals(solo.notes.map { it.pitch }, rhythmic.notes.map { it.pitch })

        // ---------------------------------------------------------------- 5
        // Set the key to E minor. This must not move a single note.
        val beforeKeyChange = composition.track(trackId)!!.notes
        composition = CompositionEditor.setKey(composition, Key.of("E", ScaleType.NATURAL_MINOR)!!)
        edits.push(composition, "Set key")
        assertEquals(beforeKeyChange, composition.track(trackId)!!.notes)
        assertEquals("E min", composition.key.shortName)
        assertEquals(1, composition.key.sharpsInSignature)
        // The whole line is inside the key.
        assertTrue(composition.track(trackId)!!.notes.all { composition.key.contains(it.pitch) })

        // ---------------------------------------------------------------- 6
        // Generate a diatonic harmony a third above.
        val (harmonised, warnings) = CompositionEditor.generateHarmonies(
            composition, trackId, listOf(HarmonySpec.THIRD_ABOVE),
        )
        composition = harmonised
        edits.push(composition, "Generate harmony")
        assertTrue("harmony generation reported problems: $warnings", warnings.isEmpty())
        assertEquals(2, composition.tracks.size)

        val harmony = composition.tracks.first { it.role == TrackRole.HARMONY }
        assertEquals(trackId, harmony.derivedFromTrackId)
        // A third above E F# G A B A G E in E minor is G A B C D C B G.
        assertEquals(
            listOf("G3", "A3", "B3", "C4", "D4", "C4", "B3", "G3").map { n(it) },
            harmony.notes.map { it.pitch },
        )
        // Which is a mixture of minor and major thirds — not a fixed transposition.
        val intervals = harmony.notes.indices.map {
            harmony.notes[it].pitch - rhythmic.notes[it].pitch
        }
        assertEquals(listOf(3, 3, 4, 3, 3, 3, 4, 3), intervals)
        assertTrue("the harmony collapsed to a constant interval", intervals.distinct().size > 1)
        // The rhythm is preserved note for note.
        assertEquals(rhythmic.notes.map { it.startTick }, harmony.notes.map { it.startTick })
        assertEquals(rhythmic.notes.map { it.durationTicks }, harmony.notes.map { it.durationTicks })
        // And every harmony note is playable on the custom tuning.
        val harmonyBoard = harmony.fretboard
        for (note in harmony.notes) {
            assertNotNull("harmony note lost its fingering", note.fret)
            assertEquals(note.pitch, harmonyBoard.pitchAt(note.string!!, note.fret!!))
        }

        // ---------------------------------------------------------------- 7
        // Play the original and the harmony together: both tracks are audible, and the
        // scheduler emits both parts against one clock.
        assertEquals(2, composition.audibleTracks.size)
        val together = com.tabtap.app.audio.Sequencer.build(composition, sampleRate = 48_000)
        val notesOn = together.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
        assertEquals(16, notesOn.size)
        assertEquals(2, notesOn.map { it.busIndex }.distinct().size)
        // Simultaneous notes are scheduled at the same frame, so the parts line up.
        val framesByBus = notesOn.groupBy { it.busIndex }.values.map { list -> list.map { it.frame } }
        assertEquals(framesByBus[0], framesByBus[1])
        // Every note is matched by a note-off, so nothing can stick on.
        val offs = together.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOff>()
        assertEquals(notesOn.map { it.key }.sorted(), offs.map { it.key }.sorted())

        // ---------------------------------------------------------------- 8
        // Mute the original and play the harmony alone.
        composition = CompositionEditor.setTrackMuted(composition, trackId, true)
        edits.push(composition, "Mute original")
        assertEquals(listOf(harmony.id), composition.audibleTracks.map { it.id })
        // Soloing the harmony gives the same result without touching the mute states.
        var soloed = CompositionEditor.setTrackMuted(composition, trackId, false)
        soloed = CompositionEditor.setTrackSoloed(soloed, harmony.id, true)
        assertEquals(listOf(harmony.id), soloed.audibleTracks.map { it.id })
        composition = CompositionEditor.setTrackMuted(composition, trackId, false)

        // ---------------------------------------------------------------- 9
        // Edit the tablature, and see the change in the notation.
        val tabBefore = TabEngraver.engrave(
            composition.track(trackId)!!, composition.timeSignature,
            minMeasures = composition.barCount,
        )
        val firstCell = tabBefore.systems.first().measures.first().stacks.first().cells.first()
        assertEquals("the tab shows the fret that was tapped", 2, firstCell.fret)

        // Raise that note by two frets, as a tap in the tab editor would.
        composition = CompositionEditor.transposeNote(composition, trackId, firstCell.noteId, 2)
        edits.push(composition, "Edit tab")

        val tabAfter = TabEngraver.engrave(
            composition.track(trackId)!!, composition.timeSignature,
            minMeasures = composition.barCount,
        )
        val editedCell = tabAfter.systems.first().measures.first().stacks.first().cells.first()
        assertEquals(n("F#3"), composition.track(trackId)!!.note(firstCell.noteId)!!.pitch)
        assertEquals(4, editedCell.fret)

        // ---------------------------------------------------------------- 10
        // The same edit is visible in the standard notation, because both are views of
        // the one composition.
        val score = ScoreEngraver.engrave(
            track = composition.track(trackId)!!,
            key = composition.key,
            timeSignature = composition.timeSignature,
            tempoMap = composition.tempoMap,
            minMeasures = composition.barCount,
        )
        val engraved = score.locate(firstCell.noteId)
        assertNotNull("the edited note is missing from the score", engraved)
        val head = engraved!!.second.noteheads.first { it.noteId == firstCell.noteId }
        assertEquals(n("F#3"), head.soundingPitch)
        // F# is in E minor, so the key signature carries it and no accidental is printed.
        assertEquals(com.tabtap.music.NoteLetter.F, head.writtenPitch.letter)
        assertEquals(1, head.writtenPitch.alter)
        assertEquals(null, head.accidental)
        assertEquals(1, score.systems.first().keySignatureFifths)

        // Undo puts it back, and the score follows.
        val undone = edits.undo()!!
        assertEquals(n("E3"), undone.track(trackId)!!.note(firstCell.noteId)!!.pitch)
        edits.redo()

        // ---------------------------------------------------------------- Chords
        // Also create a chord progression, play it back and export it.
        composition = CompositionEditor.appendChord(
            composition, Chord.of("E", ChordType.MINOR)!!, TimeSignature.FOUR_FOUR.barTicks,
            StrumPattern.STRUM_DOWN,
        )
        composition = CompositionEditor.appendChord(
            composition, Chord.of("C", ChordType.MAJOR)!!, TimeSignature.FOUR_FOUR.barTicks,
            StrumPattern.STRUM_DOWN,
        )
        composition = CompositionEditor.appendChord(
            composition, Chord.of("G", ChordType.MAJOR)!!, TimeSignature.FOUR_FOUR.barTicks,
            StrumPattern.STRUM_DOWN,
        )
        edits.push(composition, "Add chords")
        assertEquals(3, composition.chordProgression.size)
        // Every chord found a playable voicing on the custom tuning.
        for (event in composition.chordProgression) {
            assertNotNull("${event.chord.symbol} has no voicing", event.voicing)
            val pitches = event.voicing!!.pitches(composition.track(trackId)!!.fretboard)
            assertTrue(pitches.size >= 3)
            assertTrue(pitches.all { Pitch.pitchClass(it) in event.chord.pitchClasses })
        }
        // And the progression schedules as sounding notes.
        val withChords = com.tabtap.app.audio.Sequencer.build(composition, 48_000)
        val chordBusIndex = composition.tracks.size
        assertTrue(
            "the chord progression did not schedule",
            withChords.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
                .any { it.busIndex == chordBusIndex },
        )

        // ---------------------------------------------------------------- 11
        // Export a valid multi-track MIDI file.
        val midi = MidiExporter.export(
            composition,
            MidiExportOptions(format = MidiFormat.TYPE_1, includeChordTrack = true),
        )
        assertEquals("MThd", String(midi, 0, 4, Charsets.US_ASCII))

        val reread = MidiReader.read(midi)
        assertEquals(MidiFormat.TYPE_1, reread.format)
        assertEquals(Ticks.PPQ, reread.ticksPerQuarter)
        // Conductor, the solo, the harmony, and the chords.
        assertEquals(4, reread.tracks.size)
        assertEquals("Conductor", reread.tracks[0].name)

        val conductor = reread.tracks[0]
        assertEquals(132.0, conductor.events.filterIsInstance<MidiEvent.Tempo>().first().bpm, 0.1)
        val metre = conductor.events.filterIsInstance<MidiEvent.TimeSignatureEvent>().first()
        assertEquals(4, metre.numerator)
        assertEquals(4, metre.denominator)
        val signature = conductor.events.filterIsInstance<MidiEvent.KeySignatureEvent>().first()
        assertEquals(1, signature.fifths)
        assertTrue(signature.minor)

        // The solo and the harmony are separate tracks on separate channels.
        val soloTrack = reread.tracks.first { it.name == composition.track(trackId)!!.name }
        val harmonyTrack = reread.tracks.first { it.name == harmony.name }
        val soloOn = soloTrack.events.filterIsInstance<MidiEvent.NoteOn>()
        val harmonyOn = harmonyTrack.events.filterIsInstance<MidiEvent.NoteOn>()
        assertEquals(8, soloOn.size)
        assertEquals(8, harmonyOn.size)
        assertTrue(soloOn.first().channel != harmonyOn.first().channel)
        // Timing is exact.
        assertEquals(
            composition.track(trackId)!!.notes.map { it.startTick },
            soloOn.map { it.tick },
        )
        // And the pitches really are the harmonised ones.
        assertEquals(harmony.notes.map { it.pitch }, harmonyOn.map { it.pitch })
        // Every note is released.
        for (track in reread.tracks.drop(1)) {
            val ons = track.events.filterIsInstance<MidiEvent.NoteOn>()
            val track_offs = track.events.filterIsInstance<MidiEvent.NoteOff>()
            assertEquals("${track.name} has a stuck note", ons.size, track_offs.size)
        }

        // Type 0 is also valid, with the parts kept apart by channel.
        val type0 = MidiReader.read(
            MidiExporter.export(composition, MidiExportOptions(format = MidiFormat.TYPE_0)),
        )
        assertEquals(1, type0.tracks.size)
        assertTrue(
            type0.tracks.first().events.filterIsInstance<MidiEvent.NoteOn>()
                .map { it.channel }.distinct().size >= 3,
        )

        // ---------------------------------------------------------------- 12
        // Save and reopen the project: everything intact.
        val document = CompositionSerializer.encode(composition)
        val reopened = CompositionSerializer.decode(document)

        assertEquals(composition.bpm, reopened.bpm, 0.0)
        assertEquals(composition.timeSignature, reopened.timeSignature)
        assertEquals(composition.key, reopened.key)
        assertEquals(composition.activeTrackId, reopened.activeTrackId)
        assertEquals(composition.tracks.size, reopened.tracks.size)
        assertEquals(composition.chordProgression.size, reopened.chordProgression.size)

        for ((original, restored) in composition.tracks.zip(reopened.tracks)) {
            assertEquals(original.name, restored.name)
            assertEquals(original.role, restored.role)
            assertEquals(original.voice, restored.voice)
            assertEquals(original.tuning, restored.tuning)
            assertEquals(original.fretCount, restored.fretCount)
            assertEquals(original.volume, restored.volume, 0f)
            assertEquals(original.muted, restored.muted)
            assertEquals(original.derivedFromTrackId, restored.derivedFromTrackId)
            assertEquals(original.harmonySpec, restored.harmonySpec)
            assertEquals(original.notes, restored.notes)
        }
        assertEquals(composition.chordProgression, reopened.chordProgression)
        // The custom tuning survived, including the raised top string.
        assertEquals(n("F4"), reopened.track(trackId)!!.tuning.openStrings.last())
        assertEquals(emptyList<String>(), reopened.validate())

        // The harmony's own settings were saved, not just its notes, so the reopened
        // project can regenerate the voice rather than only replay it.
        val restoredHarmony = reopened.tracks.first { it.role == TrackRole.HARMONY }
        assertEquals(HarmonySpec.THIRD_ABOVE, restoredHarmony.harmonySpec)
        assertEquals(trackId, restoredHarmony.derivedFromTrackId)

        // Regenerating is deterministic: twice from the same state gives the same line.
        val (first, _) = CompositionEditor.regenerateHarmonies(reopened, trackId)
        val (second, _) = CompositionEditor.regenerateHarmonies(reopened, trackId)
        assertEquals(
            first.tracks.first { it.role == TrackRole.HARMONY }.notes.map { it.pitch },
            second.tracks.first { it.role == TrackRole.HARMONY }.notes.map { it.pitch },
        )
        // And it tracks the edit made in the tab: the first note of the solo is now F#3,
        // so the harmony's first note is the diatonic third above it, A3.
        assertEquals(
            n("A3"),
            first.tracks.first { it.role == TrackRole.HARMONY }.notes.first().pitch,
        )
        // Every regenerated note stays inside E minor and remains playable.
        val regeneratedTrack = first.tracks.first { it.role == TrackRole.HARMONY }
        for (note in regeneratedTrack.notes) {
            assertTrue(
                "${Pitch.fullName(note.pitch)} left E minor",
                first.key.contains(note.pitch),
            )
            assertNotNull(note.fret)
        }
        assertEquals(emptyList<String>(), first.validate())
    }

    @Test fun regeneratingWithChordsFollowsTheProgression() {
        // With a progression in place and "follow the chords" on, a harmony note that
        // would land outside the sounding chord is nudged onto a chord tone. This is the
        // documented behaviour of that setting, and it changes the result — so the test
        // pins it down rather than leaving it incidental.
        var composition = Composition.newGuitarProject(
            Tunings.STANDARD_E, 120.0, Key.of("E", ScaleType.NATURAL_MINOR)!!,
        )
        // A single B3, which a diatonic third above puts on D4 — not a tone of E minor.
        composition = CompositionEditor.addNoteByPitch(composition, 1L, n("B3"), 0L, Ticks.QUARTER)

        val withoutChords = CompositionEditor.generateHarmonies(
            composition, 1L, listOf(HarmonySpec.THIRD_ABOVE),
        ).first
        assertEquals(
            n("D4"),
            withoutChords.tracks.first { it.role == TrackRole.HARMONY }.notes.single().pitch,
        )

        val withChords = CompositionEditor.appendChord(
            composition, Chord.of("E", ChordType.MINOR)!!, TimeSignature.FOUR_FOUR.barTicks,
        ).let { CompositionEditor.generateHarmonies(it, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first }
        assertEquals(
            "the harmony should move to the nearest tone of E minor",
            n("E4"),
            withChords.tracks.first { it.role == TrackRole.HARMONY }.notes.single().pitch,
        )

        // Turning the setting off leaves the strict diatonic third.
        val strict = CompositionEditor.appendChord(
            composition, Chord.of("E", ChordType.MINOR)!!, TimeSignature.FOUR_FOUR.barTicks,
        ).let {
            CompositionEditor.generateHarmonies(
                it, 1L, listOf(HarmonySpec.THIRD_ABOVE.copy(followChords = false)),
            ).first
        }
        assertEquals(
            n("D4"),
            strict.tracks.first { it.role == TrackRole.HARMONY }.notes.single().pitch,
        )
    }

    @Test fun theWorkflowAlsoWorksOnBassAndInEveryPreset() {
        // The same path on every tuning TabTap offers: enter a line, assign a rhythm,
        // harmonise it, export it, reopen it.
        for (tuning in Tunings.presets) {
            var composition = Composition.newGuitarProject(
                tuning = tuning,
                bpm = 100.0,
                key = Key.of("A", ScaleType.NATURAL_MINOR)!!,
            )
            val trackId = composition.activeTrackId!!
            // Five notes up the lowest two strings.
            for (fret in listOf(0, 2, 3, 5, 7)) {
                composition = CompositionEditor.appendNoteAtPosition(composition, trackId, 0, fret)
            }
            composition = CompositionEditor.applyEvenRhythm(composition, trackId, NoteValue.EIGHTH)
            val (harmonised, _) = CompositionEditor.generateHarmonies(
                composition, trackId, listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.OCTAVE_BELOW),
            )
            composition = harmonised

            assertEquals("${tuning.id}: wrong track count", 3, composition.tracks.size)
            assertEquals("${tuning.id}: invalid", emptyList<String>(), composition.validate())

            // MIDI round trip.
            val back = MidiReader.read(MidiExporter.export(composition))
            assertEquals("${tuning.id}: wrong MIDI track count", 4, back.tracks.size)

            // MusicXML round trip preserves the pitches.
            val xml = MusicXmlExporter.export(composition, tuning.displayName)
            val imported = MusicXmlImporter.import(xml, tuning).composition
            assertEquals(
                "${tuning.id}: MusicXML lost pitches",
                composition.tracks.map { t -> t.notes.map { it.pitch } },
                imported.tracks.map { t -> t.notes.map { it.pitch } },
            )

            // Project round trip.
            val reopened = CompositionSerializer.decode(CompositionSerializer.encode(composition))
            assertEquals("${tuning.id}: project round trip", composition, reopened)
        }
    }

    @Test fun aDamagedDocumentStillOpens() {
        // Truncated, mangled and nonsense documents must not lose the user's project
        // outright — they open as best they can, or fail cleanly so the backup is used.
        val composition = Composition.newGuitarProject().let {
            CompositionEditor.appendNoteAtPosition(it, 1L, 0, 3)
        }
        val document = CompositionSerializer.encode(composition)

        // A valid document reads back exactly.
        assertEquals(composition, CompositionSerializer.decode(document))

        // Truncation is detected rather than producing a half-composition.
        for (fraction in listOf(0.2, 0.5, 0.8, 0.95)) {
            val truncated = document.substring(0, (document.length * fraction).toInt())
            assertEquals(
                "a truncated document should not decode",
                null,
                CompositionSerializer.decodeOrNull(truncated),
            )
        }
        assertEquals(null, CompositionSerializer.decodeOrNull("not json at all"))
        assertEquals(null, CompositionSerializer.decodeOrNull(""))
        assertEquals(null, CompositionSerializer.decodeOrNull(null))

        // A document from a newer version, with fields this build does not know, still
        // opens — which is what keeps a project readable across upgrades.
        val withExtra = document.replaceFirst("{", "{\"somethingNew\":123,")
        assertNotNull(CompositionSerializer.decodeOrNull(withExtra))
    }

    @Test fun aStructurallyInconsistentDocumentIsRepairedRatherThanRejected() {
        // A document whose fingerings disagree with its pitches — which an older build
        // or a bad import could produce — is repaired on load.
        var composition = Composition.newGuitarProject()
        composition = CompositionEditor.appendNoteAtPosition(composition, 1L, 0, 3)
        val broken = composition.updateTrack(1L) { track ->
            track.copy(notes = track.notes.map { it.copy(fret = 19) })
        }
        assertTrue("the test setup should be invalid", broken.validate().isNotEmpty())

        val repaired = CompositionSerializer.decode(CompositionSerializer.encode(broken))
        assertEquals(emptyList<String>(), repaired.validate())
        // The *pitch* is what was kept; only the bad fingering was recomputed.
        assertEquals(composition.track(1L)!!.notes.single().pitch, repaired.track(1L)!!.notes.single().pitch)
    }

    @Test fun switchingBetweenGuitarAndBassNeverLosesTheProject() {
        var composition = Composition.newGuitarProject(Tunings.STANDARD_E)
        for (fret in listOf(3, 5, 7, 8, 10)) {
            composition = CompositionEditor.appendNoteAtPosition(composition, 1L, 2, fret)
        }
        composition = CompositionEditor.generateHarmonies(
            composition, 1L, listOf(HarmonySpec.THIRD_ABOVE),
        ).first
        val pitches = composition.tracks.map { t -> t.notes.map { it.pitch } }
        val chordCount = composition.chordProgression.size

        // Back and forth several times.
        repeat(4) {
            composition = CompositionEditor.switchInstrumentFamily(composition, 1L, InstrumentFamily.BASS)
            composition = CompositionEditor.switchInstrumentFamily(composition, 1L, InstrumentFamily.GUITAR)
        }
        assertEquals(pitches, composition.tracks.map { t -> t.notes.map { it.pitch } })
        assertEquals(chordCount, composition.chordProgression.size)
        assertEquals(emptyList<String>(), composition.validate())
    }

    @Test fun theSchedulerAndTheMetronomeShareOneClock() {
        // The click and the notes must be positioned by the same conversion, or they
        // drift. Four bars at 90 BPM: the click is exactly one beat apart in frames.
        var composition = Composition.newGuitarProject(bpm = 90.0)
        for (beat in 0 until 16) {
            composition = CompositionEditor.addNoteAt(
                composition, 1L, 0, 0, beat * Ticks.QUARTER.toLong(), Ticks.QUARTER,
            )
        }
        val sampleRate = 48_000
        val schedule = com.tabtap.app.audio.Sequencer.build(
            composition, sampleRate, metronome = true,
        )
        val clicks = schedule.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.Click>()
        val notes = schedule.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
        assertEquals(16, notes.size)
        assertTrue(clicks.size >= 16)

        // A beat at 90 BPM is 2/3 of a second, which is 32000 frames.
        val framesPerBeat = 32_000L
        notes.forEachIndexed { index, note ->
            assertEquals("note $index is misplaced", index * framesPerBeat, note.frame)
        }
        // And every note lands on a click, with no accumulated drift at the end.
        val clickFrames = clicks.map { it.frame }.toSet()
        for (note in notes) {
            assertTrue("note at ${note.frame} has no click", note.frame in clickFrames)
        }
        // Accents land on bar lines.
        assertTrue(clicks.filter { it.accented }.all { it.frame % (framesPerBeat * 4) == 0L })
    }

    @Test fun playingFromTheMiddleReportsAbsoluteMusicalTime() {
        // Frame zero of a schedule is the start of playback, not of the piece. If the
        // conversions did not account for that, playing or looping from bar three would
        // report a playhead three bars behind where the music actually is.
        var composition = Composition.newGuitarProject(bpm = 120.0)
        for (beat in 0 until 32) {
            composition = CompositionEditor.addNoteAt(
                composition, 1L, 0, 0, beat * Ticks.QUARTER.toLong(), Ticks.QUARTER,
            )
        }
        val sampleRate = 48_000
        val fromTick = 3L * TimeSignature.FOUR_FOUR.barTicks     // bar four
        val schedule = com.tabtap.app.audio.Sequencer.build(
            composition, sampleRate, fromTick = fromTick,
        )

        // The first frame of playback is the tick we asked to start at.
        assertEquals(fromTick, schedule.tickAtFrame(0L))
        // And the conversions are inverses of each other.
        assertEquals(0L, schedule.frameAtTick(fromTick))
        for (bars in 0..4) {
            val tick = fromTick + bars * TimeSignature.FOUR_FOUR.barTicks
            val frame = schedule.frameAtTick(tick)
            assertEquals("round trip at bar $bars", tick, schedule.tickAtFrame(frame))
        }
        // A bar at 120 BPM is two seconds, which is 96000 frames.
        assertEquals(96_000L, schedule.frameAtTick(fromTick + TimeSignature.FOUR_FOUR.barTicks))

        // The first scheduled note is the one at the start tick, placed at frame zero.
        val firstNote = schedule.events
            .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>().first()
        assertEquals(0L, firstNote.frame)
        // And nothing before the start tick was scheduled.
        assertEquals(
            32 - 12,
            schedule.events.filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>().size,
        )
    }

    @Test fun loopingFromTheMiddleUsesTheRightFrames() {
        var composition = Composition.newGuitarProject(bpm = 120.0)
        for (beat in 0 until 16) {
            composition = CompositionEditor.addNoteAt(
                composition, 1L, 0, 0, beat * Ticks.QUARTER.toLong(), Ticks.QUARTER,
            )
        }
        val bar = TimeSignature.FOUR_FOUR.barTicks.toLong()
        val schedule = com.tabtap.app.audio.Sequencer.build(
            composition, 48_000, fromTick = bar, toTick = bar * 2,
        )
        // A loop over bar two starts at frame zero of this schedule and is one bar long.
        assertEquals(0L, schedule.frameAtTick(bar))
        assertEquals(96_000L, schedule.frameAtTick(bar * 2))
    }

    @Test fun countInDelaysTheMusicWithoutShiftingItRelativeToTheClick() {
        var composition = Composition.newGuitarProject(bpm = 120.0)
        composition = CompositionEditor.addNoteAt(composition, 1L, 0, 0, 0L, Ticks.QUARTER)
        val schedule = com.tabtap.app.audio.Sequencer.build(
            composition, 48_000, countInBars = 2, metronome = true,
        )
        // Two bars of 4/4 at 120 BPM is four seconds.
        assertEquals(4 * 48_000L, schedule.countInFrames)
        assertEquals(8, schedule.countInPulses)
        val firstNote = schedule.events
            .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>().first()
        assertEquals(schedule.countInFrames, firstNote.frame)
        // The tick at that frame is zero: the count-in does not shift musical time.
        assertEquals(0L, schedule.tickAtFrame(firstNote.frame))
        // And before the count-in ends, musical time has not started.
        assertEquals(0L, schedule.tickAtFrame(0L))
    }
}
