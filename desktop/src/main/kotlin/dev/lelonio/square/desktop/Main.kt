package dev.lelonio.square.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lelonio.square.desktop.audio.DesktopAudioOutput
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.player.PlayerController
import dev.lelonio.square.desktop.ui.AppState
import dev.lelonio.square.desktop.ui.Screen
import dev.lelonio.square.desktop.ui.SquareApp
import dev.lelonio.square.desktop.ui.SquareTheme
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--selftest" in args) exitProcess(SelfTest.run())

    // The menu bar at the top of the screen, as every Mac app has.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Square")

    val output = DesktopAudioOutput()
    val player = PlayerController(output)

    application {
        val app = remember { AppState(player) }
        val searchFocus = remember { FocusRequester() }
        val windowState = rememberWindowState(
            size = DpSize(1240.dp, 820.dp),
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        )

        Window(
            onCloseRequest = {
                player.shutdown()
                exitApplication()
            },
            state = windowState,
            title = "Square",
            onKeyEvent = { e ->
                // Space toggles playback when nothing else (a text field) took it.
                if (e.type == KeyEventType.KeyDown && e.key == Key.Spacebar) {
                    player.toggle()
                    true
                } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && (app.nowPlayingOpen || app.canGoBack)) {
                    app.back()
                    true
                } else {
                    false
                }
            },
        ) {
            // Content under a transparent title bar, traffic lights over the sidebar.
            LaunchedEffect(Unit) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                window.minimumSize = java.awt.Dimension(980, 640)
            }

            MenuBar {
                Menu("Playback") {
                    Item("Play / Pause", onClick = player::toggle, shortcut = KeyShortcut(Key.P, meta = true))
                    Item("Next", onClick = player::next, shortcut = KeyShortcut(Key.DirectionRight, meta = true))
                    Item("Previous", onClick = player::previous, shortcut = KeyShortcut(Key.DirectionLeft, meta = true))
                    Separator()
                    Item("Volume Up", onClick = { player.setVolume(player.volume.value + 0.1f) }, shortcut = KeyShortcut(Key.DirectionUp, meta = true))
                    Item("Volume Down", onClick = { player.setVolume(player.volume.value - 0.1f) }, shortcut = KeyShortcut(Key.DirectionDown, meta = true))
                    Separator()
                    Item("Shuffle", onClick = player::toggleShuffle, shortcut = KeyShortcut(Key.S, meta = true))
                    Item("Repeat", onClick = player::cycleRepeat, shortcut = KeyShortcut(Key.R, meta = true))
                }
                Menu("Go") {
                    Item("Home", onClick = { app.root(Screen.Home) }, shortcut = KeyShortcut(Key.One, meta = true))
                    Item("Search", onClick = {
                        app.root(Screen.Search)
                        runCatching { searchFocus.requestFocus() }
                    }, shortcut = KeyShortcut(Key.F, meta = true))
                    Item("Library", onClick = { app.root(Screen.Library) }, shortcut = KeyShortcut(Key.Two, meta = true))
                    Item("Queue", onClick = { app.root(Screen.Queue) }, shortcut = KeyShortcut(Key.Three, meta = true))
                    Item("Now Playing & Lyrics", onClick = { app.nowPlayingOpen = !app.nowPlayingOpen }, shortcut = KeyShortcut(Key.L, meta = true))
                    Separator()
                    Item("Back", onClick = app::back, shortcut = KeyShortcut(Key.LeftBracket, meta = true))
                }
                Menu("Source") {
                    Source.entries.forEach { s ->
                        CheckboxItem(s.label, checked = app.source == s, onCheckedChange = { app.chooseSource(s) })
                    }
                }
            }

            SquareTheme { SquareApp(app, searchFocus) }
        }
    }
}
