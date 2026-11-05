package com.tabtap.music

import kotlinx.serialization.Serializable

import kotlin.math.abs
import kotlin.math.pow

/** Instrument families TabTap can lay out a fretboard for. */
@Serializable
enum class InstrumentFamily(val displayName: String) {
    GUITAR("Guitar"),
    BASS("Bass");
}

/**
 * A tuning: the open-string pitches of an instrument, ordered from the lowest-sounding
 * string (string index 0) upwards.
 *
 * Tab convention numbers strings from the highest-sounding downwards, so a 6-string
 * guitar's high E is "string 1". That display convention is applied at the UI layer;
 * internally index 0 is always the lowest pitch, which keeps fret maths uniform across
 * 4-, 6-, 7- and 8-string instruments and arbitrary custom tunings.
 */
@Serializable
data class Tuning(
    val id: String,
    val displayName: String,
    val family: InstrumentFamily,
    /** Open-string MIDI pitches, lowest string first. */
    val openStrings: List<Int>,
) {
    init {
        require(openStrings.isNotEmpty()) { "Tuning $id has no strings" }
        require(openStrings.size <= MAX_STRINGS) { "Tuning $id has too many strings" }
        require(openStrings.all { Pitch.isValid(it) }) { "Tuning $id has an out-of-range string" }
    }

    val stringCount: Int get() = openStrings.size

    /** Open-string names from low to high, e.g. [E2, A2, D3, G3, B3, E4]. */
    val stringNames: List<String> get() = openStrings.map { Pitch.fullName(it) }

    /** Open-string names in tab display order (highest string first). */
    val stringNamesTabOrder: List<String> get() = stringNames.reversed()

    /** True when this tuning is one of the built-in presets. */
    val isPreset: Boolean get() = Tunings.presets.any { it.id == id }

    /** Change one string's open pitch, producing a custom tuning. */
    fun withString(index: Int, midi: Int): Tuning {
        require(index in openStrings.indices) { "String index $index out of range" }
        val updated = openStrings.toMutableList()
        updated[index] = Pitch.clamp(midi)
        return copy(id = CUSTOM_ID, displayName = "Custom", openStrings = updated)
    }

    /** Transpose every string by [semitones], producing a custom tuning. */
    fun transposed(semitones: Int): Tuning =
        copy(id = CUSTOM_ID, displayName = "Custom", openStrings = openStrings.map { Pitch.clamp(it + semitones) })

    /**
     * Resize to [count] strings, keeping existing strings and extending by fourths
     * below / the conventional interval above.
     */
    fun withStringCount(count: Int): Tuning {
        require(count in 1..MAX_STRINGS) { "String count $count out of range" }
        if (count == stringCount) return this
        val strings = openStrings.toMutableList()
        while (strings.size > count) strings.removeAt(0)
        while (strings.size < count) strings.add(0, Pitch.clamp(strings.first() - 5))
        return copy(id = CUSTOM_ID, displayName = "Custom", openStrings = strings)
    }

    companion object {
        const val MAX_STRINGS = 12
        const val CUSTOM_ID = "custom"
    }
}

/**
 * Built-in tuning presets.
 */
object Tunings {
    private fun p(name: String) = Pitch.parse(name) ?: error("bad preset pitch $name")
    private fun t(id: String, name: String, family: InstrumentFamily, vararg notes: String) =
        Tuning(id, name, family, notes.map { p(it) })

    // --- 6-string guitar ---
    val STANDARD_E = t("standard_e", "Standard E", InstrumentFamily.GUITAR, "E2", "A2", "D3", "G3", "B3", "E4")
    val DROP_D = t("drop_d", "Drop D", InstrumentFamily.GUITAR, "D2", "A2", "D3", "G3", "B3", "E4")
    val EB_STANDARD = t("eb_standard", "Eb Standard", InstrumentFamily.GUITAR, "Eb2", "Ab2", "Db3", "Gb3", "Bb3", "Eb4")
    val D_STANDARD = t("d_standard", "D Standard", InstrumentFamily.GUITAR, "D2", "G2", "C3", "F3", "A3", "D4")
    val DROP_C = t("drop_c", "Drop C", InstrumentFamily.GUITAR, "C2", "G2", "C3", "F3", "A3", "D4")
    val DROP_CSHARP = t("drop_csharp", "Drop C#", InstrumentFamily.GUITAR, "C#2", "G#2", "C#3", "F#3", "A#3", "D#4")
    val DROP_B = t("drop_b", "Drop B", InstrumentFamily.GUITAR, "B1", "F#2", "B2", "E3", "G#3", "C#4")
    val C_STANDARD = t("c_standard", "C Standard", InstrumentFamily.GUITAR, "C2", "F2", "Bb2", "Eb3", "G3", "C4")
    val OPEN_G = t("open_g", "Open G", InstrumentFamily.GUITAR, "D2", "G2", "D3", "G3", "B3", "D4")
    val OPEN_D = t("open_d", "Open D", InstrumentFamily.GUITAR, "D2", "A2", "D3", "F#3", "A3", "D4")
    val OPEN_E = t("open_e", "Open E", InstrumentFamily.GUITAR, "E2", "B2", "E3", "G#3", "B3", "E4")
    val DADGAD = t("dadgad", "DADGAD", InstrumentFamily.GUITAR, "D2", "A2", "D3", "G3", "A3", "D4")
    val HALF_STEP_DOWN = EB_STANDARD

