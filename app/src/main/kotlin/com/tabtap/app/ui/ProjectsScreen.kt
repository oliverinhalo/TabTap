package com.tabtap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import com.tabtap.app.CrashReporter
import androidx.compose.ui.platform.LocalContext
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
    var showNewProject by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    // The new-project form takes over the whole screen rather than opening a dialog.
    //
    // Two reasons. A dialog on a 412dp-tall landscape phone leaves a form of eight
    // sections about 200dp to live in, which is unusable. And a text field inside a
    // Compose dialog window re-measures without ever settling: the dialog sizes itself
    // to its content while the field re-reports its layout, and the two never agree, so
    // the app re-laid out forever and ran itself out of memory. Keeping every text
    // field in the main window is what fixes that, not a cleverer dialog.
    if (showNewProject) {
        NewProjectForm(
            onDismiss = { showNewProject = false },
            onCreate = { name, family, tuning, bpm, key, timeSignature ->
                showNewProject = false
                model.createProject(name, family, tuning, bpm, key, timeSignature) { id ->
                    onOpen(id)
                }
            },
        )
        return
    }

    Box(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        CrashBanner()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineMedium)
                Text(
                    buildString {
                        if (projects.isEmpty()) {
                            append("Nothing yet")
                        } else {
                            append("${projects.size} project${if (projects.size > 1) "s" else ""}")
                        }
                        // The version is shown so it is obvious which build is installed.
                        append("  \u00B7  TabTap ")
                        append(com.tabtap.app.BuildConfig.VERSION_NAME)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.tabtap.app.ui.help.HelpButton("start")
            OutlinedButton(onClick = onImport) { Text("Import") }
            Spacer(Modifier.width(spacing.small))
            Button(onClick = { showNewProject = true }) { Text("New project") }
        }

        if (projects.isEmpty()) {
            EmptyState(
                "Start a project",
                "Choose an instrument and a tuning, tap a line onto the fretboard, then " +
                    "give it a rhythm and harmonise it.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Button(onClick = { showNewProject = true }) { Text("New project") }
                        OutlinedButton(onClick = { showHelp = true }) { Text("How it works") }
                    }
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
    }

    if (showHelp) {
        com.tabtap.app.ui.help.HelpDialog(
            startTopicId = com.tabtap.app.ui.help.Help.gettingStarted.id,
            onDismiss = { showHelp = false },
        )
    }

    renaming?.let { project ->
        var text by remember(project.id) { mutableStateOf(project.name) }
        // In the main window, for the same reason as the new-project form: a text field
        // in a dialog window never settles its layout.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { renaming = null },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 420.dp).padding(spacing.large),
            ) {
                Column(Modifier.padding(spacing.large)) {
                    Text("Rename project", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(spacing.medium))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(spacing.medium))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { renaming = null }) { Text("Cancel") }
                        Spacer(Modifier.width(spacing.small))
                        Button(onClick = {
                            model.renameProject(project.id, text)
                            renaming = null
                        }) { Text("Rename") }
                    }
                }
            }
        }
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
private fun NewProjectForm(
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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize()) {
        // The header carries the actions. In landscape the form scrolls, and buttons at
        // the bottom of a scrolling form are the ones players never find.
        Row(
            Modifier.fillMaxWidth().padding(spacing.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "New project",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(spacing.small))
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
        }

        Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.large)
                    .padding(bottom = spacing.large),
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
      }
    }
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

/**
 * Shown once, after a crash.
 *
 * "It still crashes" cannot be fixed; a stack trace can. The report is written to the
 * app's own storage by [CrashReporter] - nothing is sent anywhere, the app has no network
 * permission at all - and this offers to share it so it can actually be read. Dismissing
 * deletes it.
 */
@Composable
private fun CrashBanner() {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var report by remember { mutableStateOf(CrashReporter.lastReport(context)) }
    val text = report ?: return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(spacing.small),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(spacing.medium)) {
            Text(
                "TabTap stopped unexpectedly last time",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(spacing.tiny))
            Text(
                CrashReporter.summarise(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(spacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                Button(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "TabTap crash report")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text)
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, "Send the crash report"),
                        )
                    }
                }) { Text("Send the report") }
                TextButton(onClick = {
                    CrashReporter.clear(context)
                    report = null
                }) { Text("Dismiss") }
            }
        }
    }
}
