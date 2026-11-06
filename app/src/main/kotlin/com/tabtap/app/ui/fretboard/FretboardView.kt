package com.tabtap.app.ui.fretboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    /**
     * The geometry from the last draw, for hit testing.
     *
     * Deliberately a plain holder rather than `mutableStateOf`: the geometry is computed
     * in the draw phase, and writing snapshot state from there invalidates everything
     * reading it, which recomposes, which draws again — the fretboard never went idle
     * and eventually exhausted the heap. A plain field is read by the gesture handlers
     * and observed by nothing.
     */
    val geometryHolder = remember { GeometryHolder() }

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
        // The board fills whatever space it is given and divides it between the strings.
        //
        // It used to demand a comfortable 44dp per string, which on a six-string came to
        // 310dp — more than the whole height of a phone in landscape once a transport bar
        // was above it. Everything below the fretboard was then pushed off the screen and
        // could not be reached at all. Filling the available space instead, down to a
        // floor that is still hittable, is the only arrangement that works in landscape.
        // An unbounded height - the board placed inside a vertical scroll - would make
        // the height infinite, and an infinite size cannot be measured or drawn. The
        // comfortable height is used instead, so the board is usable there too.
        val availableHeight = if (maxHeight.value.isFinite()) maxHeight else Dp.Unspecified
        val widthPx = with(density) { maxWidth.toPx() }
        val comfortable: Dp = (stringCount * 44 + 46).dp
        val floor: Dp = (stringCount * 22 + 26).dp
        val height: Dp = when {
            availableHeight == Dp.Unspecified -> comfortable
            availableHeight.value <= 0f -> comfortable
            availableHeight >= comfortable -> availableHeight
            else -> maxOf(availableHeight, floor)
        }

        val heightPx = with(density) { height.toPx() }

        // The geometry is worked out here, from the size the board has been given, not
        // inside the draw lambda. Built in the draw, a tap that arrived before the first
        // frame had been painted found no geometry and did nothing - and nothing in a
        // test could tap the board at all, because a test need never paint.
        val geometry = remember(
            widthPx, heightPx, stringCount, fretboard.fretCount,
            display.firstVisibleFret, display.visibleFretCount,
            display.showFretNumbers, display.leftHanded, density,
        ) {
            FretboardGeometry(
                size = Size(widthPx, heightPx),
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
                openColumnPx = with(density) { 34.dp.toPx() },
            )
        }
        geometryHolder.value = geometry

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                // One named node for the board. A screen reader had nothing to announce
                // here at all, and the board could not be aimed at in a test either.
                .semantics {
                    contentDescription = "Fretboard, ${fretboard.stringCount} strings"
                }
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
                                    val g = geometryHolder.value ?: continue
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
                        val g = geometryHolder.value ?: return@detectTransformGestures
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
                drawFretboard(geometry, fretboard, key, marks, display, sounding, measurer)
            }
        }
    }
}

