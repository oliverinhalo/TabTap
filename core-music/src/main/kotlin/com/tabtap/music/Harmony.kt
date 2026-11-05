package com.tabtap.music

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * How a harmony line is derived from its source line.
 */
@Serializable
enum class HarmonyMode(val displayName: String) {
    /**
     * Move each note by a number of *scale steps* inside the selected key, so the
     * harmony interval varies between major and minor as the scale requires. This is
     * what a guitarist means by "harmonise it in thirds".
     */
    DIATONIC("Diatonic"),

    /**
     * Move each note by a fixed number of semitones. Useful for deliberate parallel
     * harmony and for octaves, where it is also the diatonically correct answer.
     */
    CHROMATIC("Parallel (fixed interval)"),
}

/**
 * How notes that fall outside the selected key are harmonised.
 *
 * A guitar solo in E minor may well contain a D# leading tone or a chromatic passing
 * note, and the two musically defensible answers differ, so TabTap makes the choice
 * explicit rather than guessing.
 */
@Serializable
enum class ChromaticHandling(val displayName: String) {
    /**
     * Treat the note as an alteration of the scale degree below it and harmonise that
     * degree, so the harmony line stays inside the key. A D# in E minor harmonised a
     * third above becomes F#, the diatonic third above the seventh degree.
     */
    SNAP_TO_SCALE("Keep harmony in key"),

    /**
     * Harmonise the scale degree below and then carry the note's own alteration across,
     * which keeps the harmony exactly parallel to the source line. A D# in E minor
     * harmonised a third above becomes G, preserving the interval the D would have got.
     */
    PARALLEL("Follow chromatic motion"),
}

/**
 * A harmony voice specification.
 *
 * [diatonicSteps] counts scale steps and is used in [HarmonyMode.DIATONIC]; positive is
 * above, negative below, so +2 is "a third above" and -2 "a third below".
 * [semitones] is used in [HarmonyMode.CHROMATIC].
 */
