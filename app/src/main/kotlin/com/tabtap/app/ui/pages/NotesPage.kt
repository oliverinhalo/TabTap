package com.tabtap.app.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.components.TrackDot
import com.tabtap.app.ui.panels.valueLabel
import com.tabtap.music.CompositionAnalyzer
import com.tabtap.music.NoteValue

/**
 * The notes page: what you played, and how long each note lasts.
 *
 * One job — the rhythm. The strip at the top sets every note or just the selected ones;
 * each row sets one note. The list is lazy so a long line costs nothing to scroll, which
 * matters on a page that may hold hundreds of notes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val track = state.activeTrack ?: return
    val key = state.composition.key

    if (track.notes.isEmpty()) {
        EmptyState(
            "No notes yet",
            "Go to Play and tap some notes onto the fretboard. They will appear here, " +
                "where you can set how long each one lasts.",
            modifier = modifier,
        )
        return
    }

    val analysis = remember(track, key) { CompositionAnalyzer.analyze(track, key) }

    Column(modifier.fillMaxSize()) {

        // --- Set the rhythm: everything, or just the selection ---
        Column(Modifier.padding(horizontal = spacing.small, vertical = spacing.tiny)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    if (state.hasSelection) {
                        "Set ${state.selectedNoteIds.size} selected to"
                    } else {
                        "Set all ${track.notes.size} notes to"
                    },
                    Modifier.weight(1f),
                )
                TabTapChip(
                    if (state.hasSelection) "Clear" else "Select all",
                    onClick = {
                        if (state.hasSelection) model.clearSelection() else model.selectAll()
                    },
                )
            }
            Spacer(Modifier.height(spacing.tiny))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalArrangement = Arrangement.spacedBy(spacing.tiny),
            ) {
                for (value in RHYTHM_VALUES) {
                    TabTapChip(
                        text = valueLabel(value),
                        onClick = {
                            if (state.hasSelection) {
                                model.setSelectionDuration(value)
                            } else {
                                model.applyEvenRhythm(value)
                            }
                        },
                    )
                }
                TabTapChip("Quantise", onClick = model::quantizeTrack)
                TabTapChip("Legato", onClick = model::makeLegato)
            }
        }
        HairlineDivider()

        // --- One row per note ---
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(analysis, key = { it.note.id }) { item ->
                val index = analysis.indexOf(item)
                val selected = item.note.id in state.selectedNoteIds
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clickable {
                            model.setSelection(
                                if (selected) {
                                    state.selectedNoteIds - item.note.id
                                } else {
                                    state.selectedNoteIds + item.note.id
                                },
                            )
                        }
                        .padding(horizontal = spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            colors.textTertiary
                        },
                        modifier = Modifier.width(26.dp),
                    )
                    TrackDot(MusicColors.forSlot(track.colorSlot), 7.dp)
                    Spacer(Modifier.width(spacing.small))
                    Text(
                        item.noteName,
                        style = MonoType.tab,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        if (item.note.fret != null) {
                            "${track.tuning.stringCount - (item.note.string ?: 0)}/${item.note.fret}"
                        } else {
                            "—"
                        },
                        style = MonoType.tab,
                        color = if (item.note.fret == null) {
                            MusicColors.unplayable
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        item.degreeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.inKey) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MusicColors.unplayable
                        },
                        modifier = Modifier.width(36.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconToggle(
                        Icons.Default.Remove, "Shorten note ${index + 1}", size = 36.dp,
                    ) { model.halveNoteLength(item.note.id) }
                    Text(
                        valueLabel(item.note.noteValue),
                        style = MonoType.tab,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(46.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    IconToggle(
                        Icons.Default.Add, "Lengthen note ${index + 1}", size = 36.dp,
                    ) { model.doubleNoteLength(item.note.id) }
                    IconToggle(
                        Icons.Default.PlayArrow, "Hear note ${index + 1}", size = 36.dp,
                    ) { model.auditionNote(item.note.id) }
                }
                HairlineDivider()
            }
        }
    }
}

private val RHYTHM_VALUES = listOf(
    NoteValue.HALF, NoteValue.DOTTED_QUARTER, NoteValue.QUARTER,
    NoteValue.DOTTED_EIGHTH, NoteValue.EIGHTH, NoteValue.TRIPLET_EIGHTH,
    NoteValue.SIXTEENTH,
)
