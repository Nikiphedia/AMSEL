package ch.etasystems.amsel.ui.compare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Dialog: Vergleichsdatei oder Referenz-Sonogramm importieren.
 * Tab 1: Lokale Datei laden
 * Tab 2: Art in XC-Datenbank suchen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareImportDialog(
    availableSpecies: List<String>,
    onImportFile: (File) -> Unit,
    onSearchSpecies: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Referenz importieren") },
        text = {
            Column(modifier = Modifier.width(400.dp)) {
                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Datei laden") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Art suchen") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // Tab 1: Lokale Datei
                        Text(
                            "Audiodatei als Vergleichs-Sonogramm laden (WAV, MP3, FLAC, M4A)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            val file = showFileChooser()
                            if (file != null) onImportFile(file)
                        }) {
                            Text("Datei waehlen...")
                        }
                    }
                    1 -> {
                        // Tab 2: Art suchen in XC-Datenbank
                        Text(
                            "Referenz-Sonogramme aus der Offline-Datenbank laden",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = {
                                    searchText = it
                                    showSuggestions = it.length >= 2
                                },
                                label = { Text("Art (deutsch, englisch oder lateinisch)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Autocomplete
                            if (showSuggestions && searchText.length >= 2) {
                                val suggestions = availableSpecies.filter {
                                    it.contains(searchText, ignoreCase = true)
                                }.take(8)
                                if (suggestions.isNotEmpty()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().offset(y = 60.dp),
                                        shadowElevation = 8.dp,
                                        color = MaterialTheme.colorScheme.surface
                                    ) {
                                        Column {
                                            suggestions.forEach { species ->
                                                Text(
                                                    species,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            searchText = species
                                                            showSuggestions = false
                                                        }
                                                        .padding(12.dp),
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (searchText.isNotBlank()) onSearchSpecies(searchText)
                            },
                            enabled = searchText.isNotBlank()
                        ) {
                            Text("Sonogramme laden")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

internal fun showFileChooser(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Datei oeffnen (Audio oder Sonogramm-Bild)"
        addChoosableFileFilter(FileNameExtensionFilter(
            "Audio-Dateien (WAV, MP3, FLAC, OGG, M4A)",
            "wav", "mp3", "flac", "ogg", "m4a", "aac"
        ))
        addChoosableFileFilter(FileNameExtensionFilter(
            "Sonogramm-Bilder (PNG, JPG)",
            "png", "jpg", "jpeg"
        ))
        fileFilter = choosableFileFilters[0]  // Default: Audio
        isAcceptAllFileFilterUsed = true
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}
