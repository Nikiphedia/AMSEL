package ch.etasystems.amsel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPosition
import ch.etasystems.amsel.ui.theme.AmselTheme
import ch.etasystems.amsel.ui.compare.CompareScreen
import ch.etasystems.amsel.ui.settings.SetupDialog
import ch.etasystems.amsel.core.classifier.BirdNetBridge
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.RegionSetRegistry
import ch.etasystems.amsel.data.SpeciesRegistry
import java.io.File

fun main() {
    // Shutdown-Hook: BirdNET Daemon sauber beenden wenn App schliesst
    BirdNetBridge.registerShutdownHook()

    // Species Master Table initialisieren (kopiert aus JAR falls noetig)
    val amselDataDir = File(System.getProperty("user.home"), "Documents/AMSEL")
    SpeciesRegistry.initialize(amselDataDir)
    RegionSetRegistry.initialize(amselDataDir)

    application {
    val initialSettings = remember { SettingsStore.load() }
    // Offscreen-Check: Pruefen ob gespeicherte Position auf sichtbarem Monitor liegt
    val safePosition = remember(initialSettings.windowX, initialSettings.windowY) {
        if (initialSettings.windowX >= 0 && initialSettings.windowY >= 0) {
            val screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices.flatMap { it.configurations.map { c -> c.bounds } }
            val onScreen = screenBounds.any { bounds ->
                bounds.contains(initialSettings.windowX, initialSettings.windowY)
            }
            if (onScreen) WindowPosition(initialSettings.windowX.dp, initialSettings.windowY.dp)
            else WindowPosition.PlatformDefault
        } else WindowPosition.PlatformDefault
    }
    val windowState = rememberWindowState(
        width = if (initialSettings.windowWidth > 0) initialSettings.windowWidth.dp else 1400.dp,
        height = if (initialSettings.windowHeight > 0) initialSettings.windowHeight.dp else 900.dp,
        position = safePosition
    )
    var settings by remember { mutableStateOf(initialSettings) }
    var showSetup by remember { mutableStateOf(!settings.setupComplete) }

    Window(
        onCloseRequest = {
            // Fenster-Position und -Groesse speichern
            val currentSettings = SettingsStore.load()
            val posX = windowState.position.let { pos ->
                if (pos.isSpecified) pos.x.value.toInt() else -1
            }
            val posY = windowState.position.let { pos ->
                if (pos.isSpecified) pos.y.value.toInt() else -1
            }
            val updatedSettings = currentSettings.copy(
                windowWidth = windowState.size.width.value.toInt(),
                windowHeight = windowState.size.height.value.toInt(),
                windowX = posX,
                windowY = posY
            )
            SettingsStore.save(updatedSettings)
            exitApplication()
        },
        title = "AMSEL — Another Mel Spectrogram Event Locator",
        state = windowState
    ) {
        AmselTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                if (showSetup) {
                    SetupDialog(
                        initialSettings = settings,
                        onComplete = { newSettings ->
                            SettingsStore.save(newSettings)
                            settings = newSettings
                            showSetup = false
                        },
                        onDismiss = {
                            // Bei Abbrechen trotzdem als complete markieren (Default-Ordner)
                            val updated = settings.copy(setupComplete = true)
                            SettingsStore.save(updated)
                            settings = updated
                            showSetup = false
                        }
                    )
                } else {
                    CompareScreen(awtWindow = window)
                }
            }
        }
    }
}}
