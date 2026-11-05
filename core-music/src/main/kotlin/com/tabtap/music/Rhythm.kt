package com.tabtap.music

import kotlinx.serialization.Serializable

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Musical time in TabTap is measured in integer ticks with a fixed resolution of
 * [Ticks.PPQ] ticks per quarter note. Integer ticks mean no accumulating rounding
 * error: a bar of 4/4 is always exactly 3840 ticks, and MIDI export is exact.
 *
 * 960 ticks per quarter divides cleanly by 2, 3, 4, 5, 6, 8, 16 and 32, so straight
 * subdivisions down to 1/32, triplets, quintuplets and septuplets are all exact.
 */
object Ticks {
    /** Ticks per quarter note. */
    const val PPQ = 960

    const val WHOLE = PPQ * 4
    const val HALF = PPQ * 2
    const val QUARTER = PPQ
    const val EIGHTH = PPQ / 2
    const val SIXTEENTH = PPQ / 4
    const val THIRTY_SECOND = PPQ / 8
    const val SIXTY_FOURTH = PPQ / 16

    /** Ticks in one quarter-note triplet unit (three per half note). */
    const val TRIPLET_QUARTER = PPQ * 2 / 3
    const val TRIPLET_EIGHTH = PPQ / 3
    const val TRIPLET_SIXTEENTH = PPQ / 6

    /** Convert ticks to seconds at [bpm] quarter notes per minute. */
    fun toSeconds(ticks: Long, bpm: Double): Double = ticks / PPQ.toDouble() * 60.0 / bpm

    /** Convert seconds to ticks at [bpm]. */
    fun fromSeconds(seconds: Double, bpm: Double): Long =
        (seconds * bpm / 60.0 * PPQ).roundToLong()

    /** Convert ticks to audio frames at [bpm] and [sampleRate]. */
    fun toFrames(ticks: Long, bpm: Double, sampleRate: Int): Long =
        (toSeconds(ticks, bpm) * sampleRate).roundToLong()
}

/**
 * A written note value: a power-of-two base, optional dots, optional tuplet ratio.
 *
 * The engraver needs this; the sequencer only needs ticks. [NoteValue.forTicks]
 * recovers the best written value for an arbitrary tick duration so that notes entered
 * by tapping still engrave sensibly.
 */
