package com.tabtap.app.editing

import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ChordEvent
import com.tabtap.music.ChordVoicer
import com.tabtap.music.ChordVoicing
import com.tabtap.music.ClefType
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.GridFeel
import com.tabtap.music.GridResolution
import com.tabtap.music.HarmonyGenerator
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.Quantize
import com.tabtap.music.RhythmEngine
import com.tabtap.music.StrumPattern
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.TrackRole
import com.tabtap.music.Tuning
import kotlin.math.roundToInt

/**
 * Every edit the app can make to a composition, as pure functions.
 *
 * Keeping these out of the view model means they can be unit tested without Android, and
 * means the fretboard, the tab editor, the piano roll and the score all go through
 * exactly the same operations — which is why an edit made in one view is correctly
 * reflected in the others. There is no second code path.
 */
object CompositionEditor {

    // ------------------------------------------------------------------ note entry

    /**
     * Append a note at a fretboard position to the end of the active track.
     *
     * This is the primary entry gesture: tapping the fretboard adds notes in the order
     * tapped, each one a step after the last, so tapping the same position twice produces
     * two notes rather than one. Pitch is derived from the tuning, so the recorded note
     * is always exactly what the position sounds.
     */
    fun appendNoteAtPosition(
        composition: Composition,
        trackId: Long,
        string: Int,
        fret: Int,
        value: NoteValue = NoteValue.QUARTER,
        velocity: Int = MusicNote.DEFAULT_VELOCITY,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        val pitch = track.fretboard.pitchAtOrNull(string, fret) ?: return composition
        val (id, withId) = composition.allocateId()
        val startTick = nextFreeTick(track, value)
        return withId.updateTrack(trackId) {
            it.addNote(
                MusicNote(
                    id = id, pitch = pitch, startTick = startTick,
                    durationTicks = value.ticks, velocity = velocity,
                    string = string, fret = fret,
                ),
            )
        }
    }

    /**
     * Add a note at an explicit tick, used by the piano roll and tab editors and for
     * stacking chord tones at the same position.
     */
    fun addNoteAt(
        composition: Composition,
        trackId: Long,
        string: Int,
        fret: Int,
        startTick: Long,
        durationTicks: Int,
        velocity: Int = MusicNote.DEFAULT_VELOCITY,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        val pitch = track.fretboard.pitchAtOrNull(string, fret) ?: return composition
        val (id, withId) = composition.allocateId()
        return withId.updateTrack(trackId) {
            it.addNote(
                MusicNote(
                    id = id, pitch = pitch, startTick = startTick.coerceAtLeast(0L),
                    durationTicks = durationTicks.coerceAtLeast(1), velocity = velocity,
                    string = string, fret = fret,
                ),
            )
        }
    }

    /**
     * Add a note by pitch, letting the fretboard choose a position. Used by the piano
     * roll and the score editor, where the user picks a pitch rather than a fingering.
     */
    fun addNoteByPitch(
        composition: Composition,
        trackId: Long,
        pitch: Int,
        startTick: Long,
        durationTicks: Int,
        velocity: Int = MusicNote.DEFAULT_VELOCITY,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        if (!Pitch.isValid(pitch)) return composition
        val anchor = track.notes.lastOrNull { it.startTick <= startTick }?.fret
        val position = track.fretboard.bestPositionFor(pitch, anchor)
        val (id, withId) = composition.allocateId()
        return withId.updateTrack(trackId) {
            it.addNote(
                MusicNote(
                    id = id, pitch = pitch, startTick = startTick.coerceAtLeast(0L),
                    durationTicks = durationTicks.coerceAtLeast(1), velocity = velocity,
                    string = position?.string, fret = position?.fret,
                ),
            )
        }
    }

    /**
     * Stack a note onto the chord at the end of the track, instead of after it. This is
     * how a chord is entered from the fretboard: tap the first note, then stack the rest.
     */
    fun stackNoteAtPosition(
        composition: Composition,
        trackId: Long,
        string: Int,
        fret: Int,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        val last = track.notes.lastOrNull()
            ?: return appendNoteAtPosition(composition, trackId, string, fret)
        return addNoteAt(
            composition, trackId, string, fret,
            startTick = last.startTick, durationTicks = last.durationTicks,
        )
    }

    /** The tick a newly appended note should start at: right after whatever is there. */
    fun nextFreeTick(track: Track, value: NoteValue): Long =
        track.notes.maxOfOrNull { it.endTick } ?: 0L

