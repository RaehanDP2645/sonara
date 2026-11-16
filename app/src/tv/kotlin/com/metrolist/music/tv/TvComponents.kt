/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's building blocks, in the phone's design language (its colours, type and shapes)
 * at TV size: what the remote is on always grows a little and gets a light outline, so it can
 * be seen from the sofa.
 */

package com.metrolist.music.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song

/** Sizes for a TV seen from a few metres away. */
object TvSize {
    val Gutter = 48.dp
    val RowGap = 20.dp
    val Card = 168.dp
    val CardRadius = 14.dp
    val Rail = 84.dp
    val FocusOutline = 3.dp
    const val FOCUS_SCALE = 1.07f
}

/**
 * The remote's focus, drawn on [shape]: a little bigger, with a light outline. Click with OK,
 * long-press OK for [onLongClick] (the item's menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(TvSize.CardRadius),
    scale: Float = TvSize.FOCUS_SCALE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val grown by animateFloatAsState(if (focused) scale else 1f, label = "tvFocus")
    return this
        .graphicsLayer {
            scaleX = grown
            scaleY = grown
        }.border(TvSize.FocusOutline, if (focused) Color.White.copy(alpha = 0.92f) else Color.Transparent, shape)
        .clip(shape)
        .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}

@Composable
fun TvSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = TvSize.Gutter, bottom = 12.dp),
    )
}

/** A titled shelf of cards, scrolled left and right with the remote. */
@Composable
fun <T : Any> TvShelf(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    Column(modifier.padding(bottom = 28.dp)) {
        TvSectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvSize.Gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(TvSize.RowGap),
        ) {
            items(items, key = key, contentType = { it::class.simpleName }) { card(it) }
        }
    }
}

/** A cover with its title and subtitle under it; round for artists. */
@Composable
fun TvCoverCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    width: Dp = TvSize.Card,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(TvSize.CardRadius)
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .tvFocusable(shape = shape, onLongClick = onLongClick, onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A round button with an icon, and its label beside it when it has one. */
@Composable
fun TvButton(
    icon: Int,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
) {
    val container =
        when {
            primary -> MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        }
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .tvFocusable(shape = CircleShape, scale = 1.1f, onClick = onClick)
                .background(container)
                .padding(horizontal = if (label != null) 22.dp else 14.dp, vertical = 14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = content, modifier = Modifier.size(28.dp))
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = content)
        }
    }
}

/** Title, subtitle, cover and roundness of anything the app lists (YouTube or the library). */
data class TvItemLook(val title: String, val subtitle: String?, val image: String?, val round: Boolean)

fun tvLook(item: Any): TvItemLook =
    when (item) {
        is Song -> TvItemLook(item.song.title, item.artists.joinToString { it.name }, item.song.thumbnailUrl, false)
        is Album -> TvItemLook(item.album.title, item.artists.joinToString { it.name }, item.album.thumbnailUrl, false)
        is Artist -> TvItemLook(item.artist.name, null, item.artist.thumbnailUrl, true)
        is Playlist -> TvItemLook(item.playlist.name, null, item.thumbnails.firstOrNull() ?: item.playlist.thumbnailUrl, false)
        is SongItem -> TvItemLook(item.title, item.artists.joinToString { it.name }, item.thumbnail, false)
        is AlbumItem -> TvItemLook(item.title, item.artists?.joinToString { it.name } ?: item.year?.toString(), item.thumbnail, false)
        is ArtistItem -> TvItemLook(item.title, null, item.thumbnail, true)
        is PlaylistItem -> TvItemLook(item.title, item.author?.name ?: item.songCountText, item.thumbnail, false)
        is YTItem -> TvItemLook(item.title, null, item.thumbnail, false)
        else -> TvItemLook(item.toString(), null, null, false)
    }

fun tvKey(item: Any): Any =
    when (item) {
        is Song -> "song:" + item.id
        is Album -> "album:" + item.id
        is Artist -> "artist:" + item.id
        is Playlist -> "playlist:" + item.id
        is YTItem -> item::class.simpleName + ":" + item.id
        else -> item.hashCode()
    }
