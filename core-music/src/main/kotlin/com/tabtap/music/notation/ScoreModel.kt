package com.tabtap.music.notation

import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ClefType
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteLetter
import com.tabtap.music.NoteValue
import com.tabtap.music.SpelledPitch
import com.tabtap.music.TimeSignature

/**
 * The engraving model.
 *
 * Layout is computed as pure data — positions in abstract *staff spaces*, with no
 * reference to Android or Compose — so the same layout drives the on-screen score, the
 * printed PDF and the layout unit tests. One staff space is the distance between two
 * adjacent staff lines; a renderer picks a pixel size for it and scales everything.
 */

/** An accidental drawn before a notehead. */
enum class AccidentalGlyph(val alter: Int) {
    DOUBLE_FLAT(-2), FLAT(-1), NATURAL(0), SHARP(1), DOUBLE_SHARP(2);

    companion object {
        fun forAlter(alter: Int): AccidentalGlyph? = entries.firstOrNull { it.alter == alter }
    }
}

/** Which way a stem points. */
enum class StemDirection { UP, DOWN, NONE }

/**
 * One notehead within a chord stack.
 *
 * [staffStep] counts diatonic steps from the middle staff line: 0 is the middle line,
 * +1 the space above it, +2 the line above that. Lines sit on even steps, spaces on odd.
 */
data class EngravedNotehead(
    val noteId: Long,
    val soundingPitch: Int,
    val writtenPitch: SpelledPitch,
    val staffStep: Int,
    val accidental: AccidentalGlyph?,
    /** True when this notehead is shifted sideways to avoid colliding with a neighbour. */
    val shifted: Boolean = false,
    /** True when a tie runs from this notehead into the next stack. */
    val tieToNext: Boolean = false,
    /** True when this notehead continues a tie from the previous stack. */
    val tieFromPrevious: Boolean = false,
)

/** A chord stack: one or more noteheads sharing a stem and a time position. */
data class EngravedChord(
    val startTick: Long,
    val durationTicks: Int,
    val value: NoteValue,
    val noteheads: List<EngravedNotehead>,
    val stem: StemDirection,
    /** Index into the measure's beam groups, or null when the stack is unbeamed. */
    val beamGroup: Int? = null,
    val articulations: Set<Articulation> = emptySet(),
    /** Chord symbol to print above the staff, when this stack starts a new chord. */
    val chordSymbol: String? = null,
    /** Horizontal position within the measure, in staff spaces from the measure's content start. */
    val x: Double = 0.0,
) {
    val topStep: Int get() = noteheads.maxOf { it.staffStep }
    val bottomStep: Int get() = noteheads.minOf { it.staffStep }
    /** Ledger lines needed above the staff, as step values. */
    val ledgerStepsAbove: List<Int> get() = (6..topStep step 2).toList()
    /** Ledger lines needed below the staff, as step values. */
    val ledgerStepsBelow: List<Int> get() = (-6 downTo bottomStep step 2).toList()
}

/** A rest. */
data class EngravedRest(
    val startTick: Long,
    val durationTicks: Int,
    val value: NoteValue,
    val x: Double = 0.0,
)

/** One beam spanning several stacks at a given beam level. */
data class EngravedBeam(
    /** 1 == the eighth-note beam, 2 == the sixteenth beam, and so on. */
    val level: Int,
    /** Indices into [EngravedMeasure.chords] that this beam covers, in order. */
    val chordIndices: List<Int>,
    /** True for a short partial beam hanging off one end of a longer one. */
    val partial: Boolean = false,
    /** For a partial beam, whether it points forwards (to the right). */
    val partialForward: Boolean = true,
)

/** One tuplet bracket. */
data class EngravedTuplet(
    val chordIndices: List<Int>,
    val numerator: Int,
    val denominator: Int,
)

/** A measure of engraved music. */
data class EngravedMeasure(
    val index: Int,
    val startTick: Long,
    val timeSignature: TimeSignature,
    val chords: List<EngravedChord>,
    val rests: List<EngravedRest>,
    val beams: List<EngravedBeam>,
    val tuplets: List<EngravedTuplet>,
    /** Key signature to print at the start of this measure, when it changes here. */
    val keySignatureFifths: Int? = null,
    /** Time signature to print at the start of this measure, when it changes here. */
    val printTimeSignature: Boolean = false,
    /** Tempo text to print above this measure, e.g. "♩ = 120". */
    val tempoText: String? = null,
    /** Width of the measure in staff spaces, including its leading clef/key/metre area. */
    val width: Double = 0.0,
    /** Width of the leading clef/key/metre area, in staff spaces. */
    val headerWidth: Double = 0.0,
) {
    val isEmpty: Boolean get() = chords.isEmpty()
}

/** One line of music across the page. */
data class EngravedSystem(
    val index: Int,
    val measures: List<EngravedMeasure>,
    val clef: ClefType,
    /** Key signature printed at the start of the system. */
    val keySignatureFifths: Int,
    /** Total width in staff spaces, including the system's clef and key signature. */
    val width: Double,
)

/** A complete engraved score for one track. */
data class EngravedScore(
    val systems: List<EngravedSystem>,
    val clef: ClefType,
    val key: Key,
    val timeSignature: TimeSignature,
    /** Staff spaces of width available for one system, as requested. */
    val systemWidth: Double,
) {
    val measureCount: Int get() = systems.sumOf { it.measures.size }

    /** Find the measure containing [tick], or null. */
    fun measureAt(tick: Long): EngravedMeasure? =
        systems.asSequence().flatMap { it.measures.asSequence() }
            .lastOrNull { it.startTick <= tick }

    /** Find the engraved stack that a note id belongs to. */
    fun locate(noteId: Long): Pair<EngravedMeasure, EngravedChord>? {
        for (s in systems) for (m in s.measures) for (c in m.chords) {
            if (c.noteheads.any { it.noteId == noteId }) return m to c
        }
        return null
    }
}

/**
 * Where the middle staff line sits, as an absolute diatonic index, for each clef.
 *
 * Treble clef: the middle line is B4. Bass clef: D3. The octave-transposing guitar and
 * bass clefs use the same lines; the transposition is applied to the pitch before it is
 * placed, which is why a guitar's low E (E2) is written on the bottom line's space of
 * the treble staff rather than far below it.
 */
internal object ClefGeometry {
    fun middleLineDiatonicIndex(clef: ClefType): Int = when (clef) {
        // B4: octave 4, letter B (step 6).
        ClefType.TREBLE, ClefType.TREBLE_8VB -> SpelledPitch(NoteLetter.B, 0, 4).diatonicIndex
        // D3: octave 3, letter D (step 1).
        ClefType.BASS, ClefType.BASS_8VB -> SpelledPitch(NoteLetter.D, 0, 3).diatonicIndex
    }
}