    fun removeNotes(composition: Composition, trackId: Long, ids: Set<Long>): Composition =
        if (ids.isEmpty()) composition else composition.updateTrack(trackId) { it.removeNotes(ids) }

    /** Remove the most recently added note, for a quick correction while tapping. */
    fun removeLastNote(composition: Composition, trackId: Long): Composition {
        val track = composition.track(trackId) ?: return composition
        val last = track.notes.maxByOrNull { it.startTick } ?: return composition
        // Remove the whole stack if the last position is a chord.
        val stack = track.notes.filter { it.startTick == last.startTick }.map { it.id }.toSet()
        return composition.updateTrack(trackId) { it.removeNotes(stack) }
    }

    fun clearTrack(composition: Composition, trackId: Long): Composition =
        composition.updateTrack(trackId) { it.copy(notes = emptyList()) }

    // ------------------------------------------------------------------ note editing

    /** Change a note's fretted position, which also changes its pitch. */
    fun moveNoteToPosition(
        composition: Composition,
        trackId: Long,
        noteId: Long,
        string: Int,
        fret: Int,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        val pitch = track.fretboard.pitchAtOrNull(string, fret) ?: return composition
        return composition.updateTrack(trackId) { t ->
            t.updateNote(noteId) { it.copy(pitch = pitch, string = string, fret = fret) }
        }
    }

    /**
     * Transpose a note by semitones, keeping it on the fretboard.
     *
     * Used by the piano roll and the score, where the user drags a note up or down. The
     * fingering follows the pitch rather than the other way round.
     */
    fun transposeNote(
        composition: Composition,
        trackId: Long,
        noteId: Long,
        semitones: Int,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        val note = track.note(noteId) ?: return composition
        val pitch = note.pitch + semitones
        if (!Pitch.isValid(pitch)) return composition
        val position = track.fretboard.bestPositionFor(pitch, note.fret, note.string)
        return composition.updateTrack(trackId) { t ->
            t.updateNote(noteId) {
                it.copy(pitch = pitch, string = position?.string, fret = position?.fret)
            }
        }
    }

    fun setNoteStart(composition: Composition, trackId: Long, noteId: Long, startTick: Long): Composition =
        composition.updateTrack(trackId) { t ->
            t.updateNote(noteId) { it.copy(startTick = startTick.coerceAtLeast(0L)) }
        }

    fun setNoteDuration(composition: Composition, trackId: Long, noteId: Long, ticks: Int): Composition =
        composition.updateTrack(trackId) { t ->
            t.updateNote(noteId) { it.copy(durationTicks = ticks.coerceAtLeast(MIN_DURATION)) }
        }

    fun setNoteDurations(
        composition: Composition,
        trackId: Long,
        ids: Set<Long>,
        value: NoteValue,
    ): Composition {
        if (ids.isEmpty()) return composition
        return composition.updateTrack(trackId) { t ->
            t.withNotes(t.notes.map { if (it.id in ids) it.copy(durationTicks = value.ticks) else it })
        }
    }

    fun setNoteVelocity(composition: Composition, trackId: Long, ids: Set<Long>, velocity: Int): Composition {
        if (ids.isEmpty()) return composition
        val v = velocity.coerceIn(1, 127)
        return composition.updateTrack(trackId) { t ->
            t.withNotes(t.notes.map { if (it.id in ids) it.copy(velocity = v) else it })
        }
    }