    // --- 7- and 8-string guitar ---
    val SEVEN_STANDARD_B = t("7_standard_b", "7-String Standard B", InstrumentFamily.GUITAR, "B1", "E2", "A2", "D3", "G3", "B3", "E4")
    val SEVEN_DROP_A = t("7_drop_a", "7-String Drop A", InstrumentFamily.GUITAR, "A1", "E2", "A2", "D3", "G3", "B3", "E4")
    val EIGHT_STANDARD_F = t("8_standard_f", "8-String Standard F#", InstrumentFamily.GUITAR, "F#1", "B1", "E2", "A2", "D3", "G3", "B3", "E4")
    val EIGHT_DROP_E = t("8_drop_e", "8-String Drop E", InstrumentFamily.GUITAR, "E1", "B1", "E2", "A2", "D3", "G3", "B3", "E4")

    // --- Bass ---
    val BASS_STANDARD = t("bass_standard", "Bass Standard E", InstrumentFamily.BASS, "E1", "A1", "D2", "G2")
    val BASS_DROP_D = t("bass_drop_d", "Bass Drop D", InstrumentFamily.BASS, "D1", "A1", "D2", "G2")
    val BASS_EB = t("bass_eb", "Bass Eb Standard", InstrumentFamily.BASS, "Eb1", "Ab1", "Db2", "Gb2")
    val BASS_D_STANDARD = t("bass_d", "Bass D Standard", InstrumentFamily.BASS, "D1", "G1", "C2", "F2")
    val BASS_DROP_C = t("bass_drop_c", "Bass Drop C", InstrumentFamily.BASS, "C1", "G1", "C2", "F2")
    val BASS_5_STANDARD = t("bass5_standard", "5-String Bass B", InstrumentFamily.BASS, "B0", "E1", "A1", "D2", "G2")
    val BASS_5_DROP_A = t("bass5_drop_a", "5-String Bass Drop A", InstrumentFamily.BASS, "A0", "E1", "A1", "D2", "G2")
    val BASS_6_STANDARD = t("bass6_standard", "6-String Bass B/C", InstrumentFamily.BASS, "B0", "E1", "A1", "D2", "G2", "C3")

    val guitarPresets: List<Tuning> = listOf(
        STANDARD_E, DROP_D, EB_STANDARD, D_STANDARD, DROP_CSHARP, DROP_C, DROP_B, C_STANDARD,
        OPEN_G, OPEN_D, OPEN_E, DADGAD,
        SEVEN_STANDARD_B, SEVEN_DROP_A, EIGHT_STANDARD_F, EIGHT_DROP_E,
    )

    val bassPresets: List<Tuning> = listOf(
        BASS_STANDARD, BASS_DROP_D, BASS_EB, BASS_D_STANDARD, BASS_DROP_C,
        BASS_5_STANDARD, BASS_5_DROP_A, BASS_6_STANDARD,
    )

    val presets: List<Tuning> = guitarPresets + bassPresets

    fun byId(id: String): Tuning? = presets.firstOrNull { it.id == id }

    /** Presets matching an instrument family and, when given, a string count. */
    fun presetsFor(family: InstrumentFamily, stringCount: Int? = null): List<Tuning> =
        presets.filter { it.family == family && (stringCount == null || it.stringCount == stringCount) }

    /** The default tuning for a family and string count, falling back to a derived custom one. */
    fun defaultFor(family: InstrumentFamily, stringCount: Int): Tuning =
        presetsFor(family, stringCount).firstOrNull()
            ?: when (family) {
                InstrumentFamily.GUITAR -> STANDARD_E
                InstrumentFamily.BASS -> BASS_STANDARD
            }.withStringCount(stringCount)

    /** Build a custom tuning from explicit open-string pitches. */
    fun custom(family: InstrumentFamily, openStrings: List<Int>): Tuning =
        Tuning(Tuning.CUSTOM_ID, "Custom", family, openStrings)
}

