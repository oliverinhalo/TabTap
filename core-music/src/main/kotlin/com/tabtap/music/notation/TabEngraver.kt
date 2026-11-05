package com.tabtap.music.notation

import com.tabtap.music.Articulation
import com.tabtap.music.ChordEvent
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track

/**
 * One fret number on one string of the tab staff.
 */
data class TabCell(
    val noteId: Long,
    /** String index, lowest string == 0, matching the composition model. */
    val string: Int,
    /** Line index in tab display order, 0 == the top line (highest string). */
    val line: Int,
    val fret: Int,
    val articulations: Set<Articulation>,
    /** Technique letter drawn between this note and the next, e.g. "h", "p", "/". */
    val connectorToNext: String? = null,
    val tieToNext: Boolean = false,
    val tieFromPrevious: Boolean = false,
    /** Bend height in semitones, when this cell is bent. */
    val bendSemitones: Float = 0f,
    /** True when the note is a dead note, drawn as an x. */
    val dead: Boolean = false,
)

/** A vertical group of tab cells at one time position. */
data class TabStack(
    val startTick: Long,
    val durationTicks: Int,
    val value: NoteValue,
    val cells: List<TabCell>,
    /** Chord symbol to print above the stack, when one starts here. */
    val chordSymbol: String? = null,
    /** Horizontal position in character/column units from the measure's content start. */
    val x: Double = 0.0,
    /** Index of the beam group this stack belongs to, for the rhythm ruler. */
    val beamGroup: Int? = null,
)

/** A measure of tab. */
data class TabMeasure(
    val index: Int,
    val startTick: Long,
    val timeSignature: TimeSignature,
    val stacks: List<TabStack>,
    val rests: List<EngravedRest>,
    val beams: List<EngravedBeam>,
    val width: Double,
    val headerWidth: Double,
)

/** A line of tab across the page. */
data class TabSystem(
    val index: Int,
    val measures: List<TabMeasure>,
    val width: Double,
)

/** A complete tab layout for one track. */
data class TabLayout(
    val systems: List<TabSystem>,
    val stringCount: Int,
    /** String names in display order, highest string first. */
    val stringLabels: List<String>,
    val timeSignature: TimeSignature,
    val systemWidth: Double,
) {
    val measureCount: Int get() = systems.sumOf { it.measures.size }

    fun locate(noteId: Long): Pair<TabMeasure, TabStack>? {
        for (s in systems) for (m in s.measures) for (st in m.stacks) {
            if (st.cells.any { it.noteId == noteId }) return m to st
        }
        return null
    }
}

/**
 * Lays out a [Track] as conventional tablature.
 *
 * The tab is generated from exactly the same notes the score is, which is why editing in
 * one view moves the note in the other: there is no separate tab document. Lines are
 * numbered in display order — line 0 is the highest-sounding string, as a player reads it
 * — while the composition model keeps string 0 as the lowest, and the mapping between
 * them is the only place that convention is applied.
 *
 * Notes with no fretted position (because a tuning change put them out of reach) are
 * reported through [TabEngraver.unplaceable] rather than silently dropped.
 */
object TabEngraver {

    const val MIN_STACK_GAP = 2.2
    const val MEASURE_TRAILING_PAD = 1.5

