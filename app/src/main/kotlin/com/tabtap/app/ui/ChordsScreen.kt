package com.tabtap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabtap.app.StudioUiState
import com.tabtap.app.StudioViewModel
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.ChipRow
import com.tabtap.app.ui.components.EmptyState
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.Panel
import com.tabtap.app.ui.components.ReadoutRow
import com.tabtap.app.ui.components.SectionLabel
import com.tabtap.app.ui.components.SegmentedControl
import com.tabtap.app.ui.components.TabTapChip
import com.tabtap.app.ui.panels.valueLabel
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.ChordVoicing
import com.tabtap.music.DiatonicChords
import com.tabtap.music.Fretboard
import com.tabtap.music.NoteValue
import com.tabtap.music.StrumPattern
import kotlin.math.max

/**
 * The chord editor.
 *
 * A chord is built by choosing a root and a type, not by placing individual notes. The
 * voicings are searched on the project's actual tuning rather than looked up in a table
 * of six-string shapes, so this works on a 7-string, a bass or any custom tuning.
 *
 * The diatonic suggestions are the chords the current key generates, with their Roman
 * numerals, which is the quickest route to a progression that works.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordsScreen(
    model: StudioViewModel,
    state: StudioUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current
    val editor = state.chordEditor
    val chord = editor.chord
    val voicings = state.chordVoicings
    val roots = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chords",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            com.tabtap.app.ui.help.HelpButton("chords")
        }

        // --- Build a chord ---
        Panel(title = "Chord") {
            Column {
                SectionLabel("Root")
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (root in roots) {
                        TabTapChip(
                            text = root,
                            selected = root == editor.rootName,
                            onClick = { model.setChordEditor(editor.copy(rootName = root)) },
                        )
                    }
                }

                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Type")
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (type in ChordType.BUILT_IN) {
                        TabTapChip(
                            text = type.displayName,
                            selected = type == editor.type,
                            onClick = { model.setChordEditor(editor.copy(type = type)) },
                        )
                    }
                }

                if (chord != null) {
                    Spacer(Modifier.height(spacing.medium))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chord.symbol,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MusicColors.chord,
                        )
                        Spacer(Modifier.width(spacing.medium))
                        Column {
                            Text(
                                chord.toneNames,
                                style = MonoType.tab,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                chord.type.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconToggle(Icons.Default.PlayArrow, "Hear it") {
                            model.previewChordEditor()
                        }
                    }

                    Spacer(Modifier.height(spacing.small))
                    SectionLabel("Inversion")
                    Spacer(Modifier.height(spacing.tiny))
                    SegmentedControl(
                        options = (0 until chord.type.size).toList(),
                        selected = editor.inversion,
                        onSelect = { model.setChordEditor(editor.copy(inversion = it)) },
                        label = {
                            when (it) {
                                0 -> "Root"
                                1 -> "1st"
                                2 -> "2nd"
                                3 -> "3rd"
                                else -> "${it}th"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                    )
                }
            }
        }

        // --- Custom chord ---
        Panel(title = "Custom chord") {
            Column {
                Text(
                    "Switch intervals on or off to build a chord type the list does not " +
                        "cover. Voicings are searched for it exactly as for a built-in one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(spacing.small))
                val intervalLabels = listOf(
                    "b2" to 1, "2" to 2, "b3" to 3, "3" to 4, "4" to 5, "b5" to 6,
                    "5" to 7, "#5" to 8, "6" to 9, "b7" to 10, "7" to 11,
                    "9" to 14, "11" to 17, "13" to 21,
                )
                val current = editor.type.semitones.toSet()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for ((label, semitone) in intervalLabels) {
                        val isOn = semitone in current
                        TabTapChip(
                            text = label,
                            selected = isOn,
                            onClick = {
                                val next = if (isOn) current - semitone else current + semitone
                                if (next.size >= 2) {
                                    model.setChordEditor(
                                        editor.copy(
                                            type = ChordType.custom(
                                                "Custom",
                                                next.filter { it != 0 }.sorted(),
                                            ),
                                            voicingIndex = 0,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // --- Voicings as diagrams ---
        if (chord != null) {
            Panel(title = "Voicings on ${state.activeTrack?.tuning?.displayName ?: "this tuning"}") {
                if (voicings.isEmpty()) {
                    Text(
                        "This chord cannot be played on the current tuning and fret count.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusicColors.unplayable,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                        items(voicings.size) { index ->
                            val voicing = voicings[index]
                            ChordDiagram(
                                voicing = voicing,
                                fretboard = state.fretboard,
                                selected = index == editor.voicingIndex,
                                onClick = {
                                    model.setChordEditor(editor.copy(voicingIndex = index))
                                },
                            )
                        }
                    }
                }
            }
        }

        // --- How it is played ---
        Panel(title = "Playing") {
            Column {
                SectionLabel("Strum")
                Spacer(Modifier.height(spacing.tiny))
                ChipRow(
                    items = StrumPattern.entries.toList(),
                    isSelected = { it == editor.strum },
                    onSelect = { model.setChordEditor(editor.copy(strum = it)) },
                    label = { it.displayName },
                )
                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Length")
                Spacer(Modifier.height(spacing.tiny))
                val lengths = remember(state.composition.timeSignature) {
                    CompositionEditor.chordLengthOptions(state.composition.timeSignature)
                }
                val currentLength = editor.durationTicks.takeIf { it > 0 }
                    ?: state.composition.timeSignature.barTicks
                ChipRow(
                    items = lengths,
                    isSelected = { it.ticks == currentLength },
                    onSelect = { model.setChordEditor(editor.copy(durationTicks = it.ticks)) },
                    label = { it.label },
                )
                Spacer(Modifier.height(spacing.tiny))
                Text(
                    "Measured in bars and beats of ${state.composition.timeSignature}, so a " +
                        "chord lasts what it says whatever the metre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )

                Spacer(Modifier.height(spacing.medium))
                if (editor.isEditing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Button(
                            onClick = model::addChordToProgression,
                            enabled = chord != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Update ${chord?.symbol ?: "chord"}") }
                        OutlinedButton(onClick = model::cancelEditChord) { Text("Cancel") }
                    }
                } else {
                    Button(
                        onClick = model::addChordToProgression,
                        enabled = chord != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add ${chord?.symbol ?: "chord"} to the progression") }
                }
            }
        }

        // --- Diatonic suggestions ---
        Panel(title = "Chords in ${state.composition.key.shortName}") {
            Column {
                val triads = remember(state.composition.key) {
                    DiatonicChords.triads(state.composition.key)
                }
                val numerals = remember(state.composition.key) {
                    DiatonicChords.romanNumerals(state.composition.key)
                }
                val sevenths = remember(state.composition.key) {
                    DiatonicChords.sevenths(state.composition.key)
                }
                SectionLabel("Triads")
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    triads.forEachIndexed { index, c ->
                        TabTapChip(
                            text = "${numerals.getOrNull(index) ?: ""}  ${c.symbol}",
                            onClick = { model.addChord(c) },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Sevenths")
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for (c in sevenths) {
                        TabTapChip(text = c.symbol, onClick = { model.addChord(c) })
                    }
                }
                Spacer(Modifier.height(spacing.medium))
                SectionLabel("Common progressions")
                Spacer(Modifier.height(spacing.small))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    for ((label, degrees) in DiatonicChords.COMMON_PROGRESSIONS) {
                        TabTapChip(text = label, onClick = { model.addProgression(degrees) })
                    }
                }
            }
        }

        // --- The progression ---
        Panel(
            title = "Progression",
            trailing = {
                if (state.composition.chordProgression.isNotEmpty()) {
                    Row {
                        OutlinedButton(onClick = model::renderChordsToTrack) { Text("To notes") }
                        Spacer(Modifier.width(spacing.small))
                        OutlinedButton(onClick = model::clearChords) { Text("Clear") }
                    }
                }
            },
        ) {
            if (state.composition.chordProgression.isEmpty()) {
                Text(
                    "Add chords above, or pick a progression. They will play with the rest " +
                        "of the project and export as their own track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                Column {
                    val progression = state.composition.chordProgression
                    for ((index, event) in progression.withIndex()) {
                        val isEditing = event.id == editor.editingChordId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.tiny),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textTertiary,
                                    modifier = Modifier.width(24.dp),
                                )
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { model.beginEditChord(event.id) },
                                ) {
                                    Text(
                                        event.chord.symbol,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isEditing) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MusicColors.chord
                                        },
                                    )
                                    Text(
                                        buildString {
                                            append(
                                                CompositionEditor.describeChordLength(
                                                    event.durationTicks,
                                                    state.composition.timeSignature,
                                                ),
                                            )
                                            append(" \u00B7 ")
                                            append(event.strum.displayName)
                                            append(" \u00B7 ")
                                            append(event.voicing?.tabString() ?: "auto")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // Reorder.
                                IconToggle(
                                    Icons.Default.KeyboardArrowUp, "Move ${event.chord.symbol} earlier",
                                    enabled = index > 0, size = 38.dp,
                                ) { model.nudgeChord(event.id, -1) }
                                IconToggle(
                                    Icons.Default.KeyboardArrowDown, "Move ${event.chord.symbol} later",
                                    enabled = index < progression.lastIndex, size = 38.dp,
                                ) { model.nudgeChord(event.id, 1) }
                                IconToggle(
                                    Icons.Default.PlayArrow, "Hear ${event.chord.symbol}", size = 38.dp,
                                ) { model.previewChord(event.id) }
                                IconToggle(
                                    Icons.Default.Delete, "Remove ${event.chord.symbol}", size = 38.dp,
                                ) { model.removeChord(event.id) }
                            }

                            // Length and arrangement controls for this chord.
                            Row(
                                Modifier.padding(start = 24.dp, top = spacing.tiny),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconToggle(
                                    Icons.Default.Remove, "Halve ${event.chord.symbol}", size = 34.dp,
                                ) { model.halveChordLength(event.id) }
                                IconToggle(
                                    Icons.Default.Add, "Double ${event.chord.symbol}", size = 34.dp,
                                ) { model.doubleChordLength(event.id) }
                                Spacer(Modifier.width(spacing.small))
                                TabTapChip("Edit", selected = isEditing) {
                                    model.beginEditChord(event.id)
                                }
                                Spacer(Modifier.width(spacing.tiny))
                                TabTapChip("Repeat") { model.duplicateChord(event.id) }
                            }

                            Spacer(Modifier.height(spacing.tiny))
                            // The strum, changeable in place.
                            ChipRow(
                                items = StrumPattern.entries.toList(),
                                isSelected = { it == event.strum },
                                onSelect = { model.setChordStrum(event.id, it) },
                                label = { it.displayName },
                                modifier = Modifier.padding(start = 24.dp),
                            )
                            Spacer(Modifier.height(spacing.small))
                            // And its length, in the project's own metre.
                            ChipRow(
                                items = remember(state.composition.timeSignature) {
                                    CompositionEditor.chordLengthOptions(
                                        state.composition.timeSignature,
                                    )
                                },
                                isSelected = { it.ticks == event.durationTicks },
                                onSelect = { model.setChordDurationTicks(event.id, it.ticks) },
                                label = { it.label },
                                modifier = Modifier.padding(start = 24.dp),
                            )
                            Spacer(Modifier.height(spacing.small))
                            com.tabtap.app.ui.components.HairlineDivider()
                        }
                    }

                    Spacer(Modifier.height(spacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Total: ${
                                CompositionEditor.describeChordLength(
                                    progression.sumOf { it.durationTicks },
                                    state.composition.timeSignature,
                                )
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = model::loopWholeSection) { Text("Loop it") }
                    }
                }
            }
        }
    }
}

/**
 * A chord diagram.
 *
 * Drawn for the project's own tuning and string count, with the fret numbers of the
 * shape, muted strings marked, open strings ringed and a barre shown as a bar — the
 * conventions a chord book uses.
 */
