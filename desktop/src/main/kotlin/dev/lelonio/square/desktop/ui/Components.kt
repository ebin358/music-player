package dev.lelonio.square.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.CollectionKind
import dev.lelonio.square.desktop.model.Shelf
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A translucent panel: the "glass" the Android app is built from, without the refraction. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(16.dp), strong: Boolean = false) = this
    .clip(shape)
    .background(if (strong) SquareColors.GlassStrong else SquareColors.Glass, shape)
    .border(1.dp, SquareColors.GlassEdge, shape)

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier.clip(shape).background(Color(0xFF23252B)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = SquareColors.InkFaint, modifier = Modifier.fillMaxSize(0.36f))
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SquareColors.AccentSoft, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier, action: Pair<String, () -> Any?>? = null) {
    Column(
        modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SquareColors.InkSoft) }
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label, color = SquareColors.AccentSoft) } }
    }
}

@Composable
fun SourceBadge(source: Source) {
    val (label, color) = when (source) {
        Source.SPOTIFY -> "Spotify" to Color(0xFF1ED760)
        Source.YOUTUBE -> "YouTube" to Color(0xFFFF4E45)
        Source.LOCAL -> "Mac" to SquareColors.InkSoft
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** One song in a list: number or artwork, title, artist, album, length. Right-click for more. */
@Composable
fun TrackRow(
    track: Track,
    number: Int?,
    app: AppState,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    showAlbum: Boolean = true,
    showSource: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(items = { trackMenu(track, app) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) SquareColors.Glass else Color.Transparent)
                .hoverable(interaction)
                .clickable(onClick = onPlay)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (number != null) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (hovered) {
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = SquareColors.Ink, modifier = Modifier.size(18.dp))
                    } else {
                        Text(
                            "$number",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) SquareColors.AccentSoft else SquareColors.InkFaint,
                        )
                    }
                }
            } else {
                Artwork(track.artworkUrl, Modifier.size(40.dp), RoundedCornerShape(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) SquareColors.AccentSoft else SquareColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (track.explicit) Icon(Icons.Rounded.Explicit, "Explicit", tint = SquareColors.InkFaint, modifier = Modifier.size(14.dp))
                    if (showSource) SourceBadge(track.source)
                }
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquareColors.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showAlbum && !track.album.isNullOrBlank()) {
                Text(
                    track.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SquareColors.InkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.6f),
                )
            }
            if (app.isLiked(track)) {
                Icon(Icons.Rounded.Favorite, "Liked", tint = SquareColors.AccentSoft, modifier = Modifier.size(14.dp))
            }
            Text(
                if (track.durationMs > 0) formatTime(track.durationMs) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = SquareColors.InkFaint,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

fun trackMenu(track: Track, app: AppState): List<ContextMenuItem> = buildList {
    add(ContextMenuItem("Play next") { app.player.playNext(track) })
    add(ContextMenuItem("Add to queue") { app.player.addToQueue(track) })
    add(ContextMenuItem(if (app.isLiked(track)) "Remove from liked" else "Like") { app.toggleLike(track) })
    if (track.source == Source.YOUTUBE) add(ContextMenuItem("Start radio") { app.player.startRadio(track) })
    track.albumId?.let { id ->
        add(ContextMenuItem("Go to album") {
            app.open(Collection(id, track.source, CollectionKind.ALBUM, track.album ?: "Album", artworkUrl = track.artworkUrl))
        })
    }
    track.artistId?.let { id ->
        add(ContextMenuItem("Go to artist") {
            app.open(Collection(id, track.source, CollectionKind.ARTIST, track.artist.substringBefore(',')))
        })
    }
}

@Composable
fun CollectionTile(collection: Collection, app: AppState, width: Dp = 168.dp) {
    val round = collection.kind == CollectionKind.ARTIST
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(items = {
        listOf(ContextMenuItem(if (app.isSaved(collection)) "Remove from library" else "Save to library") { app.toggleSaved(collection) })
    }) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(12.dp))
                .background(if (hovered) SquareColors.Glass else Color.Transparent)
                .hoverable(interaction)
                .clickable { app.open(collection) }
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Artwork(
                collection.artworkUrl,
                Modifier.fillMaxWidth().aspectRatio(1f),
                if (round) CircleShape else RoundedCornerShape(10.dp),
                placeholder = when (collection.kind) {
                    CollectionKind.ARTIST -> Icons.Rounded.Person
                    CollectionKind.ALBUM -> Icons.Rounded.Album
                    else -> Icons.Rounded.QueueMusic
                },
            )
            Text(collection.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            collection.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SquareColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A song as a card, for shelves of songs on the home page. */
@Composable
fun TrackTile(track: Track, app: AppState, onPlay: () -> Unit, width: Dp = 168.dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(items = { trackMenu(track, app) }) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(12.dp))
                .background(if (hovered) SquareColors.Glass else Color.Transparent)
                .hoverable(interaction)
                .clickable(onClick = onPlay)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                Artwork(track.artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(10.dp))
                if (hovered) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp)
                            .clip(CircleShape).background(SquareColors.Accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White) }
                }
            }
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = SquareColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ShelfRow(shelf: Shelf, app: AppState) {
    Column {
        SectionTitle(shelf.title, Modifier.padding(horizontal = 28.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shelf.tracks, key = { "t:" + it.key }) { track ->
                TrackTile(track, app, onPlay = { app.player.play(shelf.tracks, shelf.tracks.indexOf(track)) })
            }
            items(shelf.collections, key = { "c:" + it.key }) { collection -> CollectionTile(collection, app) }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SquareColors.Ink else SquareColors.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = if (selected) SquareColors.Floor else SquareColors.Ink, modifier = Modifier.size(16.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) SquareColors.Floor else SquareColors.Ink)
    }
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
