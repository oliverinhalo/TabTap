package com.tabtap.app.ui.fretboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabtap.app.design.MusicColors
import com.tabtap.app.FretboardDisplay
import com.tabtap.music.Fretboard
import com.tabtap.music.Interval
import com.tabtap.music.IntervalLabels
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.Pitch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A note the fretboard should mark, and the colour it belongs to. */
data class FretboardMark(
    val string: Int,
    val fret: Int,
    val color: Color,
    /** 1-based position in the recorded sequence, drawn inside the dot. */
    val sequenceIndex: Int? = null,
    val selected: Boolean = false,
    /** Faded, for notes belonging to a track that is not the active one. */
    val secondary: Boolean = false,
)

/**
 * The interactive fretboard.
 *
 * This is the app's primary instrument and the thing the whole screen is built around.
 * It is drawn rather than composed out of views because it has to stay responsive while
 * showing several hundred positions, each possibly carrying a label and a marker.
 *
 * Layout is physically accurate: fret spacing follows the twelfth-root-of-two rule from
 * [Fretboard.fretLayout], so the board narrows towards the bridge exactly as a real one
 * does and the position of a fret on screen matches where a player expects it. Strings
 * are drawn thicker towards the bass, the nut is distinct from the frets, and the inlays
 * sit where they do on an instrument.
 *
 * Strings are drawn with the lowest at the bottom, which matches the tab staff below it,
 * so a note is in the same vertical place in both views.
 */
@Composable
fun FretboardView(
    fretboard: Fretboard,
    key: Key,
    marks: List<FretboardMark>,
    display: FretboardDisplay,
    modifier: Modifier = Modifier,
    /** The position the playhead is currently sounding, highlighted while playing. */
    sounding: Set<Pair<Int, Int>> = emptySet(),
    onTap: (string: Int, fret: Int, simultaneous: Boolean) -> Unit,
    onLongPress: (string: Int, fret: Int) -> Unit = { _, _ -> },
    onWindowChange: (firstFret: Int, fretCount: Int) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var geometry by remember { mutableStateOf<FretboardGeometry?>(null) }

    // Clamp the window whenever the board changes under it.
    LaunchedEffect(fretboard.fretCount, display.visibleFretCount, display.firstVisibleFret) {
        val count = display.visibleFretCount.coerceIn(4, fretboard.fretCount)
        val first = display.firstVisibleFret.coerceIn(0, max(0, fretboard.fretCount - count))
        if (count != display.visibleFretCount || first != display.firstVisibleFret) {
            onWindowChange(first, count)
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(MusicColors.boardEdge, RoundedCornerShape(10.dp))
            .padding(1.dp),
    ) {
        val stringCount = fretboard.stringCount
        // Give each string a comfortable touch target, and let the board grow with the
        // string count so an 8-string is as playable as a 4-string bass.
        val minHeight: Dp = (stringCount * 44 + 46).dp

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .fillMaxSize()
                .pointerInput(fretboard, display.leftHanded) {
                    // Multi-touch note entry: the first finger down starts a note, and
                    // any finger that arrives while another is still down is treated as
                    // part of the same chord. That makes entering a chord the same
                    // gesture as playing one.
                    awaitEachGesture {
                        val first = awaitPointerEvent(PointerEventPass.Main)
                        val known = HashSet<Long>()
                        var anyDown = true
                        var pointersSeen = 0
                        var event = first
                        while (anyDown) {
                            for (change in event.changes) {
                                if (change.pressed && known.add(change.id.value)) {
                                    val g = geometry ?: continue
                                    val hit = g.hitTest(change.position) ?: continue
                                    onTap(hit.first, hit.second, pointersSeen > 0)
                                    pointersSeen++
                                }
                                if (!change.pressed) known.remove(change.id.value)
                            }
                            anyDown = event.changes.any { it.pressed }
                            if (!anyDown) break
                            event = awaitPointerEvent(PointerEventPass.Main)
                        }
                    }
                }
                .pointerInput(fretboard) {
                    // Pinch to change how much of the neck is visible, drag to move
                    // along it. On a 24-fret board a phone cannot show every fret at a
                    // usable size, so the window is part of the instrument.
                    detectTransformGestures(panZoomLock = true) { _, pan, zoom, _ ->
                        val g = geometry ?: return@detectTransformGestures
                        var count = display.visibleFretCount
                        var firstFret = display.firstVisibleFret
                        if (abs(zoom - 1f) > 0.01f) {
                            count = (count / zoom).roundToInt().coerceIn(4, fretboard.fretCount)
                        }
                        if (abs(pan.x) > 0.5f) {
                            val fretsPerPixel = count / g.boardWidth
                            firstFret = (firstFret - pan.x * fretsPerPixel).roundToInt()
                        }
                        firstFret = firstFret.coerceIn(0, max(0, fretboard.fretCount - count))
                        if (count != display.visibleFretCount || firstFret != display.firstVisibleFret) {
                            onWindowChange(firstFret, count)
                        }
                    }
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val g = FretboardGeometry(
                    size = size,
                    stringCount = stringCount,
                    firstFret = display.firstVisibleFret.coerceIn(
                        0, max(0, fretboard.fretCount - display.visibleFretCount),
                    ),
                    visibleFrets = display.visibleFretCount.coerceIn(4, fretboard.fretCount),
                    totalFrets = fretboard.fretCount,
                    showFretNumbers = display.showFretNumbers,
                    leftHanded = display.leftHanded,
                    labelWidthPx = with(density) { 30.dp.toPx() },
                    numberHeightPx = with(density) { 20.dp.toPx() },
                )
                geometry = g
                drawFretboard(g, fretboard, key, marks, display, sounding, measurer)
            }
        }
    }
}

