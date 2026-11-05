package com.tabtap.music.notation

import com.tabtap.music.Articulation
import com.tabtap.music.ChordEvent
import com.tabtap.music.ClefType
import com.tabtap.music.Key
import com.tabtap.music.KeySignature
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.SpelledPitch
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns a [Track] into an [EngravedScore].
 *
 * The engraver implements the conventions a reader expects rather than approximating
 * them visually:
 *
 *  * Notes are placed by *spelled* pitch, so F# and Gb sit on different staff positions.
 *  * Octave-transposing clefs are honoured, so guitar music is written an octave above
 *    where it sounds and sits on the staff instead of below it.
 *  * Accidentals follow the key signature and persist for the rest of the bar, with
 *    naturals written where the signature would otherwise apply.
 *  * Durations that do not fit a single written value, or that cross a bar line, are
 *    split into tied notes.
 *  * Gaps are filled with rests decomposed into conventional values.
 *  * Beams follow the metre's beat groups, with secondary beams for sixteenths and
 *    shorter and partial beams where a group is uneven.
 *  * Stems point away from the middle line, decided by the stack as a whole.
 *  * Horizontal spacing is proportional to the square-ish root of duration, the standard
 *    engraving compromise between even spacing and strict proportionality.
 */
object ScoreEngraver {

    /** Width in staff spaces of a clef glyph plus its padding. */
    const val CLEF_WIDTH = 4.0
    /** Width of one key-signature accidental. */
    const val KEY_ACCIDENTAL_WIDTH = 1.2
    /** Width of a time signature. */
    const val TIME_SIGNATURE_WIDTH = 3.0
    /** Width of a notehead. */
    const val NOTEHEAD_WIDTH = 1.3
    /** Width of an accidental in front of a notehead. */
    const val ACCIDENTAL_WIDTH = 1.1
    /** Minimum horizontal gap between two stacks. */
    const val MIN_STACK_GAP = 1.6
    /** Padding at the end of a measure, before the bar line. */
    const val MEASURE_TRAILING_PAD = 1.4

    /**
     * Engrave [track].
     *
     * @param systemWidth available width for one system, in staff spaces. Measures are
     *   packed into systems up to this width; a measure wider than it gets a system of
     *   its own rather than being clipped.
     * @param chordProgression chord symbols to print above the staff.
     */
    fun engrave(
        track: Track,
        key: Key,
        timeSignature: TimeSignature,
        tempoMap: TempoMap = TempoMap.of(120.0),
        systemWidth: Double = 60.0,
        chordProgression: List<ChordEvent> = emptyList(),
        minMeasures: Int = 1,
    ): EngravedScore {
        val clef = track.clef
        val fifths = key.sharpsInSignature
        val barTicks = timeSignature.barTicks

        val lastTick = maxOf(
            track.endTick,
            chordProgression.maxOfOrNull { it.endTick } ?: 0L,
        )
        val measureCount = maxOf(
            minMeasures,
            Math.ceil(lastTick.toDouble() / barTicks).toInt().coerceAtLeast(1),
        )

        // Split notes into per-bar fragments, tying anything that crosses a bar line.
        val fragments = fragmentNotes(track.notes, barTicks, measureCount)

        val measures = ArrayList<EngravedMeasure>(measureCount)
        for (bar in 0 until measureCount) {
            measures += engraveMeasure(
                index = bar,
                startTick = bar.toLong() * barTicks,
                timeSignature = timeSignature,
                key = key,
                clef = clef,
                fragments = fragments[bar] ?: emptyList(),
                chordProgression = chordProgression,
                tempoText = if (bar == 0) tempoText(tempoMap) else tempoChangeText(tempoMap, bar, barTicks),
                printKeySignature = bar == 0,
                printTimeSignature = bar == 0,
                fifths = fifths,
            )
        }

        return EngravedScore(
            systems = packSystems(measures, clef, fifths, systemWidth),
            clef = clef,
            key = key,
            timeSignature = timeSignature,
            systemWidth = systemWidth,
        )
    }

