package dev.lelonio.square.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.lelonio.square.desktop.lyrics.LyricsService
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.CollectionKind
import dev.lelonio.square.desktop.model.Lyrics
import dev.lelonio.square.desktop.model.RepeatMode
import dev.lelonio.square.desktop.model.Track

/** The blurred cover behind everything: the closest the desktop gets to the Android app's glass. */
@Composable
fun AmbientBackdrop(url: String?, strength: Float = 0.55f) {
    Box(Modifier.fillMaxSize().background(SquareColors.Floor)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = strength,
                modifier = Modifier.fillMaxSize().blur(110.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x66000000), Color(0xCC0D0E11), SquareColors.Floor)),
            ),
        )
    }
}

@Composable
fun PlayerBar(app: AppState) {
    val player = app.player
    val track by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val loading by player.loading.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeat by player.repeat.collectAsState()

    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 12.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(18.dp), strong = true).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // What is playing.
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = track != null) { app.nowPlayingOpen = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(track?.artworkUrl, Modifier.size(54.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(track?.title ?: "Not playing", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                track?.let {
                    Text(it.artist, style = MaterialTheme.typography.bodySmall, color = SquareColors.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            track?.let { t ->
                val liked = app.isLiked(t)
                IconButton(onClick = { app.toggleLike(t) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Unlike" else "Like",
                        tint = if (liked) SquareColors.AccentSoft else SquareColors.InkSoft,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Transport and progress.
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
            Transport(app, playing, loading, shuffle, repeat, big = false)
            Progress(app, compact = true)
        }

        // Lyrics, queue, volume.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.nowPlayingOpen = !app.nowPlayingOpen }, enabled = track != null) {
                Icon(Icons.Rounded.Lyrics, "Lyrics", tint = if (app.nowPlayingOpen) SquareColors.AccentSoft else SquareColors.InkSoft)
            }
            IconButton(onClick = { if (app.screen == Screen.Queue) app.back() else app.navigate(Screen.Queue) }) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "Queue",
                    tint = if (app.screen == Screen.Queue && !app.nowPlayingOpen) SquareColors.AccentSoft else SquareColors.InkSoft,
                )
            }
            VolumeControl(app)
        }
    }
}

@Composable
fun Transport(app: AppState, playing: Boolean, loading: Boolean, shuffle: Boolean, repeat: RepeatMode, big: Boolean) {
    val player = app.player
    val small = if (big) 30.dp else 20.dp
    val main = if (big) 64.dp else 38.dp
    val shuffleTint by animateColorAsState(if (shuffle) SquareColors.AccentSoft else SquareColors.InkSoft)
    val repeatTint by animateColorAsState(if (repeat != RepeatMode.OFF) SquareColors.AccentSoft else SquareColors.InkSoft)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 18.dp else 6.dp)) {
        IconButton(onClick = player::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = shuffleTint, modifier = Modifier.size(small * 0.85f)) }
        IconButton(onClick = player::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = SquareColors.Ink, modifier = Modifier.size(small)) }
        Box(
            Modifier.size(main).clip(CircleShape).background(SquareColors.Ink).clickable(onClick = player::toggle),
            contentAlignment = Alignment.Center,
        ) {
            if (loading && playing) {
                CircularProgressIndicator(color = SquareColors.Floor, strokeWidth = 2.dp, modifier = Modifier.size(main * 0.45f))
            } else {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play",
                    tint = SquareColors.Floor,
                    modifier = Modifier.size(main * 0.55f),
                )
            }
        }
        IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = SquareColors.Ink, modifier = Modifier.size(small)) }
        IconButton(onClick = player::cycleRepeat) {
            Icon(if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = repeatTint, modifier = Modifier.size(small * 0.85f))
        }
    }
}

@Composable
fun Progress(app: AppState, compact: Boolean) {
    val position by app.player.position.collectAsState()
    val duration by app.player.duration.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().widthIn(max = if (compact) 560.dp else 2000.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val shown = dragging?.let { (it * duration).toLong() } ?: position
        Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, color = SquareColors.InkFaint, modifier = Modifier.width(40.dp))
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { app.player.seek((it * duration).toLong()) }
                dragging = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = SquareColors.Ink,
                activeTrackColor = SquareColors.Ink,
                inactiveTrackColor = SquareColors.GlassStrong,
                disabledInactiveTrackColor = SquareColors.Glass,
            ),
            modifier = Modifier.weight(1f).height(if (compact) 20.dp else 28.dp),
        )
        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = SquareColors.InkFaint, modifier = Modifier.width(40.dp))
    }
}