/** A mutable, non-observable holder, so writing to it from the draw phase is inert. */
class GeometryHolder {
    var value: FretboardGeometry? = null
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
    /**
     * Width reserved to the left of the nut for the open strings.
     *
     * Fret 0 used to have no width at all: its span was computed from the board's edge to
     * fret 0's wire, which sit in the same place, so an open string could not be tapped —
     * there was no way to play a nut note. The open strings now get a column of their own
     * before the nut, which is also how a fretboard diagram draws them.
     */
    private val openColumnPx: Float = 0f,
) {
    /** The rectangle the board itself occupies, excluding labels and fret numbers. */
    val board: Rect = Rect(
        left = if (leftHanded) 0f else labelWidthPx,
        top = 0f,
        right = if (leftHanded) size.width - labelWidthPx else size.width,
        bottom = size.height - if (showFretNumbers) numberHeightPx else 0f,
    )

    val boardWidth: Float get() = board.width

    /** True when the nut, and therefore the open strings, are on screen. */
    val showsOpenStrings: Boolean get() = firstFret == 0 && openColumnPx > 0f

    /** The open-string column, or null when the window starts up the neck. */
    val openColumn: Rect?
        get() = if (!showsOpenStrings) {
            null
        } else if (leftHanded) {
            Rect(board.right - openColumnPx, board.top, board.right, board.bottom)
        } else {
            Rect(board.left, board.top, board.left + openColumnPx, board.bottom)
        }

    /** Where the fretted part of the board begins, after the open-string column. */
    private val frettedLeft: Float
        get() = if (leftHanded) board.left else board.left + if (showsOpenStrings) openColumnPx else 0f

    private val frettedRight: Float
        get() = if (leftHanded) board.right - if (showsOpenStrings) openColumnPx else 0f else board.right

    private val frettedWidth: Float get() = (frettedRight - frettedLeft).coerceAtLeast(1f)

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
    /** Cached: this was being recomputed for every fret of every string, every frame. */
    private val layout: DoubleArray by lazy(LazyThreadSafetyMode.NONE) {
        Fretboard.fretLayout(totalFrets)
    }

    private fun fretFraction(n: Int): Float {
        // The window is laid out from the wire *before* its first fret, so that first
        // fret has a span of its own to be tapped in. Without it the leftmost fret in a
        // scrolled window was zero pixels wide and the fret after it absorbed the taps.
        val anchor = (firstFret - 1).coerceAtLeast(0)
        val start = layout[anchor.coerceIn(0, totalFrets)]
        val end = layout[lastFret.coerceIn(0, totalFrets)]
        val span = (end - start).takeIf { it > 1e-9 } ?: 1.0
        return ((layout[n.coerceIn(0, totalFrets)] - start) / span).toFloat()
    }

    /** Screen x of the wire at the high-fret end of fret [n]. */
    fun fretWireX(n: Int): Float {
        val fraction = fretFraction(n)
        return if (leftHanded) {
            frettedRight - fraction * frettedWidth
        } else {
            frettedLeft + fraction * frettedWidth
        }
    }

    /** Screen x where a finger stops fret [n], or the open-string column for fret 0. */
    fun fretCentreX(n: Int): Float = when {
        n == 0 && showsOpenStrings -> openColumn!!.center.x
        n <= 0 -> fretWireX(0)
        else -> (fretWireX(n - 1) + fretWireX(n)) / 2f
    }

    /** Radius of a note marker, sized to the string spacing and the fret width. */
    fun markerRadius(fret: Int): Float {
        val vertical = stringSpacing * 0.42f
        val horizontal = when {
            fret == 0 && showsOpenStrings -> openColumnPx * 0.4f
            fret > firstFret -> abs(fretWireX(fret) - fretWireX(fret - 1)) * 0.42f
            else -> vertical
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

        // The open strings, which have a column of their own before the nut.
        openColumn?.let { column ->
            if (position.x >= column.left && position.x <= column.right) {
                return bestString to 0
            }
        }

        // Then the fret whose span contains the touch. Fret 0's wire is the nut itself,
        // so the first fretted span starts there and belongs to fret 1.
        val lowestFretted = maxOf(firstFret, 1)
        for (f in lowestFretted..lastFret) {
            val lower = fretWireX(f - 1)
            val upper = fretWireX(f)
            val lo = min(lower, upper)
            val hi = max(lower, upper)
            if (position.x in lo..hi) return bestString to f
        }
        // A touch past the last drawn fret still belongs to it, rather than doing nothing.
        if (lastFret >= lowestFretted) {
            val edge = fretWireX(lastFret)
            if (abs(position.x - edge) < stringSpacing) return bestString to lastFret
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

    // --- The open-string column and the nut ---
    g.openColumn?.let { column ->
        // A slightly darker strip, so it reads as "behind the nut" rather than as a fret.
        drawRect(
            MusicColors.boardEdge.copy(alpha = 0.55f),
            topLeft = column.topLeft,
            size = column.size,
        )
    }
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
            // Fret 0 is only drawn when its own column is on screen; otherwise the
            // marker would land on top of the nut.
            if (fret == 0 && !g.showsOpenStrings) continue
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
        g.openColumn?.let { column ->
            val layout = measurer.measure(
                "0",
                TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MusicColors.nut.copy(alpha = 0.8f),
                ),
            )
            drawText(
                layout,
                topLeft = Offset(column.center.x - layout.size.width / 2f, g.board.bottom + 3f),
            )
        }
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
