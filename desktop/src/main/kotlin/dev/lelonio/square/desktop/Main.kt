package dev.lelonio.square.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--selftest" in args) exitProcess(SelfTest.run())

    application {
        Window(onCloseRequest = ::exitApplication, title = "Square") {
            Text("Square")
        }
    }
}