/**
 * The fretboard's geometry: where every string and fret sits, and how to turn a touch
 * back into a string and fret. Separated from the drawing so hit testing and rendering
 * cannot disagree.
 */
class FretboardGeometry(
    val size: Size,
    val stringCount: Int,
    val firstFret: Int,
    val visibleFrets: Int,
    val totalFrets: Int,
    val showFretNumbers: Boolean,
    val leftHanded: Boolean,
    private val labelWidthPx: Float,
    private val numberHeightPx: Float,
) {
    /** The rectangle the board itself occupies, excluding labels and fret numbers. */
    val board: Rect = Rect(
        left = if (leftHanded) 0f else labelWidthPx,
        top = 0f,
        right = if (leftHanded) size.width - labelWidthPx else size.width,
        bottom = size.height - if (showFretNumbers) numberHeightPx else 0f,
    )

    val boardWidth: Float get() = board.width

    /** Vertical centre of each string, index 0 (lowest) at the bottom. */
    private val stringSpacing: Float get() = board.height / (stringCount + 0.6f)

    fun stringY(index: Int): Float {
        // Index 0 is the lowest string and is drawn at the bottom, matching the tab.
        val fromTop = stringCount - 1 - index
        return board.top + stringSpacing * (0.8f + fromTop)
    }

    val lastFret: Int get() = min(firstFret + visibleFrets, totalFrets)

    /**
     * Normalised x position (0..1 across the visible window) of fret [n]'s wire, using
     * the real fret spacing of the whole neck so the window shows true proportions.
     */
    private fun fretFraction(n: Int): Float {
        val layout = Fretboard.fretLayout(totalFrets)
        val start = layout[firstFret.coerceIn(0, totalFrets)]
        val end = layout[lastFret.coerceIn(0, totalFrets)]
        val span = (end - start).takeIf { it > 1e-9 } ?: 1.0
        return ((layout[n.coerceIn(0, totalFrets)] - start) / span).toFloat()
    }

    /** Screen x of the wire at the high-fret end of fret [n]. */
    fun fretWireX(n: Int): Float {
        val fraction = fretFraction(n)
        return if (leftHanded) {
            board.right - fraction * board.width
        } else {
            board.left + fraction * board.width
        }
    }

    /** Screen x where a finger stops fret [n]: between its wire and the one before. */
    fun fretCentreX(n: Int): Float = when {
        n <= firstFret -> {
            // The open-string position sits just inside the nut.
            val nut = fretWireX(firstFret)
            val next = fretWireX(firstFret + 1)
            nut + (next - nut) * 0.18f
        }
        else -> (fretWireX(n - 1) + fretWireX(n)) / 2f
    }

    /** Radius of a note marker, sized to the string spacing and the fret width. */
    fun markerRadius(fret: Int): Float {
        val vertical = stringSpacing * 0.42f
        val horizontal = if (fret > firstFret) {
            abs(fretWireX(fret) - fretWireX(fret - 1)) * 0.42f
        } else {
            vertical
        }
        return min(vertical, max(horizontal, vertical * 0.45f))
    }

    /** The string and fret under [position], or null when the touch missed the board. */
    fun hitTest(position: Offset): Pair<Int, Int>? {
        if (!board.contains(position)) return null
        // Nearest string by vertical distance.
        var bestString = 0
        var bestDistance = Float.MAX_VALUE
        for (s in 0 until stringCount) {
            val d = abs(stringY(s) - position.y)
            if (d < bestDistance) { bestDistance = d; bestString = s }
        }
        if (bestDistance > stringSpacing * 0.85f) return null

        // The fret whose span contains the touch.
        for (f in firstFret..lastFret) {
            val lower = if (f == firstFret) {
                if (leftHanded) board.right else board.left
            } else {
                fretWireX(f - 1)
            }
            val upper = fretWireX(f)
            val lo = min(lower, upper)
            val hi = max(lower, upper)
            if (position.x in lo..hi) return bestString to f
        }
        return null
    }
}

