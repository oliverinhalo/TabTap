package com.tabtap.app.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tabtap.app.ui.score.ScoreDrawCommand
import com.tabtap.app.ui.score.ScorePainter
import com.tabtap.app.ui.score.ScorePalette
import com.tabtap.app.ui.score.TabPainter
import com.tabtap.music.Composition
import com.tabtap.music.Track
import com.tabtap.music.notation.ScoreEngraver
import com.tabtap.music.notation.TabEngraver
import java.io.OutputStream
import kotlin.math.max

/** What a PDF export should contain. */
data class PdfOptions(
    val includeNotation: Boolean = true,
    val includeTablature: Boolean = true,
    /** One section per track, rather than only the active one. */
    val allTracks: Boolean = true,
    val includeChordDiagrams: Boolean = true,
    val title: String = "Untitled",
    val landscape: Boolean = false,
)

/**
 * Renders a composition to a PDF.
 *
 * The engraving is not re-implemented here: the same [ScorePainter] and [TabPainter] that
 * draw the screen produce a list of platform-neutral commands, and this class executes
 * them on an Android canvas instead of a Compose one. That is why the printed page
 * matches what the user was looking at, down to the beaming and the accidentals.
 *
 * Pages are A4 at 72 points per inch, which is what `PdfDocument` works in.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595    // A4 at 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 42f

    /** Write a PDF of [composition] to [stream]. The stream is closed by the caller. */
    fun export(composition: Composition, options: PdfOptions, stream: OutputStream) {
        val document = PdfDocument()
        try {
            val pageWidth = if (options.landscape) PAGE_HEIGHT else PAGE_WIDTH
            val pageHeight = if (options.landscape) PAGE_WIDTH else PAGE_HEIGHT
            val contentWidth = pageWidth - MARGIN * 2

            val tracks = if (options.allTracks) {
                composition.tracks
            } else {
                listOfNotNull(composition.activeTrack)
            }

            // Build every block of drawing first, then paginate, so a staff is never
            // split across a page break.
            val blocks = ArrayList<Block>()
            blocks += titleBlock(composition, options)

            for (track in tracks) {
                blocks += headingBlock(track.name + "  —  " + track.voice.displayName)
                if (options.includeNotation) {
                    blocks += notationBlock(composition, track, contentWidth)
                }
                if (options.includeTablature) {
                    blocks += tabBlock(composition, track, contentWidth)
                }
            }
            if (options.includeChordDiagrams && composition.chordProgression.isNotEmpty()) {
                blocks += headingBlock("Chords")
                blocks += chordBlock(composition, contentWidth)
            }

            var pageNumber = 1
            var index = 0
            while (index < blocks.size) {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create(),
                )
                val canvas = page.canvas
                canvas.drawColor(android.graphics.Color.WHITE)
                var y = MARGIN
                while (index < blocks.size) {
                    val block = blocks[index]
                    if (y + block.height > pageHeight - MARGIN - 20f && y > MARGIN) break
                    canvas.save()
                    canvas.translate(MARGIN, y)
                    block.draw(canvas)
                    canvas.restore()
                    y += block.height + block.spacingAfter
                    index++
                }
                drawFooter(canvas, pageWidth, pageHeight, options.title, pageNumber)
                document.finishPage(page)
                pageNumber++
                // A block taller than a whole page would otherwise loop for ever.
                if (pageNumber > 200) break
            }

            document.writeTo(stream)
        } finally {
            document.close()
        }
    }

    /** One piece of drawing with a known height. */
    private class Block(
        val height: Float,
        val spacingAfter: Float,
        val draw: (Canvas) -> Unit,
    )

    private fun titleBlock(composition: Composition, options: PdfOptions): Block {
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.DKGRAY
            textSize = 10f
            typeface = Typeface.SANS_SERIF
        }
        val subtitle = buildString {
            append(composition.key.displayName)
            append("   ·   ")
            append("${composition.bpm.toInt()} BPM")
            append("   ·   ")
            append(composition.timeSignature.toString())
            append("   ·   ")
            append("${composition.barCount} bars")
        }
        return Block(46f, 14f) { canvas ->
            canvas.drawText(options.title, 0f, 20f, titlePaint)
            canvas.drawText(subtitle, 0f, 36f, subPaint)
        }
    }

    private fun headingBlock(text: String): Block {
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        return Block(18f, 4f) { canvas -> canvas.drawText(text, 0f, 12f, paint) }
    }

    private fun notationBlock(composition: Composition, track: Track, width: Float): Block {
        // 7 points per staff space gives a staff about 28 points tall, which is close to
        // the size used in printed guitar music.
        val spacePx = 7f
        val painter = ScorePainter(spacePx, ScorePalette.PRINT)
        val score = ScoreEngraver.engrave(
            track = track,
            key = composition.key,
            timeSignature = composition.timeSignature,
            tempoMap = composition.tempoMap,
            systemWidth = (width / spacePx).toDouble(),
            chordProgression = composition.chordProgression,
            minMeasures = composition.barCount,
        )
        val commands = painter.paint(score, width, trackColorIndex = null)
        return Block(painter.totalHeight(score.systems.size), 10f) { canvas ->
            executeCommands(canvas, commands)
        }
    }

    private fun tabBlock(composition: Composition, track: Track, width: Float): Block {
        val linePx = 9f
        val columnPx = 5f
        val painter = TabPainter(linePx, columnPx, ScorePalette.PRINT)
        val layout = TabEngraver.engrave(
            track = track,
            timeSignature = composition.timeSignature,
            systemWidth = (width / columnPx).toDouble(),
            chordProgression = composition.chordProgression,
            minMeasures = composition.barCount,
        )
        val commands = painter.paint(layout, width, trackColorIndex = null)
        return Block(painter.totalHeight(layout), 16f) { canvas ->
            executeCommands(canvas, commands)
        }
    }

    private fun chordBlock(composition: Composition, width: Float): Block {
        val diagramWidth = 56f
        val diagramHeight = 72f
        val perRow = max(1, (width / (diagramWidth + 8f)).toInt())
        val rows = (composition.chordProgression.size + perRow - 1) / perRow
        val board = composition.activeTrack?.fretboard
            ?: com.tabtap.music.Fretboard(com.tabtap.music.Tunings.STANDARD_E, 24)

        return Block(rows * (diagramHeight + 14f), 12f) { canvas ->
            composition.chordProgression.forEachIndexed { index, event ->
                val voicing = event.voicing
                    ?: com.tabtap.music.ChordVoicer.bestVoicing(event.chord, board)
                    ?: return@forEachIndexed
                val col = index % perRow
                val row = index / perRow
                canvas.save()
                canvas.translate(col * (diagramWidth + 8f), row * (diagramHeight + 14f))
                drawChordDiagram(canvas, event.chord.symbol, voicing, board.stringCount, diagramWidth, diagramHeight)
                canvas.restore()
            }
        }
    }

    /** A printed chord diagram, in the same conventions as the on-screen one. */
    private fun drawChordDiagram(
        canvas: Canvas,
        symbol: String,
        voicing: com.tabtap.music.ChordVoicing,
        stringCount: Int,
        width: Float,
        height: Float,
    ) {
        val stroke = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
        }
        val fill = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        val text = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(symbol, width / 2f, 9f, text)

        val top = 22f
        val boardHeight = height - top - 4f
        val frets = 5
        val startFret = max(1, voicing.position)
        val stringGap = (width - 8f) / (stringCount - 1).coerceAtLeast(1)
        val fretGap = boardHeight / frets

        for (s in 0 until stringCount) {
            val x = 4f + s * stringGap
            canvas.drawLine(x, top, x, top + boardHeight, stroke)
        }
        for (f in 0..frets) {
            val y = top + f * fretGap
            if (startFret == 1 && f == 0) {
                val nut = Paint(fill).apply { strokeWidth = 2.5f; style = Paint.Style.STROKE }
                canvas.drawLine(4f, y, width - 4f, y, nut)
            } else {
                canvas.drawLine(4f, y, width - 4f, y, stroke)
            }
        }
        if (startFret > 1) {
            val small = Paint(text).apply { textSize = 7f; textAlign = Paint.Align.LEFT }
            canvas.drawText("$startFret", 0f, top + fretGap * 0.75f, small)
        }
        voicing.frets.forEachIndexed { stringIndex, fret ->
            val x = 4f + stringIndex * stringGap
            when {
                fret == null -> {
                    canvas.drawLine(x - 2.5f, top - 8f, x + 2.5f, top - 3f, stroke)
                    canvas.drawLine(x - 2.5f, top - 3f, x + 2.5f, top - 8f, stroke)
                }
                fret == 0 -> canvas.drawCircle(x, top - 5.5f, 2.5f, stroke)
                else -> {
                    val offset = fret - startFret
                    if (offset in 0 until frets) {
                        canvas.drawCircle(x, top + (offset + 0.5f) * fretGap, stringGap * 0.3f, fill)
                    }
                }
            }
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        title: String,
        page: Int,
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.GRAY
            textSize = 8f
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText(title, MARGIN, pageHeight - 20f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("$page", pageWidth - MARGIN, pageHeight - 20f, paint)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Engraved with TabTap", pageWidth / 2f, pageHeight - 20f, paint)
    }

    /**
     * Executes the painter's commands on an Android canvas.
     *
     * The same list, drawn by the Compose executor, produces the on-screen score; this
     * is the print-side implementation of the identical instructions.
     */
    private fun executeCommands(canvas: Canvas, commands: List<ScoreDrawCommand>) {
        val paint = Paint().apply { isAntiAlias = true }
        val path = Path()

        for (command in commands) {
            when (command) {
                is ScoreDrawCommand.Line -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    paint.strokeWidth = command.width
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = if (command.rounded) Paint.Cap.ROUND else Paint.Cap.BUTT
                    canvas.drawLine(command.x1, command.y1, command.x2, command.y2, paint)
                }

                is ScoreDrawCommand.FillRect -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(
                        command.x, command.y,
                        command.x + command.width, command.y + command.height,
                        paint,
                    )
                }

                is ScoreDrawCommand.Ellipse -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    paint.style = if (command.filled) Paint.Style.FILL else Paint.Style.STROKE
                    if (!command.filled) paint.strokeWidth = command.strokeWidth
                    canvas.save()
                    canvas.rotate(command.rotationDegrees, command.cx, command.cy)
                    canvas.drawOval(
                        RectF(
                            command.cx - command.rx, command.cy - command.ry,
                            command.cx + command.rx, command.cy + command.ry,
                        ),
                        paint,
                    )
                    canvas.restore()
                }

                is ScoreDrawCommand.Circle -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(command.cx, command.cy, command.radius, paint)
                }

                is ScoreDrawCommand.Polygon -> {
                    val p = command.points
                    if (p.size >= 4) {
                        path.reset()
                        path.moveTo(p[0], p[1])
                        var i = 2
                        while (i + 1 < p.size) {
                            path.lineTo(p[i], p[i + 1])
                            i += 2
                        }
                        path.close()
                        paint.reset()
                        paint.isAntiAlias = true
                        paint.color = command.color
                        paint.style = Paint.Style.FILL
                        canvas.drawPath(path, paint)
                    }
                }

                is ScoreDrawCommand.Curve -> {
                    path.reset()
                    path.moveTo(command.x0, command.y0)
                    path.quadTo(command.cx, command.cy, command.x1, command.y1)
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    if (command.filled) {
                        path.quadTo(
                            command.cx - command.thickness, command.cy + command.thickness,
                            command.x0, command.y0,
                        )
                        path.close()
                        paint.style = Paint.Style.FILL
                    } else {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = command.width
                        paint.strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawPath(path, paint)
                }

                is ScoreDrawCommand.Text -> {
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.color = command.color
                    paint.textSize = command.sizePx
                    paint.typeface = Typeface.create(
                        if (command.monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                        when {
                            command.bold && command.italic -> Typeface.BOLD_ITALIC
                            command.bold -> Typeface.BOLD
                            command.italic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        },
                    )
                    paint.textAlign = when (command.align) {
                        ScoreDrawCommand.TextAlign.LEFT -> Paint.Align.LEFT
                        ScoreDrawCommand.TextAlign.CENTER -> Paint.Align.CENTER
                        ScoreDrawCommand.TextAlign.RIGHT -> Paint.Align.RIGHT
                    }
                    canvas.drawText(command.text, command.x, command.y, paint)
                }
            }
        }
    }
}
