package ch.etasystems.amsel.ui.sonogram

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Toolbar mit Import, Playback, Zoom, Auswahl, Markierung, Filter,
 * Vergleichsdatei, Farbpaletten und Auto-Erkennung.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SonogramToolbar(
    hasAudio: Boolean,
    selectionMode: Boolean,
    hasSelection: Boolean,
    hasActiveAnnotation: Boolean = false,
    annotationCount: Int = 0,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    playbackPositionText: String = "",
    filterPanelVisible: Boolean = false,
    onImport: () -> Unit,
    onClose: () -> Unit = {},
    onToggleSelection: () -> Unit,
    onCreateAnnotation: () -> Unit = {},
    onToggleFilter: () -> Unit,
    onSearch: () -> Unit = {},
    onSync: () -> Unit = {},
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    onExport: () -> Unit = {},
    exportBlackAndWhite: Boolean = false,
    onToggleExportBW: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onZoomReset: () -> Unit = {},
    onFreqZoomIn: () -> Unit = {},
    onFreqZoomOut: () -> Unit = {},
    freqZoom: Float = 1f,
    onToggleFullView: () -> Unit = {},
    isFullView: Boolean = false,
    onPlayPause: () -> Unit = {},
    onStop: () -> Unit = {},
    onDetectEvents: () -> Unit = {},
    onNormalize: () -> Unit = {},
    onImportCompare: () -> Unit = {},
    hasCompareFile: Boolean = false,
    hasApiKey: Boolean = false,
    cachedCount: Int = 0,
    onApiKeySettings: () -> Unit = {},
    onDownloadSettings: () -> Unit = {},
    onExportSettings: () -> Unit = {},
    onComparisonSettings: () -> Unit = {},
    onReferenceEditor: () -> Unit = {},
    onSettings: () -> Unit = {},
    // Tabs in der Toolbar
    selectedTab: Int = -1,
    onTabSelected: (Int) -> Unit = {},
    filterActive: Boolean = false,
    ansichtActive: Boolean = false,
    volumeActive: Boolean = false,
    onBypassBearbeitung: () -> Unit = {},
    onBypassAnsicht: () -> Unit = {},
    onBypassVolume: () -> Unit = {},
    // Projekt
    onLoadProject: () -> Unit = {},
    onSaveProject: () -> Unit = {},
    hasProject: Boolean = false,
    projectDirty: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Import
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Audiodatei oeffnen (WAV, MP3, FLAC)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onImport) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Audio importieren")
                }
            }

            // Datei schliessen
            if (hasAudio) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Datei schliessen") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Schliessen")
                    }
                }
            }

            // Projekt laden
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("AMSEL-Projekt laden (.amsel.json)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onLoadProject) {
                    Icon(
                        Icons.Default.FileOpen,
                        contentDescription = "Projekt laden",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Projekt speichern (nur sichtbar wenn Audio geladen)
            if (hasAudio) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(if (hasProject) "Projekt speichern" else "Als Projekt speichern (.amsel.json)") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onSaveProject) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Projekt speichern",
                            tint = if (projectDirty) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Vergleichsdatei importieren
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Zweite Datei zum Vergleichen laden") } },
                state = rememberTooltipState()
            ) {
                IconButton(
                    onClick = onImportCompare,
                    enabled = hasAudio
                ) {
                    Icon(
                        Icons.Default.AddChart,
                        contentDescription = "Vergleichsdatei laden",
                        tint = if (hasCompareFile) Color(0xFF4CAF50)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Playback-Controls
            IconButton(onClick = onPlayPause, enabled = hasAudio) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    tint = if (isPlaying) Color(0xFF4CAF50)
                    else if (isPaused) Color(0xFFFFC107)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onStop, enabled = isPlaying || isPaused) {
                Icon(Icons.Default.Stop, contentDescription = "Stopp")
            }

            if (playbackPositionText.isNotEmpty()) {
                Text(
                    playbackPositionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Zoom-Controls
            IconButton(onClick = onZoomIn, enabled = hasAudio) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Hineinzoomen")
            }
            IconButton(onClick = onZoomOut, enabled = hasAudio) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Herauszoomen")
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Ganze Aufnahme anzeigen") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onZoomReset, enabled = hasAudio) {
                    Icon(Icons.Default.FitScreen, contentDescription = "Ganze Aufnahme")
                }
            }

            // Frequenz-Zoom (F+ / F-)
            Spacer(modifier = Modifier.width(2.dp))
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Frequenz-Zoom: Darstellung vergroessern") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onFreqZoomIn, enabled = hasAudio && freqZoom < 8f,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("F+", style = MaterialTheme.typography.labelSmall,
                         color = if (freqZoom > 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Frequenz-Zoom zuruecksetzen") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onFreqZoomOut, enabled = freqZoom > 1f,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("F-", style = MaterialTheme.typography.labelSmall,
                         color = if (freqZoom > 1f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Full View (Sonogramm volle Hoehe)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Vollansicht: Sonogramm maximiert") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onToggleFullView, enabled = hasAudio,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (isFullView) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Vollansicht",
                        tint = if (isFullView) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Auswahl-Modus
            IconButton(onClick = onToggleSelection, enabled = hasAudio) {
                Icon(
                    Icons.Default.Crop,
                    contentDescription = "Region auswaehlen",
                    tint = if (selectionMode) Color(0xFF90CAF9)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Markierung erstellen
            if (hasSelection) {
                Button(
                    onClick = onCreateAnnotation,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.BookmarkAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Markieren")
                }
            }

            // Tabs: Bearbeitung + Ansicht
            if (hasAudio) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent,
                    modifier = Modifier.combinedClickable(
                        onClick = { onTabSelected(if (selectedTab == 0) -1 else 0) },
                        onDoubleClick = { onBypassBearbeitung() }
                    )
                ) {
                    Text(
                        "Bearbeitung",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (filterActive) Color(0xFF4CAF50)
                                else if (selectedTab == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedTab == 1) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent,
                    modifier = Modifier.combinedClickable(
                        onClick = { onTabSelected(if (selectedTab == 1) -1 else 1) },
                        onDoubleClick = { onBypassAnsicht() }
                    )
                ) {
                    Text(
                        "Ansicht",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ansichtActive) Color(0xFF4CAF50)
                                else if (selectedTab == 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (volumeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.Transparent,
                    modifier = Modifier.combinedClickable(
                        onClick = { onTabSelected(2) },  // togglet volumeEnvelopeActive
                        onDoubleClick = { onBypassVolume() }
                    )
                ) {
                    Text(
                        "Lautst\u00e4rke",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (volumeActive) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Event-Erkennung (Energie-basiert)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip { Text("Analyse starten — BirdNET erkennt Arten im gesamten Audio") }
                },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onDetectEvents, enabled = hasAudio) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Analyse starten",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sync: Viewport auf aktive Annotation ausrichten
            if (hasActiveAnnotation) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Ansicht auf Markierung synchronisieren") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Sync",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Bearbeiten: Annotations-Raender per Drag anpassen
            if (hasActiveAnnotation) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Markierung bearbeiten (Raender ziehen)") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onToggleEditMode, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Bearbeiten",
                            modifier = Modifier.size(18.dp),
                            tint = if (editMode) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Annotation-Count
            if (annotationCount > 0) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                ) {
                    Text(
                        "$annotationCount Markierung${if (annotationCount != 1) "en" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Export
            IconButton(onClick = onExport, enabled = hasAudio) {
                Icon(
                    Icons.Default.SaveAlt,
                    contentDescription = "Aktuellen Ausschnitt als Bild exportieren",
                    tint = if (hasAudio) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }

            // Referenz-Editor
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Referenz-Editor: Sonogramme verifizieren und in Sammlungen speichern") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onReferenceEditor) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = "Referenz-Editor",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Einstellungen (vereinheitlichter Dialog)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Einstellungen (Allgemein, Analyse, Export, Datenbank)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Einstellungen",
                        tint = if (hasApiKey && cachedCount > 0) Color(0xFF4CAF50)
                        else if (!hasApiKey) Color(0xFFF44336)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sonogramm-Vergleich: Referenz-Sonogramme der erkannten Art laden
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Referenz-Sonogramme laden") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onSearch, enabled = hasAudio && hasActiveAnnotation) {
                    Icon(
                        Icons.Default.CompareArrows,
                        contentDescription = "Sonogramm vergleichen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
