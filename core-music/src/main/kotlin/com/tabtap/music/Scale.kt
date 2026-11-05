package com.tabtap.music

import kotlinx.serialization.Serializable

/**
 * A scale type: an ordered set of semitone offsets from the tonic, together with the
 * diatonic letter steps each degree occupies.
 *
 * Carrying explicit [diatonicSteps] is what lets the harmony engine and the engraver
 * spell notes correctly. For a seven-note scale the steps are 0..6; for pentatonic and
 * blues scales the steps name which letters the scale actually uses, so e.g. the blues
 * "b5" is spelled as a flattened fifth rather than a sharpened fourth.
 */
@Serializable
data class ScaleType(
    val id: String,
    val displayName: String,
    /** Semitone offsets from the tonic, ascending, starting at 0. */
    val semitones: List<Int>,
    /** Diatonic letter-step offsets from the tonic, parallel to [semitones]. */
    val diatonicSteps: List<Int>,
    /** True for the seven-note scales that can carry a conventional key signature. */
    val isHeptatonic: Boolean = semitones.size == 7,
) {
    init {
        require(semitones.size == diatonicSteps.size) {
            "Scale $id: ${semitones.size} semitones vs ${diatonicSteps.size} diatonic steps"
        }
        require(semitones.isNotEmpty() && semitones.first() == 0) { "Scale $id must start at 0" }
    }

    val size: Int get() = semitones.size

    companion object {
        private fun hepta(id: String, name: String, st: List<Int>) =
            ScaleType(id, name, st, listOf(0, 1, 2, 3, 4, 5, 6))

        val MAJOR = hepta("major", "Major (Ionian)", listOf(0, 2, 4, 5, 7, 9, 11))
        val NATURAL_MINOR = hepta("minor", "Natural Minor (Aeolian)", listOf(0, 2, 3, 5, 7, 8, 10))
        val HARMONIC_MINOR = hepta("harmonic_minor", "Harmonic Minor", listOf(0, 2, 3, 5, 7, 8, 11))
        val MELODIC_MINOR = hepta("melodic_minor", "Melodic Minor", listOf(0, 2, 3, 5, 7, 9, 11))
        val DORIAN = hepta("dorian", "Dorian", listOf(0, 2, 3, 5, 7, 9, 10))
        val PHRYGIAN = hepta("phrygian", "Phrygian", listOf(0, 1, 3, 5, 7, 8, 10))
        val LYDIAN = hepta("lydian", "Lydian", listOf(0, 2, 4, 6, 7, 9, 11))
        val MIXOLYDIAN = hepta("mixolydian", "Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10))
        val LOCRIAN = hepta("locrian", "Locrian", listOf(0, 1, 3, 5, 6, 8, 10))

        /** Major pentatonic: scale degrees 1 2 3 5 6. */
        val MAJOR_PENTATONIC = ScaleType(
            "major_pentatonic", "Major Pentatonic",
            listOf(0, 2, 4, 7, 9), listOf(0, 1, 2, 4, 5), isHeptatonic = false,
        )

        /** Minor pentatonic: scale degrees 1 b3 4 5 b7. */
        val MINOR_PENTATONIC = ScaleType(
            "minor_pentatonic", "Minor Pentatonic",
            listOf(0, 3, 5, 7, 10), listOf(0, 2, 3, 4, 6), isHeptatonic = false,
        )

        /** Minor blues: 1 b3 4 b5 5 b7 — the b5 keeps the fifth letter. */
        val BLUES = ScaleType(
            "blues", "Blues (Minor)",
            listOf(0, 3, 5, 6, 7, 10), listOf(0, 2, 3, 4, 4, 6), isHeptatonic = false,
        )

        /** Major blues: 1 2 b3 3 5 6. */
        val MAJOR_BLUES = ScaleType(
            "major_blues", "Blues (Major)",
            listOf(0, 2, 3, 4, 7, 9), listOf(0, 1, 2, 2, 4, 5), isHeptatonic = false,
        )

        val CHROMATIC = ScaleType(
            "chromatic", "Chromatic",
            (0..11).toList(), listOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6), isHeptatonic = false,
        )

        val WHOLE_TONE = ScaleType(
            "whole_tone", "Whole Tone",
            listOf(0, 2, 4, 6, 8, 10), listOf(0, 1, 2, 3, 4, 5), isHeptatonic = false,
        )

        val HARMONIC_MAJOR = hepta("harmonic_major", "Harmonic Major", listOf(0, 2, 4, 5, 7, 8, 11))
        val PHRYGIAN_DOMINANT = hepta("phrygian_dominant", "Phrygian Dominant", listOf(0, 1, 4, 5, 7, 8, 10))

        /** Every built-in scale, in menu order. */
        val BUILT_IN: List<ScaleType> = listOf(
            MAJOR, NATURAL_MINOR, HARMONIC_MINOR, MELODIC_MINOR,
            MAJOR_PENTATONIC, MINOR_PENTATONIC, BLUES, MAJOR_BLUES,
            DORIAN, PHRYGIAN, LYDIAN, MIXOLYDIAN, LOCRIAN,
            HARMONIC_MAJOR, PHRYGIAN_DOMINANT, WHOLE_TONE, CHROMATIC,
        )

        fun byId(id: String): ScaleType? = BUILT_IN.firstOrNull { it.id == id }

        /**
         * Build a custom scale from semitone offsets, inferring sensible letter steps.
         * Offsets are sorted, de-duplicated and forced to start at 0.
         */
        fun custom(id: String, name: String, semitones: List<Int>): ScaleType {
            val st = (listOf(0) + semitones.map { ((it % 12) + 12) % 12 }).distinct().sorted()
            // Assign each offset the letter step of the nearest natural degree, never
            // repeating a step unless two offsets are a semitone apart.
            val steps = ArrayList<Int>(st.size)
            var lastStep = -1
            for (s in st) {
                var step = NATURAL_STEP_FOR_SEMITONE[s]
                if (step == lastStep && st.size > 3) step = (step + 1) % 7
                steps += step
                lastStep = step
            }
            return ScaleType(id, name, st, steps, isHeptatonic = st.size == 7)
        }

        /** For each semitone 0..11, the diatonic step of the major scale degree it belongs to. */
        private val NATURAL_STEP_FOR_SEMITONE = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
    }
}

