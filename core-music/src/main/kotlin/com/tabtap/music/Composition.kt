package com.tabtap.music

import kotlinx.serialization.Serializable
import kotlin.math.max

/** Playing techniques a note can carry. These survive round-trips through tab and MIDI. */
@Serializable
enum class Articulation(val displayName: String, val tabSymbol: String) {
    HAMMER_ON("Hammer-on", "h"),
    PULL_OFF("Pull-off", "p"),
    SLIDE_UP("Slide up", "/"),
    SLIDE_DOWN("Slide down", "\\"),
    SLIDE_LEGATO("Legato slide", "s"),
    BEND("Bend", "b"),
    BEND_RELEASE("Bend & release", "br"),
    PRE_BEND("Pre-bend", "pb"),
    VIBRATO("Vibrato", "~"),
    WIDE_VIBRATO("Wide vibrato", "~~"),
    PALM_MUTE("Palm mute", "PM"),
    HARMONIC("Natural harmonic", "<>"),
    PINCH_HARMONIC("Pinch harmonic", "ph"),
    TAP("Tapping", "t"),
    LET_RING("Let ring", "LR"),
    DEAD_NOTE("Dead note", "x"),
    STACCATO("Staccato", "."),
    ACCENT("Accent", ">"),
    GHOST_NOTE("Ghost note", "()"),
    TRILL("Trill", "tr");
}

/**
 * One note of a composition.
 *
 * A note always carries its sounding [pitch]. [string] and [fret] are the fretted
 * position chosen for it; they are advisory, always consistent with [pitch] for a
 * given tuning, and recomputed when the tuning changes. That split is what lets the
 * fretboard, tab, piano roll and score all show the same note: the pitch is the
 * truth, the position is a view of it.
 */
@Serializable
data class MusicNote(
    /** Stable identity, so edits in one view can address a note shown in another. */
    val id: Long,
    /** Sounding MIDI pitch. */
    val pitch: Int,
    /** Start position in ticks from the beginning of the composition. */
    val startTick: Long,
    /** Sounding length in ticks. Always at least 1. */
    val durationTicks: Int,
    /** MIDI velocity 1..127. */
    val velocity: Int = DEFAULT_VELOCITY,
    /** String index on its track's fretboard, lowest string == 0, or null when unset. */
    val string: Int? = null,
    /** Fret number, 0 == open, or null when unset. */
    val fret: Int? = null,
    val articulations: Set<Articulation> = emptySet(),
    /** Tied into the following note of the same pitch on the same track. */
    val tiedToNext: Boolean = false,
    /** Bend amount in semitones at the peak of the bend, when [Articulation.BEND] is set. */
    val bendSemitones: Float = 0f,
) {
    init {
        require(Pitch.isValid(pitch)) { "Note $id pitch $pitch out of MIDI range" }
        require(startTick >= 0) { "Note $id has negative start $startTick" }
        require(durationTicks >= 1) { "Note $id has non-positive duration $durationTicks" }
        require(velocity in 1..127) { "Note $id velocity $velocity out of range" }
    }

    val endTick: Long get() = startTick + durationTicks

    /** Written note value that best represents this note's duration. */
    val noteValue: NoteValue get() = NoteValue.forTicks(durationTicks)

    fun withArticulation(a: Articulation, on: Boolean = true): MusicNote =
        copy(articulations = if (on) articulations + a else articulations - a)

    fun has(a: Articulation): Boolean = a in articulations

    /** True when the two notes overlap in time. */
    fun overlaps(other: MusicNote): Boolean =
        startTick < other.endTick && other.startTick < endTick

    companion object {
        const val DEFAULT_VELOCITY = 90
    }
}

/** What a track is for. Drives default colours, MIDI programs and harmony routing. */
@Serializable
enum class TrackRole(val displayName: String) {
    LEAD("Lead"),
    HARMONY("Harmony"),
    CHORDS("Chords"),
    BASS("Bass"),
    RHYTHM("Rhythm");
}

/**
 * An instrument voice: a synthesis preset in the audio engine plus a General MIDI
 * program number so exported files sound right in other software.
 */