    // ---------------------------------------------------------------- fragments

    /**
     * One sounding note, clipped to a single bar. A note longer than the remainder of its
     * bar becomes several fragments joined by ties.
     */
    private data class Fragment(
        val note: MusicNote,
        val startTick: Long,
        val durationTicks: Int,
        val tieFromPrevious: Boolean,
        val tieToNext: Boolean,
    )

    private fun fragmentNotes(
        notes: List<MusicNote>,
        barTicks: Int,
        measureCount: Int,
    ): Map<Int, List<Fragment>> {
        val out = HashMap<Int, MutableList<Fragment>>()
        for (note in notes) {
            var tick = note.startTick
            var remaining = note.durationTicks
            var first = true
            while (remaining > 0) {
                val bar = (tick / barTicks).toInt()
                if (bar >= measureCount) break
                val barEnd = (bar + 1).toLong() * barTicks
                val take = minOf(remaining.toLong(), barEnd - tick).toInt()
                val isLast = take == remaining
                out.getOrPut(bar) { ArrayList() } += Fragment(
                    note = note,
                    startTick = tick,
                    durationTicks = take,
                    tieFromPrevious = !first,
                    tieToNext = !isLast || note.tiedToNext,
                )
                tick += take
                remaining -= take
                first = false
            }
        }
        return out
    }

    // ---------------------------------------------------------------- measure

    private fun engraveMeasure(
        index: Int,
        startTick: Long,
        timeSignature: TimeSignature,
        key: Key,
        clef: ClefType,
        fragments: List<Fragment>,
        chordProgression: List<ChordEvent>,
        tempoText: String?,
        printKeySignature: Boolean,
        printTimeSignature: Boolean,
        fifths: Int,
    ): EngravedMeasure {
        val barTicks = timeSignature.barTicks

        // Group simultaneous fragments into chord stacks.
        val stacks = fragments.groupBy { it.startTick }.toSortedMap()

        // Accidental state for the bar, keyed by letter and octave of the written pitch.
        val barAccidentals = HashMap<Pair<Int, Int>, Int>()

        val chords = ArrayList<EngravedChord>()
        val rests = ArrayList<EngravedRest>()
        var cursor = startTick

        for ((tick, group) in stacks) {
            if (tick > cursor) {
                rests += restsFor(cursor, (tick - cursor).toInt(), timeSignature)
            }
            // A stack's written duration is its shortest member, so the stem and beam
            // belong to the note that actually ends soonest.
            val duration = group.minOf { it.durationTicks }
            val value = NoteValue.exactForTicks(duration) ?: NoteValue.forTicks(duration)

            val heads = group.sortedBy { it.note.pitch }.map { frag ->
                notehead(frag, key, clef, fifths, barAccidentals)
            }
            val stem = stemDirection(heads)
            chords += EngravedChord(
                startTick = tick,
                durationTicks = duration,
                value = value,
                noteheads = resolveSeconds(heads, stem),
                stem = if (value.hasStem) stem else StemDirection.NONE,
                articulations = group.flatMap { it.note.articulations }.toSet(),
                chordSymbol = chordSymbolAt(tick, chordProgression),
            )
            cursor = tick + duration
        }
        if (cursor < startTick + barTicks) {
            val gap = (startTick + barTicks - cursor).toInt()
            // A completely empty bar gets a single whole-bar rest.
            rests += if (chords.isEmpty() && gap == barTicks) {
                listOf(EngravedRest(startTick, barTicks, NoteValue.WHOLE))
            } else {
                restsFor(cursor, gap, timeSignature)
            }
        }

        val beams = computeBeams(chords, timeSignature, startTick)
        val tuplets = computeTuplets(chords)
        val headerWidth = headerWidth(index == 0, printKeySignature, fifths, printTimeSignature)
        val (placedChords, placedRests, width) =
            layoutHorizontally(chords, rests, startTick, barTicks, headerWidth)

        return EngravedMeasure(
            index = index,
            startTick = startTick,
            timeSignature = timeSignature,
            chords = placedChords,
            rests = placedRests,
            beams = beams,
            tuplets = tuplets,
            keySignatureFifths = if (printKeySignature) fifths else null,
            printTimeSignature = printTimeSignature,
            tempoText = tempoText,
            width = width,
            headerWidth = headerWidth,
        )
    }

