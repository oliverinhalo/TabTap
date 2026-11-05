package com.tabtap.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabtap.app.audio.AudioEngine
import com.tabtap.app.audio.LoopRegion
import com.tabtap.app.audio.PlaybackPosition
import com.tabtap.app.audio.Sequencer
import com.tabtap.app.audio.TransportState
import com.tabtap.app.data.ProjectRepository
import com.tabtap.app.data.ProjectSummary
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.app.editing.EditStack
import com.tabtap.music.Articulation
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.ChordVoicing
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.Quantize
import com.tabtap.music.RhythmEngine
import com.tabtap.music.ScaleType
import com.tabtap.music.StrumPattern
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.TrackRole
import com.tabtap.music.Tuning
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which editor is showing. All four are views of the same composition. */
enum class StudioPane(val title: String) {
    FRETBOARD("Fretboard"),
    TAB("Tab"),
    PIANO_ROLL("Piano roll"),
    SCORE("Score"),
}

/** What the fretboard draws. */
data class FretboardDisplay(
    val showNoteNames: Boolean = true,
    val showIntervals: Boolean = false,
    val showScaleDegrees: Boolean = false,
    val showFretNumbers: Boolean = true,
    val showScale: Boolean = true,
    /** Dim everything that is not in the selected scale. */
    val showOnlyScaleNotes: Boolean = false,
    /** Mark positions used by the recorded sequence. */
    val showRecordedNotes: Boolean = true,
    val showInlays: Boolean = true,
    val showOctaves: Boolean = false,
    /** Mirror the board for a left-handed player. */
    val leftHanded: Boolean = false,
    /** Visible fret window, so a 24-fret board can be zoomed on a phone. */
    val firstVisibleFret: Int = 0,
    val visibleFretCount: Int = 24,
)

