/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
private fun NewActionButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val animatedBackground by animateColorAsState(
        targetValue = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.5f),
        animationSpec = tween(200),
        label = "background",
    )

    val animatedContent by animateColorAsState(
        targetValue = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
        animationSpec = tween(200),
        label = "content",
    )

    var performAction by remember { mutableStateOf(false) }

    if (performAction) {
        onClick()
        LaunchedEffect(Unit) {
            performAction = false
        }
    }

    // Action tiles keep their card: a filled, rounded surface one step above the sheet.
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(animatedBackground)
                .clickable(enabled = enabled) { performAction = true },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = animatedContent,
                textAlign = TextAlign.Center,
                // Two lines rather than a marquee, which cut words off ("o playlist").
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The menu's main actions as cards, one row of at most [columns]; any more go under them as
 * plain rows, like the menu's other items, so a menu never shows a lone card on a second row.
 * The cards are a see-through tint of the text colour, so on a sheet in the song's colour
 * (PlayerSheet) they take that colour too instead of standing out in grey.
 */
@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    val cards = actions.take(columns)
    val rest = actions.drop(columns)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cards.forEach { action ->
                NewActionButton(
                    icon = action.icon,
                    text = action.text,
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f),
                    enabled = action.enabled,
                    backgroundColor =
                        if (action.backgroundColor != Color.Unspecified) {
                            action.backgroundColor
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = CARD_TINT_ALPHA)
                        },
                    contentColor =
                        if (action.contentColor != Color.Unspecified) {
                            action.contentColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            repeat(columns - cards.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (rest.isNotEmpty()) {
            Column {
                rest.forEach { action -> NewActionRow(action) }
            }
        }
    }
}

/** How strongly an action card tints the sheet under it. */
private const val CARD_TINT_ALPHA = 0.07f

/** An action past the cards, as a plain row lined up with the menu's other rows. */
@Composable
private fun NewActionRow(action: NewAction) {
    var performAction by remember { mutableStateOf(false) }
    if (performAction) {
        action.onClick()
        LaunchedEffect(Unit) { performAction = false }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = action.enabled) { performAction = true }
                .heightIn(min = 52.dp)
                // The grid sits 4dp in; rows of the menu start 24dp in.
                .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { action.icon() }
        Spacer(Modifier.width(16.dp))
        Text(action.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class NewAction(
    val icon: @Composable () -> Unit,
    val text: String,
    val onClick: @Composable () -> Unit,
    val enabled: Boolean = true,
    val backgroundColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)
