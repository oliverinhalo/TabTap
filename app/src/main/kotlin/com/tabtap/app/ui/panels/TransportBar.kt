package com.tabtap.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
import com.tabtap.app.LoopScope
import com.tabtap.app.SaveState
import com.tabtap.app.ui.components.IconToggle
import com.tabtap.app.ui.components.Stepper
import com.tabtap.music.TimeSignature
import kotlin.math.roundToInt

/**
 * The transport bar: play, stop, loop, tempo, position and undo.
 *
 * It sits above every editor pane and never moves, because it is the one set of controls
 * the user reaches for constantly. The position readout is in bars and beats rather than
 * seconds, which is the only unit that is useful while composing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransportBar(
    isPlaying: Boolean,
    isPaused: Boolean,
    positionTick: Long,
    countInBeatsRemaining: Int,
    bpm: Double,
    timeSignature: TimeSignature,
    metronomeEnabled: Boolean,
    looping: Boolean,
    /** The loop's extent, for the readout; null when not looping. */
    loopLabel: String? = null,
    canUndo: Boolean,
    canRedo: Boolean,
    saveState: SaveState,
    modifier: Modifier = Modifier,
    /**
     * Compact mode puts the page's name and its help button on the same row as the
     * transport, because on a phone in landscape there is no height to spare for a
     * separate title bar.
     */
    compact: Boolean = false,
    title: String? = null,
    help: (@Composable () -> Unit)? = null,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onToggleMetronome: () -> Unit,
    onToggleLoop: () -> Unit,
    /** Choose what the loop covers. Null keeps the simple toggle only. */
    onChooseLoop: ((LoopScope) -> Unit)? = null,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTempoChange: (Double) -> Unit,
    onTapTempo: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The bar has to fit a phone in portrait as well as a tablet in landscape, so
        // the secondary controls are dropped as the width shrinks rather than being
        // allowed to overflow. The play, stop and position controls always survive.
        val width = maxWidth
        // Nothing is ever dropped: controls that will not fit on one row move to a
        // second. Hiding the loop button on a phone meant the loop could not be reached
        // at all, which is worse than two rows.
        val oneRow = width >= 560.dp
        // The beat lamps are a luxury: they only appear once everything else fits on
        // one line, because three lines of transport on a 640dp landscape phone costs
        // more than the lamps are worth.
        val showBeatLamps = width >= 760.dp
        // Nothing is dropped for width any more. The loop, the metronome, undo and redo
        // were hidden below 560dp, which is every phone in portrait and some in
        // landscape: redo simply did not exist and the loop could not be reached. They
        // wrap onto a second line instead.
        val showLoopAndClick = true
        val showRestart = true
        val showUndoRedo = true
        // Below this the plus/minus stepper does not fit; the tempo becomes a tappable
        // readout instead, which still reaches tap-tempo and the tempo field.
        val showTempoStepper = width >= 360.dp
        // Icons alone are a guessing game, so each one carries its name underneath -
        // but only on a screen wide enough to keep the bar one row deep. With labels at
        // 700dp the bar wrapped to 140dp, a third of the height of a phone in
        // landscape, which is height the fretboard needs more than the labels do. On a
        // narrower screen the names are in the page's help instead.
        val showLabels = width >= 1100.dp

        Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            // A Row measures its non-weighted children in order and gives whatever is
            // left — nothing — to the last ones, so when the controls overflowed, undo
            // was half cut off and redo was measured to zero width and vanished. A
            // FlowRow wraps instead, so every control is always fully on the screen
            // whatever the width; on a wide screen it is still a single row.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.medium, vertical = spacing.small),
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalArrangement = Arrangement.spacedBy(spacing.tiny),
            ) {
                // The play button is the largest target on the bar, deliberately.
                LabelledControl(
                    if (isPlaying) "Pause" else if (isPaused) "Resume" else "Play",
                    showLabels,
                ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            if (isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                colors.surfaceHighest
                            },
                            CircleShape,
                        )
                        .clickable { if (isPlaying) onPause() else onPlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else if (isPaused) "Resume" else "Play",
                        tint = if (isPlaying) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }
                }
                LabelledControl("Stop", showLabels) {
                    IconToggle(Icons.Default.Stop, "Stop", onClick = onStop)
                }

                // The page's name, where there is room for it.
                if (compact && title != null && width >= 700.dp) {
                    Spacer(Modifier.width(spacing.small))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                if (showRestart) {
                    LabelledControl("Start", showLabels) {
                        IconToggle(Icons.Default.Replay, "Back to start", onClick = onRestart)
                    }
                }
                if (showLoopAndClick) {
                    // Tapping sets the loop; the menu chooses what it covers, because
                    // looping the whole section is as common as looping one bar.
                    Box {
                        var loopMenuOpen by remember { mutableStateOf(false) }
                        LabelledControl(if (looping) "Looping" else "Loop", showLabels) {
                            IconToggle(
                                Icons.Default.Loop,
                                if (looping) "Loop: ${loopLabel ?: "on"}" else "Loop",
                                checked = looping,
                                onClick = {
                                    if (onChooseLoop == null) onToggleLoop() else loopMenuOpen = true
                                },
                            )
                        }
                        if (onChooseLoop != null) {
                            DropdownMenu(
                                expanded = loopMenuOpen,
                                onDismissRequest = { loopMenuOpen = false },
                            ) {
                                for (scope in LoopScope.entries) {
                                    DropdownMenuItem(
                                        text = { Text(scope.label) },
                                        onClick = {
                                            loopMenuOpen = false
                                            onChooseLoop(scope)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    LabelledControl("Click", showLabels) {
                        IconToggle(
                            Icons.Default.GraphicEq, "Metronome",
                            checked = metronomeEnabled,
                            accent = MusicColors.harmony[0],
                            onClick = onToggleMetronome,
                        )
                    }
                }

                if (showBeatLamps) {
                    Spacer(Modifier.width(spacing.small))
                    // The visual beat indicator: one lamp per felt beat of the bar, the
                    // current one lit. It is what lets you follow the click without
                    // listening, and it shows the metre at a glance.
                    BeatIndicator(
                        timeSignature = timeSignature,
                        positionTick = positionTick,
                        active = isPlaying,
                        countingIn = countInBeatsRemaining > 0,
                    )
                }

                Spacer(Modifier.width(spacing.small))

                // Position, in bars and beats.
                Column(Modifier.width(80.dp)) {
                    Text(
                        if (countInBeatsRemaining > 0) {
                            "Count-in $countInBeatsRemaining"
                        } else {
                            barsAndBeats(positionTick, timeSignature)
                        },
                        style = MonoType.readout,
                        color = if (countInBeatsRemaining > 0) {
                            MusicColors.harmony[0]
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (showLoopAndClick) {
                        Text(
                            if (looping && loopLabel != null) "loop: $loopLabel" else "bar . beat",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (looping) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                colors.textTertiary
                            },
                            maxLines = 1,
                        )
                    }
                }

                if (showTempoStepper) {
                    Stepper(
                        label = "Tempo",
                        value = "${bpm.roundToInt()}",
                        onDecrement = { onTempoChange(bpm - 1) },
                        onIncrement = { onTempoChange(bpm + 1) },
                        decrementEnabled = bpm > 20,
                        incrementEnabled = bpm < 400,
                        onValueClick = onTapTempo,
                    )
                } else {
                    Text(
                        "${bpm.roundToInt()}",
                        style = MonoType.readout,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(44.dp)
                            .clickable(onClick = onTapTempo),
                    )
                }

                if (showUndoRedo) {
                    Spacer(Modifier.width(spacing.small))
                    LabelledControl("Undo", showLabels) {
                        IconToggle(Icons.Default.Undo, "Undo", enabled = canUndo, onClick = onUndo)
                    }
                    LabelledControl("Redo", showLabels) {
                        IconToggle(Icons.Default.Redo, "Redo", enabled = canRedo, onClick = onRedo)
                    }
                }
                LabelledControl("Help", showLabels) { help?.invoke() }
            }

            // A one-pixel save indicator: visible when it matters, invisible otherwise.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        when (saveState) {
                            SaveState.SAVING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            SaveState.FAILED -> MaterialTheme.colorScheme.error
                            else -> Color.Transparent
                        },
                    ),
            )
        }
    }
    }
}