@Serializable
data class HarmonySpec(
    val mode: HarmonyMode = HarmonyMode.DIATONIC,
    /** Scale steps to move by in diatonic mode. +2 == a third above. */
    val diatonicSteps: Int = 2,
    /** Semitones to move by in chromatic mode. */
    val semitones: Int = 4,
    /** Extra octaves applied after the interval. */
    val octaveShift: Int = 0,
    /** Keep the source rhythm exactly. When false the harmony's rhythm can be edited freely. */
    val preserveRhythm: Boolean = true,
    /** How notes outside the key are treated in diatonic mode. */
    val chromaticHandling: ChromaticHandling = ChromaticHandling.SNAP_TO_SCALE,
    /** Snap notes to the chord sounding underneath, when a progression exists. */
    val followChords: Boolean = true,
    /** Move notes by octaves when needed so the line fits the target fretboard. */
    val fitToFretboard: Boolean = true,
    /** Relative velocity applied to harmony notes, 1.0 == same as source. */
    val velocityScale: Float = 0.9f,
    /** Instrument voice for the generated track; null keeps the source track's voice. */
    val voice: InstrumentVoice? = null,
    /** Name for the generated track; null derives one from the interval. */
    val trackName: String? = null,
) {
    /** Human-readable label, e.g. "Diatonic 3rd above" or "Octave below". */
    val displayName: String
        get() = trackName ?: when (mode) {
            HarmonyMode.DIATONIC -> {
                val n = abs(diatonicSteps) + 1
                val dir = if (diatonicSteps >= 0) "above" else "below"
                val oct = when {
                    octaveShift > 0 -> " +${octaveShift} oct"
                    octaveShift < 0 -> " ${octaveShift} oct"
                    else -> ""
                }
                if (diatonicSteps == 0 && octaveShift != 0) {
                    if (octaveShift > 0) "Octave above" else "Octave below"
                } else {
                    "Diatonic ${ordinal(n)} $dir$oct"
                }
            }
            HarmonyMode.CHROMATIC -> {
                val total = semitones + octaveShift * 12
                val iv = Interval.simple(abs(total))
                val dir = if (total >= 0) "above" else "below"
                when {
                    abs(total) % 12 == 0 && total != 0 ->
                        "${abs(total) / 12} octave${if (abs(total) / 12 > 1) "s" else ""} $dir"
                    else -> "Parallel ${iv.shortName} $dir"
                }
            }
        }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "unison"; 2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; 5 -> "5th"
        6 -> "6th"; 7 -> "7th"; 8 -> "octave"; else -> "${n}th"
    }

    companion object {
        val THIRD_ABOVE = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = 2)
        val THIRD_BELOW = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = -2)
        val FOURTH_ABOVE = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = 3)
        val FOURTH_BELOW = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = -3)
        val FIFTH_ABOVE = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = 4)
        val FIFTH_BELOW = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = -4)
        val SIXTH_ABOVE = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = 5)
        val SIXTH_BELOW = HarmonySpec(HarmonyMode.DIATONIC, diatonicSteps = -5)
        val OCTAVE_ABOVE = HarmonySpec(HarmonyMode.CHROMATIC, semitones = 0, octaveShift = 1)
        val OCTAVE_BELOW = HarmonySpec(HarmonyMode.CHROMATIC, semitones = 0, octaveShift = -1)

        /** The presets offered in the harmony panel, in menu order. */
        val PRESETS: List<HarmonySpec> = listOf(
            THIRD_ABOVE, THIRD_BELOW, FOURTH_ABOVE, FIFTH_ABOVE, FIFTH_BELOW,
            SIXTH_ABOVE, SIXTH_BELOW, OCTAVE_ABOVE, OCTAVE_BELOW,
        )

        /** A fixed-interval harmony of [semitones], e.g. for a deliberate parallel 5th line. */
        fun customInterval(semitones: Int): HarmonySpec =
            HarmonySpec(HarmonyMode.CHROMATIC, semitones = semitones)
    }
}

/** A harmony note the generator could not place on the target fretboard. */
data class HarmonyWarning(val noteId: Long, val pitch: Int, val message: String)

/** The outcome of generating one harmony voice. */
data class HarmonyResult(
    val track: Track,
    val warnings: List<HarmonyWarning> = emptyList(),
)

/**
 * The harmony generator.
 *
 * Diatonic harmonisation works in *scale-degree space*, not semitone space: a note is
 * located as a degree of the selected key, moved by a number of scale steps, and spelled
 * back out. That is why harmonising an E natural-minor line in thirds produces G above E,
 * B above G and D above B — a mix of minor and major thirds — rather than transposing
 * everything by a constant interval.
 *
 * Notes outside the key keep their chromatic alteration relative to the scale degree
 * below them, so passing accidentals and a raised leading tone harmonise sensibly
 * instead of being forced into the scale.
 */
object HarmonyGenerator {

    /**
     * Harmonise one pitch.
     *
     * @param pitch sounding MIDI pitch of the source note.
     * @param key the key the music is in.
     * @param spec the harmony voice to generate.
     * @return the harmonised sounding pitch, clamped to the MIDI range.
     */
    fun harmonizePitch(pitch: Int, key: Key, spec: HarmonySpec): Int {
        val raw = when (spec.mode) {
            HarmonyMode.CHROMATIC -> pitch + spec.semitones + spec.octaveShift * 12
            HarmonyMode.DIATONIC ->
                diatonicTranspose(pitch, key, spec.diatonicSteps, spec.chromaticHandling) +
                    spec.octaveShift * 12
        }
        return Pitch.clamp(raw)
    }

    /**
     * Harmonise one pitch and return its spelling, so the score engraves correct
     * accidentals for the generated line.
     */
    fun harmonizeSpelled(pitch: Int, key: Key, spec: HarmonySpec): SpelledPitch {
        val result = harmonizePitch(pitch, key, spec)
        return key.spell(result)
    }