    fun toggleArticulation(
        composition: Composition,
        trackId: Long,
        ids: Set<Long>,
        articulation: Articulation,
    ): Composition {
        if (ids.isEmpty()) return composition
        val track = composition.track(trackId) ?: return composition
        val selected = track.notes.filter { it.id in ids }
        // If every selected note already has it, the gesture removes it.
        val turnOn = selected.any { !it.has(articulation) }
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map {
                    if (it.id in ids) it.withArticulation(articulation, turnOn) else it
                },
            )
        }
    }

    fun setBend(composition: Composition, trackId: Long, ids: Set<Long>, semitones: Float): Composition {
        if (ids.isEmpty()) return composition
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map {
                    if (it.id in ids) {
                        it.copy(bendSemitones = semitones.coerceIn(0f, 4f))
                            .withArticulation(Articulation.BEND, semitones > 0f)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun setTie(composition: Composition, trackId: Long, ids: Set<Long>, tied: Boolean): Composition =
        composition.updateTrack(trackId) { t ->
            t.withNotes(t.notes.map { if (it.id in ids) it.copy(tiedToNext = tied) else it })
        }

    /** Nudge selected notes in time, for fine timing corrections. */
    fun nudgeNotes(composition: Composition, trackId: Long, ids: Set<Long>, deltaTicks: Long): Composition {
        if (ids.isEmpty()) return composition
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map {
                    if (it.id in ids) {
                        it.copy(startTick = (it.startTick + deltaTicks).coerceAtLeast(0L))
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Transpose a whole selection, keeping fingerings playable. */
    fun transposeNotes(
        composition: Composition,
        trackId: Long,
        ids: Set<Long>,
        semitones: Int,
    ): Composition {
        if (ids.isEmpty() || semitones == 0) return composition
        val track = composition.track(trackId) ?: return composition
        if (track.notes.any { it.id in ids && !Pitch.isValid(it.pitch + semitones) }) return composition
        val board = track.fretboard
        var anchor = Fretboard.DEFAULT_ANCHOR_FRET
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map { note ->
                    if (note.id !in ids) return@map note
                    val pitch = note.pitch + semitones
                    val position = board.bestPositionFor(pitch, anchor, note.string)
                    if (position != null) anchor = position.fret
                    note.copy(pitch = pitch, string = position?.string, fret = position?.fret)
                },
            )
        }
    }

    // ------------------------------------------------------------------ copy and paste

    /** Notes lifted out of a track, ready to paste. */
    data class Clipboard(val notes: List<MusicNote>, val sourceTrackId: Long) {
        val isEmpty: Boolean get() = notes.isEmpty()
        /** Length of the copied material. */
        val lengthTicks: Int
            get() = if (notes.isEmpty()) {
                0
            } else {
                (notes.maxOf { it.endTick } - notes.minOf { it.startTick }).toInt()
            }
    }

    fun copy(composition: Composition, trackId: Long, ids: Set<Long>): Clipboard {
        val track = composition.track(trackId) ?: return Clipboard(emptyList(), trackId)
        return Clipboard(track.notes.filter { it.id in ids }, trackId)
    }

    /**
     * Paste at [atTick], re-fretting onto the destination track's tuning so pasting
     * between a guitar and a bass track still produces playable positions.
     */
    fun paste(
        composition: Composition,
        trackId: Long,
        clipboard: Clipboard,
        atTick: Long,
    ): Composition {
        if (clipboard.isEmpty) return composition
        val track = composition.track(trackId) ?: return composition
        val origin = clipboard.notes.minOf { it.startTick }
        val (ids, withIds) = composition.allocateIds(clipboard.notes.size)
        val board = track.fretboard
        var anchor = Fretboard.DEFAULT_ANCHOR_FRET
        val pasted = clipboard.notes.sortedBy { it.startTick }.mapIndexedNotNull { i, note ->
            val position = board.bestPositionFor(note.pitch, anchor, note.string)
            if (position != null) anchor = position.fret
            note.copy(
                id = ids[i],
                startTick = atTick + (note.startTick - origin),
                string = position?.string,
                fret = position?.fret,
            )
        }
        return withIds.updateTrack(trackId) { it.withNotes(it.notes + pasted) }
    }

    /** Repeat the selection immediately after itself, the common "double this bar" gesture. */
    fun duplicateSelection(composition: Composition, trackId: Long, ids: Set<Long>): Composition {
        val clipboard = copy(composition, trackId, ids)
        if (clipboard.isEmpty) return composition
        val end = clipboard.notes.maxOf { it.endTick }
        return paste(composition, trackId, clipboard, end)
    }

    // ------------------------------------------------------------------ rhythm

    /**
     * Lay the track's notes onto an even grid of [value].
     *
     * This is the "enter the notes first, then assign a rhythm" path: the pitches and
     * their order are untouched, and only the timing is rewritten. Chords stay chords,
     * because a stack of simultaneous notes is treated as one step.
     */
    fun applyEvenRhythm(composition: Composition, trackId: Long, value: NoteValue): Composition {
        val track = composition.track(trackId) ?: return composition
        if (track.notes.isEmpty()) return composition
        val stacks = track.notes.groupBy { it.startTick }.toSortedMap()
        val notes = ArrayList<MusicNote>(track.notes.size)
        stacks.values.forEachIndexed { index, group ->
            val start = index.toLong() * value.ticks
            group.forEach { notes += it.copy(startTick = start, durationTicks = value.ticks) }
        }
        return composition.updateTrack(trackId) { it.withNotes(notes) }
    }

    /**
     * Apply a tapped rhythm to the notes already on the track.
     *
     * [onsets] are tick positions, as recorded by tapping against the metronome. The nth
     * stack of notes takes the nth onset; if there are more notes than taps the pattern
     * repeats, and if there are more taps than notes the extra taps are ignored. The
     * durations come from the gaps between onsets, so the result is legato and gapless.
     */
    fun applyTappedRhythm(
        composition: Composition,
        trackId: Long,
        onsets: List<Long>,
        tailTicks: Int = Ticks.QUARTER,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        if (track.notes.isEmpty() || onsets.isEmpty()) return composition
        val sorted = onsets.sorted()
        val durations = RhythmEngine.durationsFromOnsets(sorted, tailTicks)
        val stacks = track.notes.groupBy { it.startTick }.toSortedMap().values.toList()

        val notes = ArrayList<MusicNote>(track.notes.size)
        stacks.forEachIndexed { index, group ->
            // When the rhythm is shorter than the melody it repeats, shifted by its length.
            val cycle = index / sorted.size
            val within = index % sorted.size
            val cycleLength = sorted.last() + durations.last() - sorted.first()
            val start = sorted[within] + cycle * cycleLength
            group.forEach {
                notes += it.copy(startTick = start, durationTicks = durations[within])
            }
        }
        return composition.updateTrack(trackId) { it.withNotes(notes) }
    }

    /**
     * Record notes against a rhythm in one pass: pitches in the order given, timings
     * from the onsets. Used by the rhythm recorder when notes are tapped live.
     */
    fun replaceWithRhythmicNotes(
        composition: Composition,
        trackId: Long,
        positions: List<Pair<Int, Int>>,
        onsets: List<Long>,
        tailTicks: Int = Ticks.QUARTER,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        if (positions.isEmpty()) return composition
        val board = track.fretboard
        val sorted = onsets.sorted()
        val durations = RhythmEngine.durationsFromOnsets(sorted, tailTicks)
        val (ids, withIds) = composition.allocateIds(positions.size)
        val notes = positions.mapIndexedNotNull { i, (string, fret) ->
            val pitch = board.pitchAtOrNull(string, fret) ?: return@mapIndexedNotNull null
            val start = sorted.getOrNull(i) ?: (sorted.lastOrNull() ?: 0L) + i * Ticks.QUARTER
            MusicNote(
                id = ids[i], pitch = pitch, startTick = start,
                durationTicks = durations.getOrNull(i) ?: tailTicks,
                string = string, fret = fret,
            )
        }
        return withIds.updateTrack(trackId) { it.withNotes(notes) }
    }

    /** Quantise a track, or just the selection when [ids] is given. */
    fun quantize(
        composition: Composition,
        trackId: Long,
        quantize: Quantize,
        ids: Set<Long>? = null,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        if (track.notes.isEmpty()) return composition
        val ts = composition.timeSignature
        // Stacks are quantised as a unit, so a chord cannot be torn apart.
        val snapped = HashMap<Long, Long>()
        for (start in track.notes.map { it.startTick }.distinct()) {
            snapped[start] = RhythmEngine.snap(start, quantize, ts)
        }
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map { note ->
                    if (ids != null && note.id !in ids) return@map note
                    val start = snapped[note.startTick] ?: note.startTick
                    val duration = if (quantize.quantizeDurations) {
                        val grid = quantize.resolution.straightTicks
                        ((note.durationTicks + grid / 2) / grid * grid).coerceAtLeast(grid)
                    } else {
                        note.durationTicks
                    }
                    note.copy(startTick = start, durationTicks = duration)
                },
            )
        }
    }

    /** Apply a deterministic humanising jitter, so a quantised part breathes. */
    fun humanize(composition: Composition, trackId: Long, amountTicks: Int): Composition {
        val track = composition.track(trackId) ?: return composition
        if (track.notes.isEmpty() || amountTicks <= 0) return composition
        val starts = track.notes.map { it.startTick }.distinct().sorted()
        val jittered = RhythmEngine.humanize(starts, amountTicks, seed = trackId)
        val map = starts.zip(jittered).toMap()
        return composition.updateTrack(trackId) { t ->
            t.withNotes(t.notes.map { it.copy(startTick = map[it.startTick] ?: it.startTick) })
        }
    }

    /** Make every note run up to the next one, removing gaps. */
    fun makeLegato(composition: Composition, trackId: Long): Composition {
        val track = composition.track(trackId) ?: return composition
        val starts = track.notes.map { it.startTick }.distinct().sorted()
        if (starts.size < 2) return composition
        return composition.updateTrack(trackId) { t ->
            t.withNotes(
                t.notes.map { note ->
                    val next = starts.firstOrNull { it > note.startTick }
                    if (next == null) {
                        note
                    } else {
                        note.copy(durationTicks = (next - note.startTick).toInt().coerceAtLeast(1))
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------ global settings

    fun setTempo(composition: Composition, bpm: Double): Composition =
        composition.copy(tempoMap = composition.tempoMap.withInitialBpm(bpm.coerceIn(20.0, 400.0)))

    fun setTimeSignature(composition: Composition, timeSignature: TimeSignature): Composition =
        composition.copy(timeSignature = timeSignature)

    /**
     * Change the key.
     *
     * The notes are deliberately left exactly as they are: changing the key changes how
     * the music is *described* — the scale shown on the fretboard, the degrees in the
     * analysis, the key signature on the page — and silently transposing someone's solo
     * because they corrected the key would be destructive. [transposeToKey] is the
     * explicit opt-in when the notes really should move.
     */
    fun setKey(composition: Composition, key: Key): Composition = composition.copy(key = key)

    /** Move every note so the music is in [key] instead, keeping its scale degrees. */
    fun transposeToKey(composition: Composition, key: Key): Composition {
        val from = composition.key
        var semitones = (key.tonicPitchClass - from.tonicPitchClass + 12) % 12
        // Take the shorter route, so moving from B to C goes up a semitone, not down 11.
        if (semitones > 6) semitones -= 12
        if (semitones == 0) return composition.copy(key = key)
        val withKey = composition.copy(key = key)
        return composition.tracks.fold(withKey) { acc, track ->
            transposeNotes(acc, track.id, track.notes.map { it.id }.toSet(), semitones)
        }
    }

    // ------------------------------------------------------------------ tracks

    /**
     * Change a track's tuning.
     *
     * Pitches are preserved and the fingerings are recomputed, which is what a player
     * expects: retuning the instrument does not change the music, it changes where you
     * put your fingers. Notes that no longer fit keep their pitch and are reported by
     * [Track.unplayableNotes] so the UI can offer to move them.
     */
    fun setTuning(composition: Composition, trackId: Long, tuning: Tuning): Composition =
        composition.updateTrack(trackId) {
            it.copy(
                tuning = tuning,
                clef = if (it.tuning.family != tuning.family) {
                    ClefType.defaultFor(tuning.family)
                } else {
                    it.clef
                },
                voice = if (it.tuning.family != tuning.family) {
                    InstrumentVoice.defaultFor(tuning.family)
                } else {
                    it.voice
                },
            ).refretted()
        }

    fun setFretCount(composition: Composition, trackId: Long, frets: Int): Composition =
        composition.updateTrack(trackId) {
            it.copy(fretCount = frets.coerceIn(Fretboard.MIN_FRETS, Fretboard.MAX_FRETS)).refretted()
        }

    fun setVoice(composition: Composition, trackId: Long, voice: InstrumentVoice): Composition =
        composition.updateTrack(trackId) { it.copy(voice = voice) }

    fun setTrackVolume(composition: Composition, trackId: Long, volume: Float): Composition =
        composition.updateTrack(trackId) { it.copy(volume = volume.coerceIn(0f, 1.5f)) }

    fun setTrackPan(composition: Composition, trackId: Long, pan: Float): Composition =
        composition.updateTrack(trackId) { it.copy(pan = pan.coerceIn(-1f, 1f)) }

    fun setTrackMuted(composition: Composition, trackId: Long, muted: Boolean): Composition =
        composition.updateTrack(trackId) { it.copy(muted = muted) }

    /** Solo a track. Soloing one track implicitly silences the others. */
    fun setTrackSoloed(composition: Composition, trackId: Long, soloed: Boolean): Composition =
        composition.updateTrack(trackId) { it.copy(soloed = soloed) }

    fun clearSolos(composition: Composition): Composition =
        composition.copy(tracks = composition.tracks.map { it.copy(soloed = false) })

    fun renameTrack(composition: Composition, trackId: Long, name: String): Composition =
        composition.updateTrack(trackId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun setActiveTrack(composition: Composition, trackId: Long): Composition =
        if (composition.tracks.any { it.id == trackId }) {
            composition.copy(activeTrackId = trackId)
        } else {
            composition
        }

    /** Add an empty track, matching the active track's instrument by default. */
    fun addTrack(
        composition: Composition,
        name: String? = null,
        role: TrackRole = TrackRole.HARMONY,
        tuning: Tuning? = null,
        voice: InstrumentVoice? = null,
    ): Composition {
        val template = composition.activeTrack ?: composition.tracks.firstOrNull()
        val resolvedTuning = tuning ?: template?.tuning
            ?: com.tabtap.music.Tunings.STANDARD_E
        val (id, withId) = composition.allocateId()
        val slot = composition.tracks.size % (HarmonyGenerator.COLOR_SLOTS + 1)
        return withId.withTrack(
            Track(
                id = id,
                name = name ?: defaultTrackName(composition, role),
                role = role,
                voice = voice ?: InstrumentVoice.defaultFor(resolvedTuning.family),
                tuning = resolvedTuning,
                fretCount = template?.fretCount ?: 24,
                clef = ClefType.defaultFor(resolvedTuning.family),
                colorSlot = slot,
            ),
        ).copy(activeTrackId = id)
    }

    private fun defaultTrackName(composition: Composition, role: TrackRole): String {
        val count = composition.tracks.count { it.role == role } + 1
        return if (count == 1) role.displayName else "${role.displayName} $count"
    }

    fun removeTrack(composition: Composition, trackId: Long): Composition =
        // Never leave a project with no track at all; the UI would have nothing to show.
        if (composition.tracks.size <= 1) composition else composition.removeTrack(trackId)

    /**
     * Switch the active track between a guitar and a bass without losing the project.
     *
     * The notes keep their pitches; only the tuning, the fingerings, the clef and the
     * instrument voice change, and anything that no longer fits is reported rather than
     * discarded.
     */
    fun switchInstrumentFamily(
        composition: Composition,
        trackId: Long,
        family: InstrumentFamily,
    ): Composition {
        val track = composition.track(trackId) ?: return composition
        if (track.tuning.family == family) return composition
        val tuning = com.tabtap.music.Tunings.defaultFor(
            family,
            if (family == InstrumentFamily.BASS) 4 else 6,
        )
        return setTuning(composition, trackId, tuning).updateTrack(trackId) {
            it.copy(role = if (family == InstrumentFamily.BASS) TrackRole.BASS else TrackRole.LEAD)
        }
    }

    // ------------------------------------------------------------------ harmony

    fun generateHarmonies(
        composition: Composition,
        sourceTrackId: Long,
        specs: List<HarmonySpec>,
    ): Pair<Composition, List<com.tabtap.music.HarmonyWarning>> =
        HarmonyGenerator.generate(composition, sourceTrackId, specs)

    fun regenerateHarmonies(
        composition: Composition,
        sourceTrackId: Long,
    ): Pair<Composition, List<com.tabtap.music.HarmonyWarning>> =
        HarmonyGenerator.regenerateDerived(composition, sourceTrackId)

    /**
     * Offer an easier voicing for a harmony note by moving it to a different position
     * for the same pitch, or by an octave when no position exists.
     */
    fun refingerNote(composition: Composition, trackId: Long, noteId: Long): Composition {
        val track = composition.track(trackId) ?: return composition
        val note = track.note(noteId) ?: return composition
        val board = track.fretboard
        val options = board.positionsFor(note.pitch)
        if (options.isEmpty()) {
            // No position at this pitch: try the octaves.
            for (shift in listOf(-12, 12, -24, 24)) {
                val candidate = note.pitch + shift
                if (!Pitch.isValid(candidate)) continue
                val position = board.bestPositionFor(candidate) ?: continue
                return composition.updateTrack(trackId) { t ->
                    t.updateNote(noteId) {
                        it.copy(pitch = candidate, string = position.string, fret = position.fret)
                    }
                }
            }
            return composition
        }
        // Cycle to the next position for the same pitch.
        val currentIndex = options.indexOfFirst { it.string == note.string && it.fret == note.fret }
        val next = options[(currentIndex + 1).mod(options.size)]
        return composition.updateTrack(trackId) { t ->
            t.updateNote(noteId) { it.copy(string = next.string, fret = next.fret) }
        }
    }

    // ------------------------------------------------------------------ chords

    /**
     * A length a chord can be given, measured in the project's own metre.
     *
     * Chord length used to be chosen from note values, which is wrong: a whole note
     * equals a bar only in 4/4. In 2/4 a "whole note" chord lasts exactly twice as long
     * as the bar, and in 3/4 or 6/8 it lasts one and a third bars. Bars and beats are
     * what a chord is actually counted in, so that is what is offered.
     */
    data class ChordLength(val label: String, val ticks: Int)

    /**
     * The chord lengths offered for [ts], shortest first.
     *
     * Beats are *felt* beats, so a beat in 6/8 is a dotted quarter rather than an eighth,
     * which is how the metre is actually counted. Where a beat count and a bar count come
     * to the same length — two beats is a whole bar in 2/4, and in 6/8 — the bar label
     * wins, because "1 bar" is what a player means.
     */
    fun chordLengthOptions(ts: TimeSignature): List<ChordLength> {
        // Ticks to label, bars inserted first so they take precedence on a collision.
        val byTicks = LinkedHashMap<Int, String>()
        for (bars in listOf(1, 2, 4, 8)) {
            byTicks[ts.barTicks * bars] = if (bars == 1) "1 bar" else "$bars bars"
        }
        for (beats in 1 until ts.pulsesPerBar.coerceAtMost(5)) {
            val ticks = ts.pulseTicks * beats
            if (ticks < ts.barTicks) {
                byTicks.putIfAbsent(ticks, if (beats == 1) "1 beat" else "$beats beats")
            }
        }
        // Half a bar is only worth offering when it is not already a whole beat count,
        // which is the case in irregular metres such as 7/8.
        val half = ts.barTicks / 2
        if (half > 0) byTicks.putIfAbsent(half, "\u00BD bar")

        return byTicks.entries
            .sortedBy { it.key }
            .map { ChordLength(it.value, it.key) }
    }

    /** The label for an arbitrary chord length in [ts], e.g. "2 bars" or "3 beats". */
    fun describeChordLength(ticks: Int, ts: TimeSignature): String {
        chordLengthOptions(ts).firstOrNull { it.ticks == ticks }?.let { return it.label }
        if (ticks % ts.barTicks == 0) {
            val bars = ticks / ts.barTicks
            return if (bars == 1) "1 bar" else "$bars bars"
        }
        if (ticks % ts.pulseTicks == 0) {
            val beats = ticks / ts.pulseTicks
            return if (beats == 1) "1 beat" else "$beats beats"
        }
        return NoteValue.forTicks(ticks).displayName
    }

    /** Double a chord's length, up to a sane maximum of sixteen bars. */
    fun doubleChordLength(composition: Composition, chordId: Long): Composition {
        val event = composition.chordProgression.firstOrNull { it.id == chordId } ?: return composition
        val limit = composition.timeSignature.barTicks * 16
        return setChordDuration(composition, chordId, (event.durationTicks * 2).coerceAtMost(limit))
    }

    /** Halve a chord's length, down to a sixteenth note. */
    fun halveChordLength(composition: Composition, chordId: Long): Composition {
        val event = composition.chordProgression.firstOrNull { it.id == chordId } ?: return composition
        return setChordDuration(composition, chordId, (event.durationTicks / 2).coerceAtLeast(Ticks.SIXTEENTH))
    }

    /** Replace a chord in place, keeping its position in the progression. */
    fun replaceChord(
        composition: Composition,
        chordId: Long,
        chord: Chord,
        durationTicks: Int,
        strum: StrumPattern,
        voicing: ChordVoicing?,
    ): Composition {
        val board = composition.activeTrack?.fretboard
        return reflowChords(
            composition.copy(
                chordProgression = composition.chordProgression.map { event ->
                    if (event.id != chordId) {
                        event
                    } else {
                        event.copy(
                            chord = chord,
                            durationTicks = durationTicks.coerceAtLeast(1),
                            strum = strum,
                            voicing = voicing ?: board?.let { ChordVoicer.bestVoicing(chord, it) },
                        )
                    }
                },
            ),
        )
    }

    /** Insert a copy of a chord straight after it. */
    fun duplicateChord(composition: Composition, chordId: Long): Composition {
        val index = composition.chordProgression.indexOfFirst { it.id == chordId }
        if (index < 0) return composition
        val source = composition.chordProgression[index]
        val (id, withId) = composition.allocateId()
        val list = withId.chordProgression.toMutableList()
        list.add(index + 1, source.copy(id = id))
        return reflowChords(withId.copy(chordProgression = list))
    }

    /** Move a chord one place earlier or later in the progression. */
    fun nudgeChord(composition: Composition, chordId: Long, delta: Int): Composition {
        val index = composition.chordProgression.indexOfFirst { it.id == chordId }
        if (index < 0) return composition
        return moveChord(composition, chordId, index + delta)
    }

    /** Append a chord to the progression, after whatever is already there. */
    fun appendChord(
        composition: Composition,
        chord: Chord,
        durationTicks: Int = composition.timeSignature.barTicks,
        strum: StrumPattern = StrumPattern.BLOCK,
        voicing: ChordVoicing? = null,
    ): Composition {
        val (id, withId) = composition.allocateId()
        val start = composition.chordProgression.maxOfOrNull { it.endTick } ?: 0L
        val board = composition.activeTrack?.fretboard
        return withId.withChordProgression(
            composition.chordProgression + ChordEvent(
                id = id,
                chord = chord,
                startTick = start,
                durationTicks = durationTicks.coerceAtLeast(1),
                voicing = voicing ?: board?.let { ChordVoicer.bestVoicing(chord, it) },
                strum = strum,
            ),
        )
    }

    fun removeChord(composition: Composition, chordId: Long): Composition =
        reflowChords(
            composition.withChordProgression(composition.chordProgression.filterNot { it.id == chordId }),
        )

    fun setChordDuration(composition: Composition, chordId: Long, ticks: Int): Composition =
        reflowChords(
            composition.withChordProgression(
                composition.chordProgression.map {
                    if (it.id == chordId) it.copy(durationTicks = ticks.coerceAtLeast(1)) else it
                },
            ),
        )

    fun setChordStrum(composition: Composition, chordId: Long, strum: StrumPattern): Composition =
        composition.withChordProgression(
            composition.chordProgression.map { if (it.id == chordId) it.copy(strum = strum) else it },
        )

    fun setChordVoicing(composition: Composition, chordId: Long, voicing: ChordVoicing): Composition =
        composition.withChordProgression(
            composition.chordProgression.map { if (it.id == chordId) it.copy(voicing = voicing) else it },
        )

    /**
     * Move a chord to a different place in the progression.
     *
     * The reordered list is reflowed *before* it goes back into the composition, because
     * [Composition.withChordProgression] sorts by start tick — passing the reordered but
     * not-yet-retimed list through it would simply restore the original order.
     */
    fun moveChord(composition: Composition, chordId: Long, toIndex: Int): Composition {
        val list = composition.chordProgression.toMutableList()
        val from = list.indexOfFirst { it.id == chordId }
        if (from < 0) return composition
        val target = toIndex.coerceIn(0, list.size - 1)
        list.add(target, list.removeAt(from))
        return reflowChords(composition.copy(chordProgression = list))
    }

    fun clearChords(composition: Composition): Composition =
        composition.withChordProgression(emptyList())

    /**
     * Re-pack the progression so the chords sit end to end with no gaps, keeping the
     * list's current order rather than re-sorting by the old timings.
     */
    fun reflowChords(composition: Composition): Composition {
        var tick = 0L
        val reflowed = composition.chordProgression.map { event ->
            val placed = event.copy(startTick = tick)
            tick += event.durationTicks
            placed
        }
        return composition.copy(chordProgression = reflowed)
    }

    /**
     * Write the chord progression into a track as sounding notes, so it can be edited
     * note by note in the tab or the score.
     */
    fun renderChordsToTrack(composition: Composition, trackId: Long): Composition {
        val track = composition.track(trackId) ?: return composition
        if (composition.chordProgression.isEmpty()) return composition
        val board = track.fretboard
        var next = composition.nextId
        val notes = ArrayList<MusicNote>()
        for (event in composition.chordProgression) {
            val rendered = com.tabtap.io.ChordRenderer.render(event, board, next)
            notes += rendered
            next += rendered.size
        }
        return composition.copy(nextId = next).updateTrack(trackId) { it.withNotes(notes) }
    }

    // ------------------------------------------------------------------ misc

    /** Default quantise setting for a new project. */
    val DEFAULT_QUANTIZE = Quantize(GridResolution.SIXTEENTH, GridFeel.STRAIGHT, strength = 1.0)

    /** Shortest note the editor will create, so a drag cannot produce a zero-length note. */
    const val MIN_DURATION = 30
}
