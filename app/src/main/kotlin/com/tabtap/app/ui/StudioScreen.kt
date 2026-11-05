package com.tabtap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioPane
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.components.TrackDot
import com.tabtap.app.ui.fretboard.FretboardView
import com.tabtap.app.ui.fretboard.buildFretboardMarks
import com.tabtap.app.ui.panels.DisplayPanel
import com.tabtap.app.ui.panels.HarmonyPanel
import com.tabtap.app.ui.panels.InstrumentPanel
import com.tabtap.app.ui.panels.KeyScalePanel
import com.tabtap.app.ui.panels.MixerPanel
import com.tabtap.app.ui.panels.RhythmPanel
import com.tabtap.app.ui.panels.TransportBar
import com.tabtap.app.ui.panels.valueLabel
import com.tabtap.app.ui.panes.PianoRollPane
import com.tabtap.app.ui.panes.ScorePane
import com.tabtap.app.ui.panes.TabPane
import com.tabtap.music.Articulation
import com.tabtap.music.ChromaticHandling
import com.tabtap.music.CompositionAnalyzer
import com.tabtap.music.HarmonyMode
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.Ticks

/** Which side panel is open. */
enum class StudioTool(val title: String) {
    NONE(""),
    RHYTHM("Rhythm"),
    HARMONY("Harmony"),
    KEY("Key"),
    INSTRUMENT("Instrument"),
    MIXER("Mixer"),
    DISPLAY("Display"),
    INSPECTOR("Note"),
}