    fun engrave(
        track: Track,
        timeSignature: TimeSignature,
        systemWidth: Double = 60.0,
        chordProgression: List<ChordEvent> = emptyList(),
        minMeasures: Int = 1,
    ): TabLayout {
        val barTicks = timeSignature.barTicks
        val stringCount = track.tuning.stringCount
        val lastTick = maxOf(track.endTick, chordProgression.maxOfOrNull { it.endTick } ?: 0L)
        val measureCount = maxOf(
            minMeasures,
            Math.ceil(lastTick.toDouble() / barTicks).toInt().coerceAtLeast(1),
        )

        // Which notes carry a technique that connects them to the following note.
        val connectors = connectorMap(track.notes)

        val byBar = track.notes.groupBy { (it.startTick / barTicks).toInt() }
        val measures = ArrayList<TabMeasure>(measureCount)

        for (bar in 0 until measureCount) {
            val start = bar.toLong() * barTicks
            val notes = byBar[bar] ?: emptyList()
            val grouped = notes.groupBy { it.startTick }.toSortedMap()

            val stacks = ArrayList<TabStack>(grouped.size)
            val rests = ArrayList<EngravedRest>()
            var cursor = start

            for ((tick, group) in grouped) {
                if (tick > cursor) {
                    rests += restsFor(cursor, (tick - cursor).toInt())
                }
                val duration = group.minOf { it.durationTicks }
                val cells = group
                    .filter { it.string != null && it.fret != null }
                    .sortedByDescending { it.string }
                    .map { n ->
                        TabCell(
                            noteId = n.id,
                            string = n.string!!,
                            line = stringCount - 1 - n.string,
                            fret = n.fret!!,
                            articulations = n.articulations,
                            connectorToNext = connectors[n.id],
                            tieToNext = n.tiedToNext,
                            tieFromPrevious = track.notes.any {
                                it.tiedToNext && it.pitch == n.pitch && it.endTick == n.startTick
                            },
                            bendSemitones = n.bendSemitones,
                            dead = n.has(Articulation.DEAD_NOTE),
                        )
                    }
                if (cells.isNotEmpty()) {
                    stacks += TabStack(
                        startTick = tick,
                        durationTicks = duration,
                        value = NoteValue.exactForTicks(duration) ?: NoteValue.forTicks(duration),
                        cells = cells,
                        chordSymbol = chordProgression.firstOrNull { it.startTick == tick }?.chord?.symbol,
                    )
                }
                cursor = tick + duration
            }
            if (cursor < start + barTicks) {
                rests += restsFor(cursor, (start + barTicks - cursor).toInt())
            }

            val beams = beamsFor(stacks, timeSignature, start)
            var x = 1.0
            val placed = stacks.map { st ->
                val here = x
                x += maxOf(ScoreEngraver.spacingFor(st.durationTicks) * 0.9, MIN_STACK_GAP)
                st.copy(x = here)
            }
            val placedRests = rests.map { r ->
                r.copy(x = placed.firstOrNull { it.startTick >= r.startTick }?.x ?: x)
            }
            measures += TabMeasure(
                index = bar,
                startTick = start,
                timeSignature = timeSignature,
                stacks = placed,
                rests = placedRests,
                beams = beams,
                width = x + MEASURE_TRAILING_PAD,
                headerWidth = 1.0,
            )
        }

        return TabLayout(
            systems = packSystems(measures, systemWidth),
            stringCount = stringCount,
            stringLabels = track.tuning.stringNamesTabOrder.map { it.dropLast(1) },
            timeSignature = timeSignature,
            systemWidth = systemWidth,
        )
    }

    /** Notes whose pitch has no position on the current fretboard and so cannot be tabbed. */
    fun unplaceable(track: Track): List<MusicNote> =
        track.notes.filter { it.string == null || it.fret == null }