/** A single playable position: a string index plus a fret number (0 == open). */
@Serializable
data class FretPosition(val string: Int, val fret: Int) {
    init { require(fret >= 0) { "Negative fret $fret" } }
}

/**
 * A fretboard: a tuning plus a fret count. Converts between positions and pitches and
 * finds playable positions for a pitch.
 */
data class Fretboard(
    val tuning: Tuning,
    val fretCount: Int = 24,
) {
    init {
        require(fretCount in MIN_FRETS..MAX_FRETS) { "Fret count $fretCount out of range" }
    }

    val stringCount: Int get() = tuning.stringCount

    /** Sounding pitch at [string]/[fret]. */
    fun pitchAt(string: Int, fret: Int): Int {
        require(string in 0 until stringCount) { "String $string out of range (0..${stringCount - 1})" }
        require(fret in 0..fretCount) { "Fret $fret out of range (0..$fretCount)" }
        return tuning.openStrings[string] + fret
    }

    /** Sounding pitch at [position]. */
    fun pitchAt(position: FretPosition): Int = pitchAt(position.string, position.fret)

    /** Sounding pitch at [string]/[fret], or null when the position does not exist. */
    fun pitchAtOrNull(string: Int, fret: Int): Int? =
        if (string in 0 until stringCount && fret in 0..fretCount) {
            tuning.openStrings[string] + fret
        } else {
            null
        }

    /** The lowest and highest pitches obtainable on this fretboard. */
    val pitchRange: IntRange
        get() = tuning.openStrings.min()..(tuning.openStrings.max() + fretCount)

    /** True when [midi] can be played somewhere on this fretboard. */
    fun canPlay(midi: Int): Boolean = positionsFor(midi).isNotEmpty()

    /** Every position that sounds [midi], ordered by string from low to high. */
    fun positionsFor(midi: Int): List<FretPosition> {
        val out = ArrayList<FretPosition>(stringCount)
        for (s in 0 until stringCount) {
            val fret = midi - tuning.openStrings[s]
            if (fret in 0..fretCount) out += FretPosition(s, fret)
        }
        return out
    }

    /**
     * Pick the most playable position for [midi].
     *
     * Preference order: stay close to [preferredFret] (the current hand position),
     * prefer lower frets slightly, and prefer not to crowd the two highest strings
     * when an equally close alternative exists. Returns null when the pitch is
     * unreachable on this fretboard.
     */
    fun bestPositionFor(
        midi: Int,
        preferredFret: Int? = null,
        preferredString: Int? = null,
    ): FretPosition? {
        val candidates = positionsFor(midi)
        if (candidates.isEmpty()) return null
        val anchor = preferredFret ?: DEFAULT_ANCHOR_FRET
        return candidates.minByOrNull { pos ->
            var cost = abs(pos.fret - anchor) * 10.0
            if (preferredString != null) cost += abs(pos.string - preferredString) * 6.0
            // Mild preference for lower frets, which are easier to reach.
            cost += pos.fret * 0.4
            // Open strings are easy but cannot be bent or vibratoed; only a slight bonus.
            if (pos.fret == 0) cost -= 2.0
            cost
        }
    }

    /** Fret positions of the conventional single/double inlay markers. */
    fun inlayFrets(): List<Int> = INLAY_FRETS.filter { it <= fretCount }

    /** Frets that carry a double inlay (octave markers). */
    fun doubleInlayFrets(): List<Int> = DOUBLE_INLAY_FRETS.filter { it <= fretCount }

    companion object {
        const val MIN_FRETS = 12
        const val MAX_FRETS = 30
        const val DEFAULT_ANCHOR_FRET = 5

        private val INLAY_FRETS = listOf(3, 5, 7, 9, 12, 15, 17, 19, 21, 24, 27, 29)
        private val DOUBLE_INLAY_FRETS = listOf(12, 24)

        /**
         * Physically accurate relative distance from the nut to fret [n], as a fraction
         * of the full scale length, using the 12th-root-of-two rule.
         * Fret 0 returns 0.0; the 12th fret returns 0.5.
         */
        fun fretDistanceFraction(n: Int): Double {
            require(n >= 0) { "Negative fret $n" }
            return 1.0 - (1.0 / 2.0.pow(n / 12.0))
        }

        /**
         * Normalised x positions (0..1) of frets 0..[fretCount] across a board drawn to
         * the width of [fretCount] frets. Used by the fretboard renderer so that fret
         * spacing narrows correctly towards the bridge.
         */
        fun fretLayout(fretCount: Int): DoubleArray {
            val total = fretDistanceFraction(fretCount)
            return DoubleArray(fretCount + 1) { fretDistanceFraction(it) / total }
        }
    }
}