/**
 * The composition workspace.
 *
 * The fretboard is the centre of the screen, not something behind a menu: on a phone it
 * fills the upper half with the chosen editor below it, and in landscape or on a tablet
 * it sits beside the tools so nothing has to be dismissed to get back to playing.
 *
 * The pane switcher changes which *view* of the composition is below the fretboard. All
 * four read the same data, so switching loses nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    var tool by remember { mutableStateOf(StudioTool.NONE) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // A wide window puts the tools beside the music; a narrow one stacks them.
        val wide = maxWidth >= 840.dp
        val track = state.activeTrack

        if (track == null) {
            EmptyState(
                "No track",
                "This project has no track to edit.",
                action = { Button(onClick = { model.addTrack() }) { Text("Add a track") } },
            )
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize()) {
            TransportBar(
                isPlaying = state.isPlaying,
                isPaused = state.playback.state == com.tabtap.app.audio.TransportState.PAUSED,
                positionTick = state.playback.tick,
                countInBeatsRemaining = state.playback.countInBeatsRemaining,
                bpm = state.composition.bpm,
                timeSignature = state.composition.timeSignature,
                metronomeEnabled = state.metronomeEnabled,
                looping = state.loop != null,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                saveState = state.saveState,
                onPlay = model::play,
                onPause = model::pause,
                onStop = model::stop,
                onRestart = model::restart,
                onToggleMetronome = { model.setMetronomeEnabled(!state.metronomeEnabled) },
                onToggleLoop = {
                    if (state.loop != null) model.setLoop(null) else model.loopCurrentBar()
                },
                onUndo = model::undo,
                onRedo = model::redo,
                onTempoChange = model::setTempo,
                onTapTempo = model::tapTempo,
            )

            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    // --- The fretboard ---
                    Column(Modifier.padding(spacing.small)) {
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
                            sounding = remember(state.playback.tick, state.composition) {
                                soundingPositions(state)
                            },
                            onTap = { string, fret, simultaneous ->
                                if (simultaneous) {
                                    model.stackFret(string, fret)
                                } else {
                                    model.tapFret(string, fret)
                                }
                            },
                            onLongPress = { string, fret -> model.auditionFret(string, fret) },
                            onWindowChange = { first, count ->
                                model.setDisplay(
                                    state.display.copy(
                                        firstVisibleFret = first,
                                        visibleFretCount = count,
                                    ),
                                )
                            },
                        )
                        Spacer(Modifier.height(spacing.small))
                        EntryToolbar(model, state, onOpenTool = { tool = it })
                    }

                    HairlineDivider()

                    // --- The pane switcher ---
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.small, vertical = spacing.tiny),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SegmentedControl(
                            options = StudioPane.entries.toList(),
                            selected = state.pane,
                            onSelect = model::setPane,
                            label = { it.title },
                            modifier = Modifier.weight(1f),
                            compact = true,
                        )
                    }

                    // --- The chosen editor ---
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (state.pane) {
                            StudioPane.FRETBOARD -> SequenceList(model, state)
                            StudioPane.TAB -> TabPane(
                                track = track,
                                timeSignature = state.composition.timeSignature,
                                chordProgression = state.composition.chordProgression,
                                selectedNoteIds = state.selectedNoteIds,
                                playheadTick = state.playback.tick.takeIf { state.isPlaying },
                                minMeasures = state.composition.barCount,
                                onSelectNote = { id, additive ->
                                    if (additive) model.toggleSelection(id) else model.setSelection(setOf(id))
                                },
                                onAddNote = { string, fret, tick ->
                                    model.addNoteAt(string, fret, tick, state.entryValue.ticks)
                                },
                                onAdjustNote = { id, delta -> model.transposeNote(id, delta) },
                            )
                            StudioPane.PIANO_ROLL -> PianoRollPane(
                                composition = state.composition,
                                track = track,
                                selectedNoteIds = state.selectedNoteIds,
                                playheadTick = state.playback.tick.takeIf { state.isPlaying },
                                onSelectNote = { id, additive ->
                                    if (additive) model.toggleSelection(id) else model.setSelection(setOf(id))
                                },
                                onMoveNote = { id, semitones, deltaTicks ->
                                    val note = track.note(id)
                                    if (note != null) {
                                        if (semitones != 0) model.transposeNote(id, semitones)
                                        model.setNoteStart(id, note.startTick + deltaTicks)
                                    }
                                },
                                onResizeNote = model::setNoteDuration,
                                onAddNote = { pitch, tick ->
                                    model.addNoteByPitch(pitch, tick, state.entryValue.ticks)
                                },
                                onSeek = model::seekTo,
                            )
                            StudioPane.SCORE -> ScorePane(
                                composition = state.composition,
                                selectedNoteIds = state.selectedNoteIds,
                                playheadTick = state.playback.tick.takeIf { state.isPlaying },
                                onSelectNote = { id, additive ->
                                    if (additive) {
                                        model.toggleSelection(id)
                                    } else {
                                        model.setSelection(setOf(id))
                                    }
                                },
                                // Dragging a notehead moves it by scale steps, as moving
                                // a note to the next line or space on a staff does.
                                onDragNote = model::moveNoteByScaleSteps,
                                onTapEmpty = { model.setActiveTrack(it) },
                            )
                        }
                    }
                }

                // --- Tools ---
                if (wide && tool != StudioTool.NONE) {
                    Surface(
                        Modifier.width(360.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        ToolContent(model, state, tool) { tool = StudioTool.NONE }
                    }
                }
            }
        }

        // On a narrow window the tools slide up over the music instead.
        if (!wide) {
            AnimatedVisibility(visible = tool != StudioTool.NONE) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    ToolContent(model, state, tool) { tool = StudioTool.NONE }
                }
            }
        }
    }
}

/** The row of entry controls under the fretboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryToolbar(
    model: StudioViewModel,
    state: StudioUiState,
    onOpenTool: (StudioTool) -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Track switcher: tapping a dot changes which track the fretboard writes to.
            for (track in state.composition.tracks) {
                val selected = track.id == state.activeTrackId
                TabTapChip(
                    text = track.name,
                    selected = selected,
                    accent = MusicColors.forSlot(track.colorSlot),
                    onClick = { model.setActiveTrack(track.id) },
                )
                Spacer(Modifier.width(spacing.tiny))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${state.activeTrack?.notes?.size ?: 0} notes",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
        }

        Spacer(Modifier.height(spacing.small))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
            verticalArrangement = Arrangement.spacedBy(spacing.tiny),
        ) {
            TabTapChip(valueLabel(state.entryValue), onClick = { onOpenTool(StudioTool.RHYTHM) })
            TabTapChip(state.composition.key.shortName, onClick = { onOpenTool(StudioTool.KEY) })
            TabTapChip("Harmony", onClick = { onOpenTool(StudioTool.HARMONY) })
            TabTapChip(
                state.activeTrack?.tuning?.displayName ?: "Tuning",
                onClick = { onOpenTool(StudioTool.INSTRUMENT) },
            )
            TabTapChip("Mixer", onClick = { onOpenTool(StudioTool.MIXER) })
            TabTapChip("Display", onClick = { onOpenTool(StudioTool.DISPLAY) })
            if (state.hasSelection) {
                TabTapChip(
                    "${state.selectedNoteIds.size} selected",
                    selected = true,
                    onClick = { onOpenTool(StudioTool.INSPECTOR) },
                )
            }
        }

        Spacer(Modifier.height(spacing.tiny))

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.tiny)) {
            IconToggle(Icons.Default.Save, "Save now") { model.saveAndConfirm() }
            IconToggle(Icons.Default.Undo, "Undo last note", enabled = state.canUndo) {
                model.undo()
            }
            IconToggle(
                Icons.Default.Delete, "Delete selection",
                enabled = state.hasSelection,
            ) { model.deleteSelection() }
            IconToggle(
                Icons.Default.ContentCopy, "Copy", enabled = state.hasSelection,
            ) { model.copySelection() }
            IconToggle(
                Icons.Default.ContentCut, "Cut", enabled = state.hasSelection,
            ) { model.cutSelection() }
            IconToggle(
                Icons.Default.ContentPaste, "Paste", enabled = state.clipboard != null,
            ) { model.pasteAtPlayhead() }
            IconToggle(Icons.Default.PlayArrow, "Audition") { model.auditionSelection() }
            Spacer(Modifier.weight(1f))
            IconToggle(Icons.Default.Tune, "Note options", enabled = state.hasSelection) {
                onOpenTool(StudioTool.INSPECTOR)
            }
        }
    }
}

/** The ordered list of entered notes, with their analysis against the key. */
@Composable
private fun SequenceList(model: StudioViewModel, state: StudioUiState) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val track = state.activeTrack ?: return
    val key = state.composition.key

    if (track.notes.isEmpty()) {
        EmptyState(
            "Tap the fretboard to begin",
            "Notes are recorded in the order you tap them. Two fingers at once makes a " +
                "chord. When the line is there, give it a rhythm and generate a harmony.",
        )
        return
    }

    val analysis = remember(track, key) { CompositionAnalyzer.analyze(track, key) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.small),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = spacing.tiny)) {
            SectionLabel("#", Modifier.width(28.dp))
            SectionLabel("Note", Modifier.width(56.dp))
            SectionLabel("Pos", Modifier.width(52.dp))
            SectionLabel("Degree", Modifier.width(56.dp))
            SectionLabel("Length", Modifier.weight(1f))
        }
        HairlineDivider()
        analysis.forEachIndexed { index, item ->
            val selected = item.note.id in state.selectedNoteIds
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable {
                        if (selected) {
                            model.setSelection(state.selectedNoteIds - item.note.id)
                        } else {
                            model.setSelection(setOf(item.note.id))
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.width(28.dp),
                )
                Row(Modifier.width(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackDot(MusicColors.forSlot(track.colorSlot), 7.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        item.noteName,
                        style = com.tabtap.app.design.MonoType.tab,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    if (item.note.fret != null) {
                        "s${(track.tuning.stringCount - (item.note.string ?: 0))}/${item.note.fret}"
                    } else {
                        "—"
                    },
                    style = com.tabtap.app.design.MonoType.tab,
                    color = if (item.note.fret == null) {
                        MusicColors.unplayable
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    item.degreeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.inKey) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MusicColors.unplayable
                    },
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    item.note.noteValue.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HairlineDivider()
        }
    }
}

/** The side-panel content for the open tool. */
@Composable
private fun ToolContent(
    model: StudioViewModel,
    state: StudioUiState,
    tool: StudioTool,
    onClose: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val track = state.activeTrack ?: return

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.medium, vertical = spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tool.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onClose) { Text("Done") }
        }
        HairlineDivider()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.medium),
        ) {
            when (tool) {
                StudioTool.RHYTHM -> RhythmPanel(
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
                StudioTool.HARMONY -> HarmonyPanel(
                    composition = state.composition,
                    key = state.composition.key,
                    staged = state.stagedHarmonies,
                    sourceTrackName = track.name,
                    sourceNoteCount = track.notes.size,
                    onToggleSpec = model::toggleStagedHarmony,
                    onSetMode = { mode ->
                        model.setStagedHarmonies(state.stagedHarmonies.map { it.copy(mode = mode) })
                    },
                    onSetChromaticHandling = { handling ->
                        model.setStagedHarmonies(
                            state.stagedHarmonies.map { it.copy(chromaticHandling = handling) },
                        )
                    },
                    onSetFollowChords = { follow ->
                        model.setStagedHarmonies(
                            state.stagedHarmonies.map { it.copy(followChords = follow) },
                        )
                    },
                    onSetOctaveShift = model::setStagedHarmonyOctave,
                    onGenerate = model::generateHarmonies,
                    onRegenerate = model::regenerateHarmonies,
                )
                StudioTool.KEY -> KeyScalePanel(
                    key = state.composition.key,
                    track = track,
                    onSetKey = model::setKey,
                    onTransposeToKey = model::transposeToKey,
                    onSuggestKey = model::suggestKey,
                    onSetCustomScale = model::setCustomScale,
                )
                StudioTool.INSTRUMENT -> InstrumentPanel(
                    track = track,
                    onSetFamily = model::switchInstrumentFamily,
                    onSetStringCount = model::setStringCount,
                    onSetTuning = model::setTuning,
                    onSetStringPitch = model::setCustomStringTuning,
                    onSetFretCount = model::setFretCount,
                    onSetVoice = model::setVoice,
                )
                StudioTool.MIXER -> MixerPanel(
                    composition = state.composition,
                    activeTrackId = state.activeTrackId,
                    onSelectTrack = model::setActiveTrack,
                    onSetVolume = model::setTrackVolume,
                    onSetPan = model::setTrackPan,
                    onToggleMute = model::setTrackMuted,
                    onToggleSolo = model::setTrackSoloed,
                    onSetVoice = model::setTrackVoice,
                    onRemoveTrack = model::removeTrack,
                    onAddTrack = model::addTrack,
                )
                StudioTool.DISPLAY -> DisplayPanel(
                    display = state.display,
                    onChange = model::setDisplay,
                )
                StudioTool.INSPECTOR -> NoteInspector(model, state)
                StudioTool.NONE -> Unit
            }
        }
    }
}

