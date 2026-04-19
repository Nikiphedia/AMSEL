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
import ch.etasystems.amsel.ui.compare.PlaybackMode

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
    isSyncMode: Boolean = false,           // NEU
    hasSelectedReference: Boolean = false, // NEU
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    isSoloMode: Boolean = false,
    onToggleSoloMode: () -> Unit = {},
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
    isLooping: Boolean = false,
    isReferenceLooping: Boolean = false,
    playbackMode: PlaybackMode = PlaybackMode.MAIN,
    onToggleLoop: () -> Unit = {},
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
    // Report (U4)
    onExportReport: () -> Unit = {},
    // Arten-CSV (AP-4)
    onExportSpeciesCsv: () -> Unit = {},
    speciesCsvEnabled: Boolean = false,
    // Projekt
    onNewProject: () -> Unit = {},
    onOpenProject: () -> Unit = {},
    onLoadProject: () -> Unit = {},
    onSaveProject: () -> Unit = {},
    hasProject: Boolean = false,
    projectDirty: Boolean = false,
    // AP-88: Raven- und GPX-Import
    onImportRaven: () -> Unit = {},
    onImportGpx: () -> Unit = {},
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

            // Neues Projekt
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Neues Projekt erstellen...") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onNewProject) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = "Neues Projekt",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Projekt oeffnen
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Projekt oeffnen (.amsel.json)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onOpenProject) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = "Projekt oeffnen",
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

            // Raven Selection Table importieren (AP-88)
            if (hasAudio) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Raven Selection Table importieren (.txt)") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onImportRaven) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Raven Selection Table importieren",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // GPX-Tracklog importieren (AP-88)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("GPX-Tracklog importieren") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onImportGpx) {
                    Icon(
                        Icons.Default.GpsFixed,
                        contentDescription = "GPX-Tracklog importieren",
                        modifier = Modifier.size(18.dp)
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

            // Loop-Toggle (AP-29, AP-75)
            val effektivLooping = if (playbackMode == PlaybackMode.REFERENCE) isReferenceLooping else isLooping
            IconButton(onClick = onToggleLoop, enabled = hasAudio) {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = if (effektivLooping) "Loop aus" else "Loop ein",
                    tint = if (effektivLooping) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            // Sync: Referenz-Zeitachse mit Haupt-Audio koppeln (Toggle)
            if (hasSelectedReference) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip {
                        Text(if (isSyncMode) "Referenz-Sync aktiv (Klick zum Deaktivieren)" else "Referenz-Sync aktivieren")
                    } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = if (isSyncMode) "Sync aktiv" else "Sync",
                            modifier = Modifier.size(18.dp),
                            tint = if (isSyncMode) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
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

            // Solo-Modus: Annotation in voller Breite anzeigen
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Solo-Modus: Chunk in voller Breite (Tab = naechster)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onToggleSoloMode, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.CenterFocusStrong,
                        contentDescription = "Solo-Modus",
                        modifier = Modifier.size(18.dp),
                        tint = if (isSoloMode) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Report (U4)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Report exportieren (PDF + CSV)") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onExportReport, enabled = hasAudio && annotationCount > 0) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = "Report exportieren",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            // Arten-CSV Export (AP-4)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Arten-CSV exportieren") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onExportSpeciesCsv, enabled = speciesCsvEnabled) {
                    Icon(
                        Icons.Default.TableChart,
                        contentDescription = "Arten-CSV exportieren",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
