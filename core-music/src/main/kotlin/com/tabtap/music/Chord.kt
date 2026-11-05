package com.tabtap.music

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * A chord type: the intervals above the root, in semitones, plus the diatonic steps
 * those intervals occupy so that chord tones spell correctly (a diminished fifth is a
 * flattened fifth, not a sharpened fourth).
 */
@Serializable
data class ChordType(
    val id: String,
    val displayName: String,
    /** Suffix used in chord symbols, e.g. "m7", "maj7", "5". */
    val symbol: String,
    val semitones: List<Int>,
    val diatonicSteps: List<Int>,
    /** Chord tones that may be omitted when a voicing cannot fit them all. */
    val optionalDegrees: Set<Int> = emptySet(),
) {
    init {
        require(semitones.size == diatonicSteps.size) { "Chord $id: interval arity mismatch" }
        require(semitones.isNotEmpty() && semitones.first() == 0) { "Chord $id must contain its root" }
    }

    val size: Int get() = semitones.size

    /** Pitch classes of the chord when rooted on [rootPitchClass]. */
    fun pitchClasses(rootPitchClass: Int): Set<Int> =
        semitones.map { ((rootPitchClass + it) % 12 + 12) % 12 }.toSet()

    companion object {
        private fun c(
            id: String, name: String, symbol: String,
            semis: List<Int>, steps: List<Int>, optional: Set<Int> = emptySet(),
        ) = ChordType(id, name, symbol, semis, steps, optional)

        val MAJOR = c("major", "Major", "", listOf(0, 4, 7), listOf(0, 2, 4))
        val MINOR = c("minor", "Minor", "m", listOf(0, 3, 7), listOf(0, 2, 4))
        val DIMINISHED = c("dim", "Diminished", "dim", listOf(0, 3, 6), listOf(0, 2, 4))
        val DIMINISHED7 = c("dim7", "Diminished 7", "dim7", listOf(0, 3, 6, 9), listOf(0, 2, 4, 6))
        val HALF_DIMINISHED = c("m7b5", "Half-diminished", "m7b5", listOf(0, 3, 6, 10), listOf(0, 2, 4, 6))
        val AUGMENTED = c("aug", "Augmented", "aug", listOf(0, 4, 8), listOf(0, 2, 4))
        val SUS2 = c("sus2", "Suspended 2nd", "sus2", listOf(0, 2, 7), listOf(0, 1, 4))
        val SUS4 = c("sus4", "Suspended 4th", "sus4", listOf(0, 5, 7), listOf(0, 3, 4))
        val MAJOR7 = c("maj7", "Major 7", "maj7", listOf(0, 4, 7, 11), listOf(0, 2, 4, 6))
        val MINOR7 = c("m7", "Minor 7", "m7", listOf(0, 3, 7, 10), listOf(0, 2, 4, 6))
        val DOMINANT7 = c("7", "Dominant 7", "7", listOf(0, 4, 7, 10), listOf(0, 2, 4, 6))
        val MINOR_MAJOR7 = c("mMaj7", "Minor-Major 7", "mMaj7", listOf(0, 3, 7, 11), listOf(0, 2, 4, 6))
        val MAJOR9 = c("maj9", "Major 9", "maj9", listOf(0, 4, 7, 11, 14), listOf(0, 2, 4, 6, 8), setOf(4))
        val MINOR9 = c("m9", "Minor 9", "m9", listOf(0, 3, 7, 10, 14), listOf(0, 2, 4, 6, 8), setOf(4))
        val DOMINANT9 = c("9", "Dominant 9", "9", listOf(0, 4, 7, 10, 14), listOf(0, 2, 4, 6, 8), setOf(4))
        val ADD9 = c("add9", "Add 9", "add9", listOf(0, 4, 7, 14), listOf(0, 2, 4, 8))
        val MINOR_ADD9 = c("madd9", "Minor add 9", "m(add9)", listOf(0, 3, 7, 14), listOf(0, 2, 4, 8))
        val SIXTH = c("6", "Sixth", "6", listOf(0, 4, 7, 9), listOf(0, 2, 4, 5))
        val MINOR6 = c("m6", "Minor 6", "m6", listOf(0, 3, 7, 9), listOf(0, 2, 4, 5))
        val POWER = c("5", "Power chord", "5", listOf(0, 7), listOf(0, 4))
        val MAJOR11 = c("maj11", "Major 11", "maj11", listOf(0, 4, 7, 11, 14, 17), listOf(0, 2, 4, 6, 8, 10), setOf(4, 5))
        val DOMINANT13 = c("13", "Dominant 13", "13", listOf(0, 4, 7, 10, 14, 21), listOf(0, 2, 4, 6, 8, 12), setOf(4, 5))
        val SEVEN_SUS4 = c("7sus4", "7 sus4", "7sus4", listOf(0, 5, 7, 10), listOf(0, 3, 4, 6))
        val SEVEN_SHARP5 = c("7#5", "7 sharp 5", "7#5", listOf(0, 4, 8, 10), listOf(0, 2, 4, 6))
        val SEVEN_FLAT9 = c("7b9", "7 flat 9", "7b9", listOf(0, 4, 7, 10, 13), listOf(0, 2, 4, 6, 8), setOf(4))

        val BUILT_IN: List<ChordType> = listOf(
            MAJOR, MINOR, POWER, SUS2, SUS4, DIMINISHED, AUGMENTED,
            MAJOR7, MINOR7, DOMINANT7, MINOR_MAJOR7, HALF_DIMINISHED, DIMINISHED7,
            SIXTH, MINOR6, ADD9, MINOR_ADD9, MAJOR9, MINOR9, DOMINANT9,
            SEVEN_SUS4, SEVEN_SHARP5, SEVEN_FLAT9, MAJOR11, DOMINANT13,
        )

        fun byId(id: String): ChordType? = BUILT_IN.firstOrNull { it.id == id }

        /** Build a custom chord type from semitone offsets above the root. */
        fun custom(name: String, semitones: List<Int>): ChordType {
            val semis = (listOf(0) + semitones).distinct().sorted()
            val steps = semis.map { s ->
                val within = ((s % 12) + 12) % 12
                val octave = Math.floorDiv(s, 12)
                NATURAL_STEP[within] + octave * 7
            }
            return ChordType("custom_" + semis.joinToString("_"), name, "custom", semis, steps)
        }

        private val NATURAL_STEP = intArrayOf(0, 0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6)
    }
}