@Composable
fun ChordDiagram(
    voicing: ChordVoicing,
    fretboard: Fretboard,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val stringCount = fretboard.stringCount
    val frets = 5
    val startFret = max(1, voicing.position)

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MusicColors.chord else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .size(width = (stringCount * 17 + 36).dp, height = 150.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                voicing.tabString(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Canvas(Modifier.fillMaxSize()) {
                val left = 10f
                val top = 14f
                val boardWidth = size.width - left - 4f
                val boardHeight = size.height - top - 10f
                val stringGap = boardWidth / (stringCount - 1).coerceAtLeast(1)
                val fretGap = boardHeight / frets

                // Strings, highest on the right as a chord diagram is drawn.
                for (s in 0 until stringCount) {
                    val x = left + s * stringGap
                    drawLine(
                        MusicColors.string.copy(alpha = 0.7f),
                        Offset(x, top),
                        Offset(x, top + boardHeight),
                        1.2f,
                    )
                }
                // Frets.
                for (f in 0..frets) {
                    val y = top + f * fretGap
                    val isNut = startFret == 1 && f == 0
                    drawLine(
                        if (isNut) MusicColors.nut else MusicColors.fret,
                        Offset(left, y),
                        Offset(left + boardWidth, y),
                        if (isNut) 4f else 1.4f,
                    )
                }
                // The starting fret, when the shape is up the neck.
                if (startFret > 1) {
                    val layout = measurer.measure(
                        "$startFret",
                        TextStyle(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MusicColors.nut,
                        ),
                    )
                    drawText(layout, topLeft = Offset(0f, top + fretGap * 0.2f))
                }

                // Dots, open-string rings and muted crosses.
                voicing.frets.forEachIndexed { stringIndex, fret ->
                    // String 0 is the lowest; a diagram puts it on the left.
                    val x = left + stringIndex * stringGap
                    when {
                        fret == null -> {
                            // A muted string: a cross above the nut.
                            val r = 3.2f
                            drawLine(
                                MusicColors.string,
                                Offset(x - r, top - 9f), Offset(x + r, top - 3f), 1.4f,
                            )
                            drawLine(
                                MusicColors.string,
                                Offset(x - r, top - 3f), Offset(x + r, top - 9f), 1.4f,
                            )
                        }
                        fret == 0 -> drawCircle(
                            MusicColors.string, 3.4f, Offset(x, top - 6f), style = Stroke(1.4f),
                        )
                        else -> {
                            val offsetFret = fret - startFret
                            if (offsetFret in 0 until frets) {
                                val y = top + (offsetFret + 0.5f) * fretGap
                                drawCircle(MusicColors.chord, stringGap * 0.33f, Offset(x, y))
                                val finger = voicing.fingers.getOrNull(stringIndex)
                                if (finger != null && finger > 0) {
                                    val layout = measurer.measure(
                                        "$finger",
                                        TextStyle(
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10141A),
                                        ),
                                    )
                                    drawText(
                                        layout,
                                        topLeft = Offset(
                                            x - layout.size.width / 2f,
                                            y - layout.size.height / 2f,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
