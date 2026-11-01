package dev.lelonio.square.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.desktop.Settings
import dev.lelonio.square.desktop.local.LocalLibrary
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.CollectionKind
import dev.lelonio.square.desktop.model.CollectionPage
import dev.lelonio.square.desktop.model.SearchResults
import dev.lelonio.square.desktop.model.Shelf
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import dev.lelonio.square.desktop.spotify.SpotifyMusic
import dev.lelonio.square.desktop.youtube.YouTubeMusic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

const val LOCAL_LIKED = "local-liked"
const val LOCAL_ALL = "local-all"

private val PagePadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp)

@Composable
private fun PageHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = SquareColors.InkSoft) }
            Text(title, style = MaterialTheme.typography.displaySmall)
        }
        action?.invoke()
    }
}

/** Shown wherever Spotify is the source but not signed in. */
@Composable
fun SpotifyGate(app: AppState, content: @Composable () -> Unit) {
    val state by SpotifyMusic.state.collectAsState()
    when (val s = state) {
        is SpotifyMusic.State.Connected -> content()
        SpotifyMusic.State.Connecting -> Column {
            Loading()
            Text("Connecting to Spotify…", color = SquareColors.InkSoft, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        SpotifyMusic.State.SignedOut -> Message(
            "Sign in to Spotify",
            "Square plays Spotify through your own Premium account.",
            action = "Sign in" to { app.scope.launch { SpotifyMusic.signIn(app.player.output) } },
        )
        is SpotifyMusic.State.Failed -> Message(
            "Spotify isn't connected",
            s.message,
            action = "Try again" to {
                app.scope.launch {
                    if (Settings.value.spotify != null) SpotifyMusic.connect(app.player.output) else SpotifyMusic.signIn(app.player.output)
                }
            },
        )
    }
}

// --- Home ------------------------------------------------------------------

@Composable
fun HomeScreen(app: AppState) {
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    when (app.source) {
        Source.SPOTIFY -> SpotifyGate(app) { ShelvesPage(app, greeting) { SpotifyMusic.home() } }
        Source.YOUTUBE -> ShelvesPage(app, greeting) { YouTubeMusic.home() }
        Source.LOCAL -> LocalHome(app, greeting)
    }
}

@Composable
private fun ShelvesPage(app: AppState, greeting: String, fetch: suspend () -> List<Shelf>) {
    val state by produceState<Load<List<Shelf>>>(Load.Loading, app.source, app.refreshKey) {
        value = Load.Loading
        value = load(fetch)
    }
    LazyColumn(contentPadding = PagePadding) {
        item {
            PageHeader(greeting, app.source.label) {
                IconButton(onClick = app::refresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = SquareColors.InkSoft) }
            }
        }
        when (val s = state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { Message("Couldn't load your home page", s.message, action = "Try again" to app::refresh) }
            is Load.Ready -> {
                val liked = app.settings.likedTracks.filter { it.source == app.source }
                if (liked.isNotEmpty()) item { ShelfRow(Shelf("Liked on this Mac", tracks = liked.take(20)), app) }
                items(s.value, key = { it.title }) { shelf -> ShelfRow(shelf, app) }
                if (s.value.isEmpty()) item { Message("Nothing here yet", "Search for something to play.") }
            }
        }
    }
}

@Composable
private fun LocalHome(app: AppState, greeting: String) {
    val current by app.player.current.collectAsState()
    val folders = app.settings.localFolders
    val state by produceState<Load<List<Track>>>(Load.Loading, folders, app.refreshKey) {
        value = Load.Loading
        value = load { LocalLibrary.scan(folders) }
    }
    LazyColumn(contentPadding = PagePadding) {
        item { PageHeader(greeting, "Music on this Mac") }
        if (folders.isEmpty()) {
            item {
                Message(
                    "Add a music folder",
                    "Square plays MP3, AAC, FLAC, Ogg and WAV files from folders you choose.",
                    action = "Add folder…" to { chooseFolder()?.let { f -> Settings.update { it.copy(localFolders = it.localFolders + f) } } },
                )
            }
        } else {
            when (val s = state) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { Message("Couldn't read your folders", s.message) }
                is Load.Ready -> {
                    item {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayButtons(app, s.value, null)
                        }
                    }
                    itemsIndexed(s.value, key = { _, t -> t.key }) { i, track ->
                        TrackRow(track, i + 1, app, current?.key == track.key, onPlay = { app.player.play(s.value, i) })
                    }
                    if (s.value.isEmpty()) item { Message("No music files found", folders.joinToString("\n")) }
                }
            }
        }
    }
}