/**
 * An icon with its name underneath, where the screen is wide enough for it.
 *
 * The transport's icons were not clear on their own — several of them are only obvious
 * once you already know what they do — so in landscape, where there is width going
 * spare, each one is labelled.
 */
@Composable
private fun LabelledControl(
    label: String,
    showLabel: Boolean,
    content: @Composable () -> Unit,
) {
    if (!showLabel) {
        content()
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The beat indicator: a row of lamps, one per felt pulse of the bar.
 *
 * The downbeat's lamp is wider, so an irregular metre like 7/8 reads as its grouping
 * rather than as seven identical dots.
 */
@Composable
private fun BeatIndicator(
    timeSignature: TimeSignature,
    positionTick: Long,
    active: Boolean,
    countingIn: Boolean,
) {
    val pulses = timeSignature.pulsesPerBar.coerceIn(1, 16)
    val within = positionTick % timeSignature.barTicks
    val currentPulse = (within / timeSignature.pulseTicks).toInt().coerceIn(0, pulses - 1)

    Row(verticalAlignment = Alignment.CenterVertically) {
        for (pulse in 0 until pulses) {
            val isCurrent = active && pulse == currentPulse
            val isDownbeat = pulse == 0
            val color = when {
                countingIn && isCurrent -> MusicColors.harmony[0]
                isCurrent && isDownbeat -> MusicColors.playhead
                isCurrent -> MaterialTheme.colorScheme.primary
                isDownbeat -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
            }
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(width = if (isDownbeat) 10.dp else 6.dp, height = 10.dp)
                    .background(color, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Formats a tick as "bar.beat", counting from 1 as musicians do. */
fun barsAndBeats(tick: Long, timeSignature: TimeSignature): String {
    val bar = tick / timeSignature.barTicks
    val within = tick - bar * timeSignature.barTicks
    val beat = within / timeSignature.beatTicks
    val sub = ((within % timeSignature.beatTicks) * 100 / timeSignature.beatTicks)
    return "${bar + 1}.${beat + 1}.%02d".format(sub)
}