/** Draws the whole board. Called from the canvas, so everything here is cheap. */
private fun DrawScope.drawFretboard(
    g: FretboardGeometry,
    fretboard: Fretboard,
    key: Key,
    marks: List<FretboardMark>,
    display: FretboardDisplay,
    sounding: Set<Pair<Int, Int>>,
    measurer: TextMeasurer,
) {
    // --- The board itself ---
    drawRect(MusicColors.board, topLeft = g.board.topLeft, size = g.board.size)

    // --- Inlays, behind the strings ---
    if (display.showInlays) {
        val doubles = fretboard.doubleInlayFrets().toSet()
        for (fret in fretboard.inlayFrets()) {
            if (fret < g.firstFret + 1 || fret > g.lastFret) continue
            val x = g.fretCentreX(fret)
            val radius = g.markerRadius(fret) * 0.46f
            if (fret in doubles) {
                // Octave markers are a pair, straddling the centre.
                val upper = g.stringY(g.stringCount - 1) + g.board.height * 0.18f
                val lower = g.stringY(0) - g.board.height * 0.18f
                drawCircle(MusicColors.inlay, radius, Offset(x, upper))
                drawCircle(MusicColors.inlay, radius, Offset(x, lower))
            } else {
                drawCircle(MusicColors.inlay, radius, Offset(x, g.board.center.y))
            }
        }
    }

    // --- Frets ---
    for (fret in g.firstFret..g.lastFret) {
        val x = g.fretWireX(fret)
        if (fret == 0) continue
        // A fret wire has a shadow on its low-fret side, which is what makes a drawn
        // board read as having depth without resorting to a gradient.
        drawLine(
            MusicColors.fretShadow,
            Offset(x - 1.5f, g.board.top),
            Offset(x - 1.5f, g.board.bottom),
            strokeWidth = 2f,
        )
        drawLine(
            MusicColors.fret,
            Offset(x, g.board.top),
            Offset(x, g.board.bottom),
            strokeWidth = 2.4f,
        )
    }

    // --- The nut, when the window starts at the top of the neck ---
    if (g.firstFret == 0) {
        val x = g.fretWireX(0)
        drawLine(
            MusicColors.nut,
            Offset(x, g.board.top),
            Offset(x, g.board.bottom),
            strokeWidth = 7f,
        )
    }

    // --- Strings, thicker towards the bass ---
    for (s in 0 until g.stringCount) {
        val y = g.stringY(s)
        val thickness = 1.1f + 2.0f * (1f - s.toFloat() / max(1, g.stringCount - 1))
        drawLine(
            MusicColors.string.copy(alpha = 0.85f),
            Offset(g.board.left, y),
            Offset(g.board.right, y),
            strokeWidth = thickness,
        )
    }

    // --- Scale and note overlays ---
    val markLookup = marks.associateBy { it.string to it.fret }
    val rootClass = key.tonicPitchClass

    for (s in 0 until g.stringCount) {
        for (fret in g.firstFret..g.lastFret) {
            val pitch = fretboard.pitchAtOrNull(s, fret) ?: continue
            val mark = markLookup[s to fret]
            val inScale = key.contains(pitch)
            val isRoot = Pitch.pitchClass(pitch) == rootClass
            val isSounding = (s to fret) in sounding

            val showDot = when {
                mark != null && display.showRecordedNotes -> true
                display.showScale && inScale -> true
                !display.showOnlyScaleNotes && !display.showScale -> false
                else -> false
            }
            if (!showDot && !isSounding) continue

            val centre = Offset(g.fretCentreX(fret), g.stringY(s))
            val radius = g.markerRadius(fret)

            if (mark != null && display.showRecordedNotes) {
                drawNoteMarker(g, mark, centre, radius, pitch, key, display, measurer, isSounding)
            } else if (display.showScale && inScale) {
                val color = if (isRoot) MusicColors.rootNote else MusicColors.scaleNote
                val alpha = if (display.showOnlyScaleNotes) 1f else 0.9f
                drawCircle(color.copy(alpha = if (isRoot) alpha else alpha * 0.8f), radius * 0.74f, centre)
                if (isSounding) {
                    drawCircle(MusicColors.playhead, radius, centre, style = Stroke(2.5f))
                }
                drawPositionLabel(
                    centre, radius, pitch, key, display, measurer,
                    color = if (isRoot) Color.White else MusicColors.string,
                    small = true,
                )
            } else if (isSounding) {
                drawCircle(MusicColors.playhead.copy(alpha = 0.35f), radius * 0.7f, centre)
                drawCircle(MusicColors.playhead, radius, centre, style = Stroke(2.5f))
            }
        }
    }

    // --- String name labels ---
    for (s in 0 until g.stringCount) {
        val name = Pitch.noteName(fretboard.tuning.openStrings[s], preferFlats = key.sharpsInSignature < 0)
        val layout = measurer.measure(
            name,
            TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                color = MusicColors.string,
            ),
        )
        val x = if (g.leftHanded) {
            g.board.right + (g.size.width - g.board.right - layout.size.width) / 2f
        } else {
            (g.board.left - layout.size.width) / 2f
        }
        drawText(layout, topLeft = Offset(x.coerceAtLeast(2f), g.stringY(s) - layout.size.height / 2f))
    }

    // --- Fret numbers ---
    if (display.showFretNumbers) {
        for (fret in g.firstFret..g.lastFret) {
            if (fret == 0) continue
            // Label every fret when there is room, otherwise only the marked ones.
            val spacing = abs(g.fretWireX(fret) - g.fretWireX(max(fret - 1, 0)))
            val important = fret % 12 == 0 || fret in setOf(3, 5, 7, 9, 15, 17, 19, 21)
            if (spacing < 26f && !important) continue
            val layout = measurer.measure(
                fret.toString(),
                TextStyle(
                    fontSize = if (important) 11.sp else 9.sp,
                    fontWeight = if (important) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = FontFamily.Monospace,
                    color = if (important) MusicColors.nut.copy(alpha = 0.8f) else MusicColors.string.copy(alpha = 0.55f),
                ),
            )
            drawText(
                layout,
                topLeft = Offset(
                    g.fretCentreX(fret) - layout.size.width / 2f,
                    g.board.bottom + 3f,
                ),
            )
        }
    }
}