/**
 * A chord: a spelled root plus a type, optionally with a specified bass note for
 * inversions (a slash chord).
 */
@Serializable
data class Chord(
    val root: SpelledPitch,
    val type: ChordType,
    /** Pitch class of the desired bass note, for inversions. Null means root position. */
    val bassPitchClass: Int? = null,
) {
    val rootPitchClass: Int get() = ((root.midi % 12) + 12) % 12

    /** Chord symbol, e.g. "Am7", "F#maj7", "C/G". */
    val symbol: String
        get() {
            val base = "${root.letter}${root.accidentalText}${type.symbol}"
            val bass = bassPitchClass
            return if (bass != null && bass != rootPitchClass) {
                "$base/${Pitch.noteName(bass, preferFlats = root.alter < 0)}"
            } else {
                base
            }
        }

    val displayName: String get() = "$symbol — ${type.displayName}"

    /** Pitch classes in the chord. */
    val pitchClasses: Set<Int> get() = type.pitchClasses(rootPitchClass)

    /** Spelled chord tones, ascending from the root. */
    val spelledTones: List<SpelledPitch>
        get() = type.semitones.indices.map { i ->
            val stepIndex = root.diatonicIndex + type.diatonicSteps[i]
            val letter = NoteLetter.ofStep(stepIndex)
            val targetMidi = root.midi + type.semitones[i]
            val naturalMidi = (Math.floorDiv(stepIndex, 7) + 1) * 12 + letter.semitonesFromC
            SpelledPitch(letter, targetMidi - naturalMidi, Math.floorDiv(stepIndex, 7))
        }

    /** Note names of the chord tones, e.g. "A C E G". */
    val toneNames: String get() = spelledTones.joinToString(" ") { "${it.letter}${it.accidentalText}" }

    /** Which chord degree (index into [ChordType.semitones]) a pitch belongs to, or null. */
    fun degreeOf(midi: Int): Int? {
        val offset = ((Pitch.pitchClass(midi) - rootPitchClass) % 12 + 12) % 12
        return type.semitones.indexOfFirst { ((it % 12) + 12) % 12 == offset }.takeIf { it >= 0 }
    }

    fun contains(midi: Int): Boolean = Pitch.pitchClass(midi) in pitchClasses

    /** The same chord with the bass set to chord degree [degree] (1 == first inversion). */
    fun inversion(degree: Int): Chord {
        if (degree <= 0) return copy(bassPitchClass = null)
        val idx = degree.coerceAtMost(type.size - 1)
        val pc = ((rootPitchClass + type.semitones[idx]) % 12 + 12) % 12
        return copy(bassPitchClass = pc)
    }

    companion object {
        fun of(rootName: String, type: ChordType): Chord? {
            val letter = rootName.firstOrNull()?.uppercaseChar()?.toString() ?: return null
            val nl = runCatching { NoteLetter.valueOf(letter) }.getOrNull() ?: return null
            val midi = Pitch.parse(rootName + "3") ?: return null
            val natural = 4 * 12 + nl.semitonesFromC
            return Chord(SpelledPitch(nl, midi - natural, 3), type)
        }
    }
}

