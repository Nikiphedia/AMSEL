package ch.etasystems.amsel.ui.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import ch.etasystems.amsel.ui.theme.AmselTheme

/**
 * Wrapper fuer ein abdockbares Sidebar-Panel.
 *
 * Wenn eingedockt: rendert [content] inline mit einem + Button oben rechts.
 * Wenn abgedockt: rendert [UndockedPlaceholder] inline und oeffnet [content] als eigenes Fenster.
 *
 * @param state UndockPanelState der den Dock/Undock-Zustand verwaltet
 * @param modifier Modifier fuer den eingedockten Container
 * @param content Der eigentliche Panel-Inhalt
 */
@Composable
fun UndockablePanel(
    state: UndockPanelState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (state.isUndocked) {
        // --- Platzhalter in der Sidebar ---
        UndockedPlaceholder(
            title = state.title,
            onClick = { /* TODO: toFront() — AWT-Window-Referenz noetig, siehe Handover */ }
        )

        // --- Eigenes Fenster ---
        Window(
            onCloseRequest = { state.dock() },
            title = "AMSEL \u2014 ${state.title}",
            state = rememberWindowState(
                size = DpSize(state.initialWidth.dp, state.initialHeight.dp),
                position = WindowPosition.PlatformDefault
            ),
            resizable = true,
            alwaysOnTop = false
        ) {
            AmselTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        // Dock-Button Header
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = { state.dock() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Andocken",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        // Panel-Inhalt
                        Box(modifier = Modifier.weight(1f)) {
                            content()
                        }
                    }
                }
            }
        }
    } else {
        // --- Eingedockter Modus ---
        Box(modifier = modifier) {
            content()
            // + Button oben rechts
            IconButton(
                onClick = { state.undock() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .padding(2.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Abdocken",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Platzhalter in der Sidebar wenn Panel abgedockt ist.
 * Klick soll das Fenster in den Vordergrund bringen (TODO: toFront() in AP-35/36).
 */
@Composable
fun UndockedPlaceholder(
    title: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
