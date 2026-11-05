package com.tabtap.app.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.RhythmRecording
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.LabeledSlider
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.music.GridFeel
import com.tabtap.music.GridResolution
import com.tabtap.music.NoteValue
import com.tabtap.music.Quantize
import com.tabtap.music.TimeSignature
import kotlin.math.roundToInt

/**
 * The rhythm editor and metronome.
 *
 * It supports both orders of working: enter the notes and then give them a rhythm, or
 * tap the rhythm first and then fill in the notes. Both are first-class — the "even"
 * buttons lay the existing notes onto a grid, and the recorder captures taps against the
 * click and applies them to the notes already entered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RhythmPanel(
    timeSignature: TimeSignature,
    bpm: Double,
    quantize: Quantize,
    entryValue: NoteValue,
    metronomeLevel: Float,
    countInBars: Int,
    recording: RhythmRecording,
    noteCount: Int,
    modifier: Modifier = Modifier,
    onSetTimeSignature: (TimeSignature) -> Unit,
    onSetQuantize: (Quantize) -> Unit,
    onSetEntryValue: (NoteValue) -> Unit,
    onSetMetronomeLevel: (Float) -> Unit,
    onSetCountInBars: (Int) -> Unit,
    onTapTempo: () -> Unit,
    onStartMetronome: () -> Unit,
    onStartRecording: () -> Unit,
    onRecordTap: () -> Unit,
    onCommitRhythm: () -> Unit,
    onCommitAsPlayed: () -> Unit,
    onKeepAsTemplate: () -> Unit,
    rhythmSlotsRemaining: Int,
    onClearTemplate: () -> Unit,
    onCancelRecording: () -> Unit,
    onApplyEvenRhythm: (NoteValue) -> Unit,
    onQuantize: () -> Unit,
    onHumanize: () -> Unit,
    onLegato: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.medium)) {

        // --- Metronome ---
        Panel(title = "Metronome") {
            Column {
                SectionLabel("Time signature")
                Spacer(Modifier.height(spacing.small))
                ChipRow(
                    items = TimeSignature.PRESETS,
                    isSelected = { it == timeSignature },
                    onSelect = onSetTimeSignature,
                    label = { it.toString() },
                )
                Spacer(Modifier.height(spacing.medium))
                ReadoutRow(
                    "Felt in",
                    if (timeSignature.isCompound) {
                        "${timeSignature.pulsesPerBar} dotted-quarter pulses"
                    } else {
                        "${timeSignature.pulsesPerBar} beats"
                    },
                )
                Spacer(Modifier.height(spacing.small))
                LabeledSlider(
                    label = "Click level",
                    value = metronomeLevel,
                    onValueChange = onSetMetronomeLevel,
                    valueText = "${(metronomeLevel * 100).roundToInt()}%",
                )
                Spacer(Modifier.height(spacing.small))
                SectionLabel("Count-in")
                Spacer(Modifier.height(spacing.tiny))
                SegmentedControl(
                    options = listOf(0, 1, 2, 4),
                    selected = countInBars,
                    onSelect = onSetCountInBars,
                    label = { if (it == 0) "Off" else "$it bar${if (it > 1) "s" else ""}" },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Spacer(Modifier.height(spacing.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    OutlinedButton(onClick = onTapTempo, modifier = Modifier.weight(1f)) {
                        Text("Tap tempo")
                    }
                    OutlinedButton(onClick = onStartMetronome, modifier = Modifier.weight(1f)) {
                        Text("Click only")
                    }
                }
            }
        }

        // --- Recording ---
        Panel(title = "Record a rhythm") {
            Column {
                if (!recording.isRecording) {
                    Text(
                        "Tap the rhythm against the click, then apply it to the notes you " +
                            "have already entered — or tap the fretboard while it records to " +
                            "play the notes and their timing at once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(spacing.medium))
                    Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                        Text("Start recording")
                    }
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "${recording.tapCount}",
                            style = MonoType.bigReadout,
                            color = MusicColors.playhead,
                        )
                        Spacer(Modifier.width(spacing.small))
                        Text(
                            "taps recorded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(spacing.medium))
                    Button(
                        onClick = onRecordTap,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text("Tap", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(spacing.small))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Button(
                            onClick = onCommitRhythm,
                            enabled = recording.tapCount > 0 && noteCount > 0,
                            modifier = Modifier.weight(1f),
                        ) { Text("Apply to notes") }
                        OutlinedButton(onClick = onCommitAsPlayed, modifier = Modifier.weight(1f)) {
                            Text("Keep as played")
                        }
                    }
                    Spacer(Modifier.height(spacing.tiny))
                    // The "rhythm first" route: keep the taps and fill in pitches after.
                    OutlinedButton(
                        onClick = onKeepAsTemplate,
                        enabled = recording.tapCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Keep as a rhythm to fill in") }
                    Spacer(Modifier.height(spacing.tiny))
                    OutlinedButton(onClick = onCancelRecording, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }

                if (rhythmSlotsRemaining > 0) {
                    Spacer(Modifier.height(spacing.medium))
                    Text(
                        "A rhythm is waiting: tap $rhythmSlotsRemaining more note(s) on the " +
                            "fretboard and each one will take the next slot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusicColors.harmony[0],
                    )
                    Spacer(Modifier.height(spacing.small))
                    OutlinedButton(onClick = onClearTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text("Discard the waiting rhythm")
                    }
                }
            }
        }

        // --- Note values and grid ---
        Panel(title = "Note value") {
            Column {
                Text(
                    "New notes are entered at this length.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (value in NoteValue.PALETTE) {
                        TabTapChip(
                            text = valueLabel(value),
                            selected = value == entryValue,
                            onClick = { onSetEntryValue(value) },
                        )
                    }
                }
            }
        }

        Panel(title = "Grid and feel") {
            Column {
                SegmentedControl(
                    options = GridResolution.entries.toList(),
                    selected = quantize.resolution,
                    onSelect = { onSetQuantize(quantize.copy(resolution = it)) },
                    label = { it.displayName },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Spacer(Modifier.height(spacing.small))
                SegmentedControl(
                    options = GridFeel.entries.toList(),
                    selected = quantize.feel,
                    onSelect = { onSetQuantize(quantize.copy(feel = it)) },
                    label = {
                        when (it) {
                            GridFeel.STRAIGHT -> "Straight"
                            GridFeel.TRIPLET -> "Triplet"
                            GridFeel.SWING -> "Swing"
                            GridFeel.SHUFFLE -> "Shuffle"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                if (quantize.feel == GridFeel.SWING || quantize.feel == GridFeel.SHUFFLE) {
                    Spacer(Modifier.height(spacing.small))
                    LabeledSlider(
                        label = "Swing",
                        value = quantize.swingRatio.toFloat(),
                        onValueChange = { onSetQuantize(quantize.copy(swingRatio = it.toDouble())) },
                        valueRange = 0.5f..0.8f,
                        valueText = "${(quantize.swingRatio * 100).roundToInt()}%",
                    )
                }
                Spacer(Modifier.height(spacing.small))
                LabeledSlider(
                    label = "Quantise strength",
                    value = quantize.strength.toFloat(),
                    onValueChange = { onSetQuantize(quantize.copy(strength = it.toDouble())) },
                    valueText = "${(quantize.strength * 100).roundToInt()}%",
                )
                Spacer(Modifier.height(spacing.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    Button(
                        onClick = onQuantize,
                        enabled = noteCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Quantise") }
                    OutlinedButton(
                        onClick = onHumanize,
                        enabled = noteCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Humanise") }
                }
            }
        }

        Panel(title = "Assign a rhythm") {
            Column {
                Text(
                    "Lay the notes already entered onto an even grid, keeping their pitches " +
                        "and their order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (value in listOf(
                        NoteValue.QUARTER, NoteValue.EIGHTH, NoteValue.SIXTEENTH,
                        NoteValue.TRIPLET_EIGHTH, NoteValue.DOTTED_EIGHTH, NoteValue.HALF,
                    )) {
                        TabTapChip(
                            text = "All ${valueLabel(value)}",
                            onClick = { onApplyEvenRhythm(value) },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.small))
                OutlinedButton(
                    onClick = onLegato,
                    enabled = noteCount > 1,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove gaps (legato)") }
            }
        }
    }
}

/** Short note-value labels, as a musician would write them. */
fun valueLabel(value: NoteValue): String {
    val base = when (value.divisionLevel) {
        0 -> "1/1"; 1 -> "1/2"; 2 -> "1/4"; 3 -> "1/8"
        4 -> "1/16"; 5 -> "1/32"; else -> "1/64"
    }
    val dots = ".".repeat(value.dots)
    val tuplet = if (value.isTuplet) "³" else ""
    return "$base$dots$tuplet"
}