    /**
     * Render a track as plain ASCII tab, the format guitarists paste into forums.
     * Each system is [columnsPerLine] columns wide.
     */
    fun toAsciiTab(
        track: Track,
        timeSignature: TimeSignature,
        columnsPerLine: Int = 72,
    ): String {
        val stringCount = track.tuning.stringCount
        val labels = track.tuning.stringNamesTabOrder.map { it.dropLast(1).padEnd(2) }
        val barTicks = timeSignature.barTicks
        val measureCount = Math.ceil(track.endTick.toDouble() / barTicks).toInt().coerceAtLeast(1)

        // Build one column list per measure, then wrap.
        data class Col(val chars: Array<String>)

        val allColumns = ArrayList<Pair<Int, List<Col>>>()
        for (bar in 0 until measureCount) {
            val start = bar.toLong() * barTicks
            val notes = track.notes.filter { it.startTick >= start && it.startTick < start + barTicks }
            // One column per 16th-note slot keeps the alignment readable.
            val slots = barTicks / (barTicks / 16).coerceAtLeast(1)
            val slotTicks = barTicks / slots
            val cols = ArrayList<Col>(slots)
            for (s in 0 until slots) {
                val tick = start + s.toLong() * slotTicks
                val here = notes.filter { it.startTick == tick && it.string != null && it.fret != null }
                val chars = Array(stringCount) { "-" }
                for (n in here) {
                    val line = stringCount - 1 - n.string!!
                    chars[line] = if (n.has(Articulation.DEAD_NOTE)) "x" else n.fret!!.toString()
                }
                cols += Col(chars)
            }
            allColumns += bar to cols
        }

        val sb = StringBuilder()
        var lineStart = 0
        while (lineStart < allColumns.size) {
            // Fit as many measures on this line as the width allows.
            var used = 3
            var lineEnd = lineStart
            while (lineEnd < allColumns.size) {
                val w = allColumns[lineEnd].second.sumOf { c -> c.chars.maxOf { it.length } } + 1
                if (lineEnd > lineStart && used + w > columnsPerLine) break
                used += w
                lineEnd++
            }
            for (line in 0 until stringCount) {
                sb.append(labels[line]).append('|')
                for (m in lineStart until lineEnd) {
                    for (col in allColumns[m].second) {
                        val width = col.chars.maxOf { it.length }
                        val cell = col.chars[line]
                        sb.append(cell.padEnd(width, if (cell == "-") '-' else '-'))
                    }
                    sb.append('|')
                }
                sb.append('\n')
            }
            sb.append('\n')
            lineStart = lineEnd
        }
        return sb.toString().trimEnd() + "\n"
    }

    /** Technique letters drawn between consecutive notes on the same string. */
    private fun connectorMap(notes: List<MusicNote>): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (n in notes) {
            val symbol = when {
                n.has(Articulation.HAMMER_ON) -> "h"
                n.has(Articulation.PULL_OFF) -> "p"
                n.has(Articulation.SLIDE_LEGATO) -> "s"
                n.has(Articulation.SLIDE_UP) -> "/"
                n.has(Articulation.SLIDE_DOWN) -> "\\"
                else -> null
            }
            if (symbol != null) out[n.id] = symbol
        }
        return out
    }

    private fun restsFor(startTick: Long, ticks: Int): List<EngravedRest> {
        if (ticks <= 0) return emptyList()
        var tick = startTick
        val out = ArrayList<EngravedRest>()
        for (v in NoteValue.decompose(ticks)) {
            out += EngravedRest(tick, v.ticks, v)
            tick += v.ticks
        }
        return out
    }

    private fun beamsFor(
        stacks: List<TabStack>,
        ts: TimeSignature,
        measureStart: Long,
    ): List<EngravedBeam> {
        if (stacks.size < 2) return emptyList()
        val groups = ts.beamGroups
        val bounds = ArrayList<Long>()
        var acc = measureStart
        bounds += acc
        for (g in groups) { acc += g; bounds += acc }
        fun groupOf(t: Long): Int {
            for (i in 0 until bounds.size - 1) if (t >= bounds[i] && t < bounds[i + 1]) return i
            return bounds.size - 2
        }
        val out = ArrayList<EngravedBeam>()
        var i = 0
        while (i < stacks.size) {
            if (stacks[i].value.beamCount == 0) { i++; continue }
            var j = i
            while (j + 1 < stacks.size &&
                stacks[j + 1].value.beamCount > 0 &&
                groupOf(stacks[j + 1].startTick) == groupOf(stacks[i].startTick) &&
                stacks[j + 1].startTick == stacks[j].startTick + stacks[j].durationTicks
            ) j++
            if (j > i) out += EngravedBeam(1, (i..j).toList())
            i = j + 1
        }
        return out
    }

    private fun packSystems(measures: List<TabMeasure>, systemWidth: Double): List<TabSystem> {
        val systems = ArrayList<TabSystem>()
        var current = ArrayList<TabMeasure>()
        var width = 0.0
        for (m in measures) {
            if (current.isNotEmpty() && width + m.width > systemWidth) {
                systems += TabSystem(systems.size, current, width)
                current = ArrayList()
                width = 0.0
            }
            current += m
            width += m.width
        }
        if (current.isNotEmpty()) systems += TabSystem(systems.size, current, width)
        return systems
    }
}
