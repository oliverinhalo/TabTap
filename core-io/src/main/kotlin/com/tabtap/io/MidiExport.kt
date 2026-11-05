package com.tabtap.io

import com.tabtap.music.Articulation
import com.tabtap.music.ChordEvent
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.TrackRole
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import kotlin.math.roundToInt

/** Options for MIDI export. */
data class MidiExportOptions(
    val format: MidiFormat = MidiFormat.TYPE_1,
    /** Include a click track on channel 10. */
    val includeMetronome: Boolean = false,
    /** Render the chord progression as a sounding track. */
    val includeChordTrack: Boolean = true,
    /** Write pitch-bend events for bent notes. */
    val includeBends: Boolean = true,
    /** Only export these track ids; null exports every track. */
    val trackIds: Set<Long>? = null,
    /** Skip muted tracks. */
    val respectMute: Boolean = false,
)

/**
 * Converts a [Composition] into a Standard MIDI File.
 *
 * Timing is exact: the composition already stores integer ticks at 960 per quarter, and
 * that division is written straight into the file header, so no rounding happens
 * anywhere between the fretboard and the exported file.
 *
 * Each part gets its own MIDI channel and General MIDI program, so harmony voices stay
 * separate and open in a DAW as distinct instruments. Channel 9 (the General MIDI drum
 * channel) is reserved and skipped for pitched parts.
 */
object MidiExporter {

    /** Serialise [composition] to Standard MIDI File bytes. */
    fun export(composition: Composition, options: MidiExportOptions = MidiExportOptions()): ByteArray =
        MidiWriter.write(buildSequence(composition, options))

    /** Build the sequence without serialising it, so callers can inspect or test it. */
    fun buildSequence(
        composition: Composition,
        options: MidiExportOptions = MidiExportOptions(),
    ): MidiSequence {
        val tracks = ArrayList<MidiTrack>()

        // A conductor track carries tempo, metre and key, as Format 1 expects.
        tracks += conductorTrack(composition)

        var channel = 0
        fun nextChannel(): Int {
            // Channel 9 is the percussion channel in General MIDI.
            if (channel == 9) channel = 10
            val c = channel
            channel = (channel + 1) % 16
            return c
        }

        val selected = composition.tracks
            .filter { options.trackIds == null || it.id in options.trackIds }
            .filter { !options.respectMute || !it.muted }

        for (track in selected) {
            tracks += instrumentTrack(track, nextChannel(), options)
        }

        if (options.includeChordTrack && composition.chordProgression.isNotEmpty()) {
            tracks += chordTrack(composition, nextChannel())
        }

        if (options.includeMetronome) {
            tracks += metronomeTrack(composition)
        }

        return MidiSequence(options.format, Ticks.PPQ, tracks)
    }

    private fun conductorTrack(composition: Composition): MidiTrack {
        val events = ArrayList<MidiEvent>()
        events += MidiEvent.TrackName(0L, "Conductor")
        for (marking in composition.tempoMap.markings.sortedBy { it.startTick }) {
            events += MidiEvent.Tempo(marking.startTick, marking.bpm)
        }
        events += MidiEvent.TimeSignatureEvent(
            0L, composition.timeSignature.beats, composition.timeSignature.beatValue,
        )
        for (change in composition.timeSignatureChanges.sortedBy { it.barIndex }) {
            events += MidiEvent.TimeSignatureEvent(
                change.barIndex.toLong() * composition.timeSignature.barTicks,
                change.timeSignature.beats,
                change.timeSignature.beatValue,
            )
        }
        events += MidiEvent.KeySignatureEvent(
            0L,
            composition.key.sharpsInSignature,
            composition.key.scale.id.contains("minor") || composition.key.scale.id == "blues",
        )
        events += MidiEvent.Text(0L, "Created with TabTap")
        return MidiTrack("Conductor", events)
    }

    private fun instrumentTrack(track: Track, channel: Int, options: MidiExportOptions): MidiTrack {
        val events = ArrayList<MidiEvent>(track.notes.size * 2 + 8)
        events += MidiEvent.TrackName(0L, track.name)
        events += MidiEvent.InstrumentName(0L, track.voice.displayName)
        events += MidiEvent.ProgramChange(0L, channel, track.voice.gmProgram)
        // Volume and pan as channel controllers, so the mix survives the export.
        events += MidiEvent.ControlChange(0L, channel, 7, (track.volume * 127).roundToInt().coerceIn(0, 127))
        events += MidiEvent.ControlChange(
            0L, channel, 10, (((track.pan + 1f) / 2f) * 127).roundToInt().coerceIn(0, 127),
        )
        // Record the tuning as text so the file documents the instrument it came from.
        events += MidiEvent.Text(0L, "Tuning: " + track.tuning.stringNames.joinToString(" "))

        for (note in track.notes) {
            val velocity = velocityFor(note)
            val duration = soundingDuration(note)
            events += MidiEvent.NoteOn(note.startTick, channel, note.pitch, velocity)
            events += MidiEvent.NoteOff(note.startTick + duration, channel, note.pitch)

            if (options.includeBends && note.has(Articulation.BEND) && note.bendSemitones > 0f) {
                events += bendEvents(note, channel)
            }
        }
        return MidiTrack(track.name, events)
    }

