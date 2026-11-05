package com.tabtap.app.ui.pages

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.PlayAlongMode
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.panels.HarmonyPanel
import com.tabtap.app.ui.panels.MixerPanel

/**
 * The harmony page: generate a part that follows your line, then choose which parts you
 * hear so you can play the other one yourself.
 *
 * Harmonising and deciding what to listen to are the same task from the player's side,
 * so they share a page — but nothing else does, and the page scrolls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HarmonyPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val lead = state.leadTrack
    val noteCount = lead?.notes?.size ?: 0

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {

        // --- Harmonise ---
        Button(
            onClick = model::harmoniseLead,
            enabled = noteCount > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when {
                    noteCount == 0 -> "Play some notes first"
                    state.hasHarmony -> "Harmonise again"
                    else -> "Harmonise these $noteCount notes"
                },
            )
        }

        // --- Play along: what you hear ---
        if (state.hasHarmony || state.composition.chordProgression.isNotEmpty()) {
            Panel(title = "You hear") {
                Column {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                        verticalArrangement = Arrangement.spacedBy(spacing.tiny),
                    ) {
                        for (mode in PlayAlongMode.entries) {
                            TabTapChip(
                                text = mode.label,
                                selected = mode == state.playAlong,
                                accent = when (mode) {
                                    PlayAlongMode.I_PLAY_LEAD -> MusicColors.harmony[0]
                                    PlayAlongMode.I_PLAY_HARMONY -> MusicColors.original
                                    else -> null
                                },
                                onClick = { model.setPlayAlong(mode) },
                            )
                        }
                    }
                    Spacer(Modifier.height(spacing.tiny))
                    Text(
                        state.playAlong.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        // --- The harmony settings ---
        HarmonyPanel(
            composition = state.composition,
            key = state.composition.key,
            staged = state.stagedHarmonies,
            sourceTrackName = lead?.name ?: "Lead",
            sourceNoteCount = noteCount,
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
                model.setStagedHarmonies(state.stagedHarmonies.map { it.copy(followChords = follow) })
            },
            onSetOctaveShift = model::setStagedHarmonyOctave,
            onGenerate = model::generateHarmonies,
            onRegenerate = model::regenerateHarmonies,
        )

        // --- The mix ---
        MixerPanel(
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
            reverbMix = state.reverbMix,
            onSetReverbMix = model::setReverbMix,
        )

        Spacer(Modifier.height(spacing.xxLarge))
    }
}