@Composable
fun VolumeControl(app: AppState) {
    val volume by app.player.volume.collectAsState()
    var before by remember { mutableStateOf(0.8f) }
    IconButton(onClick = {
        if (volume > 0f) {
            before = volume
            app.player.setVolume(0f)
        } else {
            app.player.setVolume(before.coerceAtLeast(0.1f))
        }
    }) {
        Icon(
            when {
                volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            "Volume",
            tint = SquareColors.InkSoft,
        )
    }
    Slider(
        value = volume,
        onValueChange = app.player::setVolume,
        colors = SliderDefaults.colors(thumbColor = SquareColors.Ink, activeTrackColor = SquareColors.InkSoft, inactiveTrackColor = SquareColors.GlassStrong),
        modifier = Modifier.width(110.dp),
    )
}

/** The full-window player: cover, controls, and the words. */
@Composable
fun NowPlaying(app: AppState) {
    val player = app.player
    val track by player.current.collectAsState()
    val playing by player.playing.collectAsState()
    val loading by player.loading.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeat by player.repeat.collectAsState()
    val error by player.error.collectAsState()
    val t = track

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(t?.artworkUrl, strength = 0.85f)
        IconButton(onClick = { app.nowPlayingOpen = false }, modifier = Modifier.padding(start = 20.dp, top = 36.dp)) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = SquareColors.Ink, modifier = Modifier.size(30.dp))
        }
        if (t == null) {
            Message("Nothing playing", modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        Row(
            Modifier.fillMaxSize().padding(start = 64.dp, end = 48.dp, top = 72.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(t.artworkUrl, Modifier.widthIn(max = 460.dp).fillMaxWidth().aspectRatio(1f), RoundedCornerShape(18.dp))
                Spacer(Modifier.height(28.dp))
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                    Text(t.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = SquareColors.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = t.artistId != null) {
                            t.artistId?.let { app.open(Collection(it, t.source, CollectionKind.ARTIST, t.artist.substringBefore(','))) }
                        },
                    )
                    error?.let { Text(it, color = SquareColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    Spacer(Modifier.height(14.dp))
                    Progress(app, compact = false)
                }
                Spacer(Modifier.height(8.dp))
                Transport(app, playing, loading, shuffle, repeat, big = true)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { VolumeControl(app) }
            }
            LyricsPanel(app, t, Modifier.weight(1.1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LyricsPanel(app: AppState, track: Track, modifier: Modifier) {
    val lyrics by produceState<Load<Lyrics?>>(Load.Loading, track.key) {
        value = Load.Loading
        value = load { LyricsService.lyricsFor(track) }
    }
    val position by app.player.position.collectAsState()
    val list = rememberLazyListState()

    Box(modifier) {
        when (val l = lyrics) {
            Load.Loading -> Loading(Modifier.align(Alignment.Center))
            is Load.Failed -> Message("Lyrics unavailable", l.message, Modifier.align(Alignment.Center))
            is Load.Ready -> {
                val found = l.value
                if (found == null || found.lines.isEmpty()) {
                    Message("No lyrics for this song", modifier = Modifier.align(Alignment.Center))
                    return@Box
                }
                // Shown a little early, as the eye reads ahead of the voice.
                val now = position + 250
                val active = if (found.synced) found.lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= now } else -1
                LaunchedEffect(active) {
                    if (active >= 0) list.animateScrollToItem((active - 3).coerceAtLeast(0))
                }
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(top = 80.dp, bottom = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(found.lines) { i, line ->
                        val past = found.synced && i < active
                        val color by animateColorAsState(
                            when {
                                !found.synced -> SquareColors.Ink
                                i == active -> SquareColors.Ink
                                past -> SquareColors.Ink.copy(alpha = 0.45f)
                                else -> SquareColors.Ink.copy(alpha = 0.3f)
                            },
                        )
                        Text(
                            line.text.ifBlank { "♪" },
                            color = color,
                            fontSize = if (found.synced) 30.sp else 22.sp,
                            lineHeight = if (found.synced) 38.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = line.timeMs != null) { line.timeMs?.let(app.player::seek) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    item {
                        Text(
                            "Lyrics from ${found.provider}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquareColors.InkFaint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
