package com.tabtap.app.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType
import com.tabtap.app.design.MusicColors
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
    canUndo: Boolean,
    canRedo: Boolean,
    saveState: SaveState,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onToggleMetronome: () -> Unit,
    onToggleLoop: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onTempoChange: (Double) -> Unit,
    onTapTempo: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.medium, vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
            ) {
                // The play button is the largest target on the bar, deliberately.
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
                IconToggle(Icons.Default.Stop, "Stop", onClick = onStop)
                IconToggle(Icons.Default.Replay, "Back to start", onClick = onRestart)
                IconToggle(
                    Icons.Default.Loop, "Loop",
                    checked = looping, onClick = onToggleLoop,
                )
                IconToggle(
                    Icons.Default.GraphicEq, "Metronome",
                    checked = metronomeEnabled,
                    accent = MusicColors.harmony[0],
                    onClick = onToggleMetronome,
                )

                Spacer(Modifier.width(spacing.small))

                // Position, in bars and beats.
                Column(Modifier.width(96.dp)) {
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
                    Text(
                        "bar . beat",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }

                Spacer(Modifier.weight(1f))

                Stepper(
                    label = "Tempo",
                    value = "${bpm.roundToInt()}",
                    onDecrement = { onTempoChange(bpm - 1) },
                    onIncrement = { onTempoChange(bpm + 1) },
                    decrementEnabled = bpm > 20,
                    incrementEnabled = bpm < 400,
                    onValueClick = onTapTempo,
                )

                Spacer(Modifier.width(spacing.small))

                IconToggle(
                    Icons.Default.Undo, "Undo", enabled = canUndo, onClick = onUndo,
                )
                IconToggle(
                    Icons.Default.Redo, "Redo", enabled = canRedo, onClick = onRedo,
                )
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

/** Formats a tick as "bar.beat", counting from 1 as musicians do. */
fun barsAndBeats(tick: Long, timeSignature: TimeSignature): String {
    val bar = tick / timeSignature.barTicks
    val within = tick - bar * timeSignature.barTicks
    val beat = within / timeSignature.beatTicks
    val sub = ((within % timeSignature.beatTicks) * 100 / timeSignature.beatTicks)
    return "${bar + 1}.${beat + 1}.%02d".format(sub)
}