/**
 * A concrete fretboard voicing of a chord: one entry per string, from the lowest string
 * upwards. A null entry means the string is not played.
 */
@Serializable
data class ChordVoicing(
    /** Fret per string, lowest string first; null == muted. */
    val frets: List<Int?>,
    /** Fingers per string, 0 == open, 1..4 == fingers, null == muted. Advisory. */
    val fingers: List<Int?> = emptyList(),
    /** Lowest fretted fret of the shape, 0 when the shape uses only open strings. */
    val position: Int = frets.filterNotNull().filter { it > 0 }.minOrNull() ?: 0,
    /** Difficulty estimate, lower is easier. */
    val difficulty: Double = 0.0,
) {
    val playedStringCount: Int get() = frets.count { it != null }

    /** Fret span of the fretted notes. */
    val span: Int
        get() {
            val fretted = frets.filterNotNull().filter { it > 0 }
            return if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
        }

    /**
     * Whether the shape needs a barre: two or more *adjacent* strings stopped at the
     * lowest fretted fret. Adjacency matters — D major (xx0232) has two notes at the
     * second fret but they are a string apart, so they take two fingers, not a barre.
     */
    val isBarre: Boolean
        get() {
            val fretted = frets.withIndex().filter { it.value != null && it.value!! > 0 }
            if (fretted.size < 2) return false
            val min = fretted.minOf { it.value!! }
            val atMin = fretted.filter { it.value == min }.map { it.index }
            return atMin.zipWithNext().any { (a, b) -> b - a == 1 }
        }

    /** Sounding pitches of this voicing on [board], low to high. */
    fun pitches(board: Fretboard): List<Int> =
        frets.mapIndexedNotNull { s, f ->
            if (f == null) null else board.pitchAtOrNull(s, f)
        }

    /** Tab-style text, highest string first, e.g. "x 0 2 2 1 0". */
    fun tabString(): String = frets.reversed().joinToString(" ") { it?.toString() ?: "x" }

    companion object {
        val EMPTY = ChordVoicing(emptyList())
    }
}

/**
 * Builds and ranks playable chord voicings on an arbitrary fretboard.
 *
 * The search is exhaustive within a sliding fret window, then scored for musical and
 * physical plausibility, so it works for 7- and 8-string guitars, basses and any
 * custom tuning rather than relying on a table of standard six-string shapes.
 */
object ChordVoicer {

    /** Maximum comfortable fret span of a shape, excluding open strings. */
    const val MAX_SPAN = 4