@Serializable
enum class InstrumentVoice(
    val displayName: String,
    /** General MIDI program number, 0-based. */
    val gmProgram: Int,
    val family: InstrumentFamily,
) {
    ELECTRIC_CLEAN("Electric Guitar (clean)", 27, InstrumentFamily.GUITAR),
    ELECTRIC_OVERDRIVE("Electric Guitar (overdriven)", 29, InstrumentFamily.GUITAR),
    ELECTRIC_DISTORTION("Electric Guitar (distorted)", 30, InstrumentFamily.GUITAR),
    ACOUSTIC_STEEL("Acoustic Guitar (steel)", 25, InstrumentFamily.GUITAR),
    ACOUSTIC_NYLON("Acoustic Guitar (nylon)", 24, InstrumentFamily.GUITAR),
    BASS_FINGER("Bass Guitar (finger)", 33, InstrumentFamily.BASS),
    BASS_PICK("Bass Guitar (pick)", 34, InstrumentFamily.BASS),
    PIANO("Acoustic Piano", 0, InstrumentFamily.GUITAR);

    companion object {
        fun defaultFor(family: InstrumentFamily): InstrumentVoice = when (family) {
            InstrumentFamily.GUITAR -> ELECTRIC_CLEAN
            InstrumentFamily.BASS -> BASS_FINGER
        }

        fun forFamily(family: InstrumentFamily): List<InstrumentVoice> =
            entries.filter { it.family == family || it == PIANO }
    }
}

/** The clef a track is engraved on. */
@Serializable
enum class ClefType(val displayName: String) {
    TREBLE("Treble"),
    /** Treble clef sounding an octave lower than written — standard guitar notation. */
    TREBLE_8VB("Treble 8vb"),
    BASS("Bass"),
    /** Bass clef sounding an octave lower — standard bass guitar notation. */
    BASS_8VB("Bass 8vb");

    /** Semitones the written pitch is transposed by relative to sounding pitch. */
    val writtenOctaveShift: Int get() = when (this) {
        TREBLE, BASS -> 0
        TREBLE_8VB, BASS_8VB -> 12
    }

    val isBassClef: Boolean get() = this == BASS || this == BASS_8VB

    companion object {
        fun defaultFor(family: InstrumentFamily): ClefType = when (family) {
            InstrumentFamily.GUITAR -> TREBLE_8VB
            InstrumentFamily.BASS -> BASS_8VB
        }
    }
}

/**
 * One track: an instrument, a tuning, mix settings and a time-ordered list of notes.
 *
 * Notes are kept sorted by (startTick, pitch) by every mutating helper, so playback,
 * engraving and export can all assume order.
 */
@Serializable
data class Track(
    val id: Long,
    val name: String,
    val role: TrackRole,
    val voice: InstrumentVoice,
    val tuning: Tuning,
    val fretCount: Int = 24,
    val notes: List<MusicNote> = emptyList(),
    /** Linear gain 0..1. */
    val volume: Float = 0.8f,
    /** Stereo position -1 (left) .. +1 (right). */
    val pan: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val clef: ClefType = ClefType.defaultFor(tuning.family),
    /** Index into the project's colour slots, for consistent colouring across views. */
    val colorSlot: Int = 0,
    /** Track this one was generated from, when it is a generated harmony. */
    val derivedFromTrackId: Long? = null,
    /** Harmony settings used to generate this track, for regeneration. */
    val harmonySpec: HarmonySpec? = null,
) {
    val fretboard: Fretboard get() = Fretboard(tuning, fretCount)

    /** Last tick occupied by any note on this track. */
    val endTick: Long get() = notes.maxOfOrNull { it.endTick } ?: 0L

    val isEmpty: Boolean get() = notes.isEmpty()

    /** Notes sorted canonically: by start, then by pitch low to high. */
    fun sorted(): Track =
        if (isSorted()) this else copy(notes = notes.sortedWith(NOTE_ORDER))

    private fun isSorted(): Boolean {
        for (i in 1 until notes.size) {
            if (NOTE_ORDER.compare(notes[i - 1], notes[i]) > 0) return false
        }
        return true
    }

    fun withNotes(newNotes: List<MusicNote>): Track =
        copy(notes = newNotes.sortedWith(NOTE_ORDER))

    fun addNote(note: MusicNote): Track = withNotes(notes + note)

    fun removeNote(noteId: Long): Track = copy(notes = notes.filterNot { it.id == noteId })

    fun removeNotes(ids: Set<Long>): Track = copy(notes = notes.filterNot { it.id in ids })

    fun updateNote(noteId: Long, transform: (MusicNote) -> MusicNote): Track {
        var changed = false
        val updated = notes.map { if (it.id == noteId) { changed = true; transform(it) } else it }
        return if (changed) withNotes(updated) else this
    }

    fun note(noteId: Long): MusicNote? = notes.firstOrNull { it.id == noteId }

    /** Notes whose sounding span intersects [from, to). */
    fun notesInRange(from: Long, to: Long): List<MusicNote> =
        notes.filter { it.startTick < to && it.endTick > from }

    /**
     * Recompute string/fret for every note against the current tuning, so a tuning
     * change keeps the pitches and only moves the fingerings. Notes that no longer fit
     * on the board keep their pitch and lose their position, and are reported by
     * [unplayableNotes].
     */
    fun refretted(): Track {
        val board = fretboard
        var anchor = Fretboard.DEFAULT_ANCHOR_FRET
        val updated = notes.map { n ->
            val pos = board.bestPositionFor(n.pitch, anchor, n.string)
            if (pos != null) {
                anchor = pos.fret
                n.copy(string = pos.string, fret = pos.fret)
            } else {
                n.copy(string = null, fret = null)
            }
        }
        return copy(notes = updated)
    }

    /** Notes that cannot be played on this track's current fretboard. */
    fun unplayableNotes(): List<MusicNote> {
        val board = fretboard
        return notes.filterNot { board.canPlay(it.pitch) }
    }

    companion object {
        val NOTE_ORDER: Comparator<MusicNote> =
            compareBy<MusicNote> { it.startTick }.thenBy { it.pitch }.thenBy { it.id }
    }
}