    private fun headerWidth(
        isFirst: Boolean,
        printKey: Boolean,
        fifths: Int,
        printTime: Boolean,
    ): Double {
        var w = 0.0
        if (isFirst) w += CLEF_WIDTH
        if (printKey) w += abs(fifths) * KEY_ACCIDENTAL_WIDTH + if (fifths != 0) 0.8 else 0.0
        if (printTime) w += TIME_SIGNATURE_WIDTH
        return w + 0.8
    }

    // ---------------------------------------------------------------- noteheads

    private fun notehead(
        frag: Fragment,
        key: Key,
        clef: ClefType,
        fifths: Int,
        barAccidentals: MutableMap<Pair<Int, Int>, Int>,
    ): EngravedNotehead {
        // Written pitch: octave-transposing clefs are written above where they sound.
        val writtenMidi = frag.note.pitch + clef.writtenOctaveShift
        val spelled = key.spell(writtenMidi)
        val step = spelled.diatonicIndex - ClefGeometry.middleLineDiatonicIndex(clef)

        val stateKey = spelled.letter.step to spelled.octave
        val signatureAlter = KeySignature.alterFor(spelled.letter, fifths)
        val current = barAccidentals[stateKey] ?: signatureAlter
        // A tied continuation never repeats its accidental.
        val accidental = if (frag.tieFromPrevious || spelled.alter == current) {
            null
        } else {
            AccidentalGlyph.forAlter(spelled.alter)
        }
        if (accidental != null) barAccidentals[stateKey] = spelled.alter

        return EngravedNotehead(
            noteId = frag.note.id,
            soundingPitch = frag.note.pitch,
            writtenPitch = spelled,
            staffStep = step,
            accidental = accidental,
            tieToNext = frag.tieToNext,
            tieFromPrevious = frag.tieFromPrevious,
        )
    }

    /**
     * Stems point away from the middle line. For a stack, the note furthest from the
     * middle line decides, which is the conventional rule.
     */
    private fun stemDirection(heads: List<EngravedNotehead>): StemDirection {
        if (heads.isEmpty()) return StemDirection.NONE
        val top = heads.maxOf { it.staffStep }
        val bottom = heads.minOf { it.staffStep }
        return if (abs(top) > abs(bottom)) {
            if (top > 0) StemDirection.DOWN else StemDirection.UP
        } else {
            if (bottom >= 0) StemDirection.DOWN else StemDirection.UP
        }
    }

    /**
     * Two noteheads a diatonic second apart cannot share a column, so one is shifted
     * across the stem. The shifted note is the upper one for an up stem, the lower for a
     * down stem.
     */
    private fun resolveSeconds(
        heads: List<EngravedNotehead>,
        stem: StemDirection,
    ): List<EngravedNotehead> {
        if (heads.size < 2) return heads
        val result = heads.toMutableList()
        var i = 1
        while (i < result.size) {
            val prev = result[i - 1]
            val cur = result[i]
            if (cur.staffStep - prev.staffStep == 1 && !prev.shifted) {
                if (stem == StemDirection.UP) {
                    result[i] = cur.copy(shifted = true)
                } else {
                    result[i - 1] = prev.copy(shifted = true)
                }
            }
            i++
        }
        return result
    }

    // ---------------------------------------------------------------- rests