/** Draws a recorded note: a filled dot in the track's colour, with its sequence number. */
private fun DrawScope.drawNoteMarker(
    g: FretboardGeometry,
    mark: FretboardMark,
    centre: Offset,
    radius: Float,
    pitch: Int,
    key: Key,
    display: FretboardDisplay,
    measurer: TextMeasurer,
    isSounding: Boolean,
) {
    val alpha = if (mark.secondary) 0.45f else 1f
    drawCircle(mark.color.copy(alpha = alpha), radius, centre)
    if (mark.selected) {
        drawCircle(MusicColors.selection, radius + 3f, centre, style = Stroke(2.5f))
    }
    if (isSounding) {
        drawCircle(MusicColors.playhead, radius + 5f, centre, style = Stroke(2f))
    }

    // The sequence number tells the player the order the notes will sound in, which is
    // the single most useful thing to know when tapping out a line.
    val text = when {
        display.showNoteNames -> Pitch.noteName(pitch, preferFlats = key.sharpsInSignature < 0)
        display.showScaleDegrees -> key.degreeLabel(pitch)
        display.showIntervals -> IntervalLabels.label(pitch, key.tonicPitchClass)
        mark.sequenceIndex != null -> mark.sequenceIndex.toString()
        else -> ""
    }
    if (text.isEmpty()) return
    val layout = measurer.measure(
        text,
        TextStyle(
            fontSize = (radius * 0.75f).coerceIn(8f, 13f).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            color = Color(0xFF10141A),
        ),
    )
    drawText(
        layout,
        topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f),
    )
}