    /**
     * Velocity after articulation. Accents are louder, ghost notes and dead notes much
     * quieter, so an exported file carries the same dynamics the app plays back.
     */
    private fun velocityFor(note: MusicNote): Int {
        var v = note.velocity.toDouble()
        if (note.has(Articulation.ACCENT)) v *= 1.25
        if (note.has(Articulation.GHOST_NOTE)) v *= 0.55
        if (note.has(Articulation.DEAD_NOTE)) v *= 0.6
        if (note.has(Articulation.PALM_MUTE)) v *= 0.85
        return v.roundToInt().coerceIn(1, 127)
    }

    /**
     * Sounding length after articulation. Staccato and dead notes are shortened, palm
     * mutes damped, and let-ring notes left at full length.
     */
    private fun soundingDuration(note: MusicNote): Int {
        var d = note.durationTicks.toDouble()
        if (note.has(Articulation.STACCATO)) d *= 0.5
        if (note.has(Articulation.DEAD_NOTE)) d = minOf(d, Ticks.SIXTEENTH.toDouble())
        if (note.has(Articulation.PALM_MUTE)) d *= 0.7
        return d.roundToInt().coerceAtLeast(1)
    }

    /**
     * A bend as a ramp of pitch-bend messages followed by a return to centre, assuming
     * the General MIDI default bend range of two semitones.
     */
    private fun bendEvents(note: MusicNote, channel: Int): List<MidiEvent> {
        val steps = 8
        val peak = ((note.bendSemitones / 2f).coerceIn(-1f, 1f) * 8191).toInt()
        val out = ArrayList<MidiEvent>(steps + 2)
        // The bend rises over the first half of the note.
        val rampTicks = note.durationTicks / 2
        for (i in 0..steps) {
            val t = note.startTick + (rampTicks.toLong() * i / steps)
            out += MidiEvent.PitchBend(t, channel, peak * i / steps)
        }
        if (note.has(Articulation.BEND_RELEASE)) {
            for (i in 0..steps) {
                val t = note.startTick + rampTicks + (rampTicks.toLong() * i / steps)
                out += MidiEvent.PitchBend(t, channel, peak - peak * i / steps)
            }
        } else {
            out += MidiEvent.PitchBend(note.startTick + note.durationTicks, channel, 0)
        }
        return out
    }

    private fun chordTrack(composition: Composition, channel: Int): MidiTrack {
        val events = ArrayList<MidiEvent>()
        events += MidiEvent.TrackName(0L, "Chords")
        events += MidiEvent.ProgramChange(0L, channel, InstrumentVoice.ACOUSTIC_STEEL.gmProgram)
        val board = composition.activeTrack?.fretboard ?: Fretboard(Tunings.STANDARD_E, 24)
        for (event in composition.chordProgression) {
            for (note in ChordRenderer.render(event, board)) {
                events += MidiEvent.NoteOn(note.startTick, channel, note.pitch, note.velocity)
                events += MidiEvent.NoteOff(note.startTick + note.durationTicks, channel, note.pitch)
            }
            events += MidiEvent.Marker(event.startTick, event.chord.symbol)
        }
        return MidiTrack("Chords", events)
    }

    private fun metronomeTrack(composition: Composition): MidiTrack {
        val events = ArrayList<MidiEvent>()
        events += MidiEvent.TrackName(0L, "Metronome")
        val ts = composition.timeSignature
        val end = composition.endTick.coerceAtLeast(ts.barTicks.toLong())
        var tick = 0L
        var pulse = 0
        while (tick < end) {
            val accented = pulse % ts.pulsesPerBar == 0
            // General MIDI percussion: 34 is a metronome click, 33 a metronome bell.
            val pitch = if (accented) 34 else 33
            events += MidiEvent.NoteOn(tick, 9, pitch, if (accented) 110 else 80)
            events += MidiEvent.NoteOff(tick + 60, 9, pitch)
            tick += ts.pulseTicks
            pulse++
        }
        return MidiTrack("Metronome", events)
    }
}

/**
 * Renders a chord event into sounding notes, applying its strum or arpeggio pattern.
 * Shared by MIDI export and audio playback so a progression sounds the same in both.
 */
object ChordRenderer {

