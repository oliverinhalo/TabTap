package com.tabtap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.export.ExportResult
import com.tabtap.app.export.FileExport
import com.tabtap.app.export.PdfOptions
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.TrackDot
import com.tabtap.io.ExportFormat
import com.tabtap.io.MidiExportOptions
import kotlinx.coroutines.launch

/**
 * The export screen.
 *
 * Each format says what it actually carries, so the choice is informed. The file is
 * written wherever the user points the system picker — TabTap holds no storage permission
 * and keeps nothing hidden in its own directory.
 */
@Composable
fun ExportScreen(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var format by remember { mutableStateOf(ExportFormat.MIDI_TYPE_1) }
    var includeMetronome by remember { mutableStateOf(false) }
    var includeChords by remember { mutableStateOf(true) }
    var respectMute by remember { mutableStateOf(false) }
    var pdfNotation by remember { mutableStateOf(true) }
    var pdfTab by remember { mutableStateOf(true) }
    var pdfAllTracks by remember { mutableStateOf(true) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Which parts to export. Starts as all of them; narrowing it is how you save a
    // generated harmony as its own tab, MIDI or PDF.
    var selectedTracks by remember(state.projectId, state.composition.tracks.size) {
        mutableStateOf(state.composition.tracks.map { it.id }.toSet())
    }

    /**
     * The composition as it will be exported.
     *
     * Narrowing the track list here rather than inside each exporter means every format
     * honours the choice identically, and the chord progression is dropped with the
     * chord track so a harmony-only export really is harmony only.
     */
    val exported = remember(state.composition, selectedTracks, includeChords) {
        val tracks = state.composition.tracks.filter { it.id in selectedTracks }
        state.composition.copy(
            tracks = tracks.ifEmpty { state.composition.tracks },
            activeTrackId = tracks.firstOrNull()?.id ?: state.composition.activeTrackId,
            chordProgression = if (includeChords) state.composition.chordProgression else emptyList(),
        )
    }
    val exportTitle = remember(state.projectName, selectedTracks, state.composition.tracks) {
        val tracks = state.composition.tracks.filter { it.id in selectedTracks }
        if (tracks.size == 1 && state.composition.tracks.size > 1) {
            "${state.projectName} - ${tracks.single().name}"
        } else {
            state.projectName
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format.mimeType),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = FileExport.export(
                context = context,
                uri = uri,
                composition = exported,
                format = format,
                title = exportTitle,
                midiOptions = MidiExportOptions(
                    includeMetronome = includeMetronome,
                    includeChordTrack = includeChords,
                    respectMute = respectMute,
                ),
                pdfOptions = PdfOptions(
                    includeNotation = pdfNotation,
                    includeTablature = pdfTab,
                    allTracks = pdfAllTracks,
                    title = exportTitle,
                ),
            )
            busy = false
            lastResult = when (result) {
                is ExportResult.Success ->
                    "Saved as ${format.displayName}" +
                        if (result.bytes > 0) " (${result.bytes / 1024 + 1} KB)" else ""
                is ExportResult.Failure -> result.message
            }
        }
    }

    if (state.projectId == null) {
        EmptyState("No project open", "Open a project to export it.")
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Export",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            com.tabtap.app.ui.help.HelpButton("export")
        }

        Panel(title = "Project") {
            Column {
                ReadoutRow("Name", state.projectName)
                ReadoutRow("Key", state.composition.key.displayName)
                ReadoutRow("Tempo", "${state.composition.bpm.toInt()} BPM")
                ReadoutRow("Metre", state.composition.timeSignature.toString())
                ReadoutRow("Bars", "${state.composition.barCount}")
                Spacer(Modifier.height(spacing.small))
                SectionLabel("Tracks")
                Spacer(Modifier.height(spacing.tiny))
                for (track in state.composition.tracks) {
                    Row(
                        Modifier.fillMaxWidth().height(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackDot(MusicColors.forSlot(track.colorSlot))
                        Spacer(Modifier.width(spacing.small))
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${track.notes.size} notes" + if (track.muted) " · muted" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                        )
                    }
                }
                if (state.composition.chordProgression.isNotEmpty()) {
                    ReadoutRow(
                        "Chord progression",
                        "${state.composition.chordProgression.size} chords",
                    )
                }
            }
        }

        Panel(title = "Format") {
            Column {
                for (option in ExportFormat.entries) {
                    Surface(
                        color = if (option == format) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { format = option },
                    ) {
                        Row(
                            Modifier.padding(spacing.small),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = option == format, onClick = { format = option })
                            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        option.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Spacer(Modifier.width(spacing.small))
                                    Text(
                                        ".${option.extension}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textTertiary,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HairlineDivider()
                }
            }
        }

        when (format) {
            ExportFormat.MIDI_TYPE_0, ExportFormat.MIDI_TYPE_1 -> Panel(title = "MIDI options") {
                Column {
                    SwitchRow("Include a click track", includeMetronome) { includeMetronome = it }
                    SwitchRow("Include the chord progression", includeChords) { includeChords = it }
                    SwitchRow("Leave out muted tracks", respectMute) { respectMute = it }
                    Spacer(Modifier.height(spacing.small))
                    Text(
                        if (format == ExportFormat.MIDI_TYPE_1) {
                            "Each part becomes its own track with its own instrument, so the " +
                                "file opens in a DAW ready to work on."
                        } else {
                            "Everything is merged onto one track, with the parts kept apart " +
                                "by MIDI channel."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
            ExportFormat.PDF -> Panel(title = "PDF options") {
                Column {
                    SwitchRow("Standard notation", pdfNotation) { pdfNotation = it }
                    SwitchRow("Tablature", pdfTab) { pdfTab = it }
                    SwitchRow("Every track", pdfAllTracks) { pdfAllTracks = it }
                }
            }
            ExportFormat.GUITAR_PRO_5 -> Panel(title = "About Guitar Pro") {
                Text(
                    "TabTap writes Guitar Pro 5.10, which Guitar Pro 6, 7 and 8 all open. " +
                        "The later versions use an undocumented container, so rather than " +
                        "produce a file with a misleading extension TabTap offers GP5 and " +
                        "MusicXML — and those versions import MusicXML with full notation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
            else -> Unit
        }

        Button(
            onClick = {
                lastResult = null
                createDocument.launch(FileExport.fileName(exportTitle, format))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (busy) "Exporting…" else "Export as ${format.displayName}")
        }

        lastResult?.let { message ->
            Panel {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            "Files are saved wherever you choose. TabTap asks for no storage permission " +
                "and works entirely offline.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(spacing.xLarge))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
