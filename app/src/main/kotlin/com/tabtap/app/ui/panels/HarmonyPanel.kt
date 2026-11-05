package com.tabtap.app.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.music.ChromaticHandling
import com.tabtap.music.Composition
import com.tabtap.music.HarmonyMode
import com.tabtap.music.HarmonySpec
import com.tabtap.music.Key
import com.tabtap.music.TrackRole

/**
 * The harmony generator's controls.
 *
 * Intervals are offered as chips rather than a dropdown because choosing several at once
 * is the normal case — a third above and a sixth below is a standard twin-guitar
 * arrangement. The diatonic/parallel choice is exposed rather than hidden, because they
 * produce genuinely different music and a guitarist knows which they want.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HarmonyPanel(
    composition: Composition,
    key: Key,
    staged: List<HarmonySpec>,
    sourceTrackName: String,
    sourceNoteCount: Int,
    modifier: Modifier = Modifier,
    onToggleSpec: (HarmonySpec) -> Unit,
    onSetMode: (HarmonyMode) -> Unit,
    onSetChromaticHandling: (ChromaticHandling) -> Unit,
    onSetFollowChords: (Boolean) -> Unit,
    onSetOctaveShift: (Int) -> Unit,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val mode = staged.firstOrNull()?.mode ?: HarmonyMode.DIATONIC
    val chromatic = staged.firstOrNull()?.chromaticHandling ?: ChromaticHandling.SNAP_TO_SCALE
    val followChords = staged.firstOrNull()?.followChords ?: true
    val existing = composition.tracks.filter { it.role == TrackRole.HARMONY }

    Panel(modifier, title = "Harmony") {
        Column {
            ReadoutRow("Source", "$sourceTrackName ($sourceNoteCount notes)")
            ReadoutRow("Key", key.displayName)
            Spacer(Modifier.height(spacing.medium))

            SectionLabel("Interval")
            Spacer(Modifier.height(spacing.small))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                HarmonySpec.PRESETS.forEachIndexed { index, preset ->
                    val spec = preset.copy(
                        mode = if (preset.mode == HarmonyMode.CHROMATIC) preset.mode else mode,
                        chromaticHandling = chromatic,
                        followChords = followChords,
                    )
                    val selected = staged.any { sameVoice(it, spec) }
                    TabTapChip(
                        text = shortLabel(preset),
                        selected = selected,
                        accent = if (selected) {
                            MusicColors.harmony[index % MusicColors.harmony.size]
                        } else {
                            null
                        },
                        onClick = { onToggleSpec(spec) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Method")
            Spacer(Modifier.height(spacing.tiny))
            SegmentedControl(
                options = HarmonyMode.entries.toList(),
                selected = mode,
                onSelect = onSetMode,
                label = {
                    when (it) {
                        HarmonyMode.DIATONIC -> "Diatonic"
                        HarmonyMode.CHROMATIC -> "Parallel"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            Spacer(Modifier.height(spacing.tiny))
            Text(
                when (mode) {
                    HarmonyMode.DIATONIC ->
                        "Each note moves by scale steps inside ${key.shortName}, so thirds " +
                            "come out major or minor as the key requires."
                    HarmonyMode.CHROMATIC ->
                        "Every note moves by the same number of semitones, whatever the key."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Octave")
            Spacer(Modifier.height(spacing.tiny))
            SegmentedControl(
                options = listOf(-2, -1, 0, 1, 2),
                selected = staged.firstOrNull()?.octaveShift ?: 0,
                onSelect = onSetOctaveShift,
                label = {
                    when (it) {
                        0 -> "Same"
                        1 -> "+1"
                        2 -> "+2"
                        -1 -> "\u22121"
                        else -> "\u22122"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            Spacer(Modifier.height(spacing.tiny))
            Text(
                "Shifts the whole harmony line by octaves after the interval, which is how " +
                    "a part is moved into a register that suits the instrument.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )

            if (mode == HarmonyMode.DIATONIC) {
                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Notes outside the key")
                Spacer(Modifier.height(spacing.tiny))
                SegmentedControl(
                    options = ChromaticHandling.entries.toList(),
                    selected = chromatic,
                    onSelect = onSetChromaticHandling,
                    label = {
                        when (it) {
                            ChromaticHandling.SNAP_TO_SCALE -> "Stay in key"
                            ChromaticHandling.PARALLEL -> "Follow the line"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
            }

            if (composition.chordProgression.isNotEmpty()) {
                Spacer(Modifier.height(spacing.medium))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabTapChip(
                        text = "Follow the chords",
                        selected = followChords,
                        onClick = { onSetFollowChords(!followChords) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.large))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                Button(
                    onClick = onGenerate,
                    enabled = sourceNoteCount > 0 && staged.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (staged.size <= 1) {
                            "Generate harmony"
                        } else {
                            "Generate ${staged.size} voices"
                        },
                    )
                }
                if (existing.isNotEmpty()) {
                    OutlinedButton(onClick = onRegenerate) { Text("Update") }
                }
            }

            if (existing.isNotEmpty()) {
                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Generated")
                Spacer(Modifier.height(spacing.tiny))
                for (track in existing) {
                    ReadoutRow(
                        track.name,
                        "${track.notes.size} notes",
                        valueColor = MusicColors.forSlot(track.colorSlot),
                    )
                }
            }
        }
    }
}

private fun sameVoice(a: HarmonySpec, b: HarmonySpec): Boolean =
    a.mode == b.mode && a.diatonicSteps == b.diatonicSteps &&
        a.semitones == b.semitones && a.octaveShift == b.octaveShift

private fun shortLabel(spec: HarmonySpec): String = when {
    spec.mode == HarmonyMode.CHROMATIC && spec.octaveShift > 0 -> "8ve up"
    spec.mode == HarmonyMode.CHROMATIC && spec.octaveShift < 0 -> "8ve down"
    spec.diatonicSteps > 0 -> "${ordinal(spec.diatonicSteps + 1)} up"
    spec.diatonicSteps < 0 -> "${ordinal(-spec.diatonicSteps + 1)} down"
    else -> spec.displayName
}

private fun ordinal(n: Int): String = when (n) {
    2 -> "2nd"; 3 -> "3rd"; 4 -> "4th"; 5 -> "5th"; 6 -> "6th"; 7 -> "7th"; 8 -> "8ve"
    else -> "${n}th"
}
