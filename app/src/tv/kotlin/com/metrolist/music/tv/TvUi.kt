/**
 * Sonara (C) 2026, derived from Metrolist
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sonara TV's building blocks: the phone's colours and type at TV size, inside the TV's safe
 * area. Whatever the remote is on grows a little and gets a light outline.
 */

package com.metrolist.music.tv

import androidx.compose.ui.focus.focusRestorer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

/** Sizes for a screen seen from the sofa; the safe area keeps everything off the TV's edges. */
object Tv {
    val SafeX = 56.dp
    val SafeY = 28.dp
    val TopBar = 64.dp
    val Card = 176.dp
    val CardRadius = 12.dp
    val Gap = 18.dp
    val Outline = 2.5.dp
    const val GROW = 1.07f
}

/** The remote's focus on [shape]: a little bigger, with a light outline; OK clicks, holding OK long-clicks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocus(
    shape: Shape = RoundedCornerShape(Tv.CardRadius),
    grow: Float = Tv.GROW,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) grow else 1f, label = "tvFocus")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.border(Tv.Outline, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape)
        .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}

@Composable
fun TvShelfTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = Tv.SafeX, bottom = 10.dp),
    )
}

/** A titled row of cards, moved through left and right. */
@Composable
fun <T : Any> TvShelf(
    title: String,
    items: List<T>,
    key: (T) -> Any = ::tvKey,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 26.dp)) {
        TvShelfTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Tv.SafeX, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Tv.Gap),
            // Back on this row, the remote lands on the card it left, not the first one.
            modifier = Modifier.focusRestorer(),
        ) {
            items(items, key = key, contentType = { it::class.simpleName }) { card(it) }
        }
    }
}

/** A cover with its title and subtitle; round for artists. */
@Composable
fun TvCard(
    item: Any,
    onClick: () -> Unit,
    width: Dp = Tv.Card,
    onLongClick: (() -> Unit)? = null,
) {
    val look = tvLook(item)
    val shape = if (look.round) CircleShape else RoundedCornerShape(Tv.CardRadius)
    Column(Modifier.width(width)) {
        AsyncImage(
            model = look.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .tvFocus(shape = shape, onLongClick = onLongClick, onClick = onClick)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.height(10.dp))
        Text(look.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!look.subtitle.isNullOrBlank()) {
            Text(look.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A pill button: icon and label; [primary] is the filled one. */
@Composable
fun TvButton(
    icon: Int,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
) {
    val container =
        when {
            primary -> Color.White
            selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        }
    val content = if (primary) Color.Black else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .tvFocus(shape = CircleShape, grow = 1.1f, onClick = onClick)
                .background(container)
                .padding(horizontal = if (label != null) 22.dp else 13.dp, vertical = 12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = content)
        }
    }
}

/** A settings row with a real switch; OK turns it on or off. */
@Composable
fun TvSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    TvRowFrame(onClick = { onChange(!checked) }) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** A settings row with a value; OK moves to the next value. */
@Composable
fun TvChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    TvRowFrame(onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun TvRowFrame(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth()
                .tvFocus(shape = RoundedCornerShape(14.dp), grow = 1.02f, onClick = onClick)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 24.dp, vertical = 14.dp),
        content = content,
    )
}

/** Title, subtitle, cover and roundness of anything listed (YouTube or the library). */
data class TvLook(val title: String, val subtitle: String?, val image: String?, val round: Boolean)

fun tvLook(item: Any): TvLook =
    when (item) {
        is Song -> TvLook(item.song.title, item.artists.joinToString { it.name }, item.song.thumbnailUrl, false)
        is Album -> TvLook(item.album.title, item.artists.joinToString { it.name }, item.album.thumbnailUrl, false)
        is Artist -> TvLook(item.artist.name, null, item.artist.thumbnailUrl, true)
        is Playlist -> TvLook(item.playlist.name, null, item.thumbnails.firstOrNull() ?: item.playlist.thumbnailUrl, false)
        is SongItem -> TvLook(item.title, item.artists.joinToString { it.name }, item.thumbnail, false)
        is AlbumItem -> TvLook(item.title, item.artists?.joinToString { it.name } ?: item.year?.toString(), item.thumbnail, false)
        is ArtistItem -> TvLook(item.title, null, item.thumbnail, true)
        is PlaylistItem -> TvLook(item.title, item.author?.name ?: item.songCountText, item.thumbnail, false)
        is YTItem -> TvLook(item.title, null, item.thumbnail, false)
        else -> TvLook(item.toString(), null, null, false)
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