/** A transient message for the user. */
data class UserMessage(
    val id: Long,
    val text: String,
    val isError: Boolean = false,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/** Autosave status, surfaced so the user can see their work is safe. */
enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/** Rhythm-recording state: taps collected against the metronome. */
data class RhythmRecording(
    val isRecording: Boolean = false,
    /** Tapped onsets, in ticks, quantised on commit rather than as they arrive. */
    val onsets: List<Long> = emptyList(),
    /** Raw tap times in milliseconds, for tap tempo. */
    val tapTimesMs: List<Long> = emptyList(),
) {
    val tapCount: Int get() = onsets.size
}

/** The chord editor's working state. */
data class ChordEditorState(
    val rootName: String = "C",
    val type: ChordType = ChordType.MAJOR,
    val inversion: Int = 0,
    val strum: StrumPattern = StrumPattern.BLOCK,
    /**
     * Length in ticks. Chords are counted in bars and beats of the project's metre, not
     * in note values — a whole note is a bar only in 4/4 — so the length is kept as
     * ticks and the options offered are derived from the time signature.
     */
    val durationTicks: Int = 0,
    val voicingIndex: Int = 0,
    /** When set, the editor is changing this existing chord rather than adding a new one. */
    val editingChordId: Long? = null,
) {
    val chord: Chord? get() = Chord.of(rootName, type)?.inversion(inversion)
    val isEditing: Boolean get() = editingChordId != null
}

/**
 * Which parts you hear, so you can play the other one yourself.
 *
 * This is the point of generating a harmony: you want to hear the part you are *not*
 * playing. Setting a mode is one tap rather than muting tracks by hand.
 */
enum class PlayAlongMode(val label: String, val description: String) {
    EVERYTHING("Everything", "The whole arrangement"),
    I_PLAY_LEAD("I play the lead", "You hear the harmony and the chords"),
    I_PLAY_HARMONY("I play the harmony", "You hear the lead and the chords"),
    CHORDS_ONLY("Just the chords", "A backing to play over"),
}

/** What the loop covers. */
enum class LoopScope(val label: String) {
    OFF("Off"),
    WHOLE("Whole section"),
    CURRENT_BAR("This bar"),
    TWO_BARS("2 bars"),
    FOUR_BARS("4 bars"),
    SELECTION("Selection"),
}

/** Everything a studio screen needs to draw itself. */
data class StudioUiState(
    val projectId: Long? = null,
    val projectName: String = "Untitled",
    val composition: Composition = Composition.newGuitarProject(),
    val pane: StudioPane = StudioPane.FRETBOARD,
    val selectedNoteIds: Set<Long> = emptySet(),
    val display: FretboardDisplay = FretboardDisplay(),
    val entryValue: NoteValue = NoteValue.QUARTER,
    val quantize: Quantize = CompositionEditor.DEFAULT_QUANTIZE,
    val metronomeEnabled: Boolean = false,
    val metronomeLevel: Float = 0.7f,
    val countInBars: Int = 1,
    val loop: LoopRegion? = null,
    val loopScope: LoopScope = LoopScope.OFF,
    val playAlong: PlayAlongMode = PlayAlongMode.EVERYTHING,
    /** The chord backing is silenced independently of the tracks. */
    val chordsMuted: Boolean = false,
    val playback: PlaybackPosition = PlaybackPosition(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val saveState: SaveState = SaveState.IDLE,
    val recording: RhythmRecording = RhythmRecording(),
    val chordEditor: ChordEditorState = ChordEditorState(),
    val stagedHarmonies: List<HarmonySpec> = listOf(HarmonySpec.THIRD_ABOVE),
    val clipboard: CompositionEditor.Clipboard? = null,
    /**
     * A rhythm recorded before any notes were chosen. While it is non-empty, each tap on
     * the fretboard takes the next onset, which is the "rhythm first" way of working.
     */
    val rhythmTemplate: List<Long> = emptyList(),
    /** Show every part's tablature at once, rather than only the track being edited. */
    val showAllTabs: Boolean = true,
    /**
     * How large the tab and the score are drawn, 1.0 being the default. Readability on a
     * phone is a matter of taste and eyesight, so it is a control rather than a constant.
     */
    val notationScale: Float = 1.25f,
    /** Give the fretboard the whole screen, for playing a line in quickly. */
    val focusFretboard: Boolean = false,
    /** How much room is on the output. */
    val reverbMix: Float = 0.16f,
    val isLoading: Boolean = false,
) {
    val activeTrack: Track? get() = composition.activeTrack
    val activeTrackId: Long? get() = activeTrack?.id
    val fretboard: Fretboard get() = activeTrack?.fretboard ?: Fretboard(com.tabtap.music.Tunings.STANDARD_E, 24)
    val isPlaying: Boolean
        get() = playback.state == TransportState.PLAYING || playback.state == TransportState.COUNTING_IN
    val hasSelection: Boolean get() = selectedNoteIds.isNotEmpty()

    /** The track the harmoniser treats as the original line. */
    val leadTrack: Track?
        get() = composition.tracks.firstOrNull { it.role == TrackRole.LEAD }
            ?: composition.tracks.firstOrNull { it.role != TrackRole.HARMONY }
            ?: composition.tracks.firstOrNull()

    /** The generated harmony parts. */
    val harmonyTracks: List<Track>
        get() = composition.tracks.filter { it.role == TrackRole.HARMONY }

    val hasHarmony: Boolean get() = harmonyTracks.isNotEmpty()

    /** A short description of the loop's extent, for the transport readout. */
    val loopLabel: String?
        get() {
            val region = loop ?: return null
            val bar = composition.timeSignature.barTicks.toLong()
            val firstBar = region.startTick / bar + 1
            val bars = ((region.endTick - region.startTick) + bar - 1) / bar
            return when {
                loopScope == LoopScope.WHOLE -> "all $bars bars"
                bars <= 1 -> "bar $firstBar"
                else -> "bars $firstBar\u2013${firstBar + bars - 1}"
            }
        }

    /** How many slots of the rhythm template are still to be filled. */
    val rhythmSlotsRemaining: Int
        get() = (rhythmTemplate.size - (activeTrack?.notes?.map { it.startTick }?.distinct()?.size ?: 0))
            .coerceAtLeast(0)

    /** The notes currently selected on the active track. */
    val selectedNotes: List<MusicNote>
        get() = activeTrack?.notes?.filter { it.id in selectedNoteIds } ?: emptyList()

    /** Available chord voicings for the chord editor's current chord. */
    val chordVoicings: List<ChordVoicing>
        get() = chordEditor.chord?.let {
            com.tabtap.music.ChordVoicer.voicings(it, fretboard, maxResults = 8)
        } ?: emptyList()
}

/**
 * The studio's single view model.
 *
 * All four editors share this one state, which is the mechanism behind the requirement
 * that the fretboard, tab, piano roll and score always agree: there is one
 * [Composition], every edit goes through [CompositionEditor], and the views are pure
 * renderings of it. Switching panes changes nothing about the document.
 *
 * Work is never lost. Every edit is pushed onto an [EditStack] and the result is
 * autosaved on a short debounce, and [saveNow] is called from the activity's lifecycle
 * so backgrounding, rotating or closing the app all flush the current state to disk.
 */
@OptIn(FlowPreview::class)
class StudioViewModel(
    application: Application,
    private val repository: ProjectRepository,
    private val engine: AudioEngine,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages = _messages

    private var edits = EditStack(Composition.newGuitarProject())
    private var messageCounter = 0L
    private var saveJob: Job? = null

    /** Pushed whenever the document changes, to drive the autosave debounce. */
    private val saveRequests = MutableStateFlow<Composition?>(null)

    val projects: StateFlow<List<ProjectSummary>> =
        repository.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Autosave: coalesce rapid edits so tapping out a solo is one write, not fifty.
        saveRequests
            .filterNotNull()
            .debounce(AUTOSAVE_DEBOUNCE_MS)
            .onEach { persist(it) }
            .launchIn(viewModelScope)

        // Mirror the engine's transport position into the UI state.
        engine.playbackPosition
            .onEach { position -> _state.value = _state.value.copy(playback = position) }
            .launchIn(viewModelScope)
    }

    // ---------------------------------------------------------------- projects

    /** Open a project, or create one when the library is empty. */
    fun openProject(id: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val loaded = repository.load(id)
            if (loaded == null) {
                _state.value = _state.value.copy(isLoading = false)
                emit("That project could not be found", isError = true)
                return@launch
            }
            edits.reset(loaded.composition)
            _state.value = StudioUiState(
                projectId = loaded.id,
                projectName = loaded.name,
                composition = loaded.composition,
                display = _state.value.display,
                quantize = _state.value.quantize,
                metronomeLevel = _state.value.metronomeLevel,
                countInBars = _state.value.countInBars,
                saveState = SaveState.SAVED,
            )
            refreshUndoFlags()
            engine.prepare(loaded.composition)
            if (loaded.recoveredFromBackup) {
                emit(
                    "This project was recovered from its last saved backup",
                    isError = false,
                )
                repository.acknowledgeRecovery(loaded.id)
            }
        }
    }

    /** Create a project and open it. */
    fun createProject(
        name: String,
        family: InstrumentFamily,
        tuning: Tuning,
        bpm: Double,
        key: Key,
        timeSignature: TimeSignature,
        onOpened: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = repository.create(name, family, tuning, bpm, key, timeSignature)
            openProject(id)
            onOpened(id)
        }
    }

    fun renameProject(name: String) {
        val id = _state.value.projectId ?: return
        _state.value = _state.value.copy(projectName = name)
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun renameProject(id: Long, name: String) {
        viewModelScope.launch {
            repository.rename(id, name)
            if (_state.value.projectId == id) _state.value = _state.value.copy(projectName = name)
        }
    }

    fun duplicateProject(id: Long) {
        viewModelScope.launch {
            val copy = repository.duplicate(id)
            emit(if (copy != null) "Project duplicated" else "Could not duplicate that project", copy == null)
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            if (_state.value.projectId == id) {
                stop()
                _state.value = StudioUiState()
                edits.reset(Composition.newGuitarProject())
            }
            emit("Project deleted")
        }
    }

    /** Open an imported composition as a new project. */
    fun openImported(name: String, composition: Composition, warnings: List<String>) {
        viewModelScope.launch {
            val id = repository.createFromImport(name, composition)
            openProject(id)
            if (warnings.isNotEmpty()) {
                emit(warnings.first() + if (warnings.size > 1) " (+${warnings.size - 1} more)" else "")
            }
        }
    }

    /** Flush the current state to disk immediately. Called from the activity lifecycle. */
    /** The most recently opened project, so the app reopens where the user left off. */
    suspend fun projectsRepositoryMostRecentId(): Long? = repository.mostRecentProjectId()

    /**
     * Save on demand, with confirmation.
     *
     * Autosave means this is never strictly necessary, but a player who has just finished
     * something wants to be told it is safe rather than to trust a debounce.
     */
    fun saveAndConfirm() {
        if (_state.value.projectId == null) {
            emit("No project is open")
            return
        }
        viewModelScope.launch {
            persist(_state.value.composition)
            if (_state.value.saveState == SaveState.SAVED) emit("Project saved")
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        val composition = _state.value.composition
        viewModelScope.launch { persist(composition) }
    }

    private suspend fun persist(composition: Composition) {
        val id = _state.value.projectId ?: return
        _state.value = _state.value.copy(saveState = SaveState.SAVING)
        val saved = runCatching { repository.save(id, composition) }.getOrDefault(false)
        _state.value = _state.value.copy(
            saveState = if (saved) SaveState.SAVED else SaveState.FAILED,
        )
        if (!saved) emit("Could not save the project", isError = true)
    }

    // ---------------------------------------------------------------- edit plumbing

    /**
     * Apply an edit: record it for undo, publish it, keep the engine's mix in step and
     * queue an autosave. Every mutation in this view model goes through here, which is
     * why undo, autosave and the audio mix can never get out of step with the document.
     */
    private fun edit(
        label: String,
        coalesceKey: String? = null,
        rebuildBuses: Boolean = false,
        transform: (Composition) -> Composition,
    ) {
        val before = _state.value.composition
        val after = transform(before)
        if (after == before) return
        edits.push(after, label, coalesceKey)
        publish(after, rebuildBuses)
    }

    private fun publish(composition: Composition, rebuildBuses: Boolean = false) {
        // Drop selections that refer to notes which no longer exist.
        val liveIds = composition.tracks.flatMap { t -> t.notes.map { it.id } }.toSet()
        _state.value = _state.value.copy(
            composition = composition,
            selectedNoteIds = _state.value.selectedNoteIds.intersect(liveIds),
        )
        refreshUndoFlags()
        if (rebuildBuses) engine.prepare(composition) else engine.updateMix(composition)
        saveRequests.value = composition
    }

    private fun refreshUndoFlags() {
        _state.value = _state.value.copy(
            canUndo = edits.canUndo,
            canRedo = edits.canRedo,
            undoLabel = edits.undoLabel,
            redoLabel = edits.redoLabel,
        )
    }

    fun undo() {
        val composition = edits.undo() ?: return
        publish(composition, rebuildBuses = true)
    }

    fun redo() {
        val composition = edits.redo() ?: return
        publish(composition, rebuildBuses = true)
    }

    private fun emit(text: String, isError: Boolean = false) {
        _messages.tryEmit(UserMessage(messageCounter++, text, isError))
    }

    // ---------------------------------------------------------------- view options

    fun setNotationScale(scale: Float) {
        _state.value = _state.value.copy(notationScale = scale.coerceIn(0.7f, 2.6f))
    }

    /**
     * Focus mode: the fretboard fills the screen.
     *
     * Playing a line in is the thing you do most and the thing the smallest amount of
     * screen was being given to, once the workflow bar, the entry controls and the pane
     * switcher had taken their share.
     */
    fun setFocusFretboard(focus: Boolean) {
        _state.value = _state.value.copy(focusFretboard = focus)
    }

    fun setReverbMix(mix: Float) {
        _state.value = _state.value.copy(reverbMix = mix.coerceIn(0f, 0.6f))
        engine.setReverbMix(mix)
    }

    fun setShowAllTabs(show: Boolean) {
        _state.value = _state.value.copy(showAllTabs = show)
    }

    fun setPane(pane: StudioPane) {
        _state.value = _state.value.copy(pane = pane)
    }

    fun setDisplay(display: FretboardDisplay) {
        _state.value = _state.value.copy(display = display)
    }

    fun setEntryValue(value: NoteValue) {
        _state.value = _state.value.copy(entryValue = value)
    }

    fun setQuantize(quantize: Quantize) {
        _state.value = _state.value.copy(quantize = quantize)
    }

    fun setSelection(ids: Set<Long>) {
        _state.value = _state.value.copy(selectedNoteIds = ids)
    }

    fun toggleSelection(id: Long) {
        val current = _state.value.selectedNoteIds
        _state.value = _state.value.copy(
            selectedNoteIds = if (id in current) current - id else current + id,
        )
    }

    fun selectAll() {
        val track = _state.value.activeTrack ?: return
        _state.value = _state.value.copy(selectedNoteIds = track.notes.map { it.id }.toSet())
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedNoteIds = emptySet())
    }

    // ---------------------------------------------------------------- note entry

    /**
     * A tap on the fretboard.
     *
     * Appends a note at that position to the active track, in the order tapped, and
     * sounds it immediately so entry has the feel of playing rather than of data entry.
     */
    fun tapFret(string: Int, fret: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val pitch = state.fretboard.pitchAtOrNull(string, fret)
        if (pitch == null) {
            emit("That position does not exist on this fretboard", isError = true)
            return
        }
        engine.setPreviewVoice(state.activeTrack?.voice ?: InstrumentVoice.ELECTRIC_CLEAN)
        engine.preview(listOf(pitch))

        if (state.recording.isRecording) {
            // While recording a rhythm, a tap records both the note and its timing.
            recordTapAt(string, fret)
            return
        }

        // With a rhythm template in hand, each tap fills the next slot in the pattern.
        val template = state.rhythmTemplate
        if (template.isNotEmpty()) {
            val index = state.activeTrack?.notes?.map { it.startTick }?.distinct()?.size ?: 0
            if (index < template.size) {
                val start = template[index]
                val duration = if (index + 1 < template.size) {
                    (template[index + 1] - start).toInt().coerceAtLeast(1)
                } else {
                    state.entryValue.ticks
                }
                edit("Add ${Pitch.fullName(pitch)}") {
                    CompositionEditor.addNoteAt(it, trackId, string, fret, start, duration)
                }
                if (index + 1 == template.size) {
                    clearRhythmTemplate()
                    emit("Rhythm filled in")
                }
                return
            }
            clearRhythmTemplate()
        }

        edit("Add ${Pitch.fullName(pitch)}") {
            CompositionEditor.appendNoteAtPosition(it, trackId, string, fret, state.entryValue)
        }
    }

    /** A long press stacks the note onto the previous one, building a chord. */
    fun stackFret(string: Int, fret: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val pitch = state.fretboard.pitchAtOrNull(string, fret) ?: return
        engine.preview(listOf(pitch))
        edit("Stack ${Pitch.fullName(pitch)}") {
            CompositionEditor.stackNoteAtPosition(it, trackId, string, fret)
        }
    }

    /** Sound a position without recording it, for exploring the fretboard. */
    fun auditionFret(string: Int, fret: Int) {
        val pitch = _state.value.fretboard.pitchAtOrNull(string, fret) ?: return
        engine.setPreviewVoice(_state.value.activeTrack?.voice ?: InstrumentVoice.ELECTRIC_CLEAN)
        engine.preview(listOf(pitch))
    }

    fun removeLastNote() {
        val trackId = _state.value.activeTrackId ?: return
        edit("Remove last note") { CompositionEditor.removeLastNote(it, trackId) }
    }

    fun deleteSelection() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        if (state.selectedNoteIds.isEmpty()) return
        val count = state.selectedNoteIds.size
        edit(if (count == 1) "Delete note" else "Delete $count notes") {
            CompositionEditor.removeNotes(it, trackId, state.selectedNoteIds)
        }
        clearSelection()
    }

    fun clearTrack() {
        val trackId = _state.value.activeTrackId ?: return
        edit("Clear track") { CompositionEditor.clearTrack(it, trackId) }
    }

    fun addNoteAt(string: Int, fret: Int, startTick: Long, durationTicks: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Add note") {
            CompositionEditor.addNoteAt(it, trackId, string, fret, startTick, durationTicks)
        }
    }

    fun addNoteByPitch(pitch: Int, startTick: Long, durationTicks: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Add note") {
            CompositionEditor.addNoteByPitch(it, trackId, pitch, startTick, durationTicks)
        }
    }

    fun moveNoteToPosition(noteId: Long, string: Int, fret: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Move note", coalesceKey = "move-$noteId") {
            CompositionEditor.moveNoteToPosition(it, trackId, noteId, string, fret)
        }
    }

    /**
     * Move a note by diatonic steps within the key, which is what dragging a notehead up
     * or down a staff means: the next line or space, not the next semitone.
     */
    fun moveNoteByScaleSteps(noteId: Long, steps: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val note = state.activeTrack?.note(noteId) ?: return
        val target = com.tabtap.music.HarmonyGenerator.diatonicTranspose(
            note.pitch, state.composition.key, steps,
        )
        val semitones = target - note.pitch
        if (semitones == 0) return
        edit("Move note", coalesceKey = "scale-step-$noteId") {
            CompositionEditor.transposeNote(it, trackId, noteId, semitones)
        }
    }

    fun transposeNote(noteId: Long, semitones: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Transpose note", coalesceKey = "transpose-$noteId") {
            CompositionEditor.transposeNote(it, trackId, noteId, semitones)
        }
    }

    fun setNoteStart(noteId: Long, startTick: Long) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Move note", coalesceKey = "start-$noteId") {
            CompositionEditor.setNoteStart(it, trackId, noteId, startTick)
        }
    }

    fun setNoteDuration(noteId: Long, ticks: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Change length", coalesceKey = "duration-$noteId") {
            CompositionEditor.setNoteDuration(it, trackId, noteId, ticks)
        }
    }

    /** Double one note's length, up to a whole bar, for the inline rhythm controls. */
    fun doubleNoteLength(noteId: Long) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val note = state.activeTrack?.note(noteId) ?: return
        val limit = state.composition.timeSignature.barTicks * 2
        edit("Lengthen note", coalesceKey = "length-$noteId") {
            CompositionEditor.setNoteDuration(
                it, trackId, noteId, (note.durationTicks * 2).coerceAtMost(limit),
            )
        }
    }

    /** Halve one note's length, down to a 32nd. */
    fun halveNoteLength(noteId: Long) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val note = state.activeTrack?.note(noteId) ?: return
        edit("Shorten note", coalesceKey = "length-$noteId") {
            CompositionEditor.setNoteDuration(
                it, trackId, noteId,
                (note.durationTicks / 2).coerceAtLeast(Ticks.THIRTY_SECOND),
            )
        }
    }

    /** Sound one note on its own, so a length change can be heard. */
    fun auditionNote(noteId: Long) {
        val state = _state.value
        val note = state.activeTrack?.note(noteId) ?: return
        engine.setPreviewVoice(state.activeTrack?.voice ?: InstrumentVoice.ELECTRIC_CLEAN)
        engine.preview(
            listOf(note.pitch),
            durationSeconds = com.tabtap.music.Ticks
                .toSeconds(note.durationTicks.toLong(), state.composition.bpm)
                .toFloat()
                .coerceIn(0.1f, 6f),
        )
    }

    fun setSelectionDuration(value: NoteValue) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        if (state.selectedNoteIds.isEmpty()) {
            setEntryValue(value)
            return
        }
        edit("Set ${value.displayName}") {
            CompositionEditor.setNoteDurations(it, trackId, state.selectedNoteIds, value)
        }
    }

    fun setSelectionVelocity(velocity: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        edit("Set velocity", coalesceKey = "velocity") {
            CompositionEditor.setNoteVelocity(it, trackId, state.selectedNoteIds, velocity)
        }
    }

    fun toggleArticulation(articulation: Articulation) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        if (state.selectedNoteIds.isEmpty()) {
            emit("Select a note first")
            return
        }
        edit(articulation.displayName) {
            CompositionEditor.toggleArticulation(it, trackId, state.selectedNoteIds, articulation)
        }
    }

    fun setBend(semitones: Float) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        edit("Bend", coalesceKey = "bend") {
            CompositionEditor.setBend(it, trackId, state.selectedNoteIds, semitones)
        }
    }

    fun setTie(tied: Boolean) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        edit(if (tied) "Tie" else "Remove tie") {
            CompositionEditor.setTie(it, trackId, state.selectedNoteIds, tied)
        }
    }

    fun nudgeSelection(deltaTicks: Long) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        edit("Nudge", coalesceKey = "nudge") {
            CompositionEditor.nudgeNotes(it, trackId, state.selectedNoteIds, deltaTicks)
        }
    }

    fun transposeSelection(semitones: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val ids = state.selectedNoteIds.ifEmpty {
            state.activeTrack?.notes?.map { it.id }?.toSet() ?: emptySet()
        }
        edit(if (semitones > 0) "Transpose up" else "Transpose down") {
            CompositionEditor.transposeNotes(it, trackId, ids, semitones)
        }
    }

    fun refingerSelection() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val ids = state.selectedNoteIds
        if (ids.isEmpty()) {
            emit("Select a note to try another position for it")
            return
        }
        edit("Alternative fingering") { composition ->
            ids.fold(composition) { acc, id -> CompositionEditor.refingerNote(acc, trackId, id) }
        }
    }

    // ---------------------------------------------------------------- copy and paste

    fun copySelection() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val clipboard = CompositionEditor.copy(state.composition, trackId, state.selectedNoteIds)
        if (clipboard.isEmpty) {
            emit("Select some notes to copy")
            return
        }
        _state.value = state.copy(clipboard = clipboard)
        emit("Copied ${clipboard.notes.size} note(s)")
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    fun pasteAtPlayhead() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val clipboard = state.clipboard ?: run {
            emit("Nothing to paste")
            return
        }
        val at = state.playback.tick.takeIf { it > 0 }
            ?: state.activeTrack?.endTick
            ?: 0L
        edit("Paste") { CompositionEditor.paste(it, trackId, clipboard, at) }
    }

    fun duplicateSelection() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        if (state.selectedNoteIds.isEmpty()) {
            emit("Select some notes to repeat")
            return
        }
        edit("Repeat selection") {
            CompositionEditor.duplicateSelection(it, trackId, state.selectedNoteIds)
        }
    }

    // ---------------------------------------------------------------- rhythm

    fun setMetronomeEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(metronomeEnabled = enabled)
    }

    fun setMetronomeLevel(level: Float) {
        _state.value = _state.value.copy(metronomeLevel = level)
        engine.setMetronomeLevel(level)
    }

    fun setCountInBars(bars: Int) {
        _state.value = _state.value.copy(countInBars = bars.coerceIn(0, 4))
    }

    fun setTempo(bpm: Double) {
        edit("Set tempo", coalesceKey = "tempo") { CompositionEditor.setTempo(it, bpm) }
    }

    /**
     * Tap tempo. Each tap is both a timing sample and an audible click, so the user can
     * hear whether the tempo they are tapping matches what they want.
     */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        val taps = (_state.value.recording.tapTimesMs + now).takeLast(8)
        _state.value = _state.value.copy(
            recording = _state.value.recording.copy(tapTimesMs = taps),
        )
        engine.previewClick(accented = false)
        RhythmEngine.tapTempo(taps)?.let { setTempo(it) }
    }

    fun resetTapTempo() {
        _state.value = _state.value.copy(
            recording = _state.value.recording.copy(tapTimesMs = emptyList()),
        )
    }

    fun setTimeSignature(timeSignature: TimeSignature) {
        edit("Set ${timeSignature}") { CompositionEditor.setTimeSignature(it, timeSignature) }
    }

    /**
     * Start recording a rhythm.
     *
     * The metronome runs and every tap records an onset. Taps are stored raw and
     * quantised only when the recording is committed, so the quantise setting can be
     * changed after the fact without losing the performance.
     */
    fun startRhythmRecording() {
        val state = _state.value
        _state.value = state.copy(recording = RhythmRecording(isRecording = true))
        playInternal(
            fromTick = 0L,
            metronome = true,
            countInBars = state.countInBars,
            includeTracks = true,
        )
    }

    /** Record one rhythm tap at the current playhead, with no pitch. */
    fun recordRhythmTap() {
        val state = _state.value
        if (!state.recording.isRecording) return
        val tick = state.playback.tick
        _state.value = state.copy(
            recording = state.recording.copy(onsets = state.recording.onsets + tick),
        )
        engine.previewClick(accented = false)
    }

    /** Record a tap that carries both a pitch and a time. */
    private fun recordTapAt(string: Int, fret: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val tick = state.playback.tick
        _state.value = state.copy(
            recording = state.recording.copy(onsets = state.recording.onsets + tick),
        )
        edit("Record note") {
            CompositionEditor.addNoteAt(
                it, trackId, string, fret,
                startTick = tick,
                durationTicks = state.entryValue.ticks,
            )
        }
    }

    fun cancelRhythmRecording() {
        _state.value = _state.value.copy(recording = RhythmRecording())
        stop()
    }

    /**
     * Stop recording and apply the tapped rhythm to the notes already on the track.
     *
     * This is the "notes first, rhythm afterwards" path: the pitches and their order are
     * untouched and only the timing is replaced.
     */
    fun commitRhythmToNotes() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val onsets = state.recording.onsets
        stop()
        _state.value = _state.value.copy(recording = RhythmRecording())
        if (onsets.isEmpty()) {
            emit("No taps were recorded")
            return
        }
        val quantized = RhythmEngine.quantizeStarts(onsets, state.quantize, state.composition.timeSignature)
        edit("Apply tapped rhythm") {
            CompositionEditor.applyTappedRhythm(it, trackId, quantized, state.entryValue.ticks)
        }
        emit("Applied a ${onsets.size}-tap rhythm")
    }

    /**
     * Keep the recorded taps as a rhythm template, to be filled in with notes.
     *
     * This is the "rhythm first, notes afterwards" path: the taps are remembered, and
     * every note tapped on the fretboard from then on takes the next onset in the
     * pattern, so a rhythm can be worked out before a single pitch is chosen.
     */
    fun keepRhythmAsTemplate() {
        stop()
        val state = _state.value
        val onsets = RhythmEngine.quantizeStarts(
            state.recording.onsets, state.quantize, state.composition.timeSignature,
        )
        if (onsets.isEmpty()) {
            _state.value = state.copy(recording = RhythmRecording())
            emit("No taps were recorded")
            return
        }
        _state.value = state.copy(
            recording = RhythmRecording(),
            rhythmTemplate = onsets,
        )
        emit("Rhythm kept: tap ${onsets.size} notes on the fretboard to fill it in")
    }

    fun clearRhythmTemplate() {
        _state.value = _state.value.copy(rhythmTemplate = emptyList())
    }

    /** Stop recording and keep the notes exactly where they were played. */
    fun commitRecordingAsPlayed() {
        stop()
        val state = _state.value
        _state.value = state.copy(recording = RhythmRecording())
        emit("Recording kept as played")
    }

    fun applyEvenRhythm(value: NoteValue) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Even ${value.displayName} rhythm") {
            CompositionEditor.applyEvenRhythm(it, trackId, value)
        }
    }

    fun quantizeTrack() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val ids = state.selectedNoteIds.ifEmpty { null }
        edit("Quantise") { CompositionEditor.quantize(it, trackId, state.quantize, ids) }
    }

    fun humanizeTrack(amountTicks: Int = Ticks.SIXTEENTH / 4) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Humanise") { CompositionEditor.humanize(it, trackId, amountTicks) }
    }

    fun makeLegato() {
        val trackId = _state.value.activeTrackId ?: return
        edit("Legato") { CompositionEditor.makeLegato(it, trackId) }
    }

    // ---------------------------------------------------------------- key and scale

    fun setKey(key: Key) {
        edit("Set key to ${key.shortName}") { CompositionEditor.setKey(it, key) }
    }

    /**
     * Build a custom scale from a set of semitone offsets and make it the project's.
     *
     * The offsets are normalised and given sensible letter steps so the scale still
     * spells and engraves correctly, which is what distinguishes this from simply
     * highlighting arbitrary frets.
     */
    fun setCustomScale(semitones: Set<Int>) {
        if (semitones.size < 2) {
            emit("A scale needs at least two notes")
            return
        }
        val scale = ScaleType.custom("custom", "Custom", semitones.sorted())
        val key = _state.value.composition.key
        edit("Custom scale") { CompositionEditor.setKey(it, key.copy(scale = scale)) }
    }

    fun setScale(scale: ScaleType) {
        val key = _state.value.composition.key
        setKey(key.copy(scale = scale))
    }

    /** Transpose the music into a new key, rather than merely relabelling it. */
    fun transposeToKey(key: Key) {
        edit("Transpose to ${key.shortName}") { CompositionEditor.transposeToKey(it, key) }
    }

    fun suggestKey() {
        val track = _state.value.activeTrack ?: return
        if (track.notes.isEmpty()) {
            emit("Enter some notes first")
            return
        }
        val suggestion = com.tabtap.music.CompositionAnalyzer
            .suggestKeys(track, listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR))
            .firstOrNull()?.first ?: return
        setKey(suggestion)
        emit("Key set to ${suggestion.shortName}")
    }

    // ---------------------------------------------------------------- tracks

    fun setActiveTrack(trackId: Long) {
        val composition = CompositionEditor.setActiveTrack(_state.value.composition, trackId)
        edits.replaceCurrent(composition)
        _state.value = _state.value.copy(composition = composition, selectedNoteIds = emptySet())
        saveRequests.value = composition
    }

    fun setTuning(tuning: Tuning) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        edit("Set ${tuning.displayName}", rebuildBuses = false) {
            CompositionEditor.setTuning(it, trackId, tuning)
        }
        val unplayable = _state.value.activeTrack?.unplayableNotes()?.size ?: 0
        if (unplayable > 0) {
            emit("$unplayable note(s) do not fit ${tuning.displayName}; their pitches were kept")
        }
    }

    fun setCustomStringTuning(stringIndex: Int, pitch: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val track = state.activeTrack ?: return
        if (!Pitch.isValid(pitch)) return
        edit("Tune string ${stringIndex + 1}", coalesceKey = "string-$stringIndex") {
            CompositionEditor.setTuning(it, trackId, track.tuning.withString(stringIndex, pitch))
        }
    }

    fun setStringCount(count: Int) {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val track = state.activeTrack ?: return
        edit("Set $count strings") {
            CompositionEditor.setTuning(it, trackId, track.tuning.withStringCount(count))
        }
    }

    fun setFretCount(frets: Int) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Set $frets frets") { CompositionEditor.setFretCount(it, trackId, frets) }
    }

    fun setVoice(voice: InstrumentVoice) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Set ${voice.displayName}", rebuildBuses = true) {
            CompositionEditor.setVoice(it, trackId, voice)
        }
    }

    fun setTrackVoice(trackId: Long, voice: InstrumentVoice) {
        edit("Set ${voice.displayName}", rebuildBuses = true) {
            CompositionEditor.setVoice(it, trackId, voice)
        }
    }

    fun switchInstrumentFamily(family: InstrumentFamily) {
        val trackId = _state.value.activeTrackId ?: return
        edit("Switch to ${family.displayName}", rebuildBuses = true) {
            CompositionEditor.switchInstrumentFamily(it, trackId, family)
        }
    }

    fun setTrackVolume(trackId: Long, volume: Float) {
        edit("Set volume", coalesceKey = "volume-$trackId") {
            CompositionEditor.setTrackVolume(it, trackId, volume)
        }
    }

    fun setTrackPan(trackId: Long, pan: Float) {
        edit("Set pan", coalesceKey = "pan-$trackId") { CompositionEditor.setTrackPan(it, trackId, pan) }
    }

    fun setTrackMuted(trackId: Long, muted: Boolean) {
        edit(if (muted) "Mute" else "Unmute") { CompositionEditor.setTrackMuted(it, trackId, muted) }
    }

    fun setTrackSoloed(trackId: Long, soloed: Boolean) {
        edit(if (soloed) "Solo" else "Unsolo") { CompositionEditor.setTrackSoloed(it, trackId, soloed) }
    }

    fun renameTrack(trackId: Long, name: String) {
        edit("Rename track", coalesceKey = "rename-$trackId") {
            CompositionEditor.renameTrack(it, trackId, name)
        }
    }

    fun addTrack() {
        edit("Add track", rebuildBuses = true) { CompositionEditor.addTrack(it) }
    }

    fun removeTrack(trackId: Long) {
        if (_state.value.composition.tracks.size <= 1) {
            emit("A project needs at least one track")
            return
        }
        edit("Remove track", rebuildBuses = true) { CompositionEditor.removeTrack(it, trackId) }
    }

    // ---------------------------------------------------------------- harmony

    fun setStagedHarmonies(specs: List<HarmonySpec>) {
        _state.value = _state.value.copy(stagedHarmonies = specs)
    }

    /** Shift every staged harmony voice by whole octaves. */
    fun setStagedHarmonyOctave(octaveShift: Int) {
        val clamped = octaveShift.coerceIn(-2, 2)
        _state.value = _state.value.copy(
            stagedHarmonies = _state.value.stagedHarmonies.map { it.copy(octaveShift = clamped) },
        )
    }

    fun toggleStagedHarmony(spec: HarmonySpec) {
        val current = _state.value.stagedHarmonies
        _state.value = _state.value.copy(
            stagedHarmonies = if (current.any { sameVoice(it, spec) }) {
                current.filterNot { sameVoice(it, spec) }
            } else {
                current + spec
            },
        )
    }

    private fun sameVoice(a: HarmonySpec, b: HarmonySpec): Boolean =
        a.mode == b.mode && a.diatonicSteps == b.diatonicSteps &&
            a.semitones == b.semitones && a.octaveShift == b.octaveShift

    /**
     * Harmonise the lead line with the staged intervals.
     *
     * Deliberately harmonises the *lead* rather than whichever track happens to be
     * selected: harmonising a harmony is almost never what someone means, and having
     * tapped a harmony staff to read it should not change what this does.
     */
    fun harmoniseLead() {
        val state = _state.value
        val lead = state.leadTrack ?: return
        if (lead.notes.isEmpty()) {
            emit("Play a line on the fretboard first, then harmonise it")
            return
        }
        setActiveTrack(lead.id)
        generateHarmonies()
    }

    /** Generate the staged harmony voices from the active track. */
    fun generateHarmonies() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        val track = state.activeTrack ?: return
        if (track.notes.isEmpty()) {
            emit("Enter a line on the fretboard first")
            return
        }
        if (state.stagedHarmonies.isEmpty()) {
            emit("Choose at least one harmony interval")
            return
        }
        var warnings: List<com.tabtap.music.HarmonyWarning> = emptyList()
        edit("Generate harmony", rebuildBuses = true) { composition ->
            val (next, w) = CompositionEditor.generateHarmonies(
                composition, trackId, state.stagedHarmonies,
            )
            warnings = w
            next
        }
        val created = state.stagedHarmonies.size
        if (warnings.isEmpty()) {
            emit("Generated $created harmony voice${if (created > 1) "s" else ""}")
        } else {
            emit(warnings.first().message, isError = false)
        }
    }

    /** Bring derived harmonies back in step after the source line or the key changed. */
    fun regenerateHarmonies() {
        val state = _state.value
        val source = state.composition.tracks.firstOrNull { it.role == com.tabtap.music.TrackRole.LEAD }
            ?: state.activeTrack ?: return
        if (state.composition.tracks.none { it.derivedFromTrackId == source.id }) {
            emit("There are no generated harmonies to update")
            return
        }
        edit("Update harmonies", rebuildBuses = true) {
            CompositionEditor.regenerateHarmonies(it, source.id).first
        }
        emit("Harmonies updated")
    }

    // ---------------------------------------------------------------- chords

    /** The chord length currently chosen, defaulting to one bar of the project's metre. */
    private fun chordLengthTicks(): Int {
        val state = _state.value
        return state.chordEditor.durationTicks.takeIf { it > 0 }
            ?: state.composition.timeSignature.barTicks
    }

    fun setChordEditor(state: ChordEditorState) {
        _state.value = _state.value.copy(chordEditor = state)
        state.chord?.let { chord ->
            val voicings = com.tabtap.music.ChordVoicer.voicings(chord, _state.value.fretboard, 8)
            voicings.getOrNull(state.voicingIndex)?.let { voicing ->
                engine.setPreviewVoice(_state.value.activeTrack?.voice ?: InstrumentVoice.ACOUSTIC_STEEL)
                engine.preview(voicing.pitches(_state.value.fretboard), velocity = 0.7f)
            }
        }
    }

    fun previewChordEditor() {
        val state = _state.value
        val voicing = state.chordVoicings.getOrNull(state.chordEditor.voicingIndex) ?: return
        engine.setPreviewVoice(state.activeTrack?.voice ?: InstrumentVoice.ACOUSTIC_STEEL)
        engine.preview(voicing.pitches(state.fretboard), velocity = 0.75f)
    }

    fun addChordToProgression() {
        val state = _state.value
        val chord = state.chordEditor.chord ?: return
        val voicing = state.chordVoicings.getOrNull(state.chordEditor.voicingIndex)
        // When the editor was opened on an existing chord, confirming updates it in
        // place rather than appending a near-duplicate to the end.
        val editingId = state.chordEditor.editingChordId
        if (editingId != null) {
            edit("Update ${chord.symbol}", rebuildBuses = true) {
                CompositionEditor.replaceChord(
                    it, editingId, chord, chordLengthTicks(), state.chordEditor.strum, voicing,
                )
            }
            _state.value = _state.value.copy(
                chordEditor = _state.value.chordEditor.copy(editingChordId = null),
            )
            emit("${chord.symbol} updated")
            return
        }
        edit("Add ${chord.symbol}", rebuildBuses = true) {
            CompositionEditor.appendChord(
                it, chord, chordLengthTicks(), state.chordEditor.strum, voicing,
            )
        }
    }

    fun addChord(chord: Chord) {
        val state = _state.value
        edit("Add ${chord.symbol}", rebuildBuses = true) {
            CompositionEditor.appendChord(
                it, chord, chordLengthTicks(), state.chordEditor.strum,
            )
        }
        engine.setPreviewVoice(InstrumentVoice.ACOUSTIC_STEEL)
        com.tabtap.music.ChordVoicer.bestVoicing(chord, state.fretboard)?.let {
            engine.preview(it.pitches(state.fretboard), velocity = 0.7f)
        }
    }

    /** Sound one chord of the progression, with its own voicing and strum. */
    fun previewChord(chordId: Long) {
        val state = _state.value
        val event = state.composition.chordProgression.firstOrNull { it.id == chordId } ?: return
        val board = state.fretboard
        val notes = com.tabtap.io.ChordRenderer.render(event, board)
        if (notes.isEmpty()) {
            emit("That chord cannot be played on this tuning")
            return
        }
        engine.setPreviewVoice(InstrumentVoice.ACOUSTIC_STEEL)
        engine.preview(notes.map { it.pitch }, velocity = 0.75f)
    }

    /** Load an existing chord into the editor so it can be changed. */
    fun beginEditChord(chordId: Long) {
        val state = _state.value
        val event = state.composition.chordProgression.firstOrNull { it.id == chordId } ?: return
        val chord = event.chord
        val voicings = com.tabtap.music.ChordVoicer.voicings(chord, state.fretboard, 8)
        _state.value = state.copy(
            chordEditor = ChordEditorState(
                rootName = "${chord.root.letter}${chord.root.accidentalText}",
                type = chord.type,
                inversion = chord.bassPitchClass
                    ?.let { bass -> chord.type.semitones.indexOfFirst { s ->
                        ((chord.rootPitchClass + s) % 12 + 12) % 12 == bass
                    } }
                    ?.coerceAtLeast(0) ?: 0,
                strum = event.strum,
                durationTicks = event.durationTicks,
                voicingIndex = voicings.indexOfFirst { it.frets == event.voicing?.frets }
                    .coerceAtLeast(0),
                editingChordId = chordId,
            ),
        )
        previewChord(chordId)
    }

    fun cancelEditChord() {
        _state.value = _state.value.copy(
            chordEditor = _state.value.chordEditor.copy(editingChordId = null),
        )
    }

    /** Move a chord one place earlier or later in the progression. */
    fun nudgeChord(chordId: Long, delta: Int) {
        edit(if (delta < 0) "Move chord earlier" else "Move chord later") {
            CompositionEditor.nudgeChord(it, chordId, delta)
        }
    }

    fun duplicateChord(chordId: Long) {
        edit("Duplicate chord", rebuildBuses = true) {
            CompositionEditor.duplicateChord(it, chordId)
        }
    }

    fun doubleChordLength(chordId: Long) {
        edit("Lengthen chord") { CompositionEditor.doubleChordLength(it, chordId) }
    }

    fun halveChordLength(chordId: Long) {
        edit("Shorten chord") { CompositionEditor.halveChordLength(it, chordId) }
    }

    fun setChordDurationTicks(chordId: Long, ticks: Int) {
        edit("Set chord length") { CompositionEditor.setChordDuration(it, chordId, ticks) }
    }

    fun removeChord(chordId: Long) {
        edit("Remove chord", rebuildBuses = true) { CompositionEditor.removeChord(it, chordId) }
    }

    fun setChordStrum(chordId: Long, strum: StrumPattern) {
        edit("Set ${strum.displayName}") { CompositionEditor.setChordStrum(it, chordId, strum) }
    }

    fun moveChord(chordId: Long, toIndex: Int) {
        edit("Reorder chords") { CompositionEditor.moveChord(it, chordId, toIndex) }
    }

    fun clearChords() {
        edit("Clear chords", rebuildBuses = true) { CompositionEditor.clearChords(it) }
    }

    /** Write the progression into the active track as individual notes. */
    fun renderChordsToTrack() {
        val state = _state.value
        val trackId = state.activeTrackId ?: return
        if (state.composition.chordProgression.isEmpty()) {
            emit("Add some chords first")
            return
        }
        edit("Chords to notes") { CompositionEditor.renderChordsToTrack(it, trackId) }
    }

    /** Add a whole progression from a degree pattern in the current key. */
    fun addProgression(degrees: List<Int>) {
        val state = _state.value
        val chords = com.tabtap.music.DiatonicChords.triads(state.composition.key)
        edit("Add progression", rebuildBuses = true) { composition ->
            degrees.fold(composition) { acc, degree ->
                val chord = chords.getOrNull(degree % chords.size) ?: return@fold acc
                CompositionEditor.appendChord(
                    acc, chord, state.composition.timeSignature.barTicks, state.chordEditor.strum,
                )
            }
        }
    }

    // ---------------------------------------------------------------- transport

    fun play() {
        val state = _state.value
        if (state.playback.state == TransportState.PAUSED) {
            engine.resume()
            return
        }
        playInternal(
            fromTick = state.loop?.startTick ?: 0L,
            metronome = state.metronomeEnabled,
            countInBars = state.countInBars,
            includeTracks = true,
        )
    }

    /** Play from the playhead rather than from the start. */
    fun playFrom(tick: Long) {
        playInternal(
            fromTick = tick,
            metronome = _state.value.metronomeEnabled,
            countInBars = 0,
            includeTracks = true,
        )
    }

    fun pause() = engine.pause()

    fun stop() {
        engine.stop()
        PlaybackServiceController.stop(getApplication())
    }

    fun restart() {
        stop()
        play()
    }

    /** Run the metronome on its own, for practice or for tapping a rhythm against. */
    fun startMetronomeOnly() {
        val state = _state.value
        engine.prepare(state.composition)
        engine.setMetronomeLevel(state.metronomeLevel)
        val schedule = Sequencer.build(
            composition = state.composition.copy(tracks = emptyList(), chordProgression = emptyList()),
            sampleRate = engine.engineSampleRate,
            fromTick = 0L,
            toTick = state.composition.timeSignature.barTicks.toLong() * 64,
            countInBars = 0,
            metronome = true,
            includeChords = false,
        )
        engine.play(schedule, LoopRegion(0L, state.composition.timeSignature.barTicks.toLong() * 64))
        PlaybackServiceController.start(getApplication())
    }

    private fun playInternal(
        fromTick: Long,
        metronome: Boolean,
        countInBars: Int,
        includeTracks: Boolean,
    ) {
        val state = _state.value
        engine.prepare(state.composition)
        engine.setMetronomeLevel(state.metronomeLevel)
        engine.setReverbMix(state.reverbMix)
        val schedule = Sequencer.build(
            composition = if (includeTracks) {
                state.composition
            } else {
                state.composition.copy(tracks = emptyList())
            },
            sampleRate = engine.engineSampleRate,
            fromTick = fromTick,
            // The schedule is never truncated at the loop end: the engine wraps by frame,
            // so the loop can be widened mid-playback without rebuilding.
            toTick = null,
            countInBars = countInBars,
            metronome = metronome,
            metronomeLevel = state.metronomeLevel,
            includeChords = !state.chordsMuted,
        )
        engine.play(schedule, state.loop)
        PlaybackServiceController.start(getApplication())
    }

    /**
     * Set the loop region directly.
     *
     * Changing the loop while the transport is running restarts playback at the new
     * region. The alternative — leaving the playhead wherever it happens to be, possibly
     * outside the new loop — is confusing, and the schedule is built relative to the loop
     * start, so restarting is also the honest way to apply it.
     */
    fun setLoop(region: LoopRegion?, scope: LoopScope = LoopScope.OFF) {
        val wasPlaying = _state.value.isPlaying
        _state.value = _state.value.copy(
            loop = region,
            loopScope = if (region == null) LoopScope.OFF else scope,
        )
        engine.setLoop(region)
        if (wasPlaying) {
            playInternal(
                fromTick = region?.startTick ?: 0L,
                metronome = _state.value.metronomeEnabled,
                countInBars = 0,
                includeTracks = true,
            )
        }
    }

    /** Apply a loop scope, working out the region from the music and the playhead. */
    /**
     * Choose which parts sound.
     *
     * Implemented with the tracks' own mute flags rather than a separate playback-only
     * concept, so what you hear is always exactly what the mixer shows — and switching
     * mode mid-playback takes effect at the next buffer without stopping anything.
     */
    fun setPlayAlong(mode: PlayAlongMode) {
        val state = _state.value
        val lead = state.leadTrack
        val harmonies = state.harmonyTracks.map { it.id }.toSet()

        // The chord backing plays under every mode: it is the thing you want underneath
        // whichever part you are playing yourself.
        val chordsMuted = false

        var composition = CompositionEditor.clearSolos(state.composition)
        composition = composition.copy(
            tracks = composition.tracks.map { track ->
                val muted = when (mode) {
                    PlayAlongMode.EVERYTHING -> false
                    PlayAlongMode.I_PLAY_LEAD -> track.id == lead?.id
                    PlayAlongMode.I_PLAY_HARMONY -> track.id in harmonies
                    PlayAlongMode.CHORDS_ONLY -> true
                }
                if (track.muted == muted) track else track.copy(muted = muted)
            },
        )

        edits.push(composition, mode.label)
        _state.value = _state.value.copy(
            playAlong = mode,
            chordsMuted = chordsMuted,
        )
        publish(composition)
        engine.setChordsAudible(!chordsMuted)

        if (mode == PlayAlongMode.CHORDS_ONLY && state.composition.chordProgression.isEmpty()) {
            emit("There are no chords yet — add some on the Chords screen")
        }
    }

    /** Silence or restore the chord backing on its own. */
    fun setChordsMuted(muted: Boolean) {
        _state.value = _state.value.copy(chordsMuted = muted)
        engine.setChordsAudible(!muted)
    }

    fun setLoopScope(scope: LoopScope) {
        val state = _state.value
        val ts = state.composition.timeSignature
        val bar = ts.barTicks.toLong()

        val region = when (scope) {
            LoopScope.OFF -> null

            // The whole section: every bar that contains music, rounded out to bar lines
            // so the loop seam lands on a downbeat.
            LoopScope.WHOLE -> {
                val end = state.composition.endTick
                if (end <= 0L) {
                    LoopRegion(0L, bar)
                } else {
                    LoopRegion(0L, ((end + bar - 1) / bar) * bar)
                }
            }

            LoopScope.CURRENT_BAR -> {
                val start = (state.playback.tick / bar) * bar
                LoopRegion(start, start + bar)
            }

            LoopScope.TWO_BARS -> {
                val start = (state.playback.tick / bar) * bar
                LoopRegion(start, start + bar * 2)
            }

            LoopScope.FOUR_BARS -> {
                val start = (state.playback.tick / bar) * bar
                LoopRegion(start, start + bar * 4)
            }

            // The selection, rounded out to whole bars so it still loops musically.
            LoopScope.SELECTION -> {
                val notes = state.selectedNotes
                if (notes.isEmpty()) {
                    emit("Select some notes to loop them")
                    return
                }
                val from = (notes.minOf { it.startTick } / bar) * bar
                val to = ((notes.maxOf { it.endTick } + bar - 1) / bar) * bar
                LoopRegion(from, maxOf(to, from + bar))
            }
        }
        setLoop(region, scope)
    }

    /** Loop the whole section: the quickest way to hear an arrangement round and round. */
    fun loopWholeSection() = setLoopScope(LoopScope.WHOLE)

    /** Loop the bar the playhead is in. */
    fun loopCurrentBar() = setLoopScope(LoopScope.CURRENT_BAR)

    fun seekTo(tick: Long) = engine.seekToTick(tick)

    /** Audition the selected notes, or the whole track when nothing is selected. */
    fun auditionSelection() {
        val state = _state.value
        val pitches = state.selectedNotes.map { it.pitch }.ifEmpty {
            state.activeTrack?.notes?.take(8)?.map { it.pitch } ?: emptyList()
        }
        if (pitches.isEmpty()) return
        engine.setPreviewVoice(state.activeTrack?.voice ?: InstrumentVoice.ELECTRIC_CLEAN)
        engine.preview(pitches, velocity = 0.75f)
    }

    override fun onCleared() {
        saveNow()
        engine.panic()
        super.onCleared()
    }

    companion object {
        private const val AUTOSAVE_DEBOUNCE_MS = 500L
    }
}

/**
 * Starts and stops the playback service.
 *
 * Kept behind a tiny indirection so the view model does not depend on Android service
 * plumbing, which also makes it testable.
 */
object PlaybackServiceController {
    fun start(context: android.content.Context) =
        com.tabtap.app.audio.PlaybackService.start(context)

    fun stop(context: android.content.Context) =
        com.tabtap.app.audio.PlaybackService.stop(context)
}
