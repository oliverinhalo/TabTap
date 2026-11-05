package com.tabtap.app.ui.score

import com.tabtap.music.Articulation
import com.tabtap.music.notation.StemDirection
import com.tabtap.music.notation.TabLayout
import com.tabtap.music.notation.TabMeasure
import com.tabtap.music.notation.TabStack
import com.tabtap.music.notation.TabSystem
import kotlin.math.abs
import kotlin.math.max

/**
 * Paints tablature, with a rhythm ruler under the staff.
 *
 * The tab is drawn from the same [TabLayout] the editor uses for hit testing, so what is
 * on screen and what a tap lands on cannot disagree. Techniques are drawn the way
 * guitarists write them — a letter between two notes for a hammer-on or slide, a curved
 * arrow for a bend, a wavy line for vibrato, "P.M." under a muted passage.
 */
class TabPainter(
    /** Vertical distance between two tab lines, in pixels. */
    private val linePx: Float,
    /** Horizontal distance of one layout column, in pixels. */
    private val columnPx: Float,
    private val palette: ScorePalette = ScorePalette.DARK,
) {
    /** Height of one tab system including its rhythm ruler and technique rows. */
    fun systemHeight(stringCount: Int): Float = linePx * (stringCount - 1) + linePx * 5.2f

    fun totalHeight(layout: TabLayout): Float =
        layout.systems.size * systemHeight(layout.stringCount) + linePx

    /** Where a tab stack's cell sits, so the editor can hit test and draw a cursor. */
    data class CellBounds(
        val noteId: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height
    }

    /** Bounds of every cell, collected during painting for hit testing. */
    val cellBounds = ArrayList<CellBounds>()

    /** An empty position the editor can place a new note into. */
    data class SlotBounds(
        val line: Int,
        val string: Int,
        val startTick: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height
    }

    val slotBounds = ArrayList<SlotBounds>()

    fun paint(
        layout: TabLayout,
        width: Float,
        selectedNoteIds: Set<Long> = emptySet(),
        playheadTick: Long? = null,
        trackColorIndex: Int? = null,
        /** Articulations to label beneath the staff, such as palm muting. */
        showTechniqueText: Boolean = true,
    ): List<ScoreDrawCommand> {
        cellBounds.clear()
        slotBounds.clear()
        val out = ArrayList<ScoreDrawCommand>(512)
        var y = linePx * 1.4f
        for (system in layout.systems) {
            paintSystem(
                out, system, layout, y, width, selectedNoteIds, playheadTick,
                trackColorIndex, showTechniqueText,
            )
            y += systemHeight(layout.stringCount)
        }
        return out
    }

    private fun paintSystem(
        out: MutableList<ScoreDrawCommand>,
        system: TabSystem,
        layout: TabLayout,
        top: Float,
        width: Float,
        selectedNoteIds: Set<Long>,
        playheadTick: Long?,
        trackColorIndex: Int?,
        showTechniqueText: Boolean,
    ) {
        val labelWidth = linePx * 1.6f
        val staffWidth = width - labelWidth
        val natural = system.measures.sumOf { it.width }.toFloat() * columnPx
        val stretch = if (natural > 0f && natural < staffWidth) {
            (staffWidth / natural).coerceAtMost(2.0f)
        } else {
            1f
        }

        fun lineY(line: Int): Float = top + line * linePx

        // The tab lines.
        for (line in 0 until layout.stringCount) {
            val y = lineY(line)
            out += ScoreDrawCommand.Line(
                labelWidth, y, width, y, max(1f, linePx * 0.045f), palette.staff,
            )
        }

        // String names at the left, highest string first, as in a tab file.
        layout.stringLabels.forEachIndexed { line, label ->
            out += ScoreDrawCommand.Text(
                0f, lineY(line) + linePx * 0.28f, label,
                linePx * 0.72f, palette.textDim, bold = true, monospace = true,
            )
        }
        // The word "TAB" down the left, as published tab is marked.
        out += ScoreDrawCommand.Text(
            labelWidth * 0.1f, top + linePx * (layout.stringCount + 0.6f), "TAB",
            linePx * 0.6f, palette.textDim, bold = true,
        )

        var x = labelWidth
        for ((index, measure) in system.measures.withIndex()) {
            val measureWidth = measure.width.toFloat() * columnPx * stretch
            paintMeasure(
                out, measure, layout, x, top, measureWidth, stretch,
                selectedNoteIds, playheadTick, trackColorIndex, showTechniqueText,
            )
            x += measureWidth
            out += ScoreDrawCommand.Line(
                x, lineY(0), x, lineY(layout.stringCount - 1),
                max(1f, linePx * 0.07f), palette.staff,
            )
            if (index == system.measures.lastIndex && system.index == layout.systems.lastIndex) {
                out += ScoreDrawCommand.FillRect(
                    x + linePx * 0.1f, lineY(0),
                    linePx * 0.16f, lineY(layout.stringCount - 1) - lineY(0),
                    palette.staff,
                )
            }
        }
    }

    private fun paintMeasure(
        out: MutableList<ScoreDrawCommand>,
        measure: TabMeasure,
        layout: TabLayout,
        originX: Float,
        top: Float,
        measureWidth: Float,
        stretch: Float,
        selectedNoteIds: Set<Long>,
        playheadTick: Long?,
        trackColorIndex: Int?,
        showTechniqueText: Boolean,
    ) {
        fun sx(layoutX: Double): Float = originX + layoutX.toFloat() * columnPx * stretch
        fun lineY(line: Int): Float = top + line * linePx

        val noteColor = trackColorIndex
            ?.let { palette.trackColors[it % palette.trackColors.size] }
            ?: palette.note
        val rulerY = lineY(layout.stringCount - 1) + linePx * 1.25f
        val techniqueY = rulerY + linePx * 1.5f

        // Empty slots, so the editor can place a note anywhere on the grid.
        for (stack in measure.stacks) {
            for (line in 0 until layout.stringCount) {
                val string = layout.stringCount - 1 - line
                if (stack.cells.any { it.line == line }) continue
                slotBounds += SlotBounds(
                    line = line, string = string, startTick = stack.startTick,
                    x = sx(stack.x) - columnPx * 0.4f, y = lineY(line) - linePx * 0.45f,
                    width = columnPx * 1.2f, height = linePx * 0.9f,
                )
            }
        }

        for (stack in measure.stacks) {
            val x = sx(stack.x)

            for (cell in stack.cells) {
                val y = lineY(cell.line)
                val text = if (cell.dead) "x" else cell.fret.toString()
                val selected = cell.noteId in selectedNoteIds
                val size = linePx * 0.82f

                // A fret number needs the tab line behind it broken, or the digits sit
                // on a line and become hard to read.
                val boxWidth = size * 0.62f * text.length + linePx * 0.2f
                out += ScoreDrawCommand.FillRect(
                    x - boxWidth / 2f, y - linePx * 0.42f, boxWidth, linePx * 0.84f,
                    palette.background,
                )
                if (selected) {
                    out += ScoreDrawCommand.FillRect(
                        x - boxWidth / 2f, y - linePx * 0.46f, boxWidth, linePx * 0.92f,
                        palette.noteSelected and 0x40FFFFFF,
                    )
                }
                out += ScoreDrawCommand.Text(
                    x, y + linePx * 0.3f, text, size,
                    if (selected) palette.noteSelected else noteColor,
                    bold = true, monospace = true,
                    align = ScoreDrawCommand.TextAlign.CENTER,
                )
                cellBounds += CellBounds(
                    cell.noteId, x - boxWidth / 2f, y - linePx * 0.5f, boxWidth, linePx,
                )

                // Technique letter between this note and the next one.
                cell.connectorToNext?.let { symbol ->
                    out += ScoreDrawCommand.Text(
                        x + boxWidth / 2f + linePx * 0.1f, y + linePx * 0.22f, symbol,
                        linePx * 0.62f, palette.textDim, italic = true, monospace = true,
                    )
                }

                // A bend is drawn as a curved arrow with its height in tones.
                if (cell.bendSemitones > 0f) {
                    val height = linePx * 1.1f
                    out += ScoreDrawCommand.Curve(
                        x + boxWidth / 2f, y,
                        x + columnPx * 0.5f, y - height * 0.8f,
                        x + columnPx * 0.7f, y - height,
                        linePx * 0.07f, noteColor,
                    )
                    val label = when {
                        cell.bendSemitones >= 2f -> "full"
                        cell.bendSemitones >= 1f -> "½"
                        else -> "¼"
                    }
                    out += ScoreDrawCommand.Text(
                        x + columnPx * 0.7f, y - height - linePx * 0.1f, label,
                        linePx * 0.52f, palette.textDim,
                        align = ScoreDrawCommand.TextAlign.CENTER,
                    )
                }

                // Vibrato: a wavy line above the note.
                if (cell.articulations.contains(Articulation.VIBRATO) ||
                    cell.articulations.contains(Articulation.WIDE_VIBRATO)
                ) {
                    val amplitude = if (cell.articulations.contains(Articulation.WIDE_VIBRATO)) {
                        linePx * 0.22f
                    } else {
                        linePx * 0.13f
                    }
                    var wx = x - columnPx * 0.3f
                    val wy = lineY(0) - linePx * 0.55f
                    var up = true
                    while (wx < x + columnPx * 0.7f) {
                        out += ScoreDrawCommand.Line(
                            wx, wy + if (up) amplitude else -amplitude,
                            wx + linePx * 0.22f, wy + if (up) -amplitude else amplitude,
                            linePx * 0.06f, palette.textDim,
                        )
                        wx += linePx * 0.22f
                        up = !up
                    }
                }

                // Harmonics are written with angle brackets around the fret number.
                if (cell.articulations.contains(Articulation.HARMONIC) ||
                    cell.articulations.contains(Articulation.PINCH_HARMONIC)
                ) {
                    out += ScoreDrawCommand.Text(
                        x - boxWidth / 2f - linePx * 0.05f, y + linePx * 0.28f, "<",
                        linePx * 0.7f, palette.textDim,
                        align = ScoreDrawCommand.TextAlign.RIGHT,
                    )
                    out += ScoreDrawCommand.Text(
                        x + boxWidth / 2f + linePx * 0.05f, y + linePx * 0.28f, ">",
                        linePx * 0.7f, palette.textDim,
                    )
                }

                // A tie is a curve joining the two noteheads.
                if (cell.tieToNext) {
                    out += ScoreDrawCommand.Curve(
                        x + boxWidth / 2f, y + linePx * 0.1f,
                        x + columnPx * 0.6f, y + linePx * 0.6f,
                        x + columnPx * 1.2f, y + linePx * 0.1f,
                        linePx * 0.06f, noteColor,
                    )
                }
            }

            // The rhythm ruler: a stem and beams under the staff, as tab is written.
            paintRhythmStem(out, stack, x, rulerY, noteColor)

            if (showTechniqueText) {
                val labels = ArrayList<String>()
                if (stack.cells.any { it.articulations.contains(Articulation.PALM_MUTE) }) {
                    labels += "P.M."
                }
                if (stack.cells.any { it.articulations.contains(Articulation.LET_RING) }) {
                    labels += "let ring"
                }
                if (stack.cells.any { it.articulations.contains(Articulation.TAP) }) labels += "T"
                labels.forEachIndexed { i, label ->
                    out += ScoreDrawCommand.Text(
                        x, techniqueY + i * linePx * 0.8f, label,
                        linePx * 0.55f, palette.textDim, italic = true,
                        align = ScoreDrawCommand.TextAlign.CENTER,
                    )
                }
            }

            stack.chordSymbol?.let { symbol ->
                out += ScoreDrawCommand.Text(
                    x, lineY(0) - linePx * 1.3f, symbol,
                    linePx * 0.8f, palette.text, bold = true,
                    align = ScoreDrawCommand.TextAlign.CENTER,
                )
            }
        }

        // Beams on the rhythm ruler.
        for (beam in measure.beams) {
            val stacks = beam.chordIndices.mapNotNull { measure.stacks.getOrNull(it) }
            if (stacks.size < 2) continue
            val x1 = sx(stacks.first().x)
            val x2 = sx(stacks.last().x)
            val levels = stacks.minOf { it.value.beamCount }
            for (level in 1..levels) {
                val y = rulerY + linePx * 0.75f + (level - 1) * linePx * 0.22f
                out += ScoreDrawCommand.FillRect(
                    x1, y, x2 - x1, linePx * 0.1f, noteColor,
                )
            }
        }

        // Rests on the rhythm ruler.
        for (rest in measure.rests) {
            out += ScoreDrawCommand.Text(
                sx(rest.x), rulerY + linePx * 0.5f, restSymbol(rest.value.divisionLevel),
                linePx * 0.7f, palette.textDim,
                align = ScoreDrawCommand.TextAlign.CENTER,
            )
        }

        if (playheadTick != null) {
            val barTicks = measure.timeSignature.barTicks
            if (playheadTick >= measure.startTick && playheadTick < measure.startTick + barTicks) {
                val progress = (playheadTick - measure.startTick).toFloat() / barTicks
                val px = originX + measureWidth * progress
                out += ScoreDrawCommand.Line(
                    px, lineY(0) - linePx * 0.4f, px,
                    lineY(layout.stringCount - 1) + linePx * 0.4f,
                    linePx * 0.09f, palette.playhead,
                )
            }
        }
    }

    private fun paintRhythmStem(
        out: MutableList<ScoreDrawCommand>,
        stack: TabStack,
        x: Float,
        rulerY: Float,
        color: Int,
    ) {
        val value = stack.value
        if (!value.hasStem) return
        val length = linePx * 0.8f
        out += ScoreDrawCommand.Line(x, rulerY, x, rulerY + length, linePx * 0.06f, color)
        if (!value.filled) return
        // A notehead marker at the top of the stem distinguishes a quarter from a half.
        out += ScoreDrawCommand.Circle(x, rulerY, linePx * 0.1f, color)
        repeat(value.dots) { i ->
            out += ScoreDrawCommand.Circle(
                x + linePx * (0.2f + i * 0.16f), rulerY + length * 0.5f, linePx * 0.05f, color,
            )
        }
    }

    private fun restSymbol(divisionLevel: Int): String = when (divisionLevel) {
        0 -> "––"
        1 -> "–"
        2 -> "‡"
        3 -> "†"
        else -> "·"
    }
}
