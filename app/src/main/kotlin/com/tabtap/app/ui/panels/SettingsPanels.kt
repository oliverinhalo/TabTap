package com.tabtap.app.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.FretboardDisplay
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.LabeledSlider
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.components.TrackDot
import com.tabtap.music.Composition
import com.tabtap.music.CompositionAnalyzer
import com.tabtap.music.Fretboard
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.Track
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import kotlin.math.roundToInt

/**
 * The key and scale controls.
 *
 * Changing the key here never moves a note: it changes what the fretboard highlights and
 * how the music is described. Transposing is a separate, explicit button, because
 * silently rewriting someone's solo when they correct the key would be destructive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyScalePanel(
    key: Key,
    track: Track?,
    modifier: Modifier = Modifier,
    onSetKey: (Key) -> Unit,
    onTransposeToKey: (Key) -> Unit,
    onSuggestKey: () -> Unit,
    onSetCustomScale: (Set<Int>) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val tonics = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    val currentTonic = "${key.tonic.letter}${key.tonic.accidentalText}"

    Panel(modifier, title = "Key and scale") {
        Column {
            SectionLabel("Root")
            Spacer(Modifier.height(spacing.small))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                for (tonic in tonics) {
                    TabTapChip(
                        text = tonic,
                        selected = tonic == currentTonic,
                        accent = if (tonic == currentTonic) MusicColors.rootNote else null,
                        onClick = { Key.of(tonic, key.scale)?.let(onSetKey) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Scale")
            Spacer(Modifier.height(spacing.small))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                for (scale in ScaleType.BUILT_IN) {
                    TabTapChip(
                        text = scale.displayName,
                        selected = scale == key.scale,
                        onClick = { onSetKey(key.copy(scale = scale)) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Custom scale")
            Spacer(Modifier.height(spacing.tiny))
            Text(
                "Switch any degree on or off to build your own scale. It still spells and " +
                    "engraves correctly, and harmony generation moves by its steps.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(spacing.small))
            CustomScaleBuilder(
                selected = key.scale.semitones.toSet(),
                onChange = onSetCustomScale,
            )

            Spacer(Modifier.height(spacing.medium))
            ReadoutRow(
                "Notes",
                key.spelledDegrees.joinToString(" ") { "${it.letter}${it.accidentalText}" },
                mono = true,
            )
            ReadoutRow(
                "Key signature",
                when {
                    key.sharpsInSignature > 0 -> "${key.sharpsInSignature} sharp(s)"
                    key.sharpsInSignature < 0 -> "${-key.sharpsInSignature} flat(s)"
                    else -> "none"
                },
            )
            if (track != null && track.notes.isNotEmpty()) {
                val outside = CompositionAnalyzer.outOfKeyCount(track, key)
                ReadoutRow(
                    "Outside the key",
                    if (outside == 0) "none" else "$outside of ${track.notes.size} notes",
                    valueColor = if (outside == 0) null else MusicColors.unplayable,
                )
            }

            Spacer(Modifier.height(spacing.medium))
            Text(
                "Changing the key changes the highlighting and the notation, not the notes.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(spacing.small))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                OutlinedButton(
                    onClick = onSuggestKey,
                    enabled = track?.notes?.isNotEmpty() == true,
                    modifier = Modifier.weight(1f),
                ) { Text("Detect key") }
                OutlinedButton(
                    onClick = { onTransposeToKey(key) },
                    enabled = track?.notes?.isNotEmpty() == true,
                    modifier = Modifier.weight(1f),
                ) { Text("Transpose here") }
            }
        }
    }
}

/**
 * The instrument panel: family, string count, tuning preset and per-string tuning.
 *
 * Switching between guitar and bass, or retuning a string, keeps every pitch and only
 * recomputes the fingerings, so the project survives the change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentPanel(
    track: Track,
    modifier: Modifier = Modifier,
    onSetFamily: (InstrumentFamily) -> Unit,
    onSetStringCount: (Int) -> Unit,
    onSetTuning: (Tuning) -> Unit,
    onSetStringPitch: (Int, Int) -> Unit,
    onSetFretCount: (Int) -> Unit,
    onSetVoice: (InstrumentVoice) -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val family = track.tuning.family
    val presets = Tunings.presetsFor(family, track.tuning.stringCount)

    Panel(modifier, title = "Instrument") {
        Column {
            SegmentedControl(
                options = InstrumentFamily.entries.toList(),
                selected = family,
                onSelect = onSetFamily,
                label = { it.displayName },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Strings")
            Spacer(Modifier.height(spacing.small))
            val counts = if (family == InstrumentFamily.BASS) listOf(4, 5, 6) else listOf(6, 7, 8)
            SegmentedControl(
                options = counts + listOf(track.tuning.stringCount).filterNot { it in counts },
                selected = track.tuning.stringCount,
                onSelect = onSetStringCount,
                label = { "$it" },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Tuning")
            Spacer(Modifier.height(spacing.small))
            if (presets.isEmpty()) {
                Text(
                    "No preset matches ${track.tuning.stringCount} strings; tune each string below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (preset in presets) {
                        TabTapChip(
                            text = preset.displayName,
                            selected = preset.openStrings == track.tuning.openStrings,
                            onClick = { onSetTuning(preset) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Each string")
            Spacer(Modifier.height(spacing.tiny))
            // Highest string first, as a player thinks of them.
            for (index in track.tuning.stringCount - 1 downTo 0) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${track.tuning.stringCount - index}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        Pitch.fullName(track.tuning.openStrings[index]),
                        style = com.tabtap.app.design.MonoType.readout,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(56.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconToggle(
                        Icons.Default.VolumeOff, "Lower string ${index + 1}",
                        size = 36.dp,
                        onClick = { onSetStringPitch(index, track.tuning.openStrings[index] - 1) },
                    )
                    IconToggle(
                        Icons.Default.VolumeUp, "Raise string ${index + 1}",
                        size = 36.dp,
                        onClick = { onSetStringPitch(index, track.tuning.openStrings[index] + 1) },
                    )
                }
            }

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Frets")
            Spacer(Modifier.height(spacing.small))
            ChipRow(
                items = listOf(12, 15, 18, 20, 21, 22, 24, 27, 30),
                isSelected = { it == track.fretCount },
                onSelect = onSetFretCount,
                label = { "$it" },
            )

            Spacer(Modifier.height(spacing.medium))
            SectionLabel("Sound")
            Spacer(Modifier.height(spacing.small))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                for (voice in InstrumentVoice.forFamily(family)) {
                    TabTapChip(
                        text = voice.displayName,
                        selected = voice == track.voice,
                        onClick = { onSetVoice(voice) },
                    )
                }
            }

            val unplayable = track.unplayableNotes()
            if (unplayable.isNotEmpty()) {
                Spacer(Modifier.height(spacing.medium))
                Text(
                    "${unplayable.size} note(s) cannot be played on this tuning. Their " +
                        "pitches are kept, and they will sound; move them an octave or " +
                        "choose a wider tuning to fret them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MusicColors.unplayable,
                )
            }
        }
    }
}

/** What the fretboard shows. */
@Composable
fun DisplayPanel(
    display: FretboardDisplay,
    modifier: Modifier = Modifier,
    onChange: (FretboardDisplay) -> Unit,
) {
    val spacing = LocalSpacing.current
    Panel(modifier, title = "Fretboard display") {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            ToggleRow("Note names", display.showNoteNames) {
                onChange(
                    display.copy(
                        showNoteNames = it,
                        showIntervals = if (it) false else display.showIntervals,
                        showScaleDegrees = if (it) false else display.showScaleDegrees,
                    ),
                )
            }
            ToggleRow("Intervals from the root", display.showIntervals) {
                onChange(
                    display.copy(
                        showIntervals = it,
                        showNoteNames = if (it) false else display.showNoteNames,
                        showScaleDegrees = if (it) false else display.showScaleDegrees,
                    ),
                )
            }
            ToggleRow("Scale degrees", display.showScaleDegrees) {
                onChange(
                    display.copy(
                        showScaleDegrees = it,
                        showNoteNames = if (it) false else display.showNoteNames,
                        showIntervals = if (it) false else display.showIntervals,
                    ),
                )
            }
            ToggleRow("Octave numbers", display.showOctaves) {
                onChange(display.copy(showOctaves = it))
            }
            ToggleRow("Highlight the scale", display.showScale) {
                onChange(display.copy(showScale = it))
            }
            ToggleRow("Only the scale's notes", display.showOnlyScaleNotes) {
                onChange(display.copy(showOnlyScaleNotes = it))
            }
            ToggleRow("Recorded notes", display.showRecordedNotes) {
                onChange(display.copy(showRecordedNotes = it))
            }
            ToggleRow("Fret numbers", display.showFretNumbers) {
                onChange(display.copy(showFretNumbers = it))
            }
            ToggleRow("Inlay markers", display.showInlays) {
                onChange(display.copy(showInlays = it))
            }
            ToggleRow("Left-handed", display.leftHanded) {
                onChange(display.copy(leftHanded = it))
            }
            Spacer(Modifier.height(spacing.tiny))
            LabeledSlider(
                label = "Visible frets",
                value = display.visibleFretCount.toFloat(),
                onValueChange = { onChange(display.copy(visibleFretCount = it.roundToInt())) },
                valueRange = 5f..Fretboard.MAX_FRETS.toFloat(),
                valueText = "${display.visibleFretCount}",
            )
        }
    }
}

