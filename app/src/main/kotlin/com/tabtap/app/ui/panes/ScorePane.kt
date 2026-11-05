package com.tabtap.app.ui.panes

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.tabtap.app.ui.score.ScorePainter
import com.tabtap.app.ui.score.ScorePalette
import com.tabtap.app.ui.score.drawScoreCommands
import com.tabtap.music.Composition
import com.tabtap.music.Track
import com.tabtap.music.notation.ScoreEngraver

/**
 * The standard-notation view.
 *
 * Every part is engraved from the same composition the tab and the fretboard show, so
 * the notation is not a rendering of a separate document — editing a fret number in the
 * tab changes the pitch here, and the key signature, accidentals, beaming and rests are
 * all recomputed from the notes themselves.
 *
 * When more than one track exists each is shown on its own staff, in its own colour, so
 * a generated harmony can be read against the line it came from.
 */
@Composable
fun ScorePane(
    composition: Composition,
    selectedNoteIds: Set<Long>,
    playheadTick: Long?,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    /** Show only this track; null shows every track. */
    onlyTrackId: Long? = null,
    /** Multiplies the engraved size, for readability. */
    scale: Float = 1f,
    /** Tap a notehead. The second argument is true for an additive (long-press) tap. */
    onSelectNote: (Long, Boolean) -> Unit = { _, _ -> },
    /**
     * Drag a notehead up or down. [steps] is the number of *diatonic* steps moved, which
     * the caller converts into a transposition within the key.
     */
    onDragNote: (noteId: Long, steps: Int) -> Unit = { _, _ -> },
    /** Tap an empty part of a staff, to move the playhead there. */
    onTapEmpty: (trackId: Long) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val tracks = remember(composition, onlyTrackId) {
        if (onlyTrackId != null) {
            composition.tracks.filter { it.id == onlyTrackId }
        } else {
            composition.tracks
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Color(ScorePalette.DARK.background)),
    ) {
        val widthPx = with(density) { maxWidth.toPx() } - with(density) { 24.dp.toPx() }
        val spacePx = with(density) { (9.dp * scale.coerceIn(0.7f, 2.6f)).toPx() }
        val painter = remember(spacePx) { ScorePainter(spacePx, ScorePalette.DARK) }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            for (track in tracks) {
                ScoreStaff(
                    track = track,
                    composition = composition,
                    painter = painter,
                    widthPx = widthPx,
                    selectedNoteIds = selectedNoteIds,
                    playheadTick = playheadTick,
                    measurer = measurer,
                    showName = tracks.size > 1,
                    onSelectNote = onSelectNote,
                    onDragNote = onDragNote,
                    onTapEmpty = { onTapEmpty(track.id) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ScoreStaff(
    track: Track,
    composition: Composition,
    painter: ScorePainter,
    widthPx: Float,
    selectedNoteIds: Set<Long>,
    playheadTick: Long?,
    measurer: androidx.compose.ui.text.TextMeasurer,
    showName: Boolean,
    onSelectNote: (Long, Boolean) -> Unit,
    onDragNote: (noteId: Long, steps: Int) -> Unit,
    onTapEmpty: () -> Unit,
) {
    val density = LocalDensity.current
    val score = remember(track, composition.key, composition.timeSignature, composition.tempoMap, widthPx) {
        ScoreEngraver.engrave(
            track = track,
            key = composition.key,
            timeSignature = composition.timeSignature,
            tempoMap = composition.tempoMap,
            // The engraver works in staff spaces; the pixel width is converted here.
            systemWidth = (widthPx / painter.staffHeightPx * 4.0).coerceAtLeast(24.0),
            chordProgression = composition.chordProgression,
            minMeasures = composition.barCount,
        )
    }
    val commands = remember(score, selectedNoteIds, playheadTick) {
        painter.paint(
            score = score,
            width = widthPx,
            selectedNoteIds = selectedNoteIds,
            playheadTick = playheadTick,
            trackColorIndex = track.colorSlot,
            showPartName = if (showName) track.name else null,
        )
    }
    val heightDp = with(density) { painter.totalHeight(score.systems.size).toDp() }

    // Each staff keeps its own painter so the hit areas belong to the right part.
    val staffPainter = remember(painter, track.id) { ScorePainter(painter.spacePx, ScorePalette.DARK) }
    val staffCommands = remember(score, selectedNoteIds, playheadTick) {
        staffPainter.paint(
            score = score,
            width = widthPx,
            selectedNoteIds = selectedNoteIds,
            playheadTick = playheadTick,
            trackColorIndex = track.colorSlot,
            showPartName = if (showName) track.name else null,
        )
    }
    var dragOrigin by remember { mutableStateOf<Pair<Long, Float>?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .pointerInput(staffCommands) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = staffPainter.noteBounds.firstOrNull {
                            it.contains(offset.x, offset.y)
                        }
                        if (hit != null) onSelectNote(hit.noteId, false) else onTapEmpty()
                    },
                    onLongPress = { offset ->
                        staffPainter.noteBounds.firstOrNull { it.contains(offset.x, offset.y) }
                            ?.let { onSelectNote(it.noteId, true) }
                    },
                )
            }
            .pointerInput(staffCommands) {
                // Dragging a notehead up or down moves it by diatonic steps, which is how
                // a note is edited on a staff: you move it to the next line or space,
                // not by a semitone.
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val hit = staffPainter.noteBounds.firstOrNull {
                            it.contains(offset.x, offset.y)
                        }
                        dragOrigin = hit?.let { it.noteId to offset.y }
                    },
                    onDragEnd = { dragOrigin = null },
                    onDragCancel = { dragOrigin = null },
                    onDrag = { change, _ ->
                        val (noteId, startY) = dragOrigin ?: return@detectDragGesturesAfterLongPress
                        val steps = ((startY - change.position.y) / staffPainter.stepHeightPx)
                            .toInt()
                        if (steps != 0) {
                            onDragNote(noteId, steps)
                            dragOrigin = noteId to change.position.y
                        }
                    },
                )
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawScoreCommands(staffCommands, measurer)
        }
    }
}
