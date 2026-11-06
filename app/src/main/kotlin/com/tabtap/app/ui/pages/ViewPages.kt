package com.tabtap.app.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.components.TrackDot
import com.tabtap.app.ui.panels.DisplayPanel
import com.tabtap.app.ui.panels.InstrumentPanel
import com.tabtap.app.ui.panels.KeyScalePanel
import com.tabtap.app.ui.panels.RhythmPanel
import com.tabtap.app.ui.panes.PianoRollPane
import com.tabtap.app.ui.panes.ScorePane
import com.tabtap.app.ui.panes.TabPane

/** A small zoom control, shared by the tab and score pages. */
@Composable
private fun ZoomRow(
    label: String,
    scale: Float,
    onScale: (Float) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.small, vertical = spacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label, Modifier.weight(1f))
        trailing()
        Spacer(Modifier.width(spacing.tiny))
        IconToggle(Icons.Default.Remove, "Smaller", size = 36.dp, enabled = scale > 0.75f) {
            onScale(scale - 0.25f)
        }
        IconToggle(Icons.Default.Add, "Bigger", size = 36.dp, enabled = scale < 2.5f) {
            onScale(scale + 0.25f)
        }
    }
}

/**
 * The tab page: every part as tablature, one staff each.
 *
 * Reading the tab of your line and of its harmony together is the point, so all parts
 * show by default. Tapping a staff makes that part the one being edited.
 */
@Composable
fun TabPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val active = state.activeTrack ?: return
    val tracks = if (state.showAllTabs) state.composition.tracks else listOf(active)

    Column(modifier.fillMaxSize()) {
        ZoomRow(
            label = if (state.showAllTabs) "All parts" else active.name,
            scale = state.notationScale,
            onScale = model::setNotationScale,
        ) {
            TabTapChip(
                text = if (state.showAllTabs) "One part" else "All parts",
                selected = state.showAllTabs,
                onClick = { model.setShowAllTabs(!state.showAllTabs) },
            )
        }
        HairlineDivider()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            for (t in tracks) {
                if (tracks.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { model.setActiveTrack(t.id) }
                            .padding(horizontal = spacing.small, vertical = spacing.tiny),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackDot(MusicColors.forSlot(t.colorSlot), 8.dp)
                        Spacer(Modifier.width(spacing.small))
                        Text(
                            t.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (t.id == active.id) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(spacing.small))
                        Text(
                            "${t.notes.size} notes" + if (t.id == active.id) " · editing" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                        )
                    }
                }
                TabPane(
                    track = t,
                    timeSignature = state.composition.timeSignature,
                    chordProgression = state.composition.chordProgression,
                    selectedNoteIds = state.selectedNoteIds,
                    playheadTick = state.playback.tick.takeIf { state.isPlaying },
                    minMeasures = state.composition.barCount,
                    scale = state.notationScale,
                    onSelectNote = { id, additive ->
                        if (t.id != active.id) model.setActiveTrack(t.id)
                        if (additive) model.toggleSelection(id) else model.setSelection(setOf(id))
                    },
                    onAddNote = { string, fret, tick ->
                        if (t.id != active.id) model.setActiveTrack(t.id)
                        model.addNoteAt(string, fret, tick, state.entryValue.ticks)
                    },
                    onAdjustNote = { id, delta -> model.transposeNote(id, delta) },
                )
                Spacer(Modifier.height(spacing.small))
            }
            Spacer(Modifier.height(spacing.xLarge))
        }
    }
}

/**
 * The score page: the same music as ordinary sheet music, one stave per part.
 */
@Composable
fun ScorePage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ZoomRow(
            label = "Sheet music",
            scale = state.notationScale,
            onScale = model::setNotationScale,
        )
        HairlineDivider()
        ScorePane(
            composition = state.composition,
            selectedNoteIds = state.selectedNoteIds,
            playheadTick = state.playback.tick.takeIf { state.isPlaying },
            modifier = Modifier.weight(1f),
            scale = state.notationScale,
            onSelectNote = { id, additive ->
                if (additive) model.toggleSelection(id) else model.setSelection(setOf(id))
            },
            onDragNote = model::moveNoteByScaleSteps,
            onTapEmpty = { model.setActiveTrack(it) },
        )
    }
}

/**
 * The setup page: the instrument, the key, the metronome and what the fretboard shows.
 *
 * Everything that is configuration rather than composing, on one scrolling page, so it
 * is never in the way while playing.
 */