/** Per-note editing: length, velocity, technique and fingering. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteInspector(model: StudioViewModel, state: StudioUiState) {
    val spacing = LocalSpacing.current
    val notes = state.selectedNotes
    val key = state.composition.key

    if (notes.isEmpty()) {
        EmptyState("Nothing selected", "Select a note in the tab, the piano roll or the list.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        Panel(title = if (notes.size == 1) "Note" else "${notes.size} notes") {
            Column {
                if (notes.size == 1) {
                    val note = notes.first()
                    ReadoutRow("Pitch", Pitch.fullName(note.pitch), mono = true)
                    ReadoutRow("Degree in ${key.shortName}", key.degreeLabel(note.pitch))
                    ReadoutRow(
                        "Position",
                        if (note.fret != null) {
                            "string ${state.fretboard.stringCount - note.string!!}, fret ${note.fret}"
                        } else {
                            "not playable on this tuning"
                        },
                    )
                    ReadoutRow("Starts at", com.tabtap.app.ui.panels.barsAndBeats(
                        note.startTick, state.composition.timeSignature,
                    ), mono = true)
                    ReadoutRow("Length", note.noteValue.displayName)
                    ReadoutRow("Velocity", "${note.velocity}")
                }
                Spacer(Modifier.height(spacing.small))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    OutlinedButton(
                        onClick = { model.transposeSelection(1) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+1 semitone") }
                    OutlinedButton(
                        onClick = { model.transposeSelection(-1) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−1 semitone") }
                }
                Spacer(Modifier.height(spacing.tiny))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    OutlinedButton(
                        onClick = { model.transposeSelection(12) },
                        modifier = Modifier.weight(1f),
                    ) { Text("+1 octave") }
                    OutlinedButton(
                        onClick = { model.transposeSelection(-12) },
                        modifier = Modifier.weight(1f),
                    ) { Text("−1 octave") }
                }
                Spacer(Modifier.height(spacing.tiny))
                OutlinedButton(
                    onClick = model::refingerSelection,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Try another fingering") }
            }
        }

        Panel(title = "Length") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                for (value in NoteValue.PALETTE) {
                    TabTapChip(
                        text = valueLabel(value),
                        selected = notes.all { it.durationTicks == value.ticks },
                        onClick = { model.setSelectionDuration(value) },
                    )
                }
            }
        }

        Panel(title = "Timing") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                OutlinedButton(
                    onClick = { model.nudgeSelection(-(Ticks.SIXTEENTH / 4).toLong()) },
                    modifier = Modifier.weight(1f),
                ) { Text("Nudge earlier") }
                OutlinedButton(
                    onClick = { model.nudgeSelection((Ticks.SIXTEENTH / 4).toLong()) },
                    modifier = Modifier.weight(1f),
                ) { Text("Nudge later") }
            }
        }

        Panel(title = "Technique") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                for (articulation in Articulation.entries) {
                    TabTapChip(
                        text = articulation.displayName,
                        selected = notes.all { it.has(articulation) },
                        onClick = { model.toggleArticulation(articulation) },
                    )
                }
            }
        }

        Panel(title = "Bend") {
            ChipRow(
                items = listOf(0f, 0.5f, 1f, 1.5f, 2f),
                isSelected = { amount -> notes.all { it.bendSemitones == amount } },
                onSelect = model::setBend,
                label = {
                    when (it) {
                        0f -> "None"
                        0.5f -> "¼ tone"
                        1f -> "½ tone"
                        1.5f -> "¾ tone"
                        else -> "Full tone"
                    }
                },
            )
        }

        Panel(title = "Tie") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                OutlinedButton(
                    onClick = { model.setTie(true) },
                    modifier = Modifier.weight(1f),
                ) { Text("Tie to next") }
                OutlinedButton(
                    onClick = { model.setTie(false) },
                    modifier = Modifier.weight(1f),
                ) { Text("Remove tie") }
            }
        }
    }
}

/** Positions sounding at the playhead, so the fretboard lights up during playback. */
private fun soundingPositions(state: StudioUiState): Set<Pair<Int, Int>> {
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