    /**
     * Move [pitch] by [steps] scale steps within [key].
     *
     * The pitch is located as a degree of the key — the nearest degree at or below it,
     * plus any chromatic alteration — the degree is moved by [steps], and the result is
     * read back out of the scale. Because the scale's own semitone pattern does the work,
     * the harmony interval changes between major and minor exactly where the key requires.
     */
    fun diatonicTranspose(
        pitch: Int,
        key: Key,
        steps: Int,
        chromaticHandling: ChromaticHandling = ChromaticHandling.SNAP_TO_SCALE,
    ): Int {
        if (steps == 0) return pitch
        val scale = key.scale
        val n = scale.size

        // The tonic at or below the pitch: everything is measured from here.
        val base = tonicRootBelow(pitch, key.tonicPitchClass)
        val within = pitch - base          // always 0..11
        var degreeIndex = 0
        var alteration = within
        for (i in n - 1 downTo 0) {
            if (scale.semitones[i] <= within) {
                degreeIndex = i
                alteration = within - scale.semitones[i]
                break
            }
        }

        val targetDegree = degreeIndex + steps
        val targetOctaves = Math.floorDiv(targetDegree, n)
        val targetWithin = ((targetDegree % n) + n) % n
        val targetSemis = targetOctaves * 12 + scale.semitones[targetWithin]

        val carried = if (chromaticHandling == ChromaticHandling.PARALLEL) alteration else 0
        return base + targetSemis + carried
    }

    /** The highest pitch at or below [pitch] whose pitch class is [tonicPc]. */
    private fun tonicRootBelow(pitch: Int, tonicPc: Int): Int {
        val pc = Pitch.pitchClass(pitch)
        val back = ((pc - tonicPc) % 12 + 12) % 12
        return pitch - back
    }

    /**
     * Generate one harmony track from [source].
     *
     * The generated track keeps the source rhythm note for note when
     * [HarmonySpec.preserveRhythm] is set, carries over articulations, and is fretted on
     * [targetTuning] (defaulting to the source tuning). Notes that will not fit on the
     * board are moved by whole octaves when [HarmonySpec.fitToFretboard] allows it;
     * anything still unplayable keeps its pitch, loses its fingering and is reported as
     * a warning so the UI can offer an alternative voicing.
     */
    fun generateTrack(
        source: Track,
        key: Key,
        spec: HarmonySpec,
        newTrackId: Long,
        firstNoteId: Long,
        colorSlot: Int = 1,
        targetTuning: Tuning = source.tuning,
        targetFretCount: Int = source.fretCount,
        chordProgression: List<ChordEvent> = emptyList(),
    ): HarmonyResult {
        val board = Fretboard(targetTuning, targetFretCount)
        val warnings = ArrayList<HarmonyWarning>()
        val notes = ArrayList<MusicNote>(source.notes.size)
        var nextId = firstNoteId
        var anchorFret = source.notes.firstOrNull()?.fret ?: Fretboard.DEFAULT_ANCHOR_FRET

        for (n in source.notes) {
            var pitch = harmonizePitch(n.pitch, key, spec)

            if (spec.followChords && chordProgression.isNotEmpty()) {
                pitch = snapToChord(pitch, n.pitch, chordProgression, n.startTick)
            }

            // Fit to the fretboard by octave displacement when needed.
            if (spec.fitToFretboard && !board.canPlay(pitch)) {
                val alternatives = listOf(-12, 12, -24, 24)
                val fixed = alternatives
                    .map { Pitch.clamp(pitch + it) }
                    .firstOrNull { board.canPlay(it) }
                if (fixed != null) {
                    pitch = fixed
                } else {
                    warnings += HarmonyWarning(
                        n.id, pitch,
                        "${Pitch.fullName(pitch)} is outside the range of ${targetTuning.displayName}",
                    )
                }
            } else if (!board.canPlay(pitch)) {
                warnings += HarmonyWarning(
                    n.id, pitch,
                    "${Pitch.fullName(pitch)} is outside the range of ${targetTuning.displayName}",
                )
            }

            val pos = board.bestPositionFor(pitch, anchorFret, n.string)
            if (pos != null) anchorFret = pos.fret

            notes += MusicNote(
                id = nextId++,
                pitch = pitch,
                startTick = n.startTick,
                durationTicks = n.durationTicks,
                velocity = (n.velocity * spec.velocityScale).toInt().coerceIn(1, 127),
                string = pos?.string,
                fret = pos?.fret,
                // Fingering-specific techniques do not transfer; expressive ones do.
                articulations = n.articulations.filterTo(HashSet()) { it in TRANSFERABLE },
                tiedToNext = n.tiedToNext,
                bendSemitones = n.bendSemitones,
            )
        }

        val track = Track(
            id = newTrackId,
            name = spec.trackName ?: spec.displayName,
            role = TrackRole.HARMONY,
            voice = spec.voice ?: source.voice,
            tuning = targetTuning,
            fretCount = targetFretCount,
            notes = notes,
            volume = source.volume * 0.85f,
            clef = source.clef,
            colorSlot = colorSlot,
            derivedFromTrackId = source.id,
            harmonySpec = spec,
        ).sorted()

        return HarmonyResult(track, warnings)
    }