/** A tempo change at a tick position. */
@Serializable
data class TempoMarking(val startTick: Long, val bpm: Double) {
    init {
        require(startTick >= 0) { "Tempo marking at negative tick" }
        require(bpm in MIN_BPM..MAX_BPM) { "BPM $bpm out of range" }
    }

    companion object {
        const val MIN_BPM = 20.0
        const val MAX_BPM = 400.0
    }
}

/** A time-signature change at a bar index. */
@Serializable
data class TimeSignatureMarking(val barIndex: Int, val timeSignature: TimeSignature) {
    init { require(barIndex >= 0) { "Time signature at negative bar" } }
}

/**
 * The tempo map. A single initial tempo is the common case; additional markings allow
 * tempo changes, and all tick/second conversions go through this class so that playback,
 * the metronome and MIDI export agree exactly.
 */
@Serializable
data class TempoMap(val markings: List<TempoMarking> = listOf(TempoMarking(0, 120.0))) {

    init { require(markings.isNotEmpty()) { "Tempo map must have at least one marking" } }

    private val sorted: List<TempoMarking> get() = markings.sortedBy { it.startTick }

    val initialBpm: Double get() = sorted.first().bpm

    /** Tempo in effect at [tick]. */
    fun bpmAt(tick: Long): Double =
        sorted.lastOrNull { it.startTick <= tick }?.bpm ?: initialBpm

    /** Elapsed seconds from tick 0 to [tick], integrating across tempo changes. */
    fun secondsAt(tick: Long): Double {
        var seconds = 0.0
        val list = sorted
        for (i in list.indices) {
            val segStart = list[i].startTick
            if (segStart >= tick) break
            val segEnd = if (i + 1 < list.size) minOf(list[i + 1].startTick, tick) else tick
            if (segEnd > segStart) {
                seconds += Ticks.toSeconds(segEnd - segStart, list[i].bpm)
            }
        }
        return seconds
    }

    /** Inverse of [secondsAt]. */
    fun tickAtSeconds(seconds: Double): Long {
        var acc = 0.0
        val list = sorted
        for (i in list.indices) {
            val segStart = list[i].startTick
            val segEnd = if (i + 1 < list.size) list[i + 1].startTick else Long.MAX_VALUE / 4
            val segSeconds = if (segEnd == Long.MAX_VALUE / 4) {
                Double.MAX_VALUE
            } else {
                Ticks.toSeconds(segEnd - segStart, list[i].bpm)
            }
            if (acc + segSeconds >= seconds) {
                return segStart + Ticks.fromSeconds(seconds - acc, list[i].bpm)
            }
            acc += segSeconds
        }
        return 0L
    }

    fun withInitialBpm(bpm: Double): TempoMap {
        val rest = sorted.drop(1)
        return TempoMap(listOf(TempoMarking(0, bpm)) + rest)
    }

    companion object {
        fun of(bpm: Double) = TempoMap(listOf(TempoMarking(0, bpm)))
    }
}

/** A chord placed in a progression. */
@Serializable
data class ChordEvent(
    val id: Long,
    val chord: Chord,
    val startTick: Long,
    val durationTicks: Int,
    /** Chosen fretboard voicing, when one has been picked. */
    val voicing: ChordVoicing? = null,
    /** Strum/arpeggio pattern applied when rendering this chord to notes. */
    val strum: StrumPattern = StrumPattern.BLOCK,
) {
    init {
        require(startTick >= 0) { "Chord event at negative tick" }
        require(durationTicks >= 1) { "Chord event has non-positive duration" }
    }

    val endTick: Long get() = startTick + durationTicks
}

