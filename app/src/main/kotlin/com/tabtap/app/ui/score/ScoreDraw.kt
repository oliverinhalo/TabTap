package com.tabtap.app.ui.score

/**
 * Platform-neutral drawing commands.
 *
 * The engraver produces these; a Compose canvas and an Android PDF canvas both execute
 * them. That is why the printed sheet music is identical to what is on screen rather
 * than a second implementation that drifts from the first — and it means the painter can
 * be unit tested by inspecting the commands it emits.
 *
 * Coordinates are in pixels, already scaled from the layout's staff spaces by the caller.
 * Colours are packed ARGB integers so neither backend's colour type leaks in here.
 */
sealed interface ScoreDrawCommand {

    data class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val width: Float, val color: Int, val rounded: Boolean = false,
    ) : ScoreDrawCommand

    data class FillRect(
        val x: Float, val y: Float, val width: Float, val height: Float, val color: Int,
    ) : ScoreDrawCommand

    /** A notehead: an ellipse rotated slightly, as engraved noteheads are. */
    data class Ellipse(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val rotationDegrees: Float, val color: Int,
        val filled: Boolean, val strokeWidth: Float = 0f,
    ) : ScoreDrawCommand

    data class Circle(
        val cx: Float, val cy: Float, val radius: Float, val color: Int,
    ) : ScoreDrawCommand

    /** A filled polygon, used for beams and flags. */
    data class Polygon(val points: FloatArray, val color: Int) : ScoreDrawCommand {
        override fun equals(other: Any?): Boolean =
            other is Polygon && color == other.color && points.contentEquals(other.points)

        override fun hashCode(): Int = 31 * points.contentHashCode() + color
    }

    /** A quadratic curve, used for ties, slurs and flag tails. */
    data class Curve(
        val x0: Float, val y0: Float,
        val cx: Float, val cy: Float,
        val x1: Float, val y1: Float,
        val width: Float, val color: Int, val filled: Boolean = false,
        /** For a filled tie, the thickness at its middle. */
        val thickness: Float = 0f,
    ) : ScoreDrawCommand

    data class Text(
        val x: Float, val y: Float, val text: String, val sizePx: Float,
        val color: Int, val bold: Boolean = false, val italic: Boolean = false,
        val align: TextAlign = TextAlign.LEFT, val monospace: Boolean = false,
    ) : ScoreDrawCommand

    enum class TextAlign { LEFT, CENTER, RIGHT }
}

/** The colours the score is drawn in, as ARGB integers. */
data class ScorePalette(
    val staff: Int,
    val note: Int,
    val noteSelected: Int,
    val accidental: Int,
    val text: Int,
    val textDim: Int,
    val playhead: Int,
    val background: Int,
    /** Per-track colours, for multi-part display. */
    val trackColors: List<Int>,
) {
    companion object {
        /** The on-screen palette: light marks on the app's dark surface. */
        val DARK = ScorePalette(
            staff = 0xFF4A5560.toInt(),
            note = 0xFFE9EDF2.toInt(),
            noteSelected = 0xFFFFFFFF.toInt(),
            accidental = 0xFFC3CCD6.toInt(),
            text = 0xFFE9EDF2.toInt(),
            textDim = 0xFF8A96A4.toInt(),
            playhead = 0xFFFF6B5C.toInt(),
            background = 0xFF0B0D10.toInt(),
            trackColors = listOf(
                0xFFFFB454.toInt(), 0xFF4CC2A8.toInt(), 0xFF7D9BFF.toInt(),
                0xFFE57BB8.toInt(), 0xFF9BD45E.toInt(), 0xFF52BEE8.toInt(),
            ),
        )

        /** The print palette: black on white, as sheet music is read. */
        val PRINT = ScorePalette(
            staff = 0xFF000000.toInt(),
            note = 0xFF000000.toInt(),
            noteSelected = 0xFF000000.toInt(),
            accidental = 0xFF000000.toInt(),
            text = 0xFF000000.toInt(),
            textDim = 0xFF555555.toInt(),
            playhead = 0xFF000000.toInt(),
            background = 0xFFFFFFFF.toInt(),
            trackColors = List(6) { 0xFF000000.toInt() },
        )
    }
}

/**
 * Hand-built vector outlines for the glyphs no system font can be relied on to carry.
 *
 * Android's fonts include the accidental characters (♯ ♭ ♮) but not the clefs, rests or
 * flags of the Unicode musical symbols block, and no SMuFL music font is guaranteed to
 * be present. Rather than ship a font or draw an approximation, the handful of glyphs
 * that matter are defined here as outlines in a normalised coordinate space where one
 * unit is one staff space, with the origin on the glyph's own baseline — the staff line
 * the clef names, the middle line for a rest.
 */
internal object ScoreGlyphs

/** Nominal advance widths, in staff spaces, used to position text without measuring it. */
internal object GlyphMetrics {
    const val CLEF_WIDTH = 2.8f
    const val ACCIDENTAL_WIDTH = 0.95f
    const val NOTEHEAD_WIDTH = 1.18f
    const val NOTEHEAD_HEIGHT = 0.72f
    const val STEM_WIDTH = 0.13f
    const val STEM_LENGTH = 3.5f
    const val BEAM_THICKNESS = 0.5f
    const val BEAM_GAP = 0.3f
    const val STAFF_LINE_WIDTH = 0.09f
    const val BAR_LINE_WIDTH = 0.13f
    const val LEDGER_OVERHANG = 0.35f
}