    /**
     * Generate several harmony voices at once and attach them to [composition].
     *
     * Existing generated tracks derived from the same source with the same spec are
     * replaced rather than duplicated, so pressing Generate twice does not pile up
     * tracks.
     */
    fun generate(
        composition: Composition,
        sourceTrackId: Long,
        specs: List<HarmonySpec>,
    ): Pair<Composition, List<HarmonyWarning>> {
        val source = composition.track(sourceTrackId) ?: return composition to emptyList()
        if (source.notes.isEmpty() || specs.isEmpty()) return composition to emptyList()

        var comp = composition
        val warnings = ArrayList<HarmonyWarning>()
        var slot = 1 + comp.tracks.count { it.role == TrackRole.HARMONY }

        for (spec in specs) {
            // Replace a previously generated voice with the same settings.
            val existing = comp.tracks.firstOrNull {
                it.derivedFromTrackId == sourceTrackId && it.harmonySpec == spec
            }
            val (trackId, afterTrackId) = if (existing != null) {
                existing.id to comp
            } else {
                comp.allocateId()
            }
            comp = afterTrackId
            val (noteIds, afterNotes) = comp.allocateIds(source.notes.size)
            comp = afterNotes
            val result = generateTrack(
                source = source,
                key = comp.key,
                spec = spec,
                newTrackId = trackId,
                firstNoteId = noteIds.firstOrNull() ?: comp.nextId,
                colorSlot = existing?.colorSlot ?: (slot++ % COLOR_SLOTS),
                targetTuning = existing?.tuning ?: source.tuning,
                targetFretCount = existing?.fretCount ?: source.fretCount,
                chordProgression = comp.chordProgression,
            )
            warnings += result.warnings
            comp = comp.withTrack(
                if (existing != null) {
                    result.track.copy(
                        name = existing.name,
                        volume = existing.volume,
                        pan = existing.pan,
                        muted = existing.muted,
                        soloed = existing.soloed,
                        voice = existing.voice,
                    )
                } else {
                    result.track
                },
            )
        }
        return comp to warnings
    }

    /**
     * Regenerate every harmony track that was derived from a source track, used after
     * the source line or the key changes so harmonies stay in step.
     */
    fun regenerateDerived(composition: Composition, sourceTrackId: Long): Pair<Composition, List<HarmonyWarning>> {
        val derived = composition.tracks.filter {
            it.derivedFromTrackId == sourceTrackId && it.harmonySpec != null
        }
        if (derived.isEmpty()) return composition to emptyList()
        return generate(composition, sourceTrackId, derived.mapNotNull { it.harmonySpec })
    }