    /**
     * Fill a gap of [ticks] with conventional rests, aligned so that rests do not
     * straddle a beat boundary where a shorter pair would read better.
     */
    private fun restsFor(startTick: Long, ticks: Int, ts: TimeSignature): List<EngravedRest> {
        if (ticks <= 0) return emptyList()
        val out = ArrayList<EngravedRest>()
        var tick = startTick
        var remaining = ticks
        val beat = ts.beatTicks
        var guard = 0
        while (remaining > 0 && guard++ < 64) {
            // Never let a rest run past the next beat boundary unless it fills whole beats.
            val toBeat = (beat - (tick % beat).toInt()).let { if (it == 0) beat else it }
            val maxHere = if (remaining >= beat && tick % beat == 0L) remaining else minOf(remaining, toBeat)
            val value = NoteValue.decompose(maxHere).firstOrNull() ?: break
            out += EngravedRest(tick, value.ticks, value)
            tick += value.ticks
            remaining -= value.ticks
        }
        return out
    }

    // ---------------------------------------------------------------- beams

    /**
     * Group beamable stacks by the metre's beat groups and emit one beam per level.
     *
     * A beam is only drawn across two or more stacks inside the same group, with no rest
     * between them. Secondary beams (16ths and shorter) are drawn across the runs of
     * stacks that actually carry them, and a stack that carries a level alone gets a
     * partial beam pointing into the group.
     */
    private fun computeBeams(
        chords: List<EngravedChord>,
        ts: TimeSignature,
        measureStart: Long,
    ): List<EngravedBeam> {
        if (chords.size < 2) return emptyList()
        val groups = ts.beamGroups
        val boundaries = ArrayList<Long>(groups.size + 1)
        var acc = measureStart
        boundaries += acc
        for (g in groups) { acc += g; boundaries += acc }

        fun groupOf(tick: Long): Int {
            for (i in 0 until boundaries.size - 1) {
                if (tick >= boundaries[i] && tick < boundaries[i + 1]) return i
            }
            return boundaries.size - 2
        }

        val beams = ArrayList<EngravedBeam>()
        // Runs of consecutive beamable stacks within one beat group.
        var i = 0
        while (i < chords.size) {
            if (chords[i].value.beamCount == 0) { i++; continue }
            val group = groupOf(chords[i].startTick)
            var j = i
            while (j + 1 < chords.size &&
                chords[j + 1].value.beamCount > 0 &&
                groupOf(chords[j + 1].startTick) == group &&
                chords[j + 1].startTick == chords[j].startTick + chords[j].durationTicks
            ) j++
            if (j > i) {
                val indices = (i..j).toList()
                beams += EngravedBeam(1, indices)
                // Secondary beams cover runs that share the deeper level.
                var level = 2
                while (indices.any { chords[it].value.beamCount >= level }) {
                    var k = 0
                    while (k < indices.size) {
                        if (chords[indices[k]].value.beamCount < level) { k++; continue }
                        var m = k
                        while (m + 1 < indices.size && chords[indices[m + 1]].value.beamCount >= level) m++
                        if (m > k) {
                            beams += EngravedBeam(level, indices.subList(k, m + 1).toList())
                        } else {
                            // A lone short note inside the group gets a partial beam,
                            // pointing towards whichever neighbour is inside the beam.
                            beams += EngravedBeam(
                                level,
                                listOf(indices[k]),
                                partial = true,
                                partialForward = k == 0,
                            )
                        }
                        k = m + 1
                    }
                    level++
                }
            }
            i = j + 1
        }
        return beams
    }

    private fun computeTuplets(chords: List<EngravedChord>): List<EngravedTuplet> {
        val out = ArrayList<EngravedTuplet>()
        var i = 0
        while (i < chords.size) {
            if (!chords[i].value.isTuplet) { i++; continue }
            var j = i
            while (j + 1 < chords.size &&
                chords[j + 1].value.isTuplet &&
                chords[j + 1].value.tupletNum == chords[i].value.tupletNum
            ) j++
            out += EngravedTuplet(
                (i..j).toList(),
                chords[i].value.tupletNum,
                chords[i].value.tupletDen,
            )
            i = j + 1
        }
        return out
    }

    // ---------------------------------------------------------------- spacing