    /**
     * Generate voicings for [chord] on [board], best first.
     *
     * @param maxResults how many voicings to return.
     * @param minStrings fewest strings a voicing may use.
     * @param requireRootInBass when true, only voicings whose lowest note is the root
     *   (or the requested inversion bass) are returned.
     */
    fun voicings(
        chord: Chord,
        board: Fretboard,
        maxResults: Int = 8,
        minStrings: Int = minOf(3, board.stringCount),
        requireRootInBass: Boolean = false,
        maxPosition: Int = board.fretCount,
    ): List<ChordVoicing> {
        val pcs = chord.pitchClasses
        val required = requiredPitchClasses(chord)
        val results = ArrayList<ChordVoicing>()

        for (windowStart in 0..maxPosition) {
            val windowEnd = minOf(windowStart + MAX_SPAN, board.fretCount)
            // Candidate frets per string: open string if it fits, plus anything in the window.
            val candidates = (0 until board.stringCount).map { s ->
                val list = ArrayList<Int?>(MAX_SPAN + 3)
                list += null
                val open = board.tuning.openStrings[s]
                if (Pitch.pitchClass(open) in pcs) list += 0
                for (f in maxOf(windowStart, 1)..windowEnd) {
                    if (Pitch.pitchClass(open + f) in pcs) list += f
                }
                list
            }
            search(board, chord, candidates, required, minStrings, requireRootInBass, results)
            if (results.size > 4000) break
        }

        return results
            .distinctBy { it.frets }
            .map { it.copy(difficulty = difficulty(it)) }
            .sortedBy { score(it, board, chord) }
            .take(maxResults)
    }

    /**
     * The single best voicing, or null when the chord cannot be played on [board].
     * Prefers shapes near [nearFret] so that a progression keeps the hand in one place.
     */
    fun bestVoicing(chord: Chord, board: Fretboard, nearFret: Int? = null): ChordVoicing? {
        val all = voicings(chord, board, maxResults = 24)
        if (all.isEmpty()) return null
        if (nearFret == null) return all.first()
        return all.minByOrNull { it.difficulty + abs(it.position - nearFret) * 1.5 }
    }

    /** Pitch classes a voicing must contain: root, third-or-equivalent and the characteristic tones. */
    private fun requiredPitchClasses(chord: Chord): Set<Int> {
        val pc = chord.rootPitchClass
        val out = HashSet<Int>()
        chord.type.semitones.forEachIndexed { i, s ->
            if (i !in chord.type.optionalDegrees) out += ((pc + s) % 12 + 12) % 12
        }
        return out
    }

    private fun search(
        board: Fretboard,
        chord: Chord,
        candidates: List<List<Int?>>,
        required: Set<Int>,
        minStrings: Int,
        requireRootInBass: Boolean,
        out: MutableList<ChordVoicing>,
    ) {
        val n = board.stringCount
        val current = arrayOfNulls<Int>(n)
        val desiredBass = chord.bassPitchClass ?: chord.rootPitchClass

        fun recurse(s: Int, played: Int, fretted: Int) {
            if (out.size > 4000) return
            if (s == n) {
                if (played < minStrings) return
                // Played strings must be contiguous, allowing at most one internal gap.
                val idx = (0 until n).filter { current[it] != null }
                if (idx.isEmpty()) return
                var gaps = 0
                for (i in idx.first()..idx.last()) if (current[i] == null) gaps++
                if (gaps > 1) return
                val pitches = idx.map { board.pitchAt(it, current[it]!!) }
                val classes = pitches.map { Pitch.pitchClass(it) }.toSet()
                if (!classes.containsAll(required)) return
                val bass = Pitch.pitchClass(pitches.first())
                if (requireRootInBass && bass != desiredBass) return
                out += ChordVoicing(current.toList(), fingersFor(current.toList()))
                return
            }
            for (f in candidates[s]) {
                if (f != null && f > 0) {
                    // Keep the span of the fretted notes within reach.
                    val fretsSoFar = (0 until s).mapNotNull { current[it] }.filter { it > 0 }
                    if (fretsSoFar.isNotEmpty()) {
                        val lo = minOf(fretsSoFar.min(), f)
                        val hi = maxOf(fretsSoFar.max(), f)
                        if (hi - lo > MAX_SPAN) continue
                    }
                    if (fretted >= 6) continue
                }
                current[s] = f
                recurse(s + 1, played + if (f != null) 1 else 0, fretted + if (f != null && f > 0) 1 else 0)
                current[s] = null
            }
        }
        recurse(0, 0, 0)
    }

