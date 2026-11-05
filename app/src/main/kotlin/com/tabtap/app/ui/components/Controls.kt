package com.tabtap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MonoType

/**
 * The shared controls.
 *
 * Every control in TabTap is one of these, so the app is visually consistent without
 * each screen re-inventing a button. They are deliberately plain: a flat surface, a one
 * pixel border, a single accent colour to show state. Nothing animates for longer than
 * 150 ms, and nothing animates that does not communicate a change.
 */

/** A panel: the standard container for a group of controls. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(spacing.medium)) {
            if (title != null || trailing != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalTabTapColors.current.textTertiary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/** A section heading inside a panel or sheet. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = LocalTabTapColors.current.textTertiary,
        modifier = modifier,
    )
}

/**
 * A segmented control: a row of mutually exclusive options.
 *
 * Used for anything with a handful of choices — the pane switcher, note values, grid
 * resolutions — because it shows the available options rather than hiding them behind a
 * menu, which matters on a screen the user is working on quickly.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(3.dp)) {
            for (option in options) {
                val isSelected = option == selected
                val background by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    tween(120),
                    label = "segment-bg",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = if (compact) 32.dp else 40.dp)
                        .background(background, RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .alpha(if (enabled) 1f else 0.4f),
                    )
                }
            }
        }
    }
}

/** A chip: a single selectable or momentary option. */
@Composable
fun TabTapChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalTabTapColors.current
    val borderColor by animateColorAsState(
        when {
            selected && accent != null -> accent
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        tween(120),
        label = "chip-border",
    )
    Surface(
        color = if (selected) colors.surfaceHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accent != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** A horizontally scrolling row of chips. */
@Composable
fun <T> ChipRow(
    items: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accent: (T) -> Color? = { null },
) {
    val spacing = LocalSpacing.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        items(items) { item ->
            TabTapChip(
                text = label(item),
                selected = isSelected(item),
                accent = accent(item),
                onClick = { onSelect(item) },
            )
        }
    }
}

/** A square icon button that can show an on/off state. */
@Composable
fun IconToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    accent: Color? = null,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val colors = LocalTabTapColors.current
    val tint by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            checked -> accent ?: MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        tween(120),
        label = "toggle-tint",
    )
    Surface(
        color = if (checked) colors.surfaceHighest else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .size(size)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** A labelled value with minus and plus buttons, for tempo, fret count and the like. */
@Composable
fun Stepper(
    label: String,
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    onValueClick: (() -> Unit)? = null,
) {
    Column(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconToggle(
                Icons.Default.Remove, "Decrease $label",
                enabled = decrementEnabled, size = 40.dp, onClick = onDecrement,
            )
            Text(
                value,
                style = MonoType.readout,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .then(
                        if (onValueClick != null) {
                            Modifier.clickable(onClick = onValueClick)
                        } else {
                            Modifier
                        },
                    ),
            )
            IconToggle(
                Icons.Default.Add, "Increase $label",
                enabled = incrementEnabled, size = 40.dp, onClick = onIncrement,
            )
        }
    }
}

/** A slider with a label and a value readout. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    accent: Color? = null,
    enabled: Boolean = true,
    steps: Int = 0,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            if (valueText != null) {
                Text(
                    valueText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent ?: MaterialTheme.colorScheme.primary,
                activeTrackColor = (accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.75f),
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

/** A row that reads as a key and a value, used throughout the inspector. */
@Composable
fun ReadoutRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    mono: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalTabTapColors.current.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (mono) MonoType.tab else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** An empty-state message, used where a list or a view has nothing to show yet. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier.fillMaxWidth().padding(spacing.xLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.small))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(spacing.large))
            action()
        }
    }
}

/** A thin divider that reads as structure rather than decoration. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** A coloured dot that identifies a track, matching the colour used in every view. */
@Composable
fun TrackDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, RoundedCornerShape(3.dp)))
}

/** A fading progress indicator used while a long operation runs. */
@Composable
fun SubtleBusyIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(150), label = "busy")
    Box(modifier.alpha(alpha)) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}