/**
 * A key: a tonic pitch class with a spelled tonic letter, plus a scale type.
 *
 * The spelled tonic is kept because Eb minor and D# minor are the same pitch classes
 * but entirely different on the page.
 */
@Serializable
data class Key(
    val tonic: SpelledPitch,
    val scale: ScaleType,
) {
    /** Pitch class of the tonic, 0 == C. */
    val tonicPitchClass: Int get() = ((tonic.midi % 12) + 12) % 12

    val displayName: String get() = "${tonic.letter}${tonic.accidentalText} ${scale.displayName}"

    /** Short display such as "E min" or "Bb maj". */
    val shortName: String
        get() {
            val suffix = when (scale.id) {
                "major" -> "maj"
                "minor" -> "min"
                else -> scale.displayName
            }
            return "${tonic.letter}${tonic.accidentalText} $suffix"
        }

    /** The spelled pitch classes of the scale, ascending from the tonic. */
    val spelledDegrees: List<SpelledPitch> by lazy {
        scale.semitones.indices.map { degreeSpelling(it, tonic.octave) }
    }

    /** Pitch classes present in this key, as a sorted set. */
    val pitchClasses: Set<Int> by lazy {
        scale.semitones.map { ((tonicPitchClass + it) % 12) }.toSet()
    }

    /**
     * Spelling of scale degree [degree] (0-based, may exceed the scale size to run into
     * higher octaves) placed in [octaveBase] relative to the tonic octave.
     */
    fun degreeSpelling(degree: Int, octaveBase: Int = tonic.octave): SpelledPitch {
        val n = scale.size
        val octaveShift = Math.floorDiv(degree, n)
        val d = ((degree % n) + n) % n
        val stepIndex = tonic.diatonicIndex + scale.diatonicSteps[d] + octaveShift * 7 +
            (octaveBase - tonic.octave) * 7
        val targetMidi = tonic.midi + scale.semitones[d] + octaveShift * 12 +
            (octaveBase - tonic.octave) * 12
        val letter = NoteLetter.ofStep(stepIndex)
        val naturalMidi = (Math.floorDiv(stepIndex, 7) + 1) * 12 + letter.semitonesFromC
        return SpelledPitch(letter, targetMidi - naturalMidi, Math.floorDiv(stepIndex, 7))
    }

    /** True when the pitch class of [midi] belongs to this key. */
    fun contains(midi: Int): Boolean = Pitch.pitchClass(midi) in pitchClasses

    /**
     * The 0-based scale degree of [midi] if it is in the key, otherwise null.
     */
    fun degreeOf(midi: Int): Int? {
        val offset = ((Pitch.pitchClass(midi) - tonicPitchClass) % 12 + 12) % 12
        val idx = scale.semitones.indexOf(offset)
        return if (idx >= 0) idx else null
    }

    /**
     * Human-readable scale degree of [midi], e.g. "1", "b3", "#4", or null outside the key.
     * Chromatic notes get an altered-degree label relative to the nearest scale degree.
     */
    fun degreeLabel(midi: Int): String {
        val offset = ((Pitch.pitchClass(midi) - tonicPitchClass) % 12 + 12) % 12
        val idx = scale.semitones.indexOf(offset)
        if (idx >= 0) {
            val natural = Interval.DIATONIC_SEMITONES[scale.diatonicSteps[idx] % 7]
            val alter = offset - natural - 12 * Math.floorDiv(scale.diatonicSteps[idx], 7)
            val prefix = when {
                alter > 0 -> "#".repeat(alter)
                alter < 0 -> "b".repeat(-alter)
                else -> ""
            }
            return prefix + (scale.diatonicSteps[idx] % 7 + 1)
        }
        // Outside the key: describe relative to the chromatic interval from the tonic.
        return IntervalLabels.label(midi, tonicPitchClass)
    }

    /**
     * Spell [midi] in the context of this key, producing correct accidentals.
     * In-key notes take their scale spelling; out-of-key notes are spelled with the
     * accidental direction the key signature implies.
     */
    fun spell(midi: Int): SpelledPitch {
        val deg = degreeOf(midi)
        if (deg != null) {
            // Find the octave placement whose MIDI matches.
            val base = degreeSpelling(deg, tonic.octave)
            val octaveDiff = Math.floorDiv(midi - base.midi + 6, 12)
            val candidate = SpelledPitch(base.letter, base.alter, base.octave + octaveDiff)
            if (candidate.midi == midi) return candidate
            // Fall back to a direct search across neighbouring octaves.
            for (o in -2..2) {
                val c = SpelledPitch(base.letter, base.alter, base.octave + o)
                if (c.midi == midi) return c
            }
        }
        return SpelledPitch.of(midi, preferFlats = sharpsInSignature <= 0)
    }

    /**
     * Number of sharps (positive) or flats (negative) in the conventional key signature.
     * Non-heptatonic scales borrow the signature of their parent major/minor key.
     */
    val sharpsInSignature: Int by lazy { KeySignature.fifthsFor(this) }

    companion object {
        /** C major, the default key for a new project. */
        val C_MAJOR = Key(SpelledPitch(NoteLetter.C, 0, 4), ScaleType.MAJOR)

        /** Parse a tonic name plus scale id, e.g. ("Eb", "minor"). */
        fun of(tonicName: String, scale: ScaleType, octave: Int = 4): Key? {
            val midi = Pitch.parse(tonicName + octave) ?: return null
            val letter = tonicName.firstOrNull()?.uppercaseChar() ?: return null
            val nl = runCatching { NoteLetter.valueOf(letter.toString()) }.getOrNull() ?: return null
            val natural = (octave + 1) * 12 + nl.semitonesFromC
            return Key(SpelledPitch(nl, midi - natural, octave), scale)
        }
    }
}

