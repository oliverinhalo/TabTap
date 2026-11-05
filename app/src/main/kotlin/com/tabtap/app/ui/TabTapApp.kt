package com.tabtap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioPane
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.export.FileExport
import com.tabtap.app.export.ImportResult
import com.tabtap.app.ui.components.SubtleBusyIndicator
import kotlinx.coroutines.launch

/** The app's top-level destinations. */
enum class Destination(val title: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Default.FolderOpen),
    STUDIO("Studio", Icons.Default.MusicNote),
    CHORDS("Chords", Icons.Default.Piano),
    SCORE("Score", Icons.Default.LibraryMusic),
    EXPORT("Export", Icons.Default.IosShare),
}

/**
 * The application shell.
 *
 * Five destinations, always reachable: the project library, the studio where the
 * fretboard lives, the chord editor, the score, and export. On a narrow screen they are
 * a bottom bar; on a wide one — which is what a Galaxy S26 Ultra in landscape is — they
 * become a rail down the side, so the full width goes to the fretboard.
 *
 * Choosing Score selects the studio with its score pane showing, rather than opening a
 * separate read-only viewer, so the notation is editable in place.
 */
@Composable
fun TabTapApp(
    model: StudioViewModel,
    state: StudioUiState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by model.projects.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var destination by remember { mutableStateOf(Destination.PROJECTS) }
    var importing by remember { mutableStateOf(false) }

    // Open the most recent project at startup, so the app opens where it was left.
    LaunchedEffect(Unit) {
        val recent = model.projectsRepositoryMostRecentId()
        if (recent != null) {
            model.openProject(recent)
            destination = Destination.STUDIO
        }
    }

    // Surface the view model's messages.
    LaunchedEffect(Unit) {
        model.messages.collect { message ->
            snackbar.showSnackbar(
                message = message.text,
                duration = if (message.isError) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val tuning = state.activeTrack?.tuning ?: com.tabtap.music.Tunings.STANDARD_E
            when (val result = FileExport.import(context, uri, tuning)) {
                is ImportResult.Success -> {
                    model.openImported(result.suggestedName, result.composition, result.warnings)
                    destination = Destination.STUDIO
                }
                is ImportResult.Failure -> snackbar.showSnackbar(result.message)
            }
            importing = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        for (item in Destination.entries) {
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = {
                                    destination = item
                                    if (item == Destination.SCORE) model.setPane(StudioPane.SCORE)
                                },
                                icon = { Icon(item.icon, item.title) },
                                label = { Text(item.title) },
                                enabled = item == Destination.PROJECTS || state.projectId != null,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        for (item in Destination.entries) {
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = {
                                    destination = item
                                    if (item == Destination.SCORE) model.setPane(StudioPane.SCORE)
                                },
                                icon = { Icon(item.icon, item.title) },
                                label = { Text(item.title) },
                                enabled = item == Destination.PROJECTS || state.projectId != null,
                            )
                        }
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    SubtleBusyIndicator(importing || state.isLoading, Modifier.fillMaxWidth())
                    Surface(
                        Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when (destination) {
                            Destination.PROJECTS -> ProjectsScreen(
                                model = model,
                                projects = projects,
                                currentProjectId = state.projectId,
                                onOpen = { id ->
                                    model.openProject(id)
                                    destination = Destination.STUDIO
                                },
                                onImport = {
                                    openDocument.launch(
                                        arrayOf(
                                            "audio/midi", "audio/x-midi",
                                            "application/vnd.recordare.musicxml+xml",
                                            "application/xml", "text/xml", "*/*",
                                        ),
                                    )
                                },
                            )
                            Destination.STUDIO, Destination.SCORE -> {
                                if (state.projectId == null) {
                                    com.tabtap.app.ui.components.EmptyState(
                                        "No project open",
                                        "Open or create a project to start composing.",
                                    )
                                } else {
                                    StudioScreen(model, state)
                                }
                            }
                            Destination.CHORDS -> ChordsScreen(model, state)
                            Destination.EXPORT -> ExportScreen(model, state)
                        }
                    }
                }
            }
        }
    }
}