/** Draws the note name, interval or degree on a scale position. */
private fun DrawScope.drawPositionLabel(
    centre: Offset,
    radius: Float,
    pitch: Int,
    key: Key,
    display: FretboardDisplay,
    measurer: TextMeasurer,
    color: Color,
    small: Boolean,
) {
    val text = when {
        display.showNoteNames && display.showOctaves ->
            Pitch.fullName(pitch, preferFlats = key.sharpsInSignature < 0)
        display.showNoteNames -> Pitch.noteName(pitch, preferFlats = key.sharpsInSignature < 0)
        display.showScaleDegrees -> key.degreeLabel(pitch)
        display.showIntervals -> IntervalLabels.label(pitch, key.tonicPitchClass)
        else -> return
    }
    val layout = measurer.measure(
        text,
        TextStyle(
            fontSize = (radius * if (small) 0.6f else 0.75f).coerceIn(7f, 12f).sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
            color = color,
        ),
    )
    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
}

/**
 * Builds the marks for a composition: the active track's notes in their sequence order,
 * plus the other tracks faded behind them so the relationship between the parts is
 * visible on the board itself.
 */
fun buildFretboardMarks(
    activeTrackNotes: List<MusicNote>,
    activeColor: Color,
    otherTracks: List<Pair<List<MusicNote>, Color>>,
    selectedIds: Set<Long>,
): List<FretboardMark> {
    val out = ArrayList<FretboardMark>()
    for ((notes, color) in otherTracks) {
        for (note in notes) {
            val s = note.string ?: continue
            val f = note.fret ?: continue
            out += FretboardMark(s, f, color, secondary = true)
        }
    }
    // Later entries win, so the active track draws over the others.
    activeTrackNotes.forEachIndexed { index, note ->
        val s = note.string ?: return@forEachIndexed
        val f = note.fret ?: return@forEachIndexed
        out += FretboardMark(
            string = s,
            fret = f,
            color = activeColor,
            sequenceIndex = index + 1,
            selected = note.id in selectedIds,
        )
    }
    return out
}
