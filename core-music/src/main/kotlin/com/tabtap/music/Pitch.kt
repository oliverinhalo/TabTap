package com.tabtap.music

import kotlinx.serialization.Serializable

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pitch utilities.
 *
 * Pitch is represented throughout TabTap as an integer MIDI note number using the
 * scientific-pitch convention where MIDI 60 == C4 == middle C. This is the single
 * canonical pitch representation: the fretboard, tab, piano roll, score, audio engine
 * and MIDI exporter all derive from it, so no representation can drift from another.
 */
object Pitch {

    /** MIDI note number of middle C (C4). */
    const val MIDDLE_C = 60

    /** MIDI note number of A4, the tuning reference. */
    const val A4 = 69

    /** Lowest and highest representable MIDI note numbers. */
    const val MIN = 0
    const val MAX = 127

    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Pitch class 0..11 of [midi] where 0 == C. */
    fun pitchClass(midi: Int): Int = ((midi % 12) + 12) % 12

    /** Scientific octave number of [midi]; C4 (60) is octave 4. */
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    /**
     * Note name without octave, e.g. "F#" or "Bb".
     * @param preferFlats spell black keys as flats rather than sharps.
     */
    fun noteName(midi: Int, preferFlats: Boolean = false): String =
        if (preferFlats) FLAT_NAMES[pitchClass(midi)] else SHARP_NAMES[pitchClass(midi)]

    /** Note name with scientific octave, e.g. "F#3". */
    fun fullName(midi: Int, preferFlats: Boolean = false): String =
        noteName(midi, preferFlats) + octave(midi)

    /**
     * Equal-tempered frequency in Hz.
     * @param a4 reference frequency for A4 (default 440 Hz concert pitch).
     */
    fun frequency(midi: Int, a4: Double = 440.0): Double =
        a4 * 2.0.pow((midi - A4) / 12.0)

    /** Nearest MIDI note number to [hz]. */
    fun fromFrequency(hz: Double, a4: Double = 440.0): Int {
        require(hz > 0.0) { "Frequency must be positive, was $hz" }
        return (A4 + 12.0 * log2(hz / a4)).roundToInt()
    }

    /** Signed deviation in cents of [hz] from the nearest equal-tempered semitone. */
    fun centsFromNearest(hz: Double, a4: Double = 440.0): Double {
        val exact = A4 + 12.0 * log2(hz / a4)
        return (exact - exact.roundToInt()) * 100.0
    }

    /** Clamp [midi] into the legal MIDI range. */
    fun clamp(midi: Int): Int = midi.coerceIn(MIN, MAX)

    /** True when [midi] is representable in a MIDI file. */
    fun isValid(midi: Int): Boolean = midi in MIN..MAX

    /**
     * Parse a note name such as "E2", "F#3", "Bb-1", "Gbb4", "A##2" into a MIDI number.
     * Returns null when the text is not a valid pitch.
     */
    fun parse(text: String): Int? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val letter = s[0].uppercaseChar()
        val base = when (letter) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> return null
        }
        var i = 1
        var alter = 0
        while (i < s.length && (s[i] == '#' || s[i] == 'b' || s[i] == 'B' || s[i] == '♯' || s[i] == '♭')) {
            alter += if (s[i] == '#' || s[i] == '♯') 1 else -1
            i++
        }
        val octText = s.substring(i).trim()
        // Bare note name with no octave defaults to octave 4.
        val oct = if (octText.isEmpty()) 4 else octText.toIntOrNull() ?: return null
        val midi = (oct + 1) * 12 + base + alter
        return if (isValid(midi)) midi else null
    }

    /** Semitone distance between two pitches, always non-negative. */
    fun distance(a: Int, b: Int): Int = abs(a - b)
}

/** The seven diatonic letters, in order from C. */
@Serializable
enum class NoteLetter(val semitonesFromC: Int) {
    C(0), D(2), E(4), F(5), G(7), A(9), B(11);

    /** 0-based diatonic step index, C == 0 ... B == 6. */
    val step: Int get() = ordinal

    companion object {
        fun ofStep(step: Int): NoteLetter = entries[((step % 7) + 7) % 7]
    }
}

/**
 * A fully spelled pitch: letter, chromatic alteration and octave.
 *
 * Spelling matters for notation and MusicXML: F# and Gb are the same MIDI number but
 * must be engraved differently. Harmony generation and key signatures work in spelled
 * space so that accidentals come out correct.
 */
@Serializable
data class SpelledPitch(val letter: NoteLetter, val alter: Int, val octave: Int) {

    /** The MIDI note number this spelling sounds as. */
    val midi: Int get() = (octave + 1) * 12 + letter.semitonesFromC + alter

    /**
     * Absolute diatonic step number: counts letters across octaves, so C4 == 28.
     * Used by the engraver to place a notehead on a staff line or space.
     */
    val diatonicIndex: Int get() = octave * 7 + letter.step

    val accidentalText: String
        get() = when {
            alter > 0 -> "#".repeat(alter)
            alter < 0 -> "b".repeat(-alter)
            else -> ""
        }

    override fun toString(): String = "${letter.name}$accidentalText$octave"

    companion object {
        /** Build a spelling from an absolute diatonic index and an alteration. */
        fun fromDiatonicIndex(index: Int, alter: Int): SpelledPitch =
            SpelledPitch(NoteLetter.ofStep(index), alter, Math.floorDiv(index, 7))

        /**
         * Spell [midi] naively, using sharps or flats.
         * Prefer [Key.spell] when a key context is available: it produces correct
         * key-signature-aware accidentals.
         */
        fun of(midi: Int, preferFlats: Boolean = false): SpelledPitch {
            val pc = Pitch.pitchClass(midi)
            val oct = Pitch.octave(midi)
            val naturals = intArrayOf(0, 2, 4, 5, 7, 9, 11)
            val idx = naturals.indexOf(pc)
            if (idx >= 0) return SpelledPitch(NoteLetter.entries[idx], 0, oct)
            return if (preferFlats) {
                // Black key spelled as the flat of the natural above.
                val above = naturals.indexOfFirst { it == pc + 1 }
                SpelledPitch(NoteLetter.entries[above], -1, oct)
            } else {
                val below = naturals.indexOfFirst { it == pc - 1 }
                SpelledPitch(NoteLetter.entries[below], 1, oct)
            }
        }
    }
}