    /** Assign plausible finger numbers, treating the lowest fretted fret as a possible barre. */
    private fun fingersFor(frets: List<Int?>): List<Int?> {
        val fretted = frets.withIndex().filter { it.value != null && it.value!! > 0 }
        if (fretted.isEmpty()) return frets.map { if (it == null) null else 0 }
        val minFret = fretted.minOf { it.value!! }
        val distinct = fretted.map { it.value!! }.distinct().sorted()
        val fingerForFret = HashMap<Int, Int>()
        var finger = 1
        for (f in distinct) {
            fingerForFret[f] = finger.coerceAtMost(4)
            finger++
        }
        // A barre at the lowest fret uses one finger for all of those strings.
        return frets.mapIndexed { _, f ->
            when {
                f == null -> null
                f == 0 -> 0
                f == minFret -> 1
                else -> fingerForFret[f] ?: 4
            }
        }
    }

    /** Physical difficulty estimate: fret span, finger count, barre and stretch. */
    fun difficulty(v: ChordVoicing): Double {
        val fretted = v.frets.filterNotNull().filter { it > 0 }
        if (fretted.isEmpty()) return 0.0
        val distinctFrets = fretted.distinct().size
        var d = v.span * 1.6 + distinctFrets * 1.0
        if (v.isBarre) d += 1.0
        // High positions are a little harder to reach cleanly.
        d += v.position * 0.08
        // Internal muted strings need deliberate damping.
        val idx = v.frets.indices.filter { v.frets[it] != null }
        if (idx.isNotEmpty()) {
            for (i in idx.first()..idx.last()) if (v.frets[i] == null) d += 1.5
        }
        return d
    }

    /** Lower is better: fullness, root in bass, playability, low position. */
    private fun score(v: ChordVoicing, board: Fretboard, chord: Chord): Double {
        val pitches = v.pitches(board)
        if (pitches.isEmpty()) return Double.MAX_VALUE
        var s = difficulty(v)
        // Prefer more strings sounding.
        s -= v.playedStringCount * 1.2
        // Strongly prefer the intended bass note.
        val desiredBass = chord.bassPitchClass ?: chord.rootPitchClass
        if (Pitch.pitchClass(pitches.first()) != desiredBass) s += 4.0
        // Prefer all chord tones present.
        val missing = chord.pitchClasses.size - pitches.map { Pitch.pitchClass(it) }.toSet().size
        s += missing.coerceAtLeast(0) * 2.0
        // Prefer open-position shapes.
        s += v.position * 0.25
        return s
    }
}

/**
 * Diatonic harmony: the chords a key generates, used for the chord suggestions panel.
 */
object DiatonicChords {

    /**
     * The triad built on each degree of [key], 0-based. Only defined for seven-note
     * scales; other scales fall back to their parent major or minor.
     */
    fun triads(key: Key): List<Chord> = chordsOf(key, seventh = false)

    /** The seventh chord on each degree of [key]. */
    fun sevenths(key: Key): List<Chord> = chordsOf(key, seventh = true)

    private fun chordsOf(key: Key, seventh: Boolean): List<Chord> {
        val base = parentHeptatonic(key)
        val n = base.scale.size
        return (0 until n).map { degree ->
            val root = base.degreeSpelling(degree)
            val third = base.degreeSpelling(degree + 2)
            val fifth = base.degreeSpelling(degree + 4)
            val thirdSemis = third.midi - root.midi
            val fifthSemis = fifth.midi - root.midi
            val type = if (!seventh) {
                triadType(thirdSemis, fifthSemis)
            } else {
                val sev = base.degreeSpelling(degree + 6)
                seventhType(thirdSemis, fifthSemis, sev.midi - root.midi)
            }
            Chord(SpelledPitch(root.letter, root.alter, 3), type)
        }
    }

    /** Roman-numeral labels for the diatonic chords of [key]. */
    fun romanNumerals(key: Key, seventh: Boolean = false): List<String> {
        val chords = if (seventh) sevenths(key) else triads(key)
        val numerals = listOf("I", "II", "III", "IV", "V", "VI", "VII")
        return chords.mapIndexed { i, c ->
            val n = numerals[i % 7]
            when (c.type.id) {
                "minor", "m7" -> n.lowercase()
                "dim", "m7b5", "dim7" -> n.lowercase() + "°"
                "aug" -> n + "+"
                "7" -> n + "7"
                "maj7" -> n + "maj7"
                else -> n
            } + if (seventh && c.type.id == "m7") "7" else ""
        }
    }

