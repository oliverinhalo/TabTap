package com.tabtap.app.ui.panes

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tabtap.app.ui.score.ScorePalette
import com.tabtap.app.ui.score.TabPainter
import com.tabtap.app.ui.score.drawScoreCommands
import com.tabtap.music.ChordEvent
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.notation.TabEngraver

/**
 * The tablature editor.
 *
 * Tapping a fret number selects it; tapping an empty position on a line adds a note
 * there. Both go through the same editing operations the fretboard uses, so an edit made
 * here appears immediately in the score, the piano roll and on the fretboard — they are
 * all views of one composition, not four documents.
 */
@Composable
fun TabPane(
    track: Track,
    timeSignature: TimeSignature,
    chordProgression: List<ChordEvent>,
    selectedNoteIds: Set<Long>,
    playheadTick: Long?,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    minMeasures: Int = 1,
    /** Multiplies the drawn size, for readability. */
    scale: Float = 1f,
    onSelectNote: (Long, Boolean) -> Unit,
    onAddNote: (string: Int, fret: Int, startTick: Long) -> Unit,
    onAdjustNote: (noteId: Long, fretDelta: Int) -> Unit = { _, _ -> },
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(ScorePalette.DARK.background.let { androidx.compose.ui.graphics.Color(it) }),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val zoom = scale.coerceIn(0.7f, 2.6f)
        val linePx = with(density) { (20.dp * zoom).toPx() }
        val columnPx = with(density) { (11.dp * zoom).toPx() }

        val layout = remember(track, timeSignature, chordProgression, minMeasures, widthPx) {
            TabEngraver.engrave(
                track = track,
                timeSignature = timeSignature,
                // The system width is in layout columns; convert from the pixel width.
                systemWidth = (widthPx / columnPx).toDouble().coerceAtLeast(20.0),
                chordProgression = chordProgression,
                minMeasures = minMeasures,
            )
        }
        val painter = remember(linePx, columnPx) { TabPainter(linePx, columnPx, ScorePalette.DARK) }
        val commands = remember(layout, selectedNoteIds, playheadTick, track) {
            painter.paint(layout, widthPx, selectedNoteIds, playheadTick, track.colorSlot)
        }
        val heightDp = with(density) { painter.totalHeight(layout).toDp() } + 24.dp

        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .pointerInput(layout, selectedNoteIds) {
                        detectTapGestures(
                            onTap = { offset ->
                                val cell = painter.cellBounds.firstOrNull {
                                    it.contains(offset.x, offset.y)
                                }
                                if (cell != null) {
                                    onSelectNote(cell.noteId, false)
                                    return@detectTapGestures
                                }
                                val slot = painter.slotBounds.firstOrNull {
                                    it.contains(offset.x, offset.y)
                                }
                                if (slot != null) {
                                    // A tap on an empty line adds an open string there;
                                    // the fret can then be changed by dragging.
                                    onAddNote(slot.string, 0, slot.startTick)
                                }
                            },
                            onLongPress = { offset ->
                                painter.cellBounds.firstOrNull { it.contains(offset.x, offset.y) }
                                    ?.let { onSelectNote(it.noteId, true) }
                            },
                            onDoubleTap = { offset ->
                                // A double tap raises the fret, the quickest correction
                                // when a number is one out.
                                painter.cellBounds.firstOrNull { it.contains(offset.x, offset.y) }
                                    ?.let { onAdjustNote(it.noteId, 1) }
                            },
                        )
                    },
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawScoreCommands(commands, measurer)
                }
            }
        }
    }
}