/**
 * Key-signature arithmetic on the circle of fifths.
 */
object KeySignature {
    /** Sharps/flats count for each major tonic letter with no accidental. */
    private val MAJOR_FIFTHS = mapOf(
        NoteLetter.C to 0, NoteLetter.G to 1, NoteLetter.D to 2, NoteLetter.A to 3,
        NoteLetter.E to 4, NoteLetter.B to 5, NoteLetter.F to -1,
    )

    /** Order sharps appear in a signature: F C G D A E B. */
    val SHARP_ORDER = listOf(
        NoteLetter.F, NoteLetter.C, NoteLetter.G, NoteLetter.D,
        NoteLetter.A, NoteLetter.E, NoteLetter.B,
    )

    /** Order flats appear in a signature: B E A D G C F. */
    val FLAT_ORDER = SHARP_ORDER.reversed()

    /**
     * Index of the tonic within its parent major scale, for each scale type.
     * Ionian == 0, Dorian == 1 ... Aeolian == 5, Locrian == 6. Scales without a
     * conventional signature borrow the one of their parent major or minor key.
     */
    private fun modeDegree(scaleId: String): Int = when (scaleId) {
        "major", "major_pentatonic", "major_blues", "harmonic_major" -> 0
        "dorian" -> 1
        "phrygian", "phrygian_dominant" -> 2
        "lydian" -> 3
        "mixolydian" -> 4
        "minor", "minor_pentatonic", "blues", "harmonic_minor", "melodic_minor" -> 5
        "locrian" -> 6
        else -> 0
    }