@Composable
fun SetupPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val track = state.activeTrack ?: return

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        KeyScalePanel(
            key = state.composition.key,
            track = track,
            onSetKey = model::setKey,
            onTransposeToKey = model::transposeToKey,
            onSuggestKey = model::suggestKey,
            onSetCustomScale = model::setCustomScale,
        )
        InstrumentPanel(
            track = track,
            onSetFamily = model::switchInstrumentFamily,
            onSetStringCount = model::setStringCount,
            onSetTuning = model::setTuning,
            onSetStringPitch = model::setCustomStringTuning,
            onSetFretCount = model::setFretCount,
            onSetVoice = model::setVoice,
        )
        RhythmPanel(
            timeSignature = state.composition.timeSignature,
            bpm = state.composition.bpm,
            quantize = state.quantize,
            entryValue = state.entryValue,
            metronomeLevel = state.metronomeLevel,
            countInBars = state.countInBars,
            recording = state.recording,
            noteCount = track.notes.size,
            onSetTimeSignature = model::setTimeSignature,
            onSetQuantize = model::setQuantize,
            onSetEntryValue = model::setEntryValue,
            onSetMetronomeLevel = model::setMetronomeLevel,
            onSetCountInBars = model::setCountInBars,
            onTapTempo = model::tapTempo,
            onStartMetronome = model::startMetronomeOnly,
            onStartRecording = model::startRhythmRecording,
            onRecordTap = model::recordRhythmTap,
            onCommitRhythm = model::commitRhythmToNotes,
            onCommitAsPlayed = model::commitRecordingAsPlayed,
            onKeepAsTemplate = model::keepRhythmAsTemplate,
            rhythmSlotsRemaining = state.rhythmSlotsRemaining,
            onClearTemplate = model::clearRhythmTemplate,
            onCancelRecording = model::cancelRhythmRecording,
            onApplyEvenRhythm = model::applyEvenRhythm,
            onQuantize = model::quantizeTrack,
            onHumanize = { model.humanizeTrack() },
            onLegato = model::makeLegato,
        )
        // How much fits on the screen. Offered because a phone in landscape is short and
        // comfort here is a matter of eyesight as much as pixels.
        com.tabtap.app.ui.components.Panel(title = "Screen") {
            Column {
                com.tabtap.app.ui.components.SectionLabel("Everything")
                Spacer(Modifier.height(spacing.tiny))
                com.tabtap.app.ui.components.SegmentedControl(
                    options = listOf(0.8f, 0.9f, 1f, 1.15f, 1.3f),
                    selected = state.uiScale,
                    onSelect = model::setUiScale,
                    label = {
                        when (it) {
                            0.8f -> "Tiny"
                            0.9f -> "Small"
                            1f -> "Normal"
                            1.15f -> "Large"
                            else -> "Largest"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Spacer(Modifier.height(spacing.small))
                com.tabtap.app.ui.components.SectionLabel("Tab and score")
                Spacer(Modifier.height(spacing.tiny))
                com.tabtap.app.ui.components.SegmentedControl(
                    options = listOf(0.75f, 1f, 1.25f, 1.75f, 2.5f),
                    selected = state.notationScale,
                    onSelect = model::setNotationScale,
                    label = {
                        when (it) {
                            0.75f -> "Tiny"
                            1f -> "Small"
                            1.25f -> "Normal"
                            1.75f -> "Large"
                            else -> "Largest"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }
        }
        DisplayPanel(display = state.display, onChange = model::setDisplay)
        Spacer(Modifier.height(spacing.xxLarge))
    }
}

/**
 * The piano-roll page: pitch against time.
 *
 * The roll is the view that makes a line's shape and its rhythm obvious at once, and it
 * is the only one where a note can be dragged in pitch and in time together. It had
 * become unreachable when the studio was split into pages, which quietly removed an
 * editor the app is supposed to have.
 */
@Composable
fun RollPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val active = state.activeTrack ?: return

    Column(modifier.fillMaxSize()) {
        ZoomRow(
            label = active.name,
            scale = state.notationScale,
            onScale = model::setNotationScale,
        )
        HairlineDivider()
        Text(
            if (active.notes.isEmpty()) {
                "Tap in the grid to put a note there. Drag a note to move it, drag its " +
                    "right edge to change its length."
            } else {
                "Drag a note to move it in pitch and time; drag its right edge for its " +
                    "length. The fret follows the pitch."
            },
            style = MaterialTheme.typography.bodySmall,
            color = LocalTabTapColors.current.textTertiary,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.small),
        )
        PianoRollPane(
            composition = state.composition,
            track = active,
            selectedNoteIds = state.selectedNoteIds,
            playheadTick = if (state.isPlaying) state.playback.tick else null,
            pixelsPerQuarter = 48f * state.notationScale,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onSelectNote = { id, additive ->
                if (additive) model.toggleSelection(id) else model.setSelection(setOf(id))
            },
            onMoveNote = model::moveNoteInPitchAndTime,
            onResizeNote = model::setNoteDuration,
            onAddNote = { pitch, startTick ->
                model.addNoteByPitch(pitch, startTick, state.entryValue.ticks)
            },
            onSeek = model::seekTo,
        )
    }
}