@Serializable
data class NoteValue(
    /** 0 == whole, 1 == half, 2 == quarter, 3 == eighth, 4 == 16th, 5 == 32nd, 6 == 64th. */
    val divisionLevel: Int,
    val dots: Int = 0,
    /** Tuplet numerator — 3 for a triplet (3 in the time of 2). */
    val tupletNum: Int = 1,
    /** Tuplet denominator — 2 for a triplet. */
    val tupletDen: Int = 1,
) {
    init {
        require(divisionLevel in 0..6) { "Unsupported division level $divisionLevel" }
        require(dots in 0..3) { "Unsupported dot count $dots" }
        require(tupletNum >= 1 && tupletDen >= 1) { "Bad tuplet $tupletNum:$tupletDen" }
    }

    /** Duration in ticks. */
    val ticks: Int
        get() {
            val base = Ticks.WHOLE shr divisionLevel
            // Dots add half, then a quarter, then an eighth of the base.
            var total = base
            var add = base
            repeat(dots) { add /= 2; total += add }
            return total * tupletDen / tupletNum
        }

    val isTuplet: Boolean get() = tupletNum != tupletDen

    /** Number of flags/beams this value carries; 0 for quarter and longer. */
    val beamCount: Int get() = (divisionLevel - 2).coerceAtLeast(0)

    /** True when the notehead is filled (quarter and shorter). */
    val filled: Boolean get() = divisionLevel >= 2

    /** True when the note has a stem (half and shorter). */
    val hasStem: Boolean get() = divisionLevel >= 1

    val displayName: String
        get() {
            val base = when (divisionLevel) {
                0 -> "Whole"; 1 -> "Half"; 2 -> "Quarter"; 3 -> "8th"
                4 -> "16th"; 5 -> "32nd"; else -> "64th"
            }
            val d = when (dots) { 0 -> ""; 1 -> " dotted"; 2 -> " double-dotted"; else -> " triple-dotted" }
            val t = if (isTuplet) " ($tupletNum:$tupletDen)" else ""
            return base + d + t
        }

    companion object {
        val WHOLE = NoteValue(0)
        val HALF = NoteValue(1)
        val QUARTER = NoteValue(2)
        val EIGHTH = NoteValue(3)
        val SIXTEENTH = NoteValue(4)
        val THIRTY_SECOND = NoteValue(5)
        val SIXTY_FOURTH = NoteValue(6)

        val DOTTED_HALF = NoteValue(1, 1)
        val DOTTED_QUARTER = NoteValue(2, 1)
        val DOTTED_EIGHTH = NoteValue(3, 1)
        val DOTTED_SIXTEENTH = NoteValue(4, 1)

        val TRIPLET_QUARTER = NoteValue(2, 0, 3, 2)
        val TRIPLET_EIGHTH = NoteValue(3, 0, 3, 2)
        val TRIPLET_SIXTEENTH = NoteValue(4, 0, 3, 2)

        /** Values offered in the duration picker, longest first. */
        val PALETTE: List<NoteValue> = listOf(
            WHOLE, DOTTED_HALF, HALF, DOTTED_QUARTER, QUARTER, TRIPLET_QUARTER,
            DOTTED_EIGHTH, EIGHTH, TRIPLET_EIGHTH, DOTTED_SIXTEENTH, SIXTEENTH,
            TRIPLET_SIXTEENTH, THIRTY_SECOND, SIXTY_FOURTH,
        )

        /** Every value the engraver may use when splitting an arbitrary duration. */
        private val ENGRAVABLE: List<NoteValue> by lazy {
            val out = ArrayList<NoteValue>()
            for (level in 0..6) {
                for (dots in 0..2) out += NoteValue(level, dots)
                out += NoteValue(level, 0, 3, 2)
            }
            out.sortedByDescending { it.ticks }
        }

        /** The written value closest to [ticks]. Never returns null. */
        fun forTicks(ticks: Int): NoteValue =
            ENGRAVABLE.minByOrNull { abs(it.ticks - ticks) } ?: QUARTER

        /** The written value exactly equal to [ticks], or null if none matches. */
        fun exactForTicks(ticks: Int): NoteValue? = ENGRAVABLE.firstOrNull { it.ticks == ticks }

        /**
         * Decompose [ticks] into a sequence of written values that sum exactly to it,
         * longest first. Used to engrave an arbitrary duration as a note plus ties, and
         * to engrave rests of arbitrary length.
         */
        fun decompose(ticks: Int): List<NoteValue> {
            if (ticks <= 0) return emptyList()
            val out = ArrayList<NoteValue>()
            var remaining = ticks
            var guard = 0
            while (remaining > 0 && guard++ < 64) {
                val fit = ENGRAVABLE.firstOrNull { it.ticks <= remaining && !it.isTuplet }
                if (fit == null) {
                    // Shorter than a 64th: absorb into the last value rather than loop.
                    if (out.isEmpty()) out += SIXTY_FOURTH
                    break
                }
                out += fit
                remaining -= fit.ticks
            }
            return out
        }
    }
}

/**
 * A time signature. [beats] is the numerator, [beatValue] the denominator (4 == quarter).
 */
@Serializable
data class TimeSignature(val beats: Int, val beatValue: Int) {
    init {
        require(beats in 1..32) { "Beats $beats out of range" }
        require(beatValue in setOf(1, 2, 4, 8, 16, 32)) { "Beat value $beatValue unsupported" }
    }

    /** Ticks in one bar. */
    val barTicks: Int get() = Ticks.WHOLE * beats / beatValue

    /** Ticks in one notated beat (the denominator unit). */
    val beatTicks: Int get() = Ticks.WHOLE / beatValue

    /**
     * Ticks in one *felt* beat. Compound signatures (6/8, 9/8, 12/8) are felt in
     * dotted-quarter groups, so the metronome clicks three eighths at a time.
     */
    val pulseTicks: Int get() = if (isCompound) beatTicks * 3 else beatTicks

    /** Number of felt pulses per bar. */
    val pulsesPerBar: Int get() = barTicks / pulseTicks

    val isCompound: Boolean get() = beatValue == 8 && beats % 3 == 0 && beats > 3

    /**
     * Beaming groups within a bar, in ticks. 7/8 groups as 2+2+3 eighths, 5/4 as 3+2
     * quarters, compound time in dotted-quarter groups.
     */
    val beamGroups: List<Int>
        get() = when {
            isCompound -> List(beats / 3) { beatTicks * 3 }
            beatValue == 8 && beats == 5 -> listOf(beatTicks * 3, beatTicks * 2)
            beatValue == 8 && beats == 7 -> listOf(beatTicks * 2, beatTicks * 2, beatTicks * 3)
            beatValue == 8 -> List(beats) { beatTicks }
            beats == 5 -> listOf(beatTicks * 3, beatTicks * 2)
            beatValue == 4 -> List(beats) { beatTicks }
            else -> List(beats) { beatTicks }
        }

    override fun toString(): String = "$beats/$beatValue"

