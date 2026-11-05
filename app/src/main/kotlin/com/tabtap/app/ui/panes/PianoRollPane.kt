package com.tabtap.app.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabtap.app.design.MusicColors
import com.tabtap.music.Composition
import com.tabtap.music.Pitch
import com.tabtap.music.Ticks
import com.tabtap.music.Track
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The piano-roll editor.
 *
 * Notes are drawn as bars on a pitch-versus-time grid, with the bar lines and the
 * subdivision grid of the project's metre behind them. Dragging a note moves it in both
 * pitch and time; dragging its right edge changes its length. Because the piano roll
 * edits pitch directly, the fretted position follows automatically — the editor picks a
 * playable fingering for the new pitch, which is why a note moved here still has a
 * sensible fret number in the tab.
 */
@Composable
fun PianoRollPane(
    composition: Composition,
    track: Track,
    selectedNoteIds: Set<Long>,
    playheadTick: Long?,
    modifier: Modifier = Modifier,
    /** Pixels per quarter note. */
    pixelsPerQuarter: Float = 48f,
    rowHeightDp: Int = 14,
    onSelectNote: (Long, Boolean) -> Unit,
    onMoveNote: (noteId: Long, semitones: Int, deltaTicks: Long) -> Unit,
    onResizeNote: (noteId: Long, ticks: Int) -> Unit,
    onAddNote: (pitch: Int, startTick: Long) -> Unit,
    onSeek: (Long) -> Unit = {},
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scroll = rememberScrollState()

    // The visible pitch range: the fretboard's range, padded so there is room to drag.
    val range = remember(track) {
        val board = track.fretboard
        val low = min(board.pitchRange.first, track.notes.minOfOrNull { it.pitch } ?: 127) - 2
        val high = max(board.pitchRange.last, track.notes.maxOfOrNull { it.pitch } ?: 0) + 2
        low.coerceAtLeast(0)..high.coerceAtMost(127)
    }
    val rowCount = range.last - range.first + 1
    val rowHeight = with(density) { rowHeightDp.dp.toPx() }
    val keyboardWidth = with(density) { 34.dp.toPx() }

    val barTicks = composition.timeSignature.barTicks
    val totalTicks = max(composition.endTick, barTicks.toLong() * 2) + barTicks
    val contentWidthPx = (totalTicks.toFloat() / Ticks.PPQ) * pixelsPerQuarter + keyboardWidth
    var dragState by remember { mutableStateOf<DragState?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF0B0D10)),
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            BoxWithConstraints {
                val heightDp = with(density) { (rowCount * rowHeight).toDp() }
                val widthDp = with(density) { contentWidthPx.toDp() }
                Box(
                    Modifier
                        .height(heightDp)
                        .width(widthDp)
                        .pointerInput(track, range, pixelsPerQuarter) {
                            detectTapGestures(
                                onTap = { offset ->
                                    if (offset.x < keyboardWidth) return@detectTapGestures
                                    val hit = hitTest(
                                        offset, track, range, rowHeight, keyboardWidth,
                                        pixelsPerQuarter,
                                    )
                                    if (hit != null) {
                                        onSelectNote(hit, false)
                                    } else {
                                        val pitch = pitchAt(offset.y, range, rowHeight)
                                        val tick = tickAt(
                                            offset.x, keyboardWidth, pixelsPerQuarter,
                                        )
                                        onAddNote(pitch, tick)
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (offset.x >= keyboardWidth) {
                                        onSeek(tickAt(offset.x, keyboardWidth, pixelsPerQuarter))
                                    }
                                },
                            )
                        }
                        .pointerInput(track, range, pixelsPerQuarter, selectedNoteIds) {
                            // A long press then drag moves a note; grabbing its right
                            // edge changes its length instead.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val noteId = hitTest(
                                        offset, track, range, rowHeight, keyboardWidth,
                                        pixelsPerQuarter,
                                    ) ?: return@detectDragGesturesAfterLongPress
                                    val note = track.note(noteId)
                                        ?: return@detectDragGesturesAfterLongPress
                                    val noteRight = keyboardWidth +
                                        (note.endTick.toFloat() / Ticks.PPQ) * pixelsPerQuarter
                                    dragState = DragState(
                                        noteId = noteId,
                                        resizing = offset.x > noteRight - 18f,
                                        startPitch = note.pitch,
                                        startTick = note.startTick,
                                        startDuration = note.durationTicks,
                                    )
                                    onSelectNote(noteId, false)
                                },
                                onDragEnd = { dragState = null },
                                onDragCancel = { dragState = null },
                                onDrag = { change, _ ->
                                    val state = dragState ?: return@detectDragGesturesAfterLongPress
                                    val dx = change.position.x - keyboardWidth
                                    val tick = (dx / pixelsPerQuarter * Ticks.PPQ).roundToLong()
                                    if (state.resizing) {
                                        onResizeNote(
                                            state.noteId,
                                            (tick - state.startTick).toInt().coerceAtLeast(60),
                                        )
                                    } else {
                                        val pitch = pitchAt(change.position.y, range, rowHeight)
                                        onMoveNote(
                                            state.noteId,
                                            pitch - state.startPitch,
                                            tick - state.startTick,
                                        )
                                    }
                                },
                            )
                        },
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        // Rows, with the black keys shaded so pitch is readable at a glance.
                        for (row in 0 until rowCount) {
                            val pitch = range.last - row
                            val y = row * rowHeight
                            val isBlack = Pitch.pitchClass(pitch) in setOf(1, 3, 6, 8, 10)
                            drawRect(
                                color = if (isBlack) Color(0xFF101418) else Color(0xFF151A20),
                                topLeft = Offset(keyboardWidth, y),
                                size = Size(size.width - keyboardWidth, rowHeight),
                            )
                            // C is marked with a brighter line, as on a keyboard.
                            if (Pitch.pitchClass(pitch) == 0) {
                                drawLine(
                                    Color(0xFF2A323B),
                                    Offset(keyboardWidth, y),
                                    Offset(size.width, y),
                                    1.5f,
                                )
                            }
                        }

                        // The keyboard down the left edge.
                        for (row in 0 until rowCount) {
                            val pitch = range.last - row
                            val y = row * rowHeight
                            val isBlack = Pitch.pitchClass(pitch) in setOf(1, 3, 6, 8, 10)
                            drawRect(
                                color = if (isBlack) Color(0xFF0E1216) else Color(0xFF232B33),
                                topLeft = Offset(0f, y),
                                size = Size(keyboardWidth - 2f, rowHeight - 0.5f),
                            )
                            if (Pitch.pitchClass(pitch) == 0 && rowHeight > 10f) {
                                val layout = measurer.measure(
                                    Pitch.fullName(pitch),
                                    TextStyle(
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF8A96A4),
                                    ),
                                )
                                drawText(layout, topLeft = Offset(2f, y + 1f))
                            }
                        }

                        // The metre grid: beats faint, bar lines strong.
                        val beatPx = pixelsPerQuarter * composition.timeSignature.beatTicks / Ticks.PPQ
                        var tick = 0L
                        while (tick <= totalTicks) {
                            val x = keyboardWidth + (tick.toFloat() / Ticks.PPQ) * pixelsPerQuarter
                            val isBar = tick % barTicks == 0L
                            drawLine(
                                if (isBar) Color(0xFF3A444F) else Color(0xFF1E252C),
                                Offset(x, 0f),
                                Offset(x, size.height),
                                if (isBar) 1.6f else 1f,
                            )
                            tick += composition.timeSignature.beatTicks.toLong()
                        }

                        // Other tracks, faded, so the parts can be read against each other.
                        for (other in composition.tracks) {
                            if (other.id == track.id) continue
                            val color = MusicColors.forSlot(other.colorSlot).copy(alpha = 0.3f)
                            for (note in other.notes) {
                                drawNoteBar(
                                    note.pitch, note.startTick, note.durationTicks,
                                    range, rowHeight, keyboardWidth, pixelsPerQuarter,
                                    color, selected = false, velocity = note.velocity,
                                )
                            }
                        }

                        // The active track.
                        val activeColor = MusicColors.forSlot(track.colorSlot)
                        for (note in track.notes) {
                            drawNoteBar(
                                note.pitch, note.startTick, note.durationTicks,
                                range, rowHeight, keyboardWidth, pixelsPerQuarter,
                                activeColor, note.id in selectedNoteIds, note.velocity,
                            )
                        }

                        // The playhead.
                        if (playheadTick != null) {
                            val x = keyboardWidth +
                                (playheadTick.toFloat() / Ticks.PPQ) * pixelsPerQuarter
                            drawLine(
                                MusicColors.playhead,
                                Offset(x, 0f),
                                Offset(x, size.height),
                                2f,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class DragState(
    val noteId: Long,
    val resizing: Boolean,
    val startPitch: Int,
    val startTick: Long,
    val startDuration: Int,
)

/** A note bar: its opacity carries velocity, which makes dynamics visible. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNoteBar(
    pitch: Int,
    startTick: Long,
    durationTicks: Int,
    range: IntRange,
    rowHeight: Float,
    keyboardWidth: Float,
    pixelsPerQuarter: Float,
    color: Color,
    selected: Boolean,
    velocity: Int,
) {
    if (pitch !in range) return
    val row = range.last - pitch
    val y = row * rowHeight + 1f
    val x = keyboardWidth + (startTick.toFloat() / Ticks.PPQ) * pixelsPerQuarter
    val width = max(4f, (durationTicks.toFloat() / Ticks.PPQ) * pixelsPerQuarter - 1.5f)
    val alpha = (0.45f + 0.55f * velocity / 127f).coerceIn(0.3f, 1f)
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(x, y),
        size = Size(width, rowHeight - 2f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f),
    )
    if (selected) {
        drawRoundRect(
            color = MusicColors.selection,
            topLeft = Offset(x - 1f, y - 1f),
            size = Size(width + 2f, rowHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            style = Stroke(1.8f),
        )
    }
}

private fun pitchAt(y: Float, range: IntRange, rowHeight: Float): Int {
    val row = (y / rowHeight).toInt()
    return (range.last - row).coerceIn(range.first, range.last)
}

private fun tickAt(x: Float, keyboardWidth: Float, pixelsPerQuarter: Float): Long =
    (((x - keyboardWidth) / pixelsPerQuarter) * Ticks.PPQ).roundToLong().coerceAtLeast(0L)

private fun hitTest(
    offset: Offset,
    track: Track,
    range: IntRange,
    rowHeight: Float,
    keyboardWidth: Float,
    pixelsPerQuarter: Float,
): Long? {
    val pitch = pitchAt(offset.y, range, rowHeight)
    val tick = tickAt(offset.x, keyboardWidth, pixelsPerQuarter)
    // Prefer an exact hit, then the nearest note on the same row.
    return track.notes
        .filter { it.pitch == pitch && tick >= it.startTick - 40 && tick <= it.endTick + 40 }
        .minByOrNull { kotlin.math.abs(it.startTick - tick) }
        ?.id
}