    /** Tick offset between adjacent strings of a strum. */
    const val STRUM_SPREAD = 25

    fun render(event: ChordEvent, board: Fretboard, startId: Long = 1L): List<MusicNote> {
        val voicing = event.voicing
            ?: com.tabtap.music.ChordVoicer.bestVoicing(event.chord, board)
            ?: return emptyList()
        val positions = voicing.frets.withIndex()
            .filter { it.value != null }
            .map { it.index to it.value!! }
        if (positions.isEmpty()) return emptyList()

        var id = startId
        return when (event.strum) {
            com.tabtap.music.StrumPattern.BLOCK -> positions.map { (s, f) ->
                MusicNote(id++, board.pitchAt(s, f), event.startTick, event.durationTicks, 85, s, f)
            }
            com.tabtap.music.StrumPattern.STRUM_DOWN -> positions.mapIndexed { i, (s, f) ->
                MusicNote(
                    id++, board.pitchAt(s, f), event.startTick + i.toLong() * STRUM_SPREAD,
                    (event.durationTicks - i * STRUM_SPREAD).coerceAtLeast(1), 88, s, f,
                )
            }
            com.tabtap.music.StrumPattern.STRUM_UP -> positions.reversed().mapIndexed { i, (s, f) ->
                MusicNote(
                    id++, board.pitchAt(s, f), event.startTick + i.toLong() * STRUM_SPREAD,
                    (event.durationTicks - i * STRUM_SPREAD).coerceAtLeast(1), 82, s, f,
                )
            }
            com.tabtap.music.StrumPattern.ARPEGGIO_UP -> arpeggio(positions, event, board, id, false)
            com.tabtap.music.StrumPattern.ARPEGGIO_DOWN -> arpeggio(positions.reversed(), event, board, id, false)
            com.tabtap.music.StrumPattern.ARPEGGIO_UP_DOWN ->
                arpeggio(positions + positions.reversed().drop(1).dropLast(1), event, board, id, true)
        }
    }

    private fun arpeggio(
        positions: List<Pair<Int, Int>>,
        event: ChordEvent,
        board: Fretboard,
        startId: Long,
        @Suppress("UNUSED_PARAMETER") roundTrip: Boolean,
    ): List<MusicNote> {
        if (positions.isEmpty()) return emptyList()
        var id = startId
        val step = (event.durationTicks / positions.size).coerceAtLeast(1)
        return positions.mapIndexed { i, (s, f) ->
            MusicNote(
                id++, board.pitchAt(s, f), event.startTick + i.toLong() * step,
                step, 84, s, f,
            )
        }
    }
}

/** The outcome of importing a MIDI file. */
data class MidiImportResult(
    val composition: Composition,
    /** Human-readable notes about anything that could not be represented exactly. */
    val warnings: List<String>,
)

/**
 * Imports a Standard MIDI File into a [Composition].
 *
 * Notes are matched to a fretboard position on the target tuning; anything out of range
 * keeps its pitch but loses its fingering and is reported, so nothing is silently lost.
 */
object MidiImporter {

