package com.tabtap.app.ui.score

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/**
 * Executes [ScoreDrawCommand]s on a Compose canvas.
 *
 * This is the only place Compose drawing appears in the score path; the engraving and
 * the layout are platform-neutral, and the PDF exporter runs the identical command list
 * through an Android canvas instead.
 */
fun DrawScope.drawScoreCommands(
    commands: List<ScoreDrawCommand>,
    measurer: TextMeasurer,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) {
    for (command in commands) {
        when (command) {
            is ScoreDrawCommand.Line -> drawLine(
                color = Color(command.color),
                start = Offset(command.x1 + offsetX, command.y1 + offsetY),
                end = Offset(command.x2 + offsetX, command.y2 + offsetY),
                strokeWidth = command.width,
                cap = if (command.rounded) StrokeCap.Round else StrokeCap.Butt,
            )

            is ScoreDrawCommand.FillRect -> drawRect(
                color = Color(command.color),
                topLeft = Offset(command.x + offsetX, command.y + offsetY),
                size = Size(command.width, command.height),
            )

            is ScoreDrawCommand.Ellipse -> rotate(
                degrees = command.rotationDegrees,
                pivot = Offset(command.cx + offsetX, command.cy + offsetY),
            ) {
                val topLeft = Offset(
                    command.cx + offsetX - command.rx,
                    command.cy + offsetY - command.ry,
                )
                val size = Size(command.rx * 2f, command.ry * 2f)
                if (command.filled) {
                    drawOval(Color(command.color), topLeft, size)
                } else {
                    drawOval(
                        Color(command.color), topLeft, size,
                        style = Stroke(command.strokeWidth),
                    )
                }
            }

            is ScoreDrawCommand.Circle -> drawCircle(
                color = Color(command.color),
                radius = command.radius,
                center = Offset(command.cx + offsetX, command.cy + offsetY),
            )

            is ScoreDrawCommand.Polygon -> {
                val path = Path()
                val p = command.points
                if (p.size >= 4) {
                    path.moveTo(p[0] + offsetX, p[1] + offsetY)
                    var i = 2
                    while (i + 1 < p.size) {
                        path.lineTo(p[i] + offsetX, p[i + 1] + offsetY)
                        i += 2
                    }
                    path.close()
                    drawPath(path, Color(command.color))
                }
            }

            is ScoreDrawCommand.Curve -> {
                val path = Path()
                path.moveTo(command.x0 + offsetX, command.y0 + offsetY)
                path.quadraticTo(
                    command.cx + offsetX, command.cy + offsetY,
                    command.x1 + offsetX, command.y1 + offsetY,
                )
                if (command.filled) {
                    // A filled curve (a flag) is closed back on itself with an offset.
                    path.quadraticTo(
                        command.cx + offsetX - command.thickness,
                        command.cy + offsetY + command.thickness,
                        command.x0 + offsetX, command.y0 + offsetY,
                    )
                    path.close()
                    drawPath(path, Color(command.color))
                } else {
                    drawPath(
                        path, Color(command.color),
                        style = Stroke(command.width, cap = StrokeCap.Round),
                    )
                }
            }

            is ScoreDrawCommand.Text -> {
                val layout = measurer.measure(
                    command.text,
                    TextStyle(
                        fontSize = TextUnit(command.sizePx, TextUnitType.Sp),
                        fontWeight = if (command.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (command.italic) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = if (command.monospace) FontFamily.Monospace else FontFamily.SansSerif,
                        color = Color(command.color),
                    ),
                )
                val x = when (command.align) {
                    ScoreDrawCommand.TextAlign.LEFT -> command.x
                    ScoreDrawCommand.TextAlign.CENTER -> command.x - layout.size.width / 2f
                    ScoreDrawCommand.TextAlign.RIGHT -> command.x - layout.size.width
                }
                drawText(
                    layout,
                    topLeft = Offset(x + offsetX, command.y + offsetY - layout.size.height),
                )
            }
        }
    }
}

/**
 * The bounding box of a command list, so a scrollable score can size its content.
 * Text is approximated from its nominal size, which is accurate enough for layout.
 */
fun List<ScoreDrawCommand>.bounds(): Rect {
    if (isEmpty()) return Rect.Zero
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    fun include(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }
    for (command in this) {
        when (command) {
            is ScoreDrawCommand.Line -> { include(command.x1, command.y1); include(command.x2, command.y2) }
            is ScoreDrawCommand.FillRect -> {
                include(command.x, command.y)
                include(command.x + command.width, command.y + command.height)
            }
            is ScoreDrawCommand.Ellipse -> {
                include(command.cx - command.rx, command.cy - command.ry)
                include(command.cx + command.rx, command.cy + command.ry)
            }
            is ScoreDrawCommand.Circle -> {
                include(command.cx - command.radius, command.cy - command.radius)
                include(command.cx + command.radius, command.cy + command.radius)
            }
            is ScoreDrawCommand.Polygon -> {
                var i = 0
                while (i + 1 < command.points.size) {
                    include(command.points[i], command.points[i + 1]); i += 2
                }
            }
            is ScoreDrawCommand.Curve -> {
                include(command.x0, command.y0); include(command.x1, command.y1)
                include(command.cx, command.cy)
            }
            is ScoreDrawCommand.Text -> {
                include(command.x, command.y - command.sizePx)
                include(command.x + command.sizePx * 0.62f * command.text.length, command.y)
            }
        }
    }
    return Rect(minX, minY, maxX, maxY)
}
