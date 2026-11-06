package com.tabtap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.export.FileExport
import com.tabtap.app.export.ImportResult
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.SubtleBusyIndicator
import com.tabtap.app.ui.help.HelpButton
import com.tabtap.app.ui.pages.HarmonyPage
import com.tabtap.app.ui.pages.NotesPage
import com.tabtap.app.ui.pages.Page
import com.tabtap.app.ui.pages.PlayPage
import com.tabtap.app.ui.pages.ScorePage
import com.tabtap.app.ui.pages.SetupPage
import com.tabtap.app.ui.pages.TabPage
import com.tabtap.app.ui.pages.TapRhythmPage
import com.tabtap.app.ui.panels.TransportBar
import kotlinx.coroutines.launch

/**
 * The application shell.
 *
 * Nine pages, one job each, reachable from a navigation strip. In landscape — which is
 * how this app is used — the strip runs down the left so the whole width and height go to
 * the page; in portrait it runs along the bottom. Either way it scrolls, so no page is
 * ever unreachable because the strip ran out of room.
 *
 * Only the page's own content and, where it is relevant, one slim transport row share the
 * screen. Nothing else is stacked, because on a phone in landscape there are only about
 * 360dp of height to go round and anything stacked pushes the rest off the screen.
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
    var page by remember { mutableStateOf(Page.PROJECTS) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val recent = model.projectsRepositoryMostRecentId()
        if (recent != null) {
            model.openProject(recent)
            page = Page.DEFAULT
        }
    }

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
                    page = Page.DEFAULT
                }
                is ImportResult.Failure -> snackbar.showSnackbar(result.message)
            }
            importing = false
        }
    }

    fun go(destination: Page) {
        page = if (destination.needsProject && state.projectId == null) Page.PROJECTS else destination
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Landscape is the primary orientation: wide and short. The rail goes down the
        // side there, because a bottom bar would eat height the fretboard needs.
        val landscape = maxWidth > maxHeight

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!landscape) {
                    PageStrip(
                        current = page,
                        enabled = { !it.needsProject || state.projectId != null },
                        vertical = false,
                        onSelect = ::go,
                    )
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (landscape) {
                    PageStrip(
                        current = page,
                        enabled = { !it.needsProject || state.projectId != null },
                        vertical = true,
                        onSelect = ::go,
                    )
                }

                Column(Modifier.fillMaxSize()) {
                    SubtleBusyIndicator(importing || state.isLoading, Modifier.fillMaxWidth())

                    // One slim header: what this page is, the transport, and its help.
                    if (page.showTransport && state.projectId != null) {
                        TransportBar(
                            isPlaying = state.isPlaying,
                            isPaused = state.playback.state ==
                                com.tabtap.app.audio.TransportState.PAUSED,
                            positionTick = state.playback.tick,
                            countInBeatsRemaining = state.playback.countInBeatsRemaining,
                            bpm = state.composition.bpm,
                            timeSignature = state.composition.timeSignature,
                            metronomeEnabled = state.metronomeEnabled,
                            looping = state.loop != null,
                            loopLabel = state.loopLabel,
                            canUndo = state.canUndo,
                            canRedo = state.canRedo,
                            saveState = state.saveState,
                            compact = true,
                            title = page.title,
                            help = { HelpButton(page.helpTopic, size = 36.dp) },
                            onPlay = model::play,
                            onPause = model::pause,
                            onStop = model::stop,
                            onRestart = model::restart,
                            onToggleMetronome = {
                                model.setMetronomeEnabled(!state.metronomeEnabled)
                            },
                            onToggleLoop = {
                                if (state.loop != null) model.setLoop(null) else model.loopWholeSection()
                            },
                            onChooseLoop = model::setLoopScope,
                            onUndo = model::undo,
                            onRedo = model::redo,
                            onTempoChange = model::setTempo,
                            onTapTempo = model::tapTempo,
                        )
                        HairlineDivider()
                    }

                    Surface(
                        Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when {
                            page.needsProject && state.projectId == null -> EmptyState(
                                "No project open",
                                "Open or create a project to start composing.",
                            )
                            page == Page.PROJECTS -> ProjectsScreen(
                                model = model,
                                projects = projects,
                                currentProjectId = state.projectId,
                                onOpen = { id ->
                                    model.openProject(id)
                                    page = Page.DEFAULT
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
                            page == Page.PLAY -> PlayPage(model, state)
                            page == Page.NOTES -> NotesPage(model, state)
                            page == Page.TAP -> TapRhythmPage(model, state)
                            page == Page.HARMONY -> HarmonyPage(model, state)
                            page == Page.CHORDS -> ChordsScreen(model, state)
                            page == Page.TAB -> TabPage(model, state)
                            page == Page.SCORE -> ScorePage(model, state)
                            page == Page.SETUP -> SetupPage(model, state)
                            page == Page.EXPORT -> ExportScreen(model, state)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The navigation strip.
 *
 * Deliberately not a `NavigationRail` or a `NavigationBar`: with nine destinations both
 * of those either squeeze the labels away or drop items off the end. This scrolls, so
 * every page is always reachable whatever the screen size — which was the bug that made
 * the controls under the fretboard unreachable in the first place.
 */
@Composable
private fun PageStrip(
    current: Page,
    enabled: (Page) -> Boolean,
    vertical: Boolean,
    onSelect: (Page) -> Unit,
) {
    val spacing = LocalSpacing.current
    val scroll = rememberScrollState()

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (vertical) {
            Column(
                Modifier
                    .width(72.dp)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .padding(vertical = spacing.tiny),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (destination in Page.entries) {
                    PageButton(destination, destination == current, enabled(destination)) {
                        onSelect(destination)
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = spacing.tiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (destination in Page.entries) {
                    PageButton(destination, destination == current, enabled(destination)) {
                        onSelect(destination)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageButton(
    page: Page,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalTabTapColors.current
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .width(68.dp)
            .padding(2.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(page.icon, page.title, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            page.shortTitle,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