    fun import(
        bytes: ByteArray,
        tuning: Tuning = Tunings.STANDARD_E,
        fretCount: Int = 24,
    ): MidiImportResult {
        val sequence = MidiReader.read(bytes)
        val warnings = ArrayList<String>()
        val scale = Ticks.PPQ.toDouble() / sequence.ticksPerQuarter

        fun convert(tick: Long): Long = (tick * scale).roundToInt().toLong()

        var bpm = 120.0
        var timeSignature = TimeSignature.FOUR_FOUR
        var fifths = 0
        var minor = false
        for (t in sequence.tracks) {
            for (e in t.events) {
                when (e) {
                    is MidiEvent.Tempo -> if (e.tick == 0L) bpm = e.bpm
                    is MidiEvent.TimeSignatureEvent -> if (e.tick == 0L) {
                        timeSignature = runCatching { TimeSignature(e.numerator, e.denominator) }
                            .getOrElse {
                                warnings += "Unsupported time signature ${e.numerator}/${e.denominator}; using 4/4"
                                TimeSignature.FOUR_FOUR
                            }
                    }
                    is MidiEvent.KeySignatureEvent -> if (e.tick == 0L) {
                        fifths = e.fifths
                        minor = e.minor
                    }
                    else -> {}
                }
            }
        }

        // Group notes by (track, channel) so a Format 0 file still splits into parts.
        data class PartKey(val trackIndex: Int, val channel: Int)
        val parts = LinkedHashMap<PartKey, MutableList<MusicNote>>()
        val partNames = HashMap<PartKey, String>()
        val partPrograms = HashMap<PartKey, Int>()

        var nextId = 1L
        sequence.tracks.forEachIndexed { trackIndex, track ->
            // Open note-ons awaiting their note-off, keyed by channel and pitch.
            val open = HashMap<Pair<Int, Int>, MutableList<MidiEvent.NoteOn>>()
            for (event in track.events.sortedWith(MidiWriter.EVENT_ORDER)) {
                when (event) {
                    is MidiEvent.NoteOn ->
                        open.getOrPut(event.channel to event.pitch) { ArrayList() } += event
                    is MidiEvent.NoteOff -> {
                        val queue = open[event.channel to event.pitch] ?: continue
                        val on = queue.removeFirstOrNull() ?: continue
                        val key = PartKey(trackIndex, event.channel)
                        val duration = (convert(event.tick) - convert(on.tick)).toInt().coerceAtLeast(1)
                        parts.getOrPut(key) { ArrayList() } += MusicNote(
                            id = nextId++,
                            pitch = on.pitch,
                            startTick = convert(on.tick),
                            durationTicks = duration,
                            velocity = on.velocity.coerceIn(1, 127),
                        )
                    }
                    is MidiEvent.TrackName -> {
                        // Name every channel that this track turns out to use.
                        for (c in 0..15) partNames.putIfAbsent(PartKey(trackIndex, c), event.name)
                    }
                    is MidiEvent.ProgramChange ->
                        partPrograms.putIfAbsent(PartKey(trackIndex, event.channel), event.program)
                    else -> {}
                }
            }
            val stuck = open.values.sumOf { it.size }
            if (stuck > 0) {
                warnings += "Track ${trackIndex + 1}: $stuck note(s) had no note-off and were skipped"
            }
        }

        val board = Fretboard(tuning, fretCount)
        var trackId = 1L
        val outTracks = parts.entries
            .filter { it.value.isNotEmpty() }
            .mapIndexed { index, (key, notes) ->
                val voice = voiceForProgram(partPrograms[key], tuning.family)
                var anchor = Fretboard.DEFAULT_ANCHOR_FRET
                var unplayable = 0
                val fretted = notes.sortedBy { it.startTick }.map { n ->
                    val pos = board.bestPositionFor(n.pitch, anchor)
                    if (pos != null) {
                        anchor = pos.fret
                        n.copy(string = pos.string, fret = pos.fret)
                    } else {
                        unplayable++
                        n
                    }
                }
                if (unplayable > 0) {
                    warnings += "${partNames[key] ?: "Track ${key.trackIndex + 1}"}: " +
                        "$unplayable note(s) are outside the range of ${tuning.displayName}"
                }
                Track(
                    id = trackId++,
                    name = partNames[key]?.takeIf { it.isNotBlank() } ?: "Imported ${index + 1}",
                    role = if (index == 0) TrackRole.LEAD else TrackRole.HARMONY,
                    voice = voice,
                    tuning = tuning,
                    fretCount = fretCount,
                    notes = fretted,
                    colorSlot = index % 6,
                )
            }

        if (outTracks.isEmpty()) warnings += "The file contained no notes"

        val key = Key.of(
            keyNameFor(fifths, minor),
            if (minor) ScaleType.NATURAL_MINOR else ScaleType.MAJOR,
        ) ?: Key.C_MAJOR

        val composition = Composition(
            tempoMap = com.tabtap.music.TempoMap.of(bpm.coerceIn(20.0, 400.0)),
            timeSignature = timeSignature,
            key = key,
            tracks = outTracks.ifEmpty {
                listOf(Composition.newGuitarProject(tuning).tracks.first())
            },
            activeTrackId = 1L,
            nextId = nextId + outTracks.size + 1,
        ).repaired()

        return MidiImportResult(composition, warnings)
    }

    /** Map a General MIDI program onto the closest TabTap voice. */
    private fun voiceForProgram(program: Int?, family: InstrumentFamily): InstrumentVoice {
        if (program == null) return InstrumentVoice.defaultFor(family)
        return InstrumentVoice.entries.firstOrNull { it.gmProgram == program }
            ?: when (program) {
                in 0..7 -> InstrumentVoice.PIANO
                in 24..25 -> InstrumentVoice.ACOUSTIC_STEEL
                in 26..28 -> InstrumentVoice.ELECTRIC_CLEAN
                in 29..31 -> InstrumentVoice.ELECTRIC_DISTORTION
                in 32..39 -> InstrumentVoice.BASS_FINGER
                else -> InstrumentVoice.defaultFor(family)
            }
    }

    /** Tonic name for a MIDI key signature. */
    private fun keyNameFor(fifths: Int, minor: Boolean): String {
        val majors = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        val minors = listOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")
        val index = (fifths + 7).coerceIn(0, 14)
        return if (minor) minors[index] else majors[index]
    }
}