    companion object {
        val FOUR_FOUR = TimeSignature(4, 4)
        val THREE_FOUR = TimeSignature(3, 4)
        val TWO_FOUR = TimeSignature(2, 4)
        val FIVE_FOUR = TimeSignature(5, 4)
        val SIX_EIGHT = TimeSignature(6, 8)
        val SEVEN_EIGHT = TimeSignature(7, 8)
        val NINE_EIGHT = TimeSignature(9, 8)
        val TWELVE_EIGHT = TimeSignature(12, 8)

        val PRESETS: List<TimeSignature> = listOf(
            FOUR_FOUR, THREE_FOUR, TWO_FOUR, FIVE_FOUR, SIX_EIGHT, SEVEN_EIGHT,
            NINE_EIGHT, TWELVE_EIGHT, TimeSignature(6, 4), TimeSignature(11, 8),
        )

        fun parse(text: String): TimeSignature? {
            val parts = text.split('/')
            if (parts.size != 2) return null
            val b = parts[0].trim().toIntOrNull() ?: return null
            val v = parts[1].trim().toIntOrNull() ?: return null
            return runCatching { TimeSignature(b, v) }.getOrNull()
        }
    }
}

/** Quantisation grid resolution. */
@Serializable
enum class GridResolution(val displayName: String, val straightTicks: Int) {
    QUARTER("1/4", Ticks.QUARTER),
    EIGHTH("1/8", Ticks.EIGHTH),
    SIXTEENTH("1/16", Ticks.SIXTEENTH),
    THIRTY_SECOND("1/32", Ticks.THIRTY_SECOND);
}

/** How the grid is subdivided within a beat. */
@Serializable
enum class GridFeel(val displayName: String) {
    STRAIGHT("Straight"),
    TRIPLET("Triplet"),
    SWING("Swing"),
    SHUFFLE("Shuffle (hard swing)");
}

/**
 * A quantisation setting: grid size, feel, strength and an optional swing amount.
 *
 * [strength] of 1.0 snaps exactly to the grid; 0.0 leaves timing untouched; values in
 * between pull notes partway, which is how "humanised" timing is preserved while still
 * tightening a performance.
 */
@Serializable
data class Quantize(
    val resolution: GridResolution = GridResolution.SIXTEENTH,
    val feel: GridFeel = GridFeel.STRAIGHT,
    val strength: Double = 1.0,
    /** Swing ratio: 0.5 == even, 0.667 == triplet swing, 0.75 == hard shuffle. */
    val swingRatio: Double = 0.5,
    /** Also quantise note lengths to the grid. */
    val quantizeDurations: Boolean = false,
) {
    init {
        require(strength in 0.0..1.0) { "Strength $strength out of range" }
        require(swingRatio in 0.3..0.9) { "Swing ratio $swingRatio out of range" }
    }

    /** The effective swing ratio for this feel. */
    val effectiveSwing: Double
        get() = when (feel) {
            GridFeel.STRAIGHT, GridFeel.TRIPLET -> 0.5
            GridFeel.SWING -> swingRatio
            GridFeel.SHUFFLE -> maxOf(swingRatio, 0.7)
        }

    companion object {
        val OFF = Quantize(strength = 0.0)
    }
}

/**
 * Quantisation and rhythm maths. Pure functions over ticks so they can be unit tested
 * without any Android dependency.
 */
object RhythmEngine {

    /**
     * The grid line positions within one beat, as tick offsets from the beat start.
     * Straight grids divide evenly; triplet grids divide by three; swing grids offset
     * the off-beat positions by the swing ratio.
     */
    fun gridOffsetsWithinBeat(q: Quantize, beatTicks: Int): List<Int> {
        return when (q.feel) {
            GridFeel.TRIPLET -> {
                val divisions = (beatTicks / q.resolution.straightTicks) * 3 / 2
                val n = divisions.coerceAtLeast(1)
                (0 until n).map { (it.toLong() * beatTicks / n).toInt() }
            }
            GridFeel.STRAIGHT -> {
                val n = (beatTicks / q.resolution.straightTicks).coerceAtLeast(1)
                (0 until n).map { it * q.resolution.straightTicks }
            }
            GridFeel.SWING, GridFeel.SHUFFLE -> {
                // Swing applies to pairs: the first of each pair is lengthened.
                val pairTicks = q.resolution.straightTicks * 2
                val pairs = (beatTicks / pairTicks).coerceAtLeast(1)
                val ratio = q.effectiveSwing
                val out = ArrayList<Int>(pairs * 2)
                for (p in 0 until pairs) {
                    val base = p * pairTicks
                    out += base
                    out += base + (pairTicks * ratio).toInt()
                }
                out
            }
        }
    }