    /**
     * Place stacks and rests horizontally.
     *
     * Each event is allotted a width proportional to `duration^0.6`, the classical
     * compromise: a whole note is wider than a quarter but not sixteen times wider.
     * Accidentals and shifted noteheads claim extra room so nothing collides.
     */
    private fun layoutHorizontally(
        chords: List<EngravedChord>,
        rests: List<EngravedRest>,
        measureStart: Long,
        barTicks: Int,
        headerWidth: Double,
    ): Triple<List<EngravedChord>, List<EngravedRest>, Double> {
        data class Event(val tick: Long, val duration: Int, val chordIndex: Int?, val restIndex: Int?)

        val events = ArrayList<Event>(chords.size + rests.size)
        chords.forEachIndexed { i, c -> events += Event(c.startTick, c.durationTicks, i, null) }
        rests.forEachIndexed { i, r -> events += Event(r.startTick, r.durationTicks, null, i) }
        events.sortBy { it.tick }

        val xs = DoubleArray(events.size)
        var x = headerWidth
        for ((idx, e) in events.withIndex()) {
            xs[idx] = x
            val c = e.chordIndex?.let { chords[it] }
            var w = spacingFor(e.duration)
            if (c != null) {
                if (c.noteheads.any { it.accidental != null }) w += ACCIDENTAL_WIDTH
                if (c.noteheads.any { it.shifted }) w += NOTEHEAD_WIDTH * 0.7
            }
            x += maxOf(w, MIN_STACK_GAP)
        }

        val outChords = chords.toMutableList()
        val outRests = rests.toMutableList()
        for ((idx, e) in events.withIndex()) {
            e.chordIndex?.let { outChords[it] = chords[it].copy(x = xs[idx]) }
            e.restIndex?.let { outRests[it] = rests[it].copy(x = xs[idx]) }
        }
        return Triple(outChords, outRests, x + MEASURE_TRAILING_PAD)
    }

    /** Width in staff spaces allotted to an event of [ticks]. */
    fun spacingFor(ticks: Int): Double {
        val quarters = ticks.toDouble() / Ticks.PPQ
        return 3.2 * quarters.coerceAtLeast(1.0 / 32.0).pow(0.6)
    }

    // ---------------------------------------------------------------- systems

    /** Pack measures into systems no wider than [systemWidth]. */
    private fun packSystems(
        measures: List<EngravedMeasure>,
        clef: ClefType,
        fifths: Int,
        systemWidth: Double,
    ): List<EngravedSystem> {
        val systems = ArrayList<EngravedSystem>()
        var current = ArrayList<EngravedMeasure>()
        var width = 0.0
        // Every system after the first repeats the clef and key signature.
        val repeatHeader = CLEF_WIDTH + abs(fifths) * KEY_ACCIDENTAL_WIDTH + 0.8

        for (m in measures) {
            val header = if (current.isEmpty() && systems.isNotEmpty()) repeatHeader else 0.0
            if (current.isNotEmpty() && width + m.width > systemWidth) {
                systems += EngravedSystem(systems.size, current, clef, fifths, width)
                current = ArrayList()
                width = repeatHeader
            }
            if (current.isEmpty()) width += header
            current += m
            width += m.width
        }
        if (current.isNotEmpty()) {
            systems += EngravedSystem(systems.size, current, clef, fifths, width)
        }
        return systems
    }

    // ---------------------------------------------------------------- text

    private fun tempoText(map: TempoMap): String =
        "♩ = ${map.initialBpm.roundToInt()}"

    private fun tempoChangeText(map: TempoMap, bar: Int, barTicks: Int): String? {
        val barStart = bar.toLong() * barTicks
        val marking = map.markings.firstOrNull { it.startTick == barStart && it.startTick > 0 }
        return marking?.let { "♩ = ${it.bpm.roundToInt()}" }
    }

    private fun chordSymbolAt(tick: Long, progression: List<ChordEvent>): String? =
        progression.firstOrNull { it.startTick == tick }?.chord?.symbol
}