    /**
     * Fifths value for [key]: positive counts sharps, negative counts flats.
     *
     * Derived by transposing the tonic down to its parent major tonic and reading that
     * tonic off the circle of fifths, so E minor and E minor pentatonic both yield the
     * single sharp of G major, and Eb major yields three flats.
     */
    fun fifthsFor(key: Key): Int {
        val degree = modeDegree(key.scale.id)
        // Parent major tonic: the key tonic lowered by the mode's diatonic offset.
        val majorStep = key.tonic.diatonicIndex - degree
        val majorLetter = NoteLetter.ofStep(majorStep)
        val majorMidi = key.tonic.midi - Interval.DIATONIC_SEMITONES[degree]
        val naturalMidi = (Math.floorDiv(majorStep, 7) + 1) * 12 + majorLetter.semitonesFromC
        val alter = majorMidi - naturalMidi
        val base = MAJOR_FIFTHS[majorLetter] ?: 0
        return (base + alter * 7).coerceIn(-7, 7)
    }

    /** The letters carrying an accidental in a signature of [fifths], in writing order. */
    fun accidentalLetters(fifths: Int): List<NoteLetter> = when {
        fifths > 0 -> SHARP_ORDER.take(fifths.coerceAtMost(7))
        fifths < 0 -> FLAT_ORDER.take((-fifths).coerceAtMost(7))
        else -> emptyList()
    }

    /** Alteration implied by the signature for a given letter: +1, -1 or 0. */
    fun alterFor(letter: NoteLetter, fifths: Int): Int = when {
        fifths > 0 && letter in SHARP_ORDER.take(fifths.coerceAtMost(7)) -> 1
        fifths < 0 && letter in FLAT_ORDER.take((-fifths).coerceAtMost(7)) -> -1
        else -> 0
    }
}
