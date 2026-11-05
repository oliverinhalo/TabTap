package com.tabtap.app.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tabtap.app.design.LocalSpacing
import com.tabtap.app.design.LocalTabTapColors
import com.tabtap.app.design.MusicColors
import com.tabtap.app.ui.components.HairlineDivider
import com.tabtap.app.ui.components.IconToggle

/**
 * The help dialog.
 *
 * Opened by the question mark on each screen, which starts on that screen's topic, and
 * from there every other topic is one tap away. It is a dialog rather than a screen so
 * reading it never loses your place in the music.
 */
@Composable
fun HelpDialog(
    startTopicId: String?,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var topicId by remember { mutableStateOf(startTopicId) }
    val topic = topicId?.let { Help.byId(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (topic != null) {
                    IconToggle(
                        Icons.AutoMirrored.Filled.ArrowBack, "All topics", size = 36.dp,
                    ) { topicId = null }
                    Spacer(Modifier.width(spacing.small))
                }
                Text(
                    topic?.title ?: "Help",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (topic == null) {
                    Text(
                        "What each part of TabTap does.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(spacing.medium))
                    for (item in Help.topics) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { topicId = item.id }
                                .padding(vertical = spacing.small),
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (item.id == Help.gettingStarted.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HairlineDivider()
                    }
                } else {
                    TopicBody(topic)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TopicBody(topic: HelpTopic) {
    val spacing = LocalSpacing.current
    val colors = LocalTabTapColors.current

    Text(
        topic.summary,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    if (topic.steps.isNotEmpty()) {
        Spacer(Modifier.height(spacing.large))
        Text(
            "HOW TO USE IT",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(spacing.small))
        topic.steps.forEachIndexed { index, step ->
            Row(Modifier.padding(bottom = spacing.small)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                    modifier = Modifier.size(22.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(spacing.small))
                Text(
                    step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (topic.tips.isNotEmpty()) {
        Spacer(Modifier.height(spacing.medium))
        Text(
            "WORTH KNOWING",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(spacing.small))
        for (tip in topic.tips) {
            Row(Modifier.padding(bottom = spacing.small)) {
                Text(
                    "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusicColors.harmony[0],
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The question mark that opens the help on a particular topic. */
@Composable
fun HelpButton(topicId: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    var open by remember { mutableStateOf(false) }
    IconToggle(Icons.Default.HelpOutline, "Help", size = size) { open = true }
    if (open) {
        HelpDialog(startTopicId = topicId, onDismiss = { open = false })
    }
}
