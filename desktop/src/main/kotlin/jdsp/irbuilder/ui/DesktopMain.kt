package jdsp.irbuilder.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "J_DSP IR Builder — phase-only band alignment",
        state = rememberWindowState(size = DpSize(1480.dp, 940.dp)),
    ) {
        JdspTheme {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(scope) }
            App(state)
        }
    }
}