/** How a chord is articulated when rendered to notes. */
@Serializable
enum class StrumPattern(val displayName: String) {
    BLOCK("Block chord"),
    STRUM_DOWN("Strum down"),
    STRUM_UP("Strum up"),
    ARPEGGIO_UP("Arpeggio up"),
    ARPEGGIO_DOWN("Arpeggio down"),
    ARPEGGIO_UP_DOWN("Arpeggio up/down");
}

/**
 * A complete composition: global musical settings plus tracks and a chord progression.
 *
 * This is the single source of truth. Every view (fretboard, piano roll, tab, score)
 * reads and writes this structure, and every exporter serialises from it, so the views
 * cannot disagree about pitch or timing.
 */
@Serializable
data class Composition(
    val tempoMap: TempoMap = TempoMap.of(120.0),
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val timeSignatureChanges: List<TimeSignatureMarking> = emptyList(),
    val key: Key = Key.C_MAJOR,
    val tracks: List<Track> = emptyList(),
    val chordProgression: List<ChordEvent> = emptyList(),
    /** Id of the track the fretboard is currently writing into. */
    val activeTrackId: Long? = tracks.firstOrNull()?.id,
    /** Monotonic counter for note and track ids, so ids are never reused. */
    val nextId: Long = 1L,
) {
    val bpm: Double get() = tempoMap.initialBpm

    /** Last tick occupied by any track or chord. */
    val endTick: Long
        get() = max(
            tracks.maxOfOrNull { it.endTick } ?: 0L,
            chordProgression.maxOfOrNull { it.endTick } ?: 0L,
        )

    /** Total bars, rounded up, always at least one. */
    val barCount: Int
        get() = max(1, Math.ceil(endTick.toDouble() / timeSignature.barTicks).toInt())

    val activeTrack: Track? get() = tracks.firstOrNull { it.id == activeTrackId } ?: tracks.firstOrNull()

    val isEmpty: Boolean get() = tracks.all { it.isEmpty } && chordProgression.isEmpty()

    /** Tracks that should sound, honouring solo-overrides-mute semantics. */
    val audibleTracks: List<Track>
        get() {
            val soloed = tracks.filter { it.soloed }
            return if (soloed.isNotEmpty()) soloed else tracks.filterNot { it.muted }
        }

    fun track(id: Long): Track? = tracks.firstOrNull { it.id == id }

    fun timeSignatureAtBar(bar: Int): TimeSignature =
        timeSignatureChanges.filter { it.barIndex <= bar }.maxByOrNull { it.barIndex }?.timeSignature
            ?: timeSignature

    /** Allocate [count] fresh ids, returning them with the advanced composition. */
    fun allocateIds(count: Int): Pair<List<Long>, Composition> {
        val ids = (0 until count).map { nextId + it }
        return ids to copy(nextId = nextId + count)
    }

    fun allocateId(): Pair<Long, Composition> = nextId to copy(nextId = nextId + 1)

    fun withTrack(track: Track): Composition {
        val idx = tracks.indexOfFirst { it.id == track.id }
        return if (idx >= 0) {
            copy(tracks = tracks.toMutableList().also { it[idx] = track.sorted() })
        } else {
            copy(tracks = tracks + track.sorted(), activeTrackId = activeTrackId ?: track.id)
        }
    }

    fun updateTrack(id: Long, transform: (Track) -> Track): Composition {
        val idx = tracks.indexOfFirst { it.id == id }
        if (idx < 0) return this
        val updated = transform(tracks[idx]).sorted()
        return copy(tracks = tracks.toMutableList().also { it[idx] = updated })
    }

    fun removeTrack(id: Long): Composition {
        val remaining = tracks.filterNot { it.id == id }
            // Harmony tracks derived from a removed track lose their link but keep their notes.
            .map { if (it.derivedFromTrackId == id) it.copy(derivedFromTrackId = null) else it }
        return copy(
            tracks = remaining,
            activeTrackId = if (activeTrackId == id) remaining.firstOrNull()?.id else activeTrackId,
        )
    }

    fun withChordProgression(events: List<ChordEvent>): Composition =
        copy(chordProgression = events.sortedBy { it.startTick })

    /** Re-fret every track against its tuning. Pitches are never changed. */
    fun refretted(): Composition = copy(tracks = tracks.map { it.refretted() })

    /**
     * Validate structural invariants. Returns a list of human-readable problems; an
     * empty list means the composition is sound. Used before saving and after import.
     */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        val trackIds = HashSet<Long>()
        for (t in tracks) {
            if (!trackIds.add(t.id)) problems += "Duplicate track id ${t.id}"
            val noteIds = HashSet<Long>()
            for (n in t.notes) {
                if (!noteIds.add(n.id)) problems += "Duplicate note id ${n.id} on track '${t.name}'"
                if (n.string != null && n.string !in 0 until t.tuning.stringCount) {
                    problems += "Note ${n.id} on '${t.name}' references string ${n.string}"
                }
                if (n.fret != null && n.fret !in 0..t.fretCount) {
                    problems += "Note ${n.id} on '${t.name}' references fret ${n.fret}"
                }
                if (n.string != null && n.fret != null) {
                    val expected = t.tuning.openStrings[n.string.coerceIn(0, t.tuning.stringCount - 1)] + n.fret
                    if (expected != n.pitch) {
                        problems += "Note ${n.id} on '${t.name}': position sounds $expected but pitch is ${n.pitch}"
                    }
                }
            }
        }
        if (activeTrackId != null && tracks.none { it.id == activeTrackId }) {
            problems += "Active track $activeTrackId does not exist"
        }
        val maxId = (tracks.map { it.id } + tracks.flatMap { t -> t.notes.map { it.id } } +
            chordProgression.map { it.id }).maxOrNull() ?: 0L
        if (nextId <= maxId) problems += "Id counter $nextId is not above the highest used id $maxId"
        return problems
    }

    /**
     * Repair anything [validate] would complain about, preserving as much music as
     * possible. Used when loading a project written by an older version or after a
     * crash, so a damaged file still opens.
     */
    fun repaired(): Composition {
        var counter = max(
            nextId,
            (tracks.map { it.id } + tracks.flatMap { t -> t.notes.map { it.id } } +
                chordProgression.map { it.id }).maxOrNull()?.plus(1) ?: 1L,
        )
        val seenTrackIds = HashSet<Long>()
        val fixedTracks = tracks.map { t ->
            val tid = if (seenTrackIds.add(t.id)) t.id else counter++
            val seenNoteIds = HashSet<Long>()
            val fixedNotes = t.notes.mapNotNull { n ->
                if (!Pitch.isValid(n.pitch)) return@mapNotNull null
                var note = if (seenNoteIds.add(n.id)) n else n.copy(id = counter++)
                val stringOk = note.string != null && note.string in 0 until t.tuning.stringCount
                val fretOk = note.fret != null && note.fret in 0..t.fretCount
                val consistent = stringOk && fretOk &&
                    t.tuning.openStrings[note.string!!] + note.fret!! == note.pitch
                if (!consistent) note = note.copy(string = null, fret = null)
                note.copy(
                    durationTicks = note.durationTicks.coerceAtLeast(1),
                    velocity = note.velocity.coerceIn(1, 127),
                    startTick = note.startTick.coerceAtLeast(0),
                )
            }
            t.copy(id = tid, notes = fixedNotes.sortedWith(Track.NOTE_ORDER)).refretted()
        }
        return copy(
            tracks = fixedTracks,
            activeTrackId = activeTrackId?.takeIf { id -> fixedTracks.any { it.id == id } }
                ?: fixedTracks.firstOrNull()?.id,
            nextId = counter,
        )
    }

    companion object {
        /** A sensible starting composition: one empty lead guitar track in standard tuning. */
        fun newGuitarProject(
            tuning: Tuning = Tunings.STANDARD_E,
            bpm: Double = 120.0,
            key: Key = Key.C_MAJOR,
            timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
        ): Composition {
            val track = Track(
                id = 1L,
                name = "Lead",
                role = TrackRole.LEAD,
                voice = InstrumentVoice.defaultFor(tuning.family),
                tuning = tuning,
                clef = ClefType.defaultFor(tuning.family),
                colorSlot = 0,
            )
            return Composition(
                tempoMap = TempoMap.of(bpm),
                timeSignature = timeSignature,
                key = key,
                tracks = listOf(track),
                activeTrackId = 1L,
                nextId = 2L,
            )
        }

        fun newBassProject(
            tuning: Tuning = Tunings.BASS_STANDARD,
            bpm: Double = 120.0,
            key: Key = Key.C_MAJOR,
        ): Composition = newGuitarProject(tuning, bpm, key).let { c ->
            c.updateTrack(1L) { it.copy(name = "Bass", role = TrackRole.BASS) }
        }
    }
}
