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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
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
import com.tabtap.app.ui.pages.RollPage
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
    val pageState = remember { mutableStateOf(Page.PROJECTS) }
    var page by pageState
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

    val hasProject = state.projectId != null

    // Navigation reads whether a project is open through a state holder rather than
    // capturing it.
    //
    // `fun go(..)` captured the `state` of the composition that created it. The strip is
    // not recomposed on every state change, so it kept calling the first version, which
    // had been created before any project existed - and so every tap on a page sent the
    // player back to the project list with the project sitting open behind it.
    val projectIsOpen = rememberUpdatedState(hasProject)
    val go = remember {
        { destination: Page ->
            pageState.value = if (destination.needsProject && !projectIsOpen.value) {
                Page.PROJECTS
            } else {
                destination
            }
        }
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
                        hasProject = hasProject,
                        vertical = false,
                        onSelect = go,
                    )
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (landscape) {
                    PageStrip(
                        current = page,
                        hasProject = hasProject,
                        vertical = true,
                        onSelect = go,
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
                            page == Page.ROLL -> RollPage(model, state)
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
/** One destination's size in the strip, which the auto-scroll also works from. */
private val ITEM_WIDTH = 68.dp
private val ITEM_HEIGHT = 46.dp

@Composable
private fun PageStrip(
    current: Page,
    hasProject: Boolean,
    vertical: Boolean,
    onSelect: (Page) -> Unit,
) {
    val spacing = LocalSpacing.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // With eleven destinations the strip is longer than a phone's screen, so the last
    // few sit outside it. Scrolling the current one into view means a page you have just
    // opened - and its neighbours - are always visible, instead of the strip staying at
    // the top with the page you are on hidden off the end.
    val index = Page.entries.indexOf(current)
    LaunchedEffect(current, scroll.maxValue) {
        if (scroll.maxValue > 0) {
            val extent = with(density) { if (vertical) ITEM_HEIGHT.toPx() else ITEM_WIDTH.toPx() }
            val target = (index * extent - extent).toInt().coerceIn(0, scroll.maxValue)
            scroll.animateScrollTo(target)
        }
    }

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
                    PageButton(
                        destination,
                        destination == current,
                        !destination.needsProject || hasProject,
                    ) {
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
                    PageButton(
                        destination,
                        destination == current,
                        !destination.needsProject || hasProject,
                    ) {
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
    val isSelected = selected
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .width(ITEM_WIDTH)
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            // One node per destination, named the same way a screen reader would say it.
            // Picking a page out by its short label alone matched text elsewhere on the
            // page, which made navigation ambiguous both for tests and for TalkBack.
            .semantics(mergeDescendants = true) {
                contentDescription = "Go to ${page.title}"
                role = Role.Tab
                this.selected = isSelected
            }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(page.icon, page.title, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(1.dp))
        Text(
            page.step?.let { "$it. ${page.shortTitle}" } ?: page.shortTitle,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
