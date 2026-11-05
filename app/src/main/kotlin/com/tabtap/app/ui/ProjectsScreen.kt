package com.tabtap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioViewModel
import com.tabtap.app.data.ProjectSummary
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.Key
import com.tabtap.music.ScaleType
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import java.text.DateFormat
import java.util.Date

/**
 * The project library.
 *
 * Projects are listed most recently opened first, each showing enough of its own settings
 * — instrument, tuning, key, tempo, size — that it can be recognised without opening it.
 */
@Composable
fun ProjectsScreen(
    model: StudioViewModel,
    projects: List<ProjectSummary>,
    currentProjectId: Long?,
    modifier: Modifier = Modifier,
    onOpen: (Long) -> Unit,
    onImport: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var showNewDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (projects.isEmpty()) {
                        "Nothing yet"
                    } else {
                        "${projects.size} project${if (projects.size > 1) "s" else ""}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onImport) { Text("Import") }
            Spacer(Modifier.width(spacing.small))
            Button(onClick = { showNewDialog = true }) { Text("New project") }
        }

        if (projects.isEmpty()) {
            EmptyState(
                "Start a project",
                "Choose an instrument and a tuning, tap a line onto the fretboard, then " +
                    "give it a rhythm and harmonise it.",
                action = {
                    Button(onClick = { showNewDialog = true }) { Text("New project") }
                },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = spacing.large,
                    vertical = spacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        isOpen = project.id == currentProjectId,
                        onOpen = { onOpen(project.id) },
                        onRename = { renaming = project },
                        onDuplicate = { model.duplicateProject(project.id) },
                        onDelete = { deleting = project },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name, family, tuning, bpm, key, timeSignature ->
                showNewDialog = false
                model.createProject(name, family, tuning, bpm, key, timeSignature) { id ->
                    onOpen(id)
                }
            },
        )
    }

    renaming?.let { project ->
        var text by remember(project.id) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.renameProject(project.id, text)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${project.name}\"?") },
            text = {
                Text(
                    "This removes the project and everything in it. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.deleteProject(project.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    Surface(
        color = if (isOpen) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(project.tuningName)
                        append(" · ")
                        append(project.keyName)
                        append(" · ")
                        append("${project.bpm.toInt()} BPM")
                        append(" · ")
                        append(project.timeSignature)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("${project.trackCount} track${if (project.trackCount > 1) "s" else ""}")
                        append(" · ")
                        append("${project.noteCount} notes")
                        append(" · ")
                        append("${project.barCount} bars")
                        append(" · ")
                        append(relativeTime(project.lastOpenedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconToggle(Icons.Default.DriveFileRenameOutline, "Rename", onClick = onRename)
            IconToggle(Icons.Default.ContentCopy, "Duplicate", onClick = onDuplicate)
            IconToggle(Icons.Default.Delete, "Delete", onClick = onDelete)
        }
    }
}

/**
 * The new-project dialog.
 *
 * Every choice has a working default, so a player who wants to start immediately can tap
 * Create and be on the fretboard with a standard-tuned six string in a second.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, InstrumentFamily, Tuning, Double, Key, TimeSignature) -> Unit,
) {
    val spacing = LocalSpacing.current
    var name by remember { mutableStateOf("") }
    var family by remember { mutableStateOf(InstrumentFamily.GUITAR) }
    var stringCount by remember { mutableStateOf(6) }
    var tuning by remember { mutableStateOf(Tunings.STANDARD_E) }
    var bpm by remember { mutableStateOf(120) }
    var tonic by remember { mutableStateOf("E") }
    var scale by remember { mutableStateOf(ScaleType.NATURAL_MINOR) }
    var timeSignature by remember { mutableStateOf(TimeSignature.FOUR_FOUR) }

    val presets = Tunings.presetsFor(family, stringCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Untitled") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    SectionLabel("Instrument")
                    Spacer(Modifier.height(spacing.tiny))
                    SegmentedControl(
                        options = InstrumentFamily.entries.toList(),
                        selected = family,
                        onSelect = {
                            family = it
                            stringCount = if (it == InstrumentFamily.BASS) 4 else 6
                            tuning = Tunings.defaultFor(it, stringCount)
                        },
                        label = { it.displayName },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column {
                    SectionLabel("Strings")
                    Spacer(Modifier.height(spacing.tiny))
                    val counts = if (family == InstrumentFamily.BASS) {
                        listOf(4, 5, 6)
                    } else {
                        listOf(6, 7, 8)
                    }
                    SegmentedControl(
                        options = counts,
                        selected = stringCount,
                        onSelect = {
                            stringCount = it
                            tuning = Tunings.defaultFor(family, it)
                        },
                        label = { "$it" },
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                    )
                }

                Column {
                    SectionLabel("Tuning")
                    Spacer(Modifier.height(spacing.tiny))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        verticalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        for (preset in presets) {
                            TabTapChip(
                                text = preset.displayName,
                                selected = preset.id == tuning.id,
                                onClick = { tuning = preset },
                            )
                        }
                    }
                }

                Column {
                    SectionLabel("Key")
                    Spacer(Modifier.height(spacing.tiny))
                    ChipRow(
                        items = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"),
                        isSelected = { it == tonic },
                        onSelect = { tonic = it },
                        label = { it },
                    )
                    Spacer(Modifier.height(spacing.small))
                    ChipRow(
                        items = listOf(
                            ScaleType.MAJOR, ScaleType.NATURAL_MINOR, ScaleType.MINOR_PENTATONIC,
                            ScaleType.MAJOR_PENTATONIC, ScaleType.BLUES, ScaleType.DORIAN,
                            ScaleType.MIXOLYDIAN, ScaleType.HARMONIC_MINOR,
                        ),
                        isSelected = { it == scale },
                        onSelect = { scale = it },
                        label = { it.displayName },
                    )
                }

                Column {
                    SectionLabel("Tempo and metre")
                    Spacer(Modifier.height(spacing.tiny))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconToggle(
                            Icons.Default.Delete, "Slower", size = 36.dp,
                        ) { bpm = (bpm - 5).coerceAtLeast(20) }
                        Text(
                            "$bpm BPM",
                            style = MonoType.readout,
                            modifier = Modifier.width(88.dp),
                        )
                        IconToggle(
                            Icons.Default.Add, "Faster", size = 36.dp,
                        ) { bpm = (bpm + 5).coerceAtMost(400) }
                        Spacer(Modifier.width(spacing.small))
                        ChipRow(
                            items = TimeSignature.PRESETS.take(6),
                            isSelected = { it == timeSignature },
                            onSelect = { timeSignature = it },
                            label = { it.toString() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { "Untitled" },
                        family,
                        tuning,
                        bpm.toDouble(),
                        Key.of(tonic, scale) ?: Key.C_MAJOR,
                        timeSignature,
                    )
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "3 minutes ago", "yesterday", or a date for anything older than a week. */
private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
