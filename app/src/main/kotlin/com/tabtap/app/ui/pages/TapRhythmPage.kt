package com.tabtap.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.music.GridFeel
import com.tabtap.music.GridResolution
import com.tabtap.music.Pitch
import com.tabtap.music.TimeSignature

/**
 * Tapping the rhythm out.
 *
 * You have already said which notes and in which order. Here the metronome runs, and
 * each tap of the pad plays the next note and records when you tapped it — so you hear
 * the music taking shape as you tap, rather than tapping blind against a click. Stop,
 * and the taps are quantised onto the grid and applied to the notes.
 *
 * The pad is the whole page on purpose. It is the only thing you touch while the
 * metronome is running, and it has to be hittable without looking.
 */
@Composable
fun TapRhythmPage(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val track = state.activeTrack
    val stacks = remember(track) {
        track?.notes?.groupBy { it.startTick }?.toSortedMap()?.values?.toList() ?: emptyList()
    }

    if (track == null || stacks.isEmpty()) {
        EmptyState(
            "Nothing to tap yet",
            "Go to Play and tap the notes you want, in the order you want them. Come back " +
                "here and the metronome will run while you tap out their rhythm.",
            modifier = modifier,
        )
        return
    }

    val recording = state.recording
    val cursor = recording.noteCursor
    val nextStack = stacks.getOrNull(cursor)
    val remaining = (stacks.size - cursor).coerceAtLeast(0)

    Row(modifier.fillMaxSize().padding(spacing.small)) {

        // --- The pad ---
        Column(Modifier.weight(1.6f).fillMaxHeight()) {
            if (!recording.isRecording) {
                Button(
                    onClick = model::startRhythmRecording,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Start tapping",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(spacing.tiny))
                        Text(
                            "${stacks.size} notes to tap out",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                // The pad. No ripple and no indication: it has to respond the instant it
                // is touched, and an animation would read as latency.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (remaining > 0) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            RoundedCornerShape(16.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = model::recordRhythmTap,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.playback.countInBeatsRemaining > 0) {
                            Text(
                                "${state.playback.countInBeatsRemaining}",
                                style = MonoType.bigReadout,
                                color = MusicColors.harmony[0],
                            )
                            Text(
                                "get ready",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                            )
                        } else {
                            Text(
                                nextStack?.let { group ->
                                    group.joinToString(" ") {
                                        Pitch.noteName(
                                            it.pitch,
                                            preferFlats = state.composition.key.sharpsInSignature < 0,
                                        )
                                    }
                                } ?: "done",
                                style = MonoType.bigReadout,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(spacing.tiny))
                            Text(
                                if (remaining > 0) {
                                    "tap for this note · $remaining to go"
                                } else {
                                    "every note tapped — press Apply"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(spacing.small))

        // --- The settings and the way out ---
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            SectionLabel("Snap to")
            SegmentedControl(
                options = GridResolution.entries.toList(),
                selected = state.quantize.resolution,
                onSelect = { model.setQuantize(state.quantize.copy(resolution = it)) },
                label = { it.displayName },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            SegmentedControl(
                options = listOf(GridFeel.STRAIGHT, GridFeel.TRIPLET, GridFeel.SWING),
                selected = state.quantize.feel,
                onSelect = { model.setQuantize(state.quantize.copy(feel = it)) },
                label = {
                    when (it) {
                        GridFeel.STRAIGHT -> "Straight"
                        GridFeel.TRIPLET -> "Triplet"
                        else -> "Swing"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )

            SectionLabel("Count-in")
            SegmentedControl(
                options = listOf(0, 1, 2),
                selected = state.countInBars,
                onSelect = model::setCountInBars,
                label = { if (it == 0) "None" else "$it bar${if (it > 1) "s" else ""}" },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )

            Spacer(Modifier.weight(1f))

            if (recording.isRecording) {
                Text(
                    "${recording.tapCount} taps",
                    style = MonoType.readout,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = model::commitRhythmToNotes,
                    enabled = recording.tapCount > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Apply the rhythm") }
                OutlinedButton(
                    onClick = model::cancelRhythmRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            } else {
                Text(
                    "The metronome will run. Tap the pad once for each note, in time. " +
                        "Each tap plays its note so you can hear the rhythm as you build it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}
