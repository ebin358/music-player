package dev.lelonio.square.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.desktop.Settings
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.spotify.SpotifyMusic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SquareApp(app: AppState, searchFocus: FocusRequester) {
    val current by app.player.current.collectAsState()

    // Reconnect Spotify at launch when there is a stored sign-in.
    LaunchedEffect(Unit) {
        if (Settings.value.spotify != null) app.scope.launch { SpotifyMusic.connect(app.player.output) }
    }

    if (app.needsOnboarding) {
        Onboarding(app)
        return
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(current?.artworkUrl)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                Sidebar(app)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(app)
                    Box(Modifier.weight(1f)) {
                        when (val screen = app.screen) {
                            Screen.Home -> HomeScreen(app)
                            Screen.Search -> SearchScreen(app, searchFocus)
                            Screen.Library -> LibraryScreen(app)
                            Screen.Queue -> QueueScreen(app)
                            Screen.Settings -> SettingsScreen(app)
                            is Screen.Detail -> DetailScreen(app, screen.collection)
                        }
                    }
                }
            }
            PlayerBar(app)
        }

        AnimatedVisibility(
            visible = app.nowPlayingOpen,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
        ) {
            NowPlaying(app)
        }

        ErrorToast(app, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

@Composable
private fun Sidebar(app: AppState) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
            .glass(RoundedCornerShape(18.dp)).padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Room for the window's traffic lights, which sit over the content.
        Spacer(Modifier.height(36.dp))
        Text("Square", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        SourceSwitcher(app)
        Spacer(Modifier.height(10.dp))
        NavItem(Icons.Rounded.Home, "Home", app.screen == Screen.Home) { app.root(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Search", app.screen == Screen.Search) { app.root(Screen.Search) }
        NavItem(Icons.Rounded.LibraryMusic, "Library", app.screen == Screen.Library) { app.root(Screen.Library) }
        NavItem(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", app.screen == Screen.Queue) { app.root(Screen.Queue) }
        Spacer(Modifier.weight(1f))
        NavItem(Icons.Rounded.Settings, "Settings", app.screen == Screen.Settings) { app.root(Screen.Settings) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SourceSwitcher(app: AppState) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(12.dp)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Source.entries.forEach { s ->
            val selected = app.source == s
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(if (selected) SquareColors.GlassStrong else Color.Transparent)
                    .clickable { if (!selected) app.chooseSource(s) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(
                        when (s) {
                            Source.SPOTIFY -> Color(0xFF1ED760)
                            Source.YOUTUBE -> Color(0xFFFF4E45)
                            Source.LOCAL -> SquareColors.InkSoft
                        },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) SquareColors.Ink else SquareColors.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) SquareColors.Glass else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (selected) SquareColors.AccentSoft else SquareColors.InkSoft, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) SquareColors.Ink else SquareColors.InkSoft)
    }
}

@Composable
private fun TopBar(app: AppState) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.canGoBack) {
            IconButton(onClick = app::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = SquareColors.Ink) }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ErrorToast(app: AppState, modifier: Modifier) {
    val error by app.player.error.collectAsState()
    LaunchedEffect(error) {
        if (error != null) {
            delay(6_000)
            app.player.clearError()
        }
    }
    AnimatedVisibility(visible = error != null && !app.nowPlayingOpen, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.glass(RoundedCornerShape(12.dp), strong = true).background(Color(0xCC1A1B20)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 10.dp))
            IconButton(onClick = app.player::clearError) { Icon(Icons.Rounded.Close, "Dismiss", tint = SquareColors.InkSoft) }
        }
    }
}
