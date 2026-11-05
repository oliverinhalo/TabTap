package com.tabtap.app.ui.score

import com.tabtap.music.ClefType
import com.tabtap.music.Key
import com.tabtap.music.KeySignature
import com.tabtap.music.NoteLetter
import com.tabtap.music.notation.AccidentalGlyph
import com.tabtap.music.notation.EngravedBeam
import com.tabtap.music.notation.EngravedChord
import com.tabtap.music.notation.EngravedMeasure
import com.tabtap.music.notation.EngravedRest
import com.tabtap.music.notation.EngravedScore
import com.tabtap.music.notation.EngravedSystem
import com.tabtap.music.notation.StemDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns an [EngravedScore] into draw commands.
 *
 * The painter is responsible only for appearance; every decision about *what* is drawn —
 * which accidentals, where the beams go, how notes are grouped, how wide a measure is —
 * was already made by the engraver in `core-music`, which is why the printed and
 * on-screen scores cannot disagree with the tab or the fretboard.
 *
 * One *staff space* is the unit of the layout. [spacePx] converts it to pixels, and
 * every glyph is proportioned from it, which is how engraving has always been
 * scaled — so the score is legible at any size without a separate layout pass.
 */
class ScorePainter(
    private val spacePx: Float,
    private val palette: ScorePalette = ScorePalette.DARK,
) {
    /** Vertical space one system occupies, including room for ledger lines and text. */
    val systemHeightPx: Float get() = spacePx * 13f

    /** Height of the five-line staff itself. */
    val staffHeightPx: Float get() = spacePx * 4f

    /** Vertical offset from the top of a system to its middle staff line. */
    val middleLineOffsetPx: Float get() = spacePx * 6.5f

    /** Total height needed for [systems] systems. */
    fun totalHeight(systems: Int): Float = systems * systemHeightPx + spacePx * 2f

    /**
     * Paint a whole score.
     *
     * @param selectedNoteIds notes to draw in the selection colour.
     * @param playheadTick draw a playhead line at this tick, or null for none.
     * @param trackColorIndex colour slot for the part, so a harmony voice is drawn in
     *   its own colour in the multi-part view.
     */
    fun paint(
        score: EngravedScore,
        width: Float,
        selectedNoteIds: Set<Long> = emptySet(),
        playheadTick: Long? = null,
        trackColorIndex: Int? = null,
        showPartName: String? = null,
    ): List<ScoreDrawCommand> {
        val out = ArrayList<ScoreDrawCommand>(512)
        var y = spacePx * 1.5f
        for (system in score.systems) {
            paintSystem(out, system, score, y, width, selectedNoteIds, playheadTick, trackColorIndex)
            if (showPartName != null && system.index == 0) {
                out += ScoreDrawCommand.Text(
                    x = 0f,
                    y = y - spacePx * 0.6f,
                    text = showPartName,
                    sizePx = spacePx * 1.1f,
                    color = trackColorIndex?.let { palette.trackColors[it % palette.trackColors.size] }
                        ?: palette.textDim,
                    bold = true,
                )
            }
            y += systemHeightPx
        }
        return out
    }

    // ---------------------------------------------------------------- system

    private fun paintSystem(
        out: MutableList<ScoreDrawCommand>,
        system: EngravedSystem,
        score: EngravedScore,
        top: Float,
        width: Float,
        selectedNoteIds: Set<Long>,
        playheadTick: Long?,
        trackColorIndex: Int?,
    ) {
        val middle = top + middleLineOffsetPx
        val staffTop = middle - spacePx * 2f
        val staffBottom = middle + spacePx * 2f

        // Stretch the measures to fill the line, as engraved music does, unless the
        // system is the last and barely filled — then leave it at natural width.
        val natural = system.measures.sumOf { it.width } + leadingWidth(system, score)
        val isLast = system.index == score.systems.lastIndex
        val targetSpaces = width / spacePx
        val stretch = if (!isLast && natural > 0.0 && natural < targetSpaces) {
            (targetSpaces / natural).toFloat().coerceAtMost(1.6f)
        } else {
            1f
        }

        // Five staff lines.
        for (i in -2..2) {
            val y = middle + i * spacePx
            out += ScoreDrawCommand.Line(
                0f, y, width, y,
                GlyphMetrics.STAFF_LINE_WIDTH * spacePx, palette.staff,
            )
        }

        var x = 0f
        // Clef, then key signature: printed at the start of every system.
        x += paintClef(out, score.clef, x + spacePx * 0.4f, middle)
        x += paintKeySignature(out, system.keySignatureFifths, score.clef, x, middle)

        for ((index, measure) in system.measures.withIndex()) {
            val measureWidth = (measure.width * stretch).toFloat() * spacePx
            paintMeasure(
                out, measure, x, middle, measureWidth, stretch.toDouble(),
                selectedNoteIds, playheadTick, trackColorIndex,
                printTimeSignature = measure.printTimeSignature,
                timeSignatureText = "${measure.timeSignature.beats}" to "${measure.timeSignature.beatValue}",
            )
            x += measureWidth
            // Bar line at the end of the measure.
            val isFinal = isLast && index == system.measures.lastIndex
            if (isFinal) {
                // A final bar line: thin then thick.
                out += ScoreDrawCommand.Line(
                    x - spacePx * 0.45f, staffTop, x - spacePx * 0.45f, staffBottom,
                    GlyphMetrics.BAR_LINE_WIDTH * spacePx, palette.staff,
                )
                out += ScoreDrawCommand.FillRect(
                    x - spacePx * 0.22f, staffTop, spacePx * 0.22f, staffBottom - staffTop,
                    palette.staff,
                )
            } else {
                out += ScoreDrawCommand.Line(
                    x, staffTop, x, staffBottom,
                    GlyphMetrics.BAR_LINE_WIDTH * spacePx, palette.staff,
                )
            }
        }
    }

    private fun leadingWidth(system: EngravedSystem, score: EngravedScore): Double =
        (GlyphMetrics.CLEF_WIDTH + abs(system.keySignatureFifths) * GlyphMetrics.ACCIDENTAL_WIDTH + 0.6f)
            .toDouble()

    // ---------------------------------------------------------------- measure

    private fun paintMeasure(
        out: MutableList<ScoreDrawCommand>,
        measure: EngravedMeasure,
        originX: Float,
        middle: Float,
        measureWidth: Float,
        stretch: Double,
        selectedNoteIds: Set<Long>,
        playheadTick: Long?,
        trackColorIndex: Int?,
        printTimeSignature: Boolean,
        timeSignatureText: Pair<String, String>,
    ) {
        fun sx(layoutX: Double): Float = originX + (layoutX * stretch).toFloat() * spacePx

        if (printTimeSignature) {
            val x = originX + spacePx * 0.5f
            out += ScoreDrawCommand.Text(
                x, middle - spacePx * 1.05f, timeSignatureText.first,
                spacePx * 2.3f, palette.note, bold = true,
                align = ScoreDrawCommand.TextAlign.LEFT,
            )
            out += ScoreDrawCommand.Text(
                x, middle + spacePx * 0.95f, timeSignatureText.second,
                spacePx * 2.3f, palette.note, bold = true,
                align = ScoreDrawCommand.TextAlign.LEFT,
            )
        }

        measure.tempoText?.let { text ->
            out += ScoreDrawCommand.Text(
                originX + spacePx * 0.2f, middle - spacePx * 4.6f, text,
                spacePx * 1.05f, palette.textDim, bold = true,
            )
        }

        // Rests.
        for (rest in measure.rests) {
            paintRest(out, rest, sx(rest.x), middle)
        }

        // Noteheads, stems and accidentals.
        val noteColor = trackColorIndex
            ?.let { palette.trackColors[it % palette.trackColors.size] }
            ?: palette.note

        for (chord in measure.chords) {
            paintChord(out, chord, sx(chord.x), middle, selectedNoteIds, noteColor)
        }

        // Beams, drawn after the stems so they sit on top of them.
        for (beam in measure.beams) {
            paintBeam(out, beam, measure, ::sx, middle, noteColor)
        }

        // Tuplet brackets.
        for (tuplet in measure.tuplets) {
            val chords = tuplet.chordIndices.mapNotNull { measure.chords.getOrNull(it) }
            if (chords.isEmpty()) continue
            val left = sx(chords.first().x)
            val right = sx(chords.last().x) + GlyphMetrics.NOTEHEAD_WIDTH * spacePx
            val above = chords.any { it.stem != StemDirection.DOWN }
            val y = if (above) {
                middle - spacePx * 4.3f
            } else {
                middle + spacePx * 4.3f
            }
            out += ScoreDrawCommand.Line(left, y, right, y, spacePx * 0.08f, palette.textDim)
            out += ScoreDrawCommand.Text(
                (left + right) / 2f, y - spacePx * 0.25f, tuplet.numerator.toString(),
                spacePx * 1.0f, palette.textDim, italic = true,
                align = ScoreDrawCommand.TextAlign.CENTER,
            )
        }

        // Chord symbols above the staff.
        for (chord in measure.chords) {
            chord.chordSymbol?.let { symbol ->
                out += ScoreDrawCommand.Text(
                    sx(chord.x), middle - spacePx * 5.6f, symbol,
                    spacePx * 1.25f, palette.text, bold = true,
                )
            }
        }

        // The playhead, when it is inside this measure.
        if (playheadTick != null) {
            val inMeasure = playheadTick >= measure.startTick &&
                playheadTick < measure.startTick + measure.timeSignature.barTicks
            if (inMeasure) {
                val progress = (playheadTick - measure.startTick).toFloat() /
                    measure.timeSignature.barTicks
                val x = originX + measureWidth * progress
                out += ScoreDrawCommand.Line(
                    x, middle - spacePx * 3.2f, x, middle + spacePx * 3.2f,
                    spacePx * 0.14f, palette.playhead,
                )
            }
        }
    }

    // ---------------------------------------------------------------- chords

    private fun paintChord(
        out: MutableList<ScoreDrawCommand>,
        chord: EngravedChord,
        x: Float,
        middle: Float,
        selectedNoteIds: Set<Long>,
        baseColor: Int,
    ) {
        val headWidth = GlyphMetrics.NOTEHEAD_WIDTH * spacePx
        val headHeight = GlyphMetrics.NOTEHEAD_HEIGHT * spacePx

        // Ledger lines, drawn before the heads so the heads sit on them.
        for (step in chord.ledgerStepsAbove + chord.ledgerStepsBelow) {
            val y = middle - step * spacePx / 2f
            out += ScoreDrawCommand.Line(
                x - GlyphMetrics.LEDGER_OVERHANG * spacePx,
                y,
                x + headWidth + GlyphMetrics.LEDGER_OVERHANG * spacePx,
                y,
                GlyphMetrics.STAFF_LINE_WIDTH * spacePx * 1.2f,
                palette.staff,
            )
        }

        for (head in chord.noteheads) {
            val y = middle - head.staffStep * spacePx / 2f
            val shift = if (head.shifted) {
                if (chord.stem == StemDirection.UP) headWidth else -headWidth
            } else {
                0f
            }
            val cx = x + headWidth / 2f + shift
            val color = if (head.noteId in selectedNoteIds) palette.noteSelected else baseColor

            out += ScoreDrawCommand.Ellipse(
                cx = cx, cy = y,
                rx = headWidth / 2f, ry = headHeight / 2f,
                // Engraved noteheads lean, which is what stops a row of them looking
                // like a row of circles.
                rotationDegrees = -20f,
                color = color,
                filled = chord.value.filled,
                strokeWidth = if (chord.value.filled) 0f else spacePx * 0.16f,
            )

            // Accidental, to the left of the head.
            head.accidental?.let { glyph ->
                out += ScoreDrawCommand.Text(
                    x - spacePx * 0.35f, y + spacePx * 0.45f, accidentalText(glyph),
                    spacePx * 2.4f, palette.accidental,
                    align = ScoreDrawCommand.TextAlign.RIGHT,
                )
            }

            // Augmentation dots.
            repeat(chord.value.dots) { i ->
                out += ScoreDrawCommand.Circle(
                    cx + headWidth * 0.75f + i * spacePx * 0.38f,
                    // A dot on a line is nudged into the space above it.
                    if (head.staffStep % 2 == 0) y - spacePx * 0.5f else y,
                    spacePx * 0.12f,
                    color,
                )
            }

            // Ties.
            if (head.tieToNext) {
                val below = chord.stem == StemDirection.UP
                val startX = cx + headWidth * 0.55f
                val endX = startX + spacePx * 2.2f
                val tieY = y + if (below) spacePx * 0.55f else -spacePx * 0.55f
                val controlY = tieY + if (below) spacePx * 0.8f else -spacePx * 0.8f
                out += ScoreDrawCommand.Curve(
                    startX, tieY, (startX + endX) / 2f, controlY, endX, tieY,
                    width = spacePx * 0.12f, color = baseColor,
                )
            }
        }

        // Stem and flags.
        if (chord.stem != StemDirection.NONE) {
            val headWidthHalf = headWidth
            val up = chord.stem == StemDirection.UP
            val anchorStep = if (up) chord.bottomStep else chord.topStep
            val farStep = if (up) chord.topStep else chord.bottomStep
            val anchorY = middle - anchorStep * spacePx / 2f
            val farY = middle - farStep * spacePx / 2f
            // The stem is attached to the right of an up-stem head and the left of a
            // down-stem one, and reaches at least to the middle line.
            val stemX = if (up) x + headWidthHalf - spacePx * 0.07f else x + spacePx * 0.07f
            val length = max(GlyphMetrics.STEM_LENGTH * spacePx, abs(farY - anchorY) + spacePx * 2.4f)
            val tipY = if (up) anchorY - length else anchorY + length
            out += ScoreDrawCommand.Line(
                stemX, anchorY, stemX, tipY,
                GlyphMetrics.STEM_WIDTH * spacePx, baseColor,
            )

            // Unbeamed short notes get flags.
            if (chord.beamGroup == null && chord.value.beamCount > 0) {
                paintFlags(out, stemX, tipY, up, chord.value.beamCount, baseColor)
            }
        }

        // Articulation marks above or below the staff.
        if (chord.articulations.isNotEmpty()) {
            paintArticulations(out, chord, x + headWidth / 2f, middle, baseColor)
        }
    }

    private fun paintFlags(
        out: MutableList<ScoreDrawCommand>,
        stemX: Float,
        tipY: Float,
        up: Boolean,
        count: Int,
        color: Int,
    ) {
        val direction = if (up) 1f else -1f
        for (i in 0 until count) {
            val y = tipY + direction * i * spacePx * 0.78f
            // A flag is a filled hook, drawn as a curve with thickness.
            out += ScoreDrawCommand.Curve(
                x0 = stemX, y0 = y,
                cx = stemX + spacePx * 1.1f, cy = y + direction * spacePx * 0.5f,
                x1 = stemX + spacePx * 0.72f, y1 = y + direction * spacePx * 1.5f,
                width = spacePx * 0.3f, color = color, filled = true,
                thickness = spacePx * 0.3f,
            )
        }
    }

    private fun paintBeam(
        out: MutableList<ScoreDrawCommand>,
        beam: EngravedBeam,
        measure: EngravedMeasure,
        sx: (Double) -> Float,
        middle: Float,
        color: Int,
    ) {
        val chords = beam.chordIndices.mapNotNull { measure.chords.getOrNull(it) }
        if (chords.isEmpty()) return
        val up = chords.first().stem != StemDirection.DOWN
        val headWidth = GlyphMetrics.NOTEHEAD_WIDTH * spacePx

        fun stemX(chord: EngravedChord): Float =
            sx(chord.x) + if (up) headWidth - spacePx * 0.07f else spacePx * 0.07f

        fun stemTipY(chord: EngravedChord): Float {
            val anchorStep = if (up) chord.bottomStep else chord.topStep
            val farStep = if (up) chord.topStep else chord.bottomStep
            val anchorY = middle - anchorStep * spacePx / 2f
            val farY = middle - farStep * spacePx / 2f
            val length = max(GlyphMetrics.STEM_LENGTH * spacePx, abs(farY - anchorY) + spacePx * 2.4f)
            return if (up) anchorY - length else anchorY + length
        }

        if (beam.partial || chords.size == 1) {
            // A partial beam is a stub pointing into the group.
            val chord = chords.first()
            val x = stemX(chord)
            val y = stemTipY(chord) + beamOffset(beam.level, up)
            val stub = spacePx * 1.0f
            val from = if (beam.partialForward) x else x - stub
            val to = if (beam.partialForward) x + stub else x
            out += ScoreDrawCommand.FillRect(
                from, y - if (up) GlyphMetrics.BEAM_THICKNESS * spacePx else 0f,
                to - from, GlyphMetrics.BEAM_THICKNESS * spacePx, color,
            )
            return
        }

        val first = chords.first()
        val last = chords.last()
        val x1 = stemX(first)
        val x2 = stemX(last)
        // The beam is slanted to follow the contour of the notes, which is what makes a
        // beamed group read as a shape rather than as separate notes.
        val y1 = stemTipY(first) + beamOffset(beam.level, up)
        val y2 = stemTipY(last) + beamOffset(beam.level, up)
        val thickness = GlyphMetrics.BEAM_THICKNESS * spacePx
        val top1 = if (up) y1 - thickness else y1
        val top2 = if (up) y2 - thickness else y2
        out += ScoreDrawCommand.Polygon(
            floatArrayOf(
                x1, top1,
                x2, top2,
                x2, top2 + thickness,
                x1, top1 + thickness,
            ),
            color,
        )
        // Extend each stem to meet the beam, so no stem falls short of it.
        for (chord in chords) {
            val x = stemX(chord)
            val t = if (x2 == x1) 0f else (x - x1) / (x2 - x1)
            val beamY = y1 + (y2 - y1) * t
            out += ScoreDrawCommand.Line(
                x, stemTipY(chord), x, beamY,
                GlyphMetrics.STEM_WIDTH * spacePx, color,
            )
        }
    }

    private fun beamOffset(level: Int, up: Boolean): Float {
        val step = (GlyphMetrics.BEAM_THICKNESS + GlyphMetrics.BEAM_GAP) * spacePx
        return (level - 1) * step * if (up) 1f else -1f
    }

    private fun paintArticulations(
        out: MutableList<ScoreDrawCommand>,
        chord: EngravedChord,
        cx: Float,
        middle: Float,
        color: Int,
    ) {
        val above = chord.stem == StemDirection.DOWN
        var y = if (above) {
            middle - (chord.topStep.coerceAtLeast(4) + 3) * spacePx / 2f
        } else {
            middle - (chord.bottomStep.coerceAtMost(-4) - 3) * spacePx / 2f
        }
        val marks = ArrayList<String>()
        if (chord.articulations.contains(com.tabtap.music.Articulation.STACCATO)) marks += "."
        if (chord.articulations.contains(com.tabtap.music.Articulation.ACCENT)) marks += ">"
        if (chord.articulations.contains(com.tabtap.music.Articulation.PALM_MUTE)) marks += "P.M."
        if (chord.articulations.contains(com.tabtap.music.Articulation.LET_RING)) marks += "let ring"
        if (chord.articulations.contains(com.tabtap.music.Articulation.HARMONIC)) marks += "harm."
        if (chord.articulations.contains(com.tabtap.music.Articulation.VIBRATO) ||
            chord.articulations.contains(com.tabtap.music.Articulation.WIDE_VIBRATO)
        ) marks += "~"
        for (mark in marks) {
            out += ScoreDrawCommand.Text(
                cx, y, mark, spacePx * 1.0f, color,
                align = ScoreDrawCommand.TextAlign.CENTER, italic = mark.length > 1,
            )
            y += if (above) -spacePx * 1.1f else spacePx * 1.1f
        }
    }

    // ---------------------------------------------------------------- rests

    private fun paintRest(
        out: MutableList<ScoreDrawCommand>,
        rest: EngravedRest,
        x: Float,
        middle: Float,
    ) {
        val color = palette.note
        when (rest.value.divisionLevel) {
            0 -> {
                // A whole rest hangs below the fourth line.
                out += ScoreDrawCommand.FillRect(
                    x, middle - spacePx, spacePx * 1.3f, spacePx * 0.5f, color,
                )
            }
            1 -> {
                // A half rest sits on the middle line.
                out += ScoreDrawCommand.FillRect(
                    x, middle - spacePx * 0.5f, spacePx * 1.3f, spacePx * 0.5f, color,
                )
            }
            2 -> paintQuarterRest(out, x, middle, color)
            else -> paintFlagRest(out, x, middle, rest.value.divisionLevel - 2, color)
        }
        repeat(rest.value.dots) { i ->
            out += ScoreDrawCommand.Circle(
                x + spacePx * (1.6f + i * 0.38f), middle - spacePx * 0.5f, spacePx * 0.12f, color,
            )
        }
    }

    /**
     * The quarter rest, drawn as the conventional zig-zag: three strokes down the staff
     * and a small hook. Built from line segments because no reliable font carries it.
     */
    private fun paintQuarterRest(
        out: MutableList<ScoreDrawCommand>,
        x: Float,
        middle: Float,
        color: Int,
    ) {
        val w = spacePx * 0.62f
        val thickness = spacePx * 0.22f
        val top = middle - spacePx * 1.5f
        out += ScoreDrawCommand.Line(x, top, x + w, top + spacePx * 0.8f, thickness, color, rounded = true)
        out += ScoreDrawCommand.Line(
            x + w, top + spacePx * 0.8f, x, top + spacePx * 1.55f, thickness, color, rounded = true,
        )
        out += ScoreDrawCommand.Line(
            x, top + spacePx * 1.55f, x + w * 1.1f, top + spacePx * 2.4f, thickness, color, rounded = true,
        )
        // The closing hook curls back to the left.
        out += ScoreDrawCommand.Curve(
            x + w * 1.1f, top + spacePx * 2.4f,
            x + w * 0.1f, top + spacePx * 2.5f,
            x + w * 0.75f, top + spacePx * 3.0f,
            width = thickness * 0.9f, color = color,
        )
    }

    /** Eighth-note rests and shorter: a stem with one hook per beam level. */
    private fun paintFlagRest(
        out: MutableList<ScoreDrawCommand>,
        x: Float,
        middle: Float,
        hooks: Int,
        color: Int,
    ) {
        val thickness = spacePx * 0.14f
        val topY = middle - spacePx * (0.5f + 0.75f * (hooks - 1))
        val bottomY = middle + spacePx * 1.1f
        out += ScoreDrawCommand.Line(
            x + spacePx * 0.62f, topY, x + spacePx * 0.3f, bottomY, thickness, color,
        )
        for (i in 0 until hooks) {
            val y = topY + i * spacePx * 0.75f
            out += ScoreDrawCommand.Circle(x + spacePx * 0.3f, y, spacePx * 0.2f, color)
            out += ScoreDrawCommand.Line(
                x + spacePx * 0.3f, y, x + spacePx * 0.68f, y - spacePx * 0.12f, thickness, color,
            )
        }
    }

    // ---------------------------------------------------------------- clefs

    /**
     * Clefs, drawn as outlines.
     *
     * Android's system fonts do not contain the Unicode musical clef characters, and no
     * music font can be assumed present, so these are built from curves. They are
     * recognisable and correctly positioned — the treble clef's spiral centres on the G
     * line, the bass clef's dot pair straddles the F line — which is what a reader needs.
     *
     * @return the width consumed, in pixels.
     */
    private fun paintClef(
        out: MutableList<ScoreDrawCommand>,
        clef: ClefType,
        x: Float,
        middle: Float,
    ): Float {
        when (clef) {
            ClefType.TREBLE, ClefType.TREBLE_8VB -> {
                // The G line is the second from the bottom: staff step -2.
                val gLine = middle + spacePx
                val stroke = spacePx * 0.26f
                // The long vertical sweep.
                out += ScoreDrawCommand.Curve(
                    x + spacePx * 0.95f, middle - spacePx * 3.4f,
                    x + spacePx * 1.9f, middle - spacePx * 1.2f,
                    x + spacePx * 0.5f, gLine,
                    stroke, palette.staff,
                )
                out += ScoreDrawCommand.Curve(
                    x + spacePx * 0.5f, gLine,
                    x - spacePx * 0.5f, gLine + spacePx * 0.9f,
                    x + spacePx * 0.95f, middle + spacePx * 2.1f,
                    stroke, palette.staff,
                )
                out += ScoreDrawCommand.Curve(
                    x + spacePx * 0.95f, middle + spacePx * 2.1f,
                    x + spacePx * 1.75f, middle + spacePx * 3.1f,
                    x + spacePx * 0.65f, middle + spacePx * 3.5f,
                    stroke, palette.staff,
                )
                // The spiral around the G line.
                out += ScoreDrawCommand.Ellipse(
                    x + spacePx * 0.78f, gLine, spacePx * 0.72f, spacePx * 0.62f, 0f,
                    palette.staff, filled = false, strokeWidth = stroke,
                )
                out += ScoreDrawCommand.Circle(
                    x + spacePx * 0.95f, middle + spacePx * 3.6f, spacePx * 0.22f, palette.staff,
                )
                if (clef == ClefType.TREBLE_8VB) {
                    out += ScoreDrawCommand.Text(
                        x + spacePx * 0.9f, middle + spacePx * 5.0f, "8",
                        spacePx * 1.2f, palette.staff,
                        align = ScoreDrawCommand.TextAlign.CENTER,
                    )
                }
            }
            ClefType.BASS, ClefType.BASS_8VB -> {
                // The F line is the fourth from the bottom: staff step +2.
                val fLine = middle - spacePx
                val stroke = spacePx * 0.3f
                out += ScoreDrawCommand.Circle(x + spacePx * 0.45f, fLine, spacePx * 0.34f, palette.staff)
                out += ScoreDrawCommand.Curve(
                    x + spacePx * 0.45f, fLine - spacePx * 0.35f,
                    x + spacePx * 2.6f, fLine + spacePx * 0.2f,
                    x + spacePx * 0.7f, fLine + spacePx * 2.7f,
                    stroke, palette.staff,
                )
                // The two dots that straddle the F line.
                out += ScoreDrawCommand.Circle(
                    x + spacePx * 2.1f, fLine - spacePx * 0.5f, spacePx * 0.17f, palette.staff,
                )
                out += ScoreDrawCommand.Circle(
                    x + spacePx * 2.1f, fLine + spacePx * 0.5f, spacePx * 0.17f, palette.staff,
                )
                if (clef == ClefType.BASS_8VB) {
                    out += ScoreDrawCommand.Text(
                        x + spacePx * 1.2f, middle + spacePx * 4.4f, "8",
                        spacePx * 1.2f, palette.staff,
                        align = ScoreDrawCommand.TextAlign.CENTER,
                    )
                }
            }
        }
        return GlyphMetrics.CLEF_WIDTH * spacePx
    }

    /**
     * The key signature, at the conventional staff positions for the clef.
     * @return the width consumed, in pixels.
     */
    private fun paintKeySignature(
        out: MutableList<ScoreDrawCommand>,
        fifths: Int,
        clef: ClefType,
        x: Float,
        middle: Float,
    ): Float {
        if (fifths == 0) return spacePx * 0.5f
        val letters = KeySignature.accidentalLetters(fifths)
        val glyph = if (fifths > 0) "♯" else "♭"
        var cursor = x + spacePx * 0.3f
        for (letter in letters) {
            val step = signatureStep(letter, fifths > 0, clef)
            val y = middle - step * spacePx / 2f
            out += ScoreDrawCommand.Text(
                cursor, y + spacePx * 0.45f, glyph, spacePx * 2.4f, palette.staff,
            )
            cursor += GlyphMetrics.ACCIDENTAL_WIDTH * spacePx
        }
        return cursor - x + spacePx * 0.4f
    }

    /**
     * Staff step for a key-signature accidental.
     *
     * The positions are fixed by convention rather than calculated: sharps start on the
     * top line's F in treble clef and descend in the published order, and flats start on
     * the middle line's B. The bass clef's positions are each two steps lower.
     */
    private fun signatureStep(letter: NoteLetter, sharp: Boolean, clef: ClefType): Int {
        val trebleSharp = mapOf(
            NoteLetter.F to 8, NoteLetter.C to 5, NoteLetter.G to 9, NoteLetter.D to 6,
            NoteLetter.A to 3, NoteLetter.E to 7, NoteLetter.B to 4,
        )
        val trebleFlat = mapOf(
            NoteLetter.B to 4, NoteLetter.E to 7, NoteLetter.A to 3, NoteLetter.D to 6,
            NoteLetter.G to 2, NoteLetter.C to 5, NoteLetter.F to 1,
        )
        val base = (if (sharp) trebleSharp[letter] else trebleFlat[letter]) ?: 0
        return if (clef.isBassClef) base - 2 else base
    }

    private fun accidentalText(glyph: AccidentalGlyph): String = when (glyph) {
        AccidentalGlyph.DOUBLE_FLAT -> "♭♭"
        AccidentalGlyph.FLAT -> "♭"
        AccidentalGlyph.NATURAL -> "♮"
        AccidentalGlyph.SHARP -> "♯"
        AccidentalGlyph.DOUBLE_SHARP -> "×"
    }
}