    /**
     * Nudge [candidate] onto a chord tone of the chord sounding at [tick], but only
     * when doing so moves it by a semitone or two and the source note was itself a
     * chord tone. This keeps generated lines consonant over a progression without
     * flattening the melodic contour.
     */
    private fun snapToChord(
        candidate: Int,
        sourcePitch: Int,
        progression: List<ChordEvent>,
        tick: Long,
    ): Int {
        val chord = progression.lastOrNull { it.startTick <= tick && it.endTick > tick } ?: return candidate
        if (chord.chord.contains(candidate)) return candidate
        if (!chord.chord.contains(sourcePitch)) return candidate
        val options = (-2..2).map { candidate + it }.filter { chord.chord.contains(it) && Pitch.isValid(it) }
        return options.minByOrNull { abs(it - candidate) } ?: candidate
    }

    /** Colour slots available for harmony tracks. */
    const val COLOR_SLOTS = 6

    /** Articulations that carry across to a harmony line. */
    private val TRANSFERABLE = setOf(
        Articulation.VIBRATO, Articulation.WIDE_VIBRATO, Articulation.PALM_MUTE,
        Articulation.LET_RING, Articulation.STACCATO, Articulation.ACCENT,
        Articulation.SLIDE_LEGATO, Articulation.SLIDE_UP, Articulation.SLIDE_DOWN,
    )
}

/**
 * Per-note analysis of a line against a key, for the composition inspector.
 */
data class NoteAnalysis(
    val note: MusicNote,
    val spelled: SpelledPitch,
    val noteName: String,
    val degreeLabel: String,
    val intervalFromRoot: Interval,
    val inKey: Boolean,
)

/** Analyses a track's notes against the project key. */
object CompositionAnalyzer {

    fun analyze(track: Track, key: Key): List<NoteAnalysis> = track.notes.map { analyze(it, key) }

    fun analyze(note: MusicNote, key: Key): NoteAnalysis {
        val spelled = key.spell(note.pitch)
        return NoteAnalysis(
            note = note,
            spelled = spelled,
            noteName = Pitch.fullName(note.pitch, preferFlats = key.sharpsInSignature < 0),
            degreeLabel = key.degreeLabel(note.pitch),
            intervalFromRoot = Interval.simple(note.pitch - key.tonicPitchClass),
            inKey = key.contains(note.pitch),
        )
    }

    /** Count of notes outside the selected key. */
    fun outOfKeyCount(track: Track, key: Key): Int = track.notes.count { !key.contains(it.pitch) }

    /**
     * Suggest the key that best fits a track, by counting how many notes fall inside
     * each candidate key. Returns the candidates best first.
     */
    fun suggestKeys(track: Track, candidates: List<ScaleType> = listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR)): List<Pair<Key, Double>> {
        if (track.notes.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Key, Double>>()
        for (scale in candidates) {
            for (pc in 0..11) {
                val spelled = SpelledPitch.of(pc + 48, preferFlats = pc in setOf(1, 3, 6, 8, 10))
                val key = Key(SpelledPitch(spelled.letter, spelled.alter, 4), scale)
                val inside = track.notes.count { key.contains(it.pitch) }
                var score = inside.toDouble() / track.notes.size
                // Reward the tonic actually appearing, and appearing at the start or end.
                if (track.notes.any { Pitch.pitchClass(it.pitch) == pc }) score += 0.05
                if (Pitch.pitchClass(track.notes.first().pitch) == pc) score += 0.05
                if (Pitch.pitchClass(track.notes.last().pitch) == pc) score += 0.08
                out += key to score
            }
        }
        return out.sortedByDescending { it.second }
    }
}
