package ch.etasystems.amsel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ch.etasystems.amsel.ui.theme.AmselTheme
import ch.etasystems.amsel.ui.compare.CompareScreen
import ch.etasystems.amsel.core.classifier.BirdNetBridge
import ch.etasystems.amsel.data.SpeciesRegistry
import java.io.File

fun main() {
    // Shutdown-Hook: BirdNET Daemon sauber beenden wenn App schliesst
    BirdNetBridge.registerShutdownHook()

    // Species Master Table initialisieren (kopiert aus JAR falls noetig)
    val amselDataDir = File(System.getProperty("user.home"), "Documents/AMSEL")
    SpeciesRegistry.initialize(amselDataDir)

    application {
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "AMSEL — Another Mel Spectrogram Event Locator",
        state = windowState
    ) {
        AmselTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                CompareScreen(awtWindow = window)
            }
        }
    }
}}
