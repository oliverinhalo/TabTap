package com.tabtap.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.fretboard.FretboardView
import com.tabtap.app.ui.fretboard.buildFretboardMarks
import com.tabtap.app.ui.panels.valueLabel
import com.tabtap.music.NoteValue

/**
 * The playing page: the fretboard, and nothing that is not needed while playing a line
 * in.
 *
 * The board takes every pixel that is left after one slim strip. That strip holds only
 * what you reach for between notes — how long they are, undo, and which part you are
 * writing into — because anything more costs height the board needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val track = state.activeTrack ?: return

    Column(modifier.fillMaxSize()) {

        // --- The board, filling everything that is left ---
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = spacing.tiny, vertical = spacing.tiny),
        ) {
            FretboardView(
                fretboard = track.fretboard,
                key = state.composition.key,
                marks = remember(state.composition, state.selectedNoteIds) {
                    buildFretboardMarks(
                        activeTrackNotes = track.notes,
                        activeColor = MusicColors.forSlot(track.colorSlot),
                        otherTracks = state.composition.tracks
                            .filter { it.id != track.id }
                            .map { it.notes to MusicColors.forSlot(it.colorSlot) },
                        selectedIds = state.selectedNoteIds,
                    )
                },
                display = state.display,
                sounding = soundingPositions(state),
                modifier = Modifier.fillMaxSize(),
                onTap = { string, fret, simultaneous ->
                    if (simultaneous) model.stackFret(string, fret) else model.tapFret(string, fret)
                },
                onLongPress = { string, fret -> model.auditionFret(string, fret) },
                onWindowChange = { first, count ->
                    model.setDisplay(
                        state.display.copy(firstVisibleFret = first, visibleFretCount = count),
                    )
                },
            )
        }

        // --- One strip: note length, the part being written to, undo ---
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.small, vertical = spacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                modifier = Modifier.weight(1f),
            ) {
                for (value in QUICK_VALUES) {
                    TabTapChip(
                        text = valueLabel(value),
                        selected = value == state.entryValue,
                        onClick = { model.setEntryValue(value) },
                    )
                }
                if (state.composition.tracks.size > 1) {
                    for (t in state.composition.tracks) {
                        TabTapChip(
                            text = t.name,
                            selected = t.id == track.id,
                            accent = MusicColors.forSlot(t.colorSlot),
                            onClick = { model.setActiveTrack(t.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.width(spacing.small))
            Text(
                "${track.notes.size}",
                style = MonoType.readout,
                color = colors.textTertiary,
            )
            IconToggle(Icons.Default.Undo, "Undo last note", enabled = state.canUndo) {
                model.undo()
            }
            IconToggle(
                Icons.Default.Delete, "Remove the last note",
                enabled = track.notes.isNotEmpty(),
            ) { model.removeLastNote() }
        }

        if (track.notes.isEmpty()) {
            Text(
                "Tap the fretboard to record notes in the order you tap them. " +
                    "Two fingers at once makes a chord.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.tiny),
            )
        }
    }
}

private val QUICK_VALUES = listOf(
    NoteValue.QUARTER, NoteValue.EIGHTH, NoteValue.SIXTEENTH,
    NoteValue.TRIPLET_EIGHTH, NoteValue.DOTTED_EIGHTH, NoteValue.HALF,
)

/** Positions sounding at the playhead, so the board lights up during playback. */
internal fun soundingPositions(state: StudioUiState): Set<Pair<Int, Int>> {
    if (!state.isPlaying) return emptySet()
    val tick = state.playback.tick
    val out = HashSet<Pair<Int, Int>>()
    for (track in state.composition.audibleTracks) {
        for (note in track.notes) {
            if (note.startTick <= tick && note.endTick > tick) {
                val s = note.string ?: continue
                val f = note.fret ?: continue
                out += s to f
            }
        }
    }
    return out
}
