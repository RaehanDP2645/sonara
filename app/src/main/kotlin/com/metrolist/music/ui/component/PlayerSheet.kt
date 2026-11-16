/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Requested by the owner: sheets and dialogs opened over the open player (lyrics, queue, the
 * player's menu and what it opens, sleep timer, Cast, song details) take the colour of the song,
 * as the player's own background does, instead of the fixed grey that stood out against it.
 * Everywhere else they keep the theme's surface, exactly as before.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.ErShape
import com.metrolist.music.ui.theme.Motion

/** The colour of the song in the open player (set by the player), or null while it is closed. */
object PlayerTint {
    var accent by mutableStateOf<Color?>(null)
}

/** How much of the song's colour a sheet takes, at its top and at its bottom, and a dialog. */
private const val TOP_DARK = 0.34f
private const val BOTTOM_DARK = 0.12f
private const val TOP_LIGHT = 0.16f
private const val BOTTOM_LIGHT = 0.05f
private const val DIALOG_DARK = 0.22f
private const val DIALOG_LIGHT = 0.08f

@Composable
private fun animatedAccent(): Color? {
    val accent = PlayerTint.accent
    val animated by animateColorAsState(accent ?: Color.Transparent, tween(Motion.ArtworkCrossfade), label = "playerTint")
    return if (accent == null) null else animated
}

/**
 * A sheet's background: [base] (the theme's surface) on its own, or, over the open player, a
 * wash of the song's colour that fades down the sheet, so it reads as part of the player while
 * text keeps its contrast.
 */
@Composable
fun playerSheetBrush(base: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Brush {
    val accent = animatedAccent() ?: return Brush.verticalGradient(listOf(base, base))
    val dark = base.luminance() < 0.5f
    return Brush.verticalGradient(
        listOf(
            lerp(base, accent, if (dark) TOP_DARK else TOP_LIGHT),
            playerSheetColor(base),
        ),
    )
}

/**
 * The sheet's own colour: the bottom of [playerSheetBrush], so whatever the content column does
 * not cover (under the navigation bar) continues it seamlessly.
 */
@Composable
fun playerSheetColor(base: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Color {
    val accent = animatedAccent() ?: return base
    return lerp(base, accent, if (base.luminance() < 0.5f) BOTTOM_DARK else BOTTOM_LIGHT)
}

/** A dialog's colour: [base], or over the open player, [base] lightly tinted with the song. */
@Composable
fun playerDialogColor(base: Color): Color {
    val accent = animatedAccent() ?: return base
    return lerp(base, accent, if (base.luminance() < 0.5f) DIALOG_DARK else DIALOG_LIGHT)
}

/**
 * The song's colour made readable as text on these sheets (a light shade on dark ones, a deep
 * one on light), or [fallback] while the player is closed. Used for lyric translations.
 */
@Composable
fun playerTextAccent(fallback: Color): Color {
    val accent = animatedAccent() ?: return fallback
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) lerp(accent, Color.White, 0.55f) else lerp(accent, Color.Black, 0.45f)
}

/** The bar at the top of a sheet that shows it can be dragged. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 12.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
    )
}

/**
 * The column a sheet's content goes in, painted with [playerSheetBrush] from the sheet's top
 * edge (the sheet draws no handle of its own; this one is inside the wash). Starts with the handle.
 */
@Composable
fun PlayerSheetColumn(
    modifier: Modifier = Modifier,
    base: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(playerSheetBrush(base)),
    ) {
        SheetHandle(Modifier.align(Alignment.CenterHorizontally))
        content()
    }
}

/** A modal sheet over the player, in the song's colour (see the top of this file). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = ErShape.SheetTop,
        containerColor = playerSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        PlayerSheetColumn(content = content)
    }
}