/**
 * The custom-scale builder: the twelve chromatic degrees as toggles.
 *
 * The root is always on, because a scale without its own tonic is not a scale and
 * allowing it off would make every degree label meaningless.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomScaleBuilder(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val spacing = LocalSpacing.current
    val labels = listOf("1", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
        verticalArrangement = Arrangement.spacedBy(spacing.tiny),
    ) {
        for (semitone in 0..11) {
            val isOn = semitone in selected
            TabTapChip(
                text = labels[semitone],
                selected = isOn,
                enabled = semitone != 0,
                accent = if (semitone == 0) MusicColors.rootNote else null,
                onClick = {
                    val next = if (isOn) selected - semitone else selected + semitone
                    onChange(next + 0)
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The mixer: one row per track with mute, solo, volume and pan.
 *
 * This is how the required workflow of "play the original and the harmony together, then
 * mute the original and hear the harmony alone" is done, so it is always one tap away.
 */
@Composable
fun MixerPanel(
    composition: Composition,
    activeTrackId: Long?,
    modifier: Modifier = Modifier,
    onSelectTrack: (Long) -> Unit,
    onSetVolume: (Long, Float) -> Unit,
    onSetPan: (Long, Float) -> Unit,
    onToggleMute: (Long, Boolean) -> Unit,
    onToggleSolo: (Long, Boolean) -> Unit,
    onSetVoice: (Long, InstrumentVoice) -> Unit,
    onRemoveTrack: (Long) -> Unit,
    onAddTrack: () -> Unit,
    reverbMix: Float = 0.16f,
    onSetReverbMix: (Float) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val soloed = composition.tracks.any { it.soloed }

    Panel(modifier, title = "Mixer") {
        Column {
            for (track in composition.tracks) {
                val color = MusicColors.forSlot(track.colorSlot)
                val silenced = track.muted || (soloed && !track.soloed)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.tiny),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackDot(color)
                        Spacer(Modifier.width(spacing.small))
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { onSelectTrack(track.id) },
                        ) {
                            Text(
                                track.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (track.id == activeTrackId) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${track.voice.displayName} · ${track.notes.size} notes",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconToggle(
                            if (track.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            if (track.muted) "Unmute ${track.name}" else "Mute ${track.name}",
                            checked = track.muted,
                            accent = MusicColors.unplayable,
                            size = 40.dp,
                            onClick = { onToggleMute(track.id, !track.muted) },
                        )
                        IconToggle(
                            Icons.Default.GraphicEq, "Solo ${track.name}",
                            checked = track.soloed,
                            accent = MusicColors.harmony[0],
                            size = 40.dp,
                            onClick = { onToggleSolo(track.id, !track.soloed) },
                        )
                        if (composition.tracks.size > 1) {
                            IconToggle(
                                Icons.Default.Delete, "Remove ${track.name}",
                                size = 40.dp,
                                onClick = { onRemoveTrack(track.id) },
                            )
                        }
                    }
                    LabeledSlider(
                        label = if (silenced) "Volume (silenced)" else "Volume",
                        value = track.volume,
                        onValueChange = { onSetVolume(track.id, it) },
                        valueRange = 0f..1.2f,
                        valueText = "${(track.volume * 100).roundToInt()}%",
                        accent = color,
                        enabled = !silenced,
                    )
                    LabeledSlider(
                        label = "Pan",
                        value = track.pan,
                        onValueChange = { onSetPan(track.id, it) },
                        valueRange = -1f..1f,
                        valueText = panText(track.pan),
                        accent = color,
                    )
                    Spacer(Modifier.height(spacing.tiny))
                    ChipRow(
                        items = InstrumentVoice.forFamily(track.tuning.family),
                        isSelected = { it == track.voice },
                        onSelect = { onSetVoice(track.id, it) },
                        label = { it.displayName },
                    )
                    Spacer(Modifier.height(spacing.small))
                    com.tabtap.app.ui.components.HairlineDivider()
                }
            }
            Spacer(Modifier.height(spacing.small))
            // The room on the output. A completely dry guitar sounds thin however good
            // the string model is, so there is a little by default.
            LabeledSlider(
                label = "Room",
                value = reverbMix,
                onValueChange = onSetReverbMix,
                valueRange = 0f..0.5f,
                valueText = if (reverbMix < 0.01f) "dry" else "${(reverbMix * 200).roundToInt()}%",
            )
            Spacer(Modifier.height(spacing.small))
            OutlinedButton(onClick = onAddTrack, modifier = Modifier.fillMaxWidth()) {
                Text("Add a track")
            }
        }
    }
}

private fun panText(pan: Float): String = when {
    pan < -0.02f -> "L${(-pan * 100).roundToInt()}"
    pan > 0.02f -> "R${(pan * 100).roundToInt()}"
    else -> "centre"
}