/** A native folder picker. */
fun chooseFolder(): String? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a music folder", java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        java.io.File(dir, file).absolutePath
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}

// --- Search ----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(app: AppState, focus: FocusRequester) {
    val current by app.player.current.collectAsState()
    var submitted by remember(app.source) { mutableStateOf(app.searchQuery.trim()) }

    LaunchedEffect(app.searchQuery) {
        val q = app.searchQuery.trim()
        if (q.isEmpty()) {
            submitted = ""
            return@LaunchedEffect
        }
        delay(450)
        submitted = q
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val results by produceState<Load<SearchResults>?>(null, submitted, app.source) {
        if (submitted.isEmpty()) {
            value = null
            return@produceState
        }
        value = Load.Loading
        value = load {
            when (app.source) {
                Source.YOUTUBE -> YouTubeMusic.search(submitted)
                Source.SPOTIFY -> SpotifyMusic.search(submitted)
                Source.LOCAL -> {
                    val q = submitted.lowercase()
                    SearchResults(
                        tracks = LocalLibrary.scan(app.settings.localFolders).filter {
                            q in it.title.lowercase() || q in it.artist.lowercase() || q in (it.album?.lowercase() ?: "")
                        },
                    )
                }
            }
        }
        if (value is Load.Ready) app.rememberSearch(submitted)
    }

    LazyColumn(contentPadding = PagePadding) {
        item { PageHeader("Search", app.source.label) }
        item {
            TextField(
                value = app.searchQuery,
                onValueChange = { app.searchQuery = it },
                placeholder = { Text("Songs, artists, albums, playlists") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (app.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { app.searchQuery = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SquareColors.GlassStrong,
                    unfocusedContainerColor = SquareColors.Glass,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = SquareColors.AccentSoft,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .focusRequester(focus)
                    .onKeyEvent { e ->
                        if (e.key == Key.Enter) {
                            submitted = app.searchQuery.trim()
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        if (app.source == Source.SPOTIFY && !SpotifyMusic.isConnected) {
            item { SpotifyGate(app) {} }
            return@LazyColumn
        }

        when (val r = results) {
            null -> {
                val recent = app.settings.recentSearches
                if (recent.isNotEmpty()) {
                    item { SectionTitle("Recent searches", Modifier.padding(horizontal = 20.dp)) }
                    item {
                        FlowRow(
                            Modifier.padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recent.forEach { q ->
                                ContextMenuArea(items = {
                                    listOf(ContextMenuItem("Remove") {
                                        Settings.update { s -> s.copy(recentSearches = s.recentSearches - q) }
                                    })
                                }) {
                                    Pill(q, false, onClick = { app.searchQuery = q; submitted = q }, icon = Icons.Rounded.History)
                                }
                            }
                        }
                    }
                }
            }

            Load.Loading -> item { Loading() }
            is Load.Failed -> item { Message("Search failed", r.message) }
            is Load.Ready -> {
                val res = r.value
                if (res.isEmpty) item { Message("No results for “$submitted”") }
                if (res.tracks.isNotEmpty()) {
                    item { SectionTitle("Songs", Modifier.padding(horizontal = 20.dp)) }
                    val songs = res.tracks.take(8)
                    itemsIndexed(songs, key = { _, t -> "s:" + t.key }) { i, track ->
                        TrackRow(track, null, app, current?.key == track.key, onPlay = { app.player.play(songs, i) })
                    }
                }
                listOf("Artists" to res.artists, "Albums" to res.albums, "Playlists" to res.playlists)
                    .filter { it.second.isNotEmpty() }
                    .forEach { (title, list) -> item(key = title) { ShelfRow(Shelf(title, collections = list), app) } }
            }
        }
    }
}

// --- Library ---------------------------------------------------------------

@Composable
fun LibraryScreen(app: AppState) {
    val source = app.source
    val spotifyState by SpotifyMusic.state.collectAsState()
    val remote by produceState<Load<List<Collection>>>(Load.Loading, source, spotifyState, app.refreshKey) {
        value = if (source == Source.SPOTIFY && spotifyState is SpotifyMusic.State.Connected) {
            load { SpotifyMusic.library() }
        } else {
            Load.Ready(emptyList())
        }
    }
    val liked = app.settings.likedTracks.filter { it.source == source }
    val saved = app.settings.savedCollections.filter { it.source == source }

    LazyColumn(contentPadding = PagePadding) {
        item { PageHeader("Library", source.label) }

        val pinned = buildList {
            if (source != Source.SPOTIFY || liked.isNotEmpty()) {
                add(Collection(LOCAL_LIKED, source, CollectionKind.LIKED, "Liked on this Mac", "${liked.size} songs"))
            }
            if (source == Source.LOCAL) add(Collection(LOCAL_ALL, source, CollectionKind.PLAYLIST, "All songs", "From your folders"))
        }
        val all = pinned + (remote as? Load.Ready)?.value.orEmpty() + saved
        if (source == Source.SPOTIFY && spotifyState !is SpotifyMusic.State.Connected) {
            item { SpotifyGate(app) {} }
        }
        if (remote is Load.Loading && source == Source.SPOTIFY && spotifyState is SpotifyMusic.State.Connected) item { Loading() }
        (remote as? Load.Failed)?.let { f -> item { Message("Couldn't load your library", f.message, action = "Try again" to app::refresh) } }

        val rows = all.chunked(5)
        items(rows.size) { row ->
            Row(Modifier.padding(horizontal = 12.dp)) {
                rows[row].forEach { c -> CollectionTile(c, app) }
            }
        }
        if (all.isEmpty() && remote !is Load.Loading) {
            item { Message("Your library is empty", "Right-click an album or playlist and choose Save to library.") }
        }
    }
}

// --- Album, playlist, artist -----------------------------------------------

@Composable
fun DetailScreen(app: AppState, collection: Collection) {
    val current by app.player.current.collectAsState()
    val state by produceState<Load<CollectionPage>>(Load.Loading, collection.key, app.refreshKey, app.settings.likedTracks) {
        if (value !is Load.Ready) value = Load.Loading
        value = load {
            when {
                collection.id == LOCAL_LIKED -> CollectionPage(
                    collection,
                    app.settings.likedTracks.filter { it.source == collection.source },
                )
                collection.id == LOCAL_ALL -> CollectionPage(collection, LocalLibrary.scan(app.settings.localFolders))
                collection.source == Source.YOUTUBE -> YouTubeMusic.open(collection)
                collection.source == Source.SPOTIFY -> SpotifyMusic.open(collection)
                else -> CollectionPage(collection, emptyList())
            }
        }
    }

    LazyColumn(contentPadding = PagePadding) {
        when (val s = state) {
            Load.Loading -> item { Loading(Modifier.padding(top = 80.dp)) }
            is Load.Failed -> item { Message("Couldn't open “${collection.title}”", s.message, action = "Try again" to app::refresh) }
            is Load.Ready -> {
                val page = s.value
                item { DetailHeader(app, page) }
                val numbered = page.collection.kind == CollectionKind.ALBUM
                itemsIndexed(page.tracks, key = { i, t -> "$i:${t.key}" }) { i, track ->
                    TrackRow(
                        track,
                        if (numbered) i + 1 else null,
                        app,
                        current?.key == track.key,
                        onPlay = { app.player.play(page.tracks, i, page.collection) },
                        showAlbum = !numbered,
                    )
                }
                if (page.tracks.isEmpty() && page.shelves.isEmpty()) item { Message("Nothing to play here") }
                items(page.shelves, key = { "shelf:" + it.title }) { shelf -> ShelfRow(shelf, app) }
                page.description?.takeIf { it.isNotBlank() }?.let { d ->
                    item {
                        Column(Modifier.padding(20.dp)) {
                            SectionTitle("About")
                            Text(d, style = MaterialTheme.typography.bodyMedium, color = SquareColors.InkSoft, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(app: AppState, page: CollectionPage) {
    val c = page.collection
    val round = c.kind == CollectionKind.ARTIST
    val total = page.tracks.sumOf { it.durationMs }
    Row(
        Modifier.fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Artwork(
            c.artworkUrl ?: page.tracks.firstOrNull()?.artworkUrl,
            Modifier.size(200.dp),
            if (round) CircleShape else RoundedCornerShape(14.dp),
            placeholder = if (c.kind == CollectionKind.LIKED) Icons.Rounded.Favorite else Icons.Rounded.PlayArrow,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (c.kind) {
                    CollectionKind.ALBUM -> "Album"
                    CollectionKind.ARTIST -> "Artist"
                    CollectionKind.LIKED -> "Songs"
                    CollectionKind.PLAYLIST -> "Playlist"
                },
                style = MaterialTheme.typography.labelLarge,
                color = SquareColors.InkSoft,
            )
            Text(c.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                c.subtitle,
                page.tracks.size.takeIf { it > 0 }?.let { "$it songs" },
                total.takeIf { it > 0 }?.let { formatLong(it) },
            ).distinct().joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = SquareColors.InkSoft)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButtons(app, page.tracks, c)
                if (c.id != LOCAL_LIKED && c.id != LOCAL_ALL && c.kind != CollectionKind.LIKED) {
                    val saved = app.isSaved(c)
                    IconButton(onClick = { app.toggleSaved(c) }) {
                        Icon(
                            if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (saved) "Remove from library" else "Save to library",
                            tint = if (saved) SquareColors.AccentSoft else SquareColors.InkSoft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayButtons(app: AppState, tracks: List<Track>, context: Collection?) {
    Button(
        onClick = {
            if (app.player.shuffle.value) app.player.toggleShuffle()
            app.player.play(tracks, 0, context)
        },
        enabled = tracks.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = SquareColors.Accent),
        shape = RoundedCornerShape(50),
    ) {
        Icon(Icons.Rounded.PlayArrow, null)
        Spacer(Modifier.width(4.dp))
        Text("Play")
    }
    OutlinedButton(
        onClick = {
            if (!app.player.shuffle.value) app.player.toggleShuffle()
            app.player.play(tracks, tracks.indices.random(), context)
        },
        enabled = tracks.isNotEmpty(),
        shape = RoundedCornerShape(50),
    ) {
        Icon(Icons.Rounded.Shuffle, null, tint = SquareColors.Ink)
        Spacer(Modifier.width(4.dp))
        Text("Shuffle", color = SquareColors.Ink)
    }
}

private fun formatLong(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

// --- Queue -----------------------------------------------------------------

@Composable
fun QueueScreen(app: AppState) {
    val queue by app.player.queue.collectAsState()
    val index by app.player.index.collectAsState()
    LazyColumn(contentPadding = PagePadding) {
        item { PageHeader("Queue", if (queue.isEmpty()) null else "${queue.size} songs") }
        if (queue.isEmpty()) {
            item { Message("Nothing queued", "Play something, or right-click a song and choose Add to queue.") }
            return@LazyColumn
        }
        queue.getOrNull(index)?.let { now ->
            item { SectionTitle("Now playing", Modifier.padding(horizontal = 20.dp)) }
            item { TrackRow(now, null, app, true, onPlay = {}, showSource = true) }
        }
        if (index + 1 < queue.size) item { SectionTitle("Up next", Modifier.padding(horizontal = 20.dp)) }
        itemsIndexed(queue.drop(index + 1), key = { i, t -> "${index + 1 + i}:${t.key}" }) { i, track ->
            val at = index + 1 + i
            ContextMenuArea(items = { listOf(ContextMenuItem("Remove from queue") { app.player.removeFromQueue(at) }) }) {
                TrackRow(track, null, app, false, onPlay = { app.player.jumpTo(at) }, showSource = true)
            }
        }
    }
}

// --- Settings --------------------------------------------------------------

@Composable
fun SettingsScreen(app: AppState) {
    val spotify by SpotifyMusic.state.collectAsState()
    val settings = app.settings
    LazyColumn(contentPadding = PagePadding) {
        item { PageHeader("Settings") }

        item {
            SettingsGroup("Music source") {
                Text("Where Home, Search and Library come from. Your queue can mix sources.", color = SquareColors.InkSoft, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Source.entries.forEach { s -> Pill(s.label, app.source == s, onClick = { app.chooseSource(s) }) }
                }
            }
        }

        item {
            SettingsGroup("Spotify") {
                when (val s = spotify) {
                    is SpotifyMusic.State.Connected -> {
                        Text("Signed in${if (s.username.isNotBlank()) " as ${s.username}" else ""}. This Mac shows up in Spotify Connect.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { app.scope.launch { SpotifyMusic.signOut() } }) { Text("Sign out", color = SquareColors.Ink) }
                    }
                    SpotifyMusic.State.Connecting -> Text("Connecting…", color = SquareColors.InkSoft)
                    SpotifyMusic.State.SignedOut -> {
                        Text("Needs a Spotify Premium account. Your browser opens to sign in.", color = SquareColors.InkSoft, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { app.scope.launch { SpotifyMusic.signIn(app.player.output) } }, colors = ButtonDefaults.buttonColors(containerColor = SquareColors.Accent)) { Text("Sign in with Spotify") }
                    }
                    is SpotifyMusic.State.Failed -> {
                        Text(s.message, color = SquareColors.Danger, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { app.scope.launch { SpotifyMusic.signIn(app.player.output) } }, colors = ButtonDefaults.buttonColors(containerColor = SquareColors.Accent)) { Text("Sign in again") }
                            if (settings.spotify != null) OutlinedButton(onClick = { app.scope.launch { SpotifyMusic.signOut() } }) { Text("Sign out", color = SquareColors.Ink) }
                        }
                    }
                }
                Text("Streaming quality", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(96 to "Low", 160 to "Normal", 320 to "Very high").forEach { (kbps, label) ->
                        Pill("$label · $kbps kbps", settings.spotifyBitrate == kbps, onClick = { Settings.update { it.copy(spotifyBitrate = kbps) } })
                    }
                }
                Text("Takes effect the next time Square starts.", color = SquareColors.InkFaint, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SettingsGroup("Music on this Mac") {
                if (settings.localFolders.isEmpty()) Text("No folders yet.", color = SquareColors.InkSoft, style = MaterialTheme.typography.bodyMedium)
                settings.localFolders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Folder, null, tint = SquareColors.InkSoft, modifier = Modifier.size(18.dp))
                        Text(folder, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { Settings.update { it.copy(localFolders = it.localFolders - folder) } }) {
                            Icon(Icons.Rounded.Close, "Remove $folder", tint = SquareColors.InkSoft)
                        }
                    }
                }
                OutlinedButton(onClick = { chooseFolder()?.let { f -> Settings.update { it.copy(localFolders = (it.localFolders + f).distinct()) } } }) {
                    Text("Add folder…", color = SquareColors.Ink)
                }
            }
        }

        item {
            SettingsGroup("Playback") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Autoplay", style = MaterialTheme.typography.titleSmall)
                        Text("When the queue ends, keep going with similar YouTube Music songs.", color = SquareColors.InkSoft, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = settings.autoplay,
                        onCheckedChange = { on -> Settings.update { it.copy(autoplay = on) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = SquareColors.Accent),
                    )
                }
            }
        }

        item {
            SettingsGroup("About") {
                Text("Square for Mac 1.0 — a port of Square (github.com/Lelonio/Square), GPL-3.0.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Not affiliated with Spotify or Google. Spotify playback re-implements Spotify's protocol, which its terms forbid, and YouTube Music is read through private endpoints. Use at your own risk.",
                    color = SquareColors.InkFaint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).glass().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

// --- First launch ------------------------------------------------------------

@Composable
fun Onboarding(app: AppState) {
    Box(Modifier.fillMaxSize().background(SquareColors.Floor), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Square", style = MaterialTheme.typography.displaySmall)
            Text("Where should the music come from?", style = MaterialTheme.typography.titleLarge, color = SquareColors.InkSoft)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SourceCard("YouTube Music", "Plays straight away. No account needed.") { app.chooseSource(Source.YOUTUBE) }
                SourceCard("Spotify", "Your own Premium account, signed in through your browser.") {
                    app.chooseSource(Source.SPOTIFY)
                    if (Settings.value.spotify == null) app.scope.launch { SpotifyMusic.signIn(app.player.output) }
                }
                SourceCard("This Mac", "Music files in folders you choose.") { app.chooseSource(Source.LOCAL) }
            }
            Text("You can change this any time in Settings.", color = SquareColors.InkFaint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SourceCard(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier.width(220.dp).glass(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SquareColors.InkSoft)
    }
}

@Composable
fun HorizontalTracks(app: AppState, tracks: List<Track>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) {
        itemsIndexed(tracks) { i, t -> TrackTile(t, app, onPlay = { app.player.play(tracks, i) }) }
    }
}

@Suppress("unused")
private fun Modifier.roundClip() = this.clip(CircleShape)