    /**
     * All grid positions from 0 up to and including [lengthTicks], for a bar structure
     * of [timeSignature]. The returned list is strictly ascending.
     */
    fun gridPositions(q: Quantize, timeSignature: TimeSignature, lengthTicks: Long): LongArray {
        val beat = timeSignature.beatTicks
        val within = gridOffsetsWithinBeat(q, beat)
        val out = ArrayList<Long>()
        var beatStart = 0L
        while (beatStart <= lengthTicks) {
            for (o in within) {
                val t = beatStart + o
                if (t <= lengthTicks) out += t
            }
            beatStart += beat
        }
        out += lengthTicks
        return out.distinct().sorted().toLongArray()
    }

    /** Snap a single tick position to the nearest grid line, honouring [Quantize.strength]. */
    fun snap(tick: Long, q: Quantize, timeSignature: TimeSignature): Long {
        if (q.strength <= 0.0) return tick
        val beat = timeSignature.beatTicks.toLong()
        val beatIndex = Math.floorDiv(tick, beat)
        val within = tick - beatIndex * beat
        val offsets = gridOffsetsWithinBeat(q, beat.toInt())
        // Candidates include the first grid line of the next beat.
        var bestOffset = offsets.first().toLong()
        var bestDelta = Long.MAX_VALUE
        for (o in offsets) {
            val d = abs(within - o)
            if (d < bestDelta) { bestDelta = d; bestOffset = o.toLong() }
        }
        val nextBeat = beat - within
        if (nextBeat < bestDelta) { bestDelta = nextBeat; bestOffset = beat }
        val target = beatIndex * beat + bestOffset
        if (q.strength >= 1.0) return target
        return tick + ((target - tick) * q.strength).roundToLong()
    }

    /**
     * Quantise a list of start times, preserving order. Equal inputs stay equal, so
     * simultaneous notes of a chord remain simultaneous.
     */
    fun quantizeStarts(starts: List<Long>, q: Quantize, ts: TimeSignature): List<Long> =
        starts.map { snap(it, q, ts) }

    /**
     * Derive durations from consecutive onsets and a final duration, so that a tapped
     * rhythm becomes a legato sequence with no gaps.
     */
    fun durationsFromOnsets(onsets: List<Long>, tailTicks: Int): List<Int> {
        if (onsets.isEmpty()) return emptyList()
        return onsets.indices.map { i ->
            if (i == onsets.lastIndex) tailTicks
            else (onsets[i + 1] - onsets[i]).toInt().coerceAtLeast(1)
        }
    }

    /**
     * Convert a list of tap timestamps in milliseconds into a tempo in BPM, by averaging
     * the inter-tap intervals. Returns null when there are fewer than two taps or the
     * taps imply an implausible tempo.
     */
    fun tapTempo(tapsMs: List<Long>, maxTaps: Int = 8): Double? {
        if (tapsMs.size < 2) return null
        val recent = tapsMs.takeLast(maxTaps)
        val intervals = recent.zipWithNext { a, b -> (b - a).toDouble() }.filter { it in 150.0..3000.0 }
        if (intervals.isEmpty()) return null
        val mean = intervals.average()
        val bpm = 60_000.0 / mean
        return if (bpm in 20.0..400.0) bpm else null
    }

    /**
     * Apply a humanising jitter of up to [amountTicks] to each onset, deterministically
     * seeded so that re-rendering a project sounds the same every time.
     */
    fun humanize(starts: List<Long>, amountTicks: Int, seed: Long = 0L): List<Long> {
        if (amountTicks <= 0) return starts
        var state = seed * 6364136223846793005L + 1442695040888963407L
        return starts.map { s ->
            state = state * 6364136223846793005L + 1442695040888963407L
            val r = ((state ushr 33).toDouble() / (1L shl 31).toDouble()) * 2.0 - 1.0
            (s + (r * amountTicks).roundToLong()).coerceAtLeast(0L)
        }
    }

    /**
     * Lay a sequence of [count] notes onto a grid of [value]-length steps starting at
     * [startTick]. This is the "assign a rhythm to the notes I already entered" path.
     */
    fun evenRhythm(count: Int, value: NoteValue, startTick: Long = 0L): List<Pair<Long, Int>> =
        (0 until count).map { startTick + it.toLong() * value.ticks to value.ticks }

    /**
     * Index of the bar containing [tick], and the tick offset within that bar.
     */
    fun barAndOffset(tick: Long, ts: TimeSignature): Pair<Int, Int> {
        val bar = Math.floorDiv(tick, ts.barTicks.toLong())
        return bar.toInt() to (tick - bar * ts.barTicks).toInt()
    }
}