    /** Map a non-heptatonic key onto the heptatonic scale it borrows its chords from. */
    private fun parentHeptatonic(key: Key): Key = when (key.scale.id) {
        "major_pentatonic", "major_blues" -> key.copy(scale = ScaleType.MAJOR)
        "minor_pentatonic", "blues" -> key.copy(scale = ScaleType.NATURAL_MINOR)
        "chromatic", "whole_tone" -> key.copy(scale = ScaleType.MAJOR)
        else -> if (key.scale.isHeptatonic) key else key.copy(scale = ScaleType.MAJOR)
    }

    private fun triadType(third: Int, fifth: Int): ChordType = when {
        third == 4 && fifth == 7 -> ChordType.MAJOR
        third == 3 && fifth == 7 -> ChordType.MINOR
        third == 3 && fifth == 6 -> ChordType.DIMINISHED
        third == 4 && fifth == 8 -> ChordType.AUGMENTED
        third == 2 && fifth == 7 -> ChordType.SUS2
        third == 5 && fifth == 7 -> ChordType.SUS4
        else -> ChordType.MAJOR
    }

    private fun seventhType(third: Int, fifth: Int, seventh: Int): ChordType = when {
        third == 4 && fifth == 7 && seventh == 11 -> ChordType.MAJOR7
        third == 4 && fifth == 7 && seventh == 10 -> ChordType.DOMINANT7
        third == 3 && fifth == 7 && seventh == 10 -> ChordType.MINOR7
        third == 3 && fifth == 7 && seventh == 11 -> ChordType.MINOR_MAJOR7
        third == 3 && fifth == 6 && seventh == 10 -> ChordType.HALF_DIMINISHED
        third == 3 && fifth == 6 && seventh == 9 -> ChordType.DIMINISHED7
        third == 4 && fifth == 8 && seventh == 10 -> ChordType.SEVEN_SHARP5
        else -> ChordType.MAJOR7
    }

    /** Common progressions in [key], expressed as 0-based degree indices. */
    val COMMON_PROGRESSIONS: List<Pair<String, List<Int>>> = listOf(
        "I - V - vi - IV" to listOf(0, 4, 5, 3),
        "I - IV - V" to listOf(0, 3, 4),
        "ii - V - I" to listOf(1, 4, 0),
        "I - vi - IV - V" to listOf(0, 5, 3, 4),
        "i - VI - III - VII" to listOf(0, 5, 2, 6),
        "i - iv - v" to listOf(0, 3, 4),
        "I - IV - vi - V" to listOf(0, 3, 5, 4),
        "12-bar blues" to listOf(0, 0, 0, 0, 3, 3, 0, 0, 4, 3, 0, 4),
    )
}

/**
 * Chord recognition: name the chord formed by a set of sounding pitches.
 * Used to label vertical stacks in the tab and score views.
 */
object ChordIdentifier {

    /**
     * Best-matching chord for [pitches], or null when fewer than two distinct pitch
     * classes are present. The lowest sounding pitch is preferred as the root, then
     * every rotation is tried and scored on how exactly it matches a known type.
     */
    fun identify(pitches: List<Int>, key: Key? = null): Chord? {
        if (pitches.isEmpty()) return null
        val classes = pitches.map { Pitch.pitchClass(it) }.distinct()
        if (classes.size < 2) return null
        val bass = Pitch.pitchClass(pitches.min())
        var best: Chord? = null
        var bestScore = Double.MAX_VALUE
        for (rootPc in 0..11) {
            for (type in ChordType.BUILT_IN) {
                val typeClasses = type.pitchClasses(rootPc)
                val missing = typeClasses.count { it !in classes }
                val extra = classes.count { it !in typeClasses }
                if (missing > 1 || extra > 0) continue
                var sc = missing * 3.0 + extra * 4.0 + type.size * 0.1
                if (rootPc != bass) sc += 1.5
                if (key != null && rootPc !in key.pitchClasses) sc += 1.0
                if (sc < bestScore) {
                    bestScore = sc
                    val preferFlats = (key?.sharpsInSignature ?: 0) < 0
                    val spelled = key?.spell(rootPc + 48) ?: SpelledPitch.of(rootPc + 48, preferFlats)
                    best = Chord(
                        SpelledPitch(spelled.letter, spelled.alter, 3),
                        type,
                        bassPitchClass = if (bass != rootPc) bass else null,
                    )
                }
            }
        }
        return best
    }
}
