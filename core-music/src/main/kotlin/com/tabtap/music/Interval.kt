package com.tabtap.music

import kotlinx.serialization.Serializable

import kotlin.math.abs

/**
 * Interval quality, used for naming and for diatonic arithmetic.
 */
@Serializable
enum class IntervalQuality(val symbol: String) {
    DIMINISHED("d"), MINOR("m"), PERFECT("P"), MAJOR("M"), AUGMENTED("A");
}

/**
 * A named musical interval.
 *
 * [diatonicSteps] is 0-based (0 == unison, 2 == third, 4 == fifth) and
 * [semitones] is the chromatic size. Keeping both means the engine can express
 * e.g. an augmented second (3 semitones) distinctly from a minor third (also 3
 * semitones) — which is what makes diatonic harmony spell correctly.
 */
@Serializable
data class Interval(val diatonicSteps: Int, val semitones: Int) {

    /** Conventional interval number: unison == 1, third == 3. */
    val number: Int get() = abs(diatonicSteps) + 1

    val quality: IntervalQuality
        get() {
            val steps = ((diatonicSteps % 7) + 7) % 7
            val octaves = Math.floorDiv(diatonicSteps, 7)
            val chroma = semitones - octaves * 12
            val perfectish = steps == 0 || steps == 3 || steps == 4
            val natural = DIATONIC_SEMITONES[steps]
            return when (chroma - natural) {
                0 -> if (perfectish) IntervalQuality.PERFECT else IntervalQuality.MAJOR
                -1 -> if (perfectish) IntervalQuality.DIMINISHED else IntervalQuality.MINOR
                -2 -> IntervalQuality.DIMINISHED
                1 -> IntervalQuality.AUGMENTED
                else -> if (chroma - natural > 0) IntervalQuality.AUGMENTED else IntervalQuality.DIMINISHED
            }
        }

    /** Short name such as "m3", "P5", "M9", "A4". */
    val shortName: String get() = quality.symbol + number

    /** Full name such as "minor third". */
    val fullName: String
        get() {
            val q = when (quality) {
                IntervalQuality.DIMINISHED -> "diminished"
                IntervalQuality.MINOR -> "minor"
                IntervalQuality.PERFECT -> "perfect"
                IntervalQuality.MAJOR -> "major"
                IntervalQuality.AUGMENTED -> "augmented"
            }
            return "$q ${ordinalName(number)}"
        }

    /** Invert the direction of this interval. */
    operator fun unaryMinus(): Interval = Interval(-diatonicSteps, -semitones)

    companion object {
        /** Semitone size of each natural diatonic interval from the tonic of a major scale. */
        val DIATONIC_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        val UNISON = Interval(0, 0)
        val MINOR_SECOND = Interval(1, 1)
        val MAJOR_SECOND = Interval(1, 2)
        val MINOR_THIRD = Interval(2, 3)
        val MAJOR_THIRD = Interval(2, 4)
        val PERFECT_FOURTH = Interval(3, 5)
        val TRITONE = Interval(3, 6)
        val PERFECT_FIFTH = Interval(4, 7)
        val MINOR_SIXTH = Interval(5, 8)
        val MAJOR_SIXTH = Interval(5, 9)
        val MINOR_SEVENTH = Interval(6, 10)
        val MAJOR_SEVENTH = Interval(6, 11)
        val OCTAVE = Interval(7, 12)

        /** Classify the interval between two spelled pitches. */
        fun between(low: SpelledPitch, high: SpelledPitch): Interval =
            Interval(high.diatonicIndex - low.diatonicIndex, high.midi - low.midi)

        /**
         * Name the chromatic interval between two MIDI pitches, ignoring spelling.
         * Used for the simple "interval from root" readout on the fretboard.
         */
        fun simple(semitones: Int): Interval {
            val s = ((semitones % 12) + 12) % 12
            return when (s) {
                0 -> UNISON; 1 -> MINOR_SECOND; 2 -> MAJOR_SECOND; 3 -> MINOR_THIRD
                4 -> MAJOR_THIRD; 5 -> PERFECT_FOURTH; 6 -> TRITONE; 7 -> PERFECT_FIFTH
                8 -> MINOR_SIXTH; 9 -> MAJOR_SIXTH; 10 -> MINOR_SEVENTH; else -> MAJOR_SEVENTH
            }
        }

        private fun ordinalName(n: Int): String = when (n) {
            1 -> "unison"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"
            6 -> "sixth"; 7 -> "seventh"; 8 -> "octave"; 9 -> "ninth"; 10 -> "tenth"
            11 -> "eleventh"; 12 -> "twelfth"; 13 -> "thirteenth"; else -> "${n}th"
        }
    }
}

/** Short labels for the 12 chromatic intervals above a root, for fretboard overlays. */
object IntervalLabels {
    private val LABELS = arrayOf("R", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7")

    /** Label for a pitch [midi] relative to [rootPitchClass]. */
    fun label(midi: Int, rootPitchClass: Int): String =
        LABELS[((Pitch.pitchClass(midi) - rootPitchClass) % 12 + 12) % 12]
}
