package ch.etasystems.amsel.ui.compare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.etasystems.amsel.ui.annotation.AnnotationPanel
import ch.etasystems.amsel.ui.annotation.CandidatePanel
import ch.etasystems.amsel.ui.annotation.ChunkSelector
import ch.etasystems.amsel.ui.reference.ReferenceEditorScreen
import ch.etasystems.amsel.ui.results.SonogramGallery
import ch.etasystems.amsel.ui.settings.UnifiedSettingsDialog
import ch.etasystems.amsel.core.spectrogram.Colormap
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import ch.etasystems.amsel.ui.layout.HorizontalSplitter
import ch.etasystems.amsel.ui.layout.VerticalSplitter
import ch.etasystems.amsel.ui.sonogram.*
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Haupt-Composable der Anwendung.
 * Layout: Toolbar → FilterPanel → StatusBar → Row(AnnotationPanel | Column(Overview → Timeline → Zoom → Results))
 *
 * PERFORMANCE-DESIGN:
 * - OverviewStrip + TimelineBar → viewModel.updateViewRange() (billig, nur Koordinaten)
 * - Zoom-Buttons → viewModel.zoomToRange() (sofortige Berechnung)
 * - Spektrogramm wird 400ms nach letzter Drag-Bewegung automatisch nachgeladen
 */
@Composable
fun CompareScreen(
    viewModel: CompareViewModel = remember { CompareViewModel() },
    awtWindow: java.awt.Window? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var showReferenceEditor by remember { mutableStateOf(false) }
    var showUnifiedSettings by remember { mutableStateOf(false) }
    var showCompareDialog by remember { mutableStateOf(false) }

    // Volume-Gains werden jetzt in der FilterPipeline angewendet (Schritt 0),
    // nicht mehr separat in der Colormap.

    // Draggable Splitter States — persistent aus Settings laden
    val savedSettings = remember { ch.etasystems.amsel.data.SettingsStore.load() }
    var sidebarWidth by remember { mutableStateOf(savedSettings.sidebarWidth) }
    var galleryHeight by remember { mutableStateOf(savedSettings.galleryHeight) }
    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    // Drag & Drop via AWT DropTarget (ausgelagert nach DragDropHandler.kt)
    DragDropHandler(
        awtWindow = awtWindow,
        hasAudio = { viewModel.uiState.value.overviewSpectrogramData != null },
        onImportAudio = viewModel::importAudio,
        onImportCompare = viewModel::importCompareFile,
        onImportImage = viewModel::importSonogramImage
    )

    // Referenz-Editor (Fullscreen-Overlay)
    if (showReferenceEditor) {
        ReferenceEditorScreen(
            onClose = { showReferenceEditor = false }
        )
        return
    }

    // Einheitlicher Settings-Dialog (ersetzt ApiKey, Download, Comparison, Export)
    // Dialog: Vergleichsdatei / Referenz-Sonogramm importieren
    if (showCompareDialog) {
        CompareImportDialog(
            availableSpecies = viewModel.getReferenceSpeciesList(),
            onImportFile = { file ->
                showCompareDialog = false
                viewModel.importCompareFile(file)
            },
            onSearchSpecies = { species ->
                showCompareDialog = false
                viewModel.searchSimilar(species)
            },
            onDismiss = { showCompareDialog = false }
        )
    }

    if (showUnifiedSettings) {
        UnifiedSettingsDialog(
            referenceSpeciesCount = uiState.referenceSpeciesCount,
            referenceRecordingCount = uiState.referenceRecordingCount,
            downloadProgress = uiState.downloadProgress,
            onStartDownload = { species, max -> viewModel.startDownload(species, max) },
            onCancelDownload = viewModel::cancelDownload,
            onApiKeySaved = { key -> viewModel.saveApiKey(key) },
            onSettingsChanged = { viewModel.reloadSettings() },
            onRescanReferences = { viewModel.rescanReferences() },
            onStartAudioBatchDownload = { onProgress, onComplete, onCancel ->
                viewModel.startAudioBatchDownload(onProgress, onComplete, onCancel)
            },
            onCancelAudioBatchDownload = { viewModel.cancelAudioBatchDownload() },
            onGetAudioStats = { regionSetId -> viewModel.getAudioStats(regionSetId) },
            onDismiss = { showUnifiedSettings = false }
        )
    }

    val isBusy = uiState.isProcessing || uiState.isComputingZoom

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab-State (fuer Toolbar + Panel)
        var selectedTab by remember { mutableIntStateOf(-1) }

        // Toolbar
        SonogramToolbar(
            hasAudio = uiState.overviewSpectrogramData != null,
            selectionMode = uiState.selectionMode,
            hasSelection = uiState.selection != null,
            hasActiveAnnotation = uiState.activeAnnotation != null,
            annotationCount = uiState.annotations.size,
            isPlaying = uiState.isPlaying,
            isPaused = uiState.isPaused,
            playbackPositionText = uiState.playbackPositionText,
            filterPanelVisible = uiState.showFilterPanel,
            onImport = {
                val file = showFileChooser()
                if (file != null) {
                    val ext = file.extension.lowercase()
                    if (ext in setOf("png", "jpg", "jpeg")) {
                        viewModel.importSonogramImage(file)
                    } else {
                        viewModel.importAudio(file)
                    }
                }
            },
            onClose = { viewModel.closeFile() },
            onToggleSelection = viewModel::toggleSelectionMode,
            onCreateAnnotation = viewModel::createAnnotationFromSelection,
            onToggleFilter = { selectedTab = if (selectedTab == 0) -1 else 0 },
            onSearch = { viewModel.searchSimilar() },
            onSync = { viewModel.syncReferenceToEvent() },
            editMode = uiState.editMode,
            onToggleEditMode = { viewModel.toggleEditMode() },
            onExport = {
                val ann = uiState.activeAnnotation
                val bwSuffix = if (uiState.exportBlackAndWhite) "_sw" else ""
                val suggestedName = if (ann != null && ann.label.isNotBlank()) {
                    ann.label.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜ ]"), "_") + bwSuffix + ".png"
                } else "sonogramm_export${bwSuffix}.png"
                val result = showExportDialog(suggestedName, isBatMode = uiState.detectedMode == "bat")
                if (result != null) {
                    val (file, type) = result
                    when (type) {
                        "wav" -> viewModel.exportAudio(file, format = "wav")
                        "mp3" -> viewModel.exportAudio(file, format = "mp3")
                        "wav10x" -> viewModel.exportAudio(file, format = "wav", timeStretch = 10)
                        else -> viewModel.exportAnnotation(file)
                    }
                }
            },
            exportBlackAndWhite = uiState.exportBlackAndWhite,
            onToggleExportBW = viewModel::toggleExportBlackAndWhite,
            onZoomIn = viewModel::zoomIn,
            onZoomOut = viewModel::zoomOut,
            onZoomReset = viewModel::zoomReset,
            onFreqZoomIn = viewModel::freqZoomIn,
            onFreqZoomOut = viewModel::freqZoomOut,
            freqZoom = uiState.displayFreqZoom,
            onToggleFullView = viewModel::toggleFullView,
            isFullView = uiState.fullView,
            onPlayPause = viewModel::togglePlayPause,
            onStop = viewModel::stopPlayback,
            onDetectEvents = {
                // BirdNET Full-Scan wenn verfuegbar, sonst lokale Event-Detection
                if (ch.etasystems.amsel.core.classifier.BirdNetBridge.isAvailable()) {
                    viewModel.fullScanBirdNet()
                } else {
                    viewModel.detectEvents()
                }
            },
            onNormalize = { viewModel.toggleNormalization() },
            onImportCompare = { showCompareDialog = true },
            hasCompareFile = uiState.compareFile != null,
            hasApiKey = uiState.hasApiKey,
            cachedCount = uiState.referenceRecordingCount,
            onApiKeySettings = { showUnifiedSettings = true },
            onDownloadSettings = { showUnifiedSettings = true },
            onReferenceEditor = { showReferenceEditor = true },
            onExportSettings = { showUnifiedSettings = true },
            onComparisonSettings = { showUnifiedSettings = true },
            onSettings = { showUnifiedSettings = true },
            // Tabs in der Toolbar
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                if (tab == 2 || tab == -1 && selectedTab == 2) {
                    // Tab 2 (Lautstaerke) togglet nur den Modus, kein Panel
                    viewModel.toggleVolumeEnvelope()
                } else {
                    selectedTab = tab
                }
            },
            filterActive = uiState.filterConfig.isActive,
            ansichtActive = uiState.displayDbRange != 10f || uiState.displayGamma != 1.0f || uiState.useLogFreqAxis,
            volumeActive = uiState.volumeEnvelopeActive,
            onBypassBearbeitung = { viewModel.toggleFilterBypass() },
            onBypassAnsicht = { viewModel.resetDisplaySettings() },
            onBypassVolume = { viewModel.toggleVolumeEnvelope() },
            // Projekt
            onLoadProject = {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "AMSEL-Projekt laden"
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("AMSEL Projekt (*.amsel.json)", "json")
                    isAcceptAllFileFilterUsed = false
                    // Startverzeichnis: Documents/AMSEL
                    currentDirectory = java.io.File(System.getProperty("user.home"), "Documents/AMSEL")
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    if (file.name.endsWith(".amsel.json")) {
                        viewModel.loadProject(file)
                    }
                }
            },
            onSaveProject = {
                if (uiState.projectFile != null) {
                    // Vorhandenes Projekt direkt speichern
                    viewModel.saveProjectManual()
                } else {
                    // Neues Projekt: Speicherort waehlen
                    val audioName = uiState.audioFile?.nameWithoutExtension ?: "projekt"
                    val chooser = javax.swing.JFileChooser().apply {
                        dialogTitle = "AMSEL-Projekt speichern"
                        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("AMSEL Projekt (*.amsel.json)", "json")
                        selectedFile = java.io.File(uiState.audioFile?.parentFile ?: java.io.File("."), "$audioName.amsel.json")
                    }
                    if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        var file = chooser.selectedFile
                        if (!file.name.endsWith(".amsel.json")) {
                            file = java.io.File(file.parentFile, file.nameWithoutExtension + ".amsel.json")
                        }
                        viewModel.saveProjectManual(file)
                    }
                }
            },
            hasProject = uiState.projectFile != null,
            projectDirty = uiState.projectDirty
        )

        // Edit-Modus ist jetzt in der Toolbar (Edit-Icon)

        // Panel-Inhalt (klappt auf/zu je nach Tab-Auswahl)
        AnimatedVisibility(visible = selectedTab >= 0 && uiState.overviewSpectrogramData != null) {
            Column {
                when (selectedTab) {
                    0 -> FilterPanel(
                        config = uiState.filterConfig,
                        isBatMode = uiState.detectedMode == "bat",
                        onConfigChanged = viewModel::applyFilterDebounced,
                        onClose = { selectedTab = -1 },
                        onNormalize = { viewModel.toggleNormalization() },
                        hasAudio = uiState.audioFile != null,
                        isNormalized = uiState.isNormalized,
                        normGainDb = uiState.normGainDb
                    )
                    1 -> DisplaySettingsPanel(
                        dbRange = uiState.displayDbRange,
                        gamma = uiState.displayGamma,
                        onDbRangeChanged = viewModel::setDisplayDbRange,
                        onGammaChanged = viewModel::setDisplayGamma,
                        currentPalette = Colormap.getActivePalette(),
                        onPaletteChanged = { palette ->
                            Colormap.setActivePalette(palette)
                            viewModel.refreshAfterPaletteChange()
                        },
                        exportBlackAndWhite = uiState.exportBlackAndWhite,
                        onToggleExportBW = viewModel::toggleExportBlackAndWhite,
                        useLogFreqAxis = uiState.useLogFreqAxis,
                        onToggleLogFreqAxis = viewModel::toggleLogFreqAxis,
                        onClose = { selectedTab = -1 }
                    )
                    // Tab 2 = Lautstaerke → kein separates Panel, Editing direkt in den Canvases
                }
            }
        }

        // Status-Zeile
        // Keine obere Statusleiste mehr — alles in der Seitenleiste

        // Hauptbereich: Seitenleiste | VerticalSplitter | Sonogramm+Galerie
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Annotation-Seitenleiste mit draggable Breite (immer sichtbar, nicht im Full View)
            if (!uiState.fullView) {
                Column(modifier = Modifier.width(sidebarWidth.dp).fillMaxHeight()) {
                    AnnotationPanel(
                        annotations = uiState.annotations,
                        activeAnnotationId = uiState.activeAnnotationId,
                        editingLabelId = uiState.editingLabelId,
                        onSelect = viewModel::selectAnnotation,
                        onDelete = viewModel::deleteAnnotation,
                        onUpdateLabel = viewModel::updateAnnotationLabel,
                        onStartEdit = viewModel::startEditingLabel,
                        onStopEdit = viewModel::stopEditingLabel,
                        onZoomToEvent = { ann ->
                            val settings = ch.etasystems.amsel.data.SettingsStore.load()
                            val pre = settings.eventPrerollSec
                            val post = settings.eventPostrollSec
                            viewModel.selectAnnotation(ann.id)
                            viewModel.zoomToRange(
                                (ann.startTimeSec - pre).coerceAtLeast(0f),
                                ann.endTimeSec + post
                            )
                        },
                        speciesLanguage = ch.etasystems.amsel.core.i18n.SpeciesTranslations.Language.valueOf(
                            ch.etasystems.amsel.data.SettingsStore.load().speciesLanguage
                        ),
                        showScientificNames = ch.etasystems.amsel.data.SettingsStore.load().showScientificNames,
                        modifier = Modifier.weight(1f)
                    )
                    // Kandidatenliste fuer aktive BirdNET-Annotation
                    val activeAnn = uiState.activeAnnotation
                    if (activeAnn != null && activeAnn.candidates.isNotEmpty()) {
                        HorizontalDivider()
                        CandidatePanel(
                            annotation = activeAnn,
                            onAdoptCandidate = { candidate ->
                                viewModel.adoptCandidate(activeAnn.id, candidate)
                            }
                        )
                    }

                    // Chunk-Auswahl (nur wenn ChunkManager aktiv)
                    val cm = uiState.chunkManager
                    if (cm != null) {
                        HorizontalDivider()
                        ChunkSelector(
                            chunks = cm.chunks,
                            activeChunkIndex = uiState.activeChunkIndex,
                            annotationCounts = cm.annotationCountsPerChunk(uiState.annotations),
                            onSelectChunk = viewModel::selectChunk,
                            onPreviousChunk = viewModel::previousChunk,
                            onNextChunk = viewModel::nextChunk
                        )
                    }

                    // Preset-Name + Dateiname + Status unten in der Seitenleiste
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Preset-Name (Vogel/Fledermaus/Insekt)
                        if (uiState.detectedMode.isNotEmpty()) {
                            val presetName = ch.etasystems.amsel.data.SettingsStore.load().let { s ->
                                if (s.maxFrequencyHz > 20000) "Fledermaus"
                                else if (s.maxFrequencyHz > 16000) "Insekt"
                                else "Vogel"
                            }
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (uiState.detectedMode == "bat")
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    presetName,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        // Dateiname
                        if (uiState.audioFile != null) {
                            Text(
                                uiState.audioFile?.name ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                softWrap = true
                            )
                        }
                        if (uiState.sidebarStatus.isNotEmpty()) {
                            Text(
                                uiState.sidebarStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                softWrap = true
                            )
                        }
                    }
                }

                // Vertikaler Splitter zwischen Seitenleiste und Hauptbereich
                VerticalSplitter(
                    onDrag = { delta ->
                        sidebarWidth = (sidebarWidth + delta).coerceIn(180f, 400f)
                    }
                )
            }

            // Sonogramm + Ergebnisse (rechte Seite)
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (uiState.overviewSpectrogramData != null && !uiState.fullView) {
                    // Overview → Viewport-Drag (frei, Bild eingefroren) + Gummiband
                    OverviewStrip(
                        spectrogramData = uiState.overviewSpectrogramData,
                        viewStartSec = uiState.viewStartSec,
                        viewEndSec = uiState.viewEndSec,
                        totalDurationSec = uiState.totalDurationSec,
                        annotations = uiState.annotations,
                        playbackPositionSec = if (uiState.isPlaying || uiState.isPaused) {
                            uiState.playbackPositionSec
                        } else null,
                        paletteVersion = uiState.paletteVersion,
                        onViewRangeChanged = { start, end -> viewModel.updateViewRange(start, end) },
                        onViewRangeDrag = { start, end -> viewModel.updateViewRangeLive(start, end) },
                        onViewRangeDragEnd = { viewModel.commitViewRange() },
                        onRubberBandSelect = { start, end -> viewModel.rubberBandSelect(start, end) },
                        displayDbRange = uiState.displayDbRange,
                        displayGamma = uiState.displayGamma,
                        volumeGainsLog10 = null,  // Gains in Pipeline
                        volumePoints = if (uiState.volumeEnvelopeActive) uiState.volumeEnvelope else emptyList()
                    )

                    // Timeline → freies Drag (Bild eingefroren), Recompute bei DragEnd
                    TimelineBar(
                        viewStartSec = uiState.viewStartSec,
                        viewEndSec = uiState.viewEndSec,
                        totalDurationSec = uiState.totalDurationSec,
                        onRangeChanged = { start, end -> viewModel.updateViewRange(start, end) },
                        onRangeDrag = { start, end -> viewModel.updateViewRangeLive(start, end) },
                        onRangeDragEnd = { viewModel.commitViewRange() }
                    )
                }

                // Zoom-Canvas mit Navigations-Pfeilen (nimmt verfuegbaren Platz)
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Links: Startpunkt verschieben
                    Column(
                        modifier = Modifier.width(28.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val step = 0.5f
                        IconButton(
                            onClick = {
                                val newStart = (uiState.viewStartSec - step).coerceAtLeast(0f)
                                viewModel.zoomToRange(newStart, uiState.viewEndSec)
                            },
                            enabled = uiState.overviewSpectrogramData != null && uiState.viewStartSec > 0f,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val newStart = (uiState.viewStartSec + step).coerceAtMost(uiState.viewEndSec - 0.5f)
                                viewModel.zoomToRange(newStart, uiState.viewEndSec)
                            },
                            enabled = uiState.overviewSpectrogramData != null && uiState.viewEndSec - uiState.viewStartSec > 0.5f,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                    if (uiState.zoomedSpectrogramData != null) {
                        // Kontextmenu-State fuer Rechtsklick auf Annotation
                        var contextMenuAnnotationId by remember { mutableStateOf<String?>(null) }
                        var contextMenuOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
                        val density = androidx.compose.ui.platform.LocalDensity.current

                        Box {
                        ZoomedCanvas(
                            spectrogramData = uiState.zoomedSpectrogramData,
                            selection = uiState.selection,
                            onSelectionChanged = viewModel::updateSelection,
                            selectionMode = uiState.selectionMode,
                            viewStartSec = uiState.viewStartSec,
                            viewEndSec = uiState.viewEndSec,
                            annotations = uiState.annotations,
                            activeAnnotationId = uiState.activeAnnotationId,
                            onAnnotationClicked = viewModel::selectAnnotation,
                            onAnnotationRightClicked = { annId, px, py ->
                                contextMenuAnnotationId = annId
                                contextMenuOffset = with(density) {
                                    androidx.compose.ui.unit.DpOffset(px.toDp(), py.toDp())
                                }
                                viewModel.selectAnnotation(annId)
                            },
                            playbackPositionSec = if (uiState.isPlaying || uiState.isPaused) {
                                uiState.playbackPositionSec
                            } else null,
                            isComputing = uiState.isComputingZoom,
                            paletteVersion = uiState.paletteVersion,
                            displayDbRange = uiState.displayDbRange,
                            displayGamma = uiState.displayGamma,
                            volumeGainsLog10 = null,  // Gains in Pipeline
                            volumePoints = if (uiState.volumeEnvelopeActive) uiState.volumeEnvelope else emptyList(),
                            volumeEditMode = uiState.volumeEnvelopeActive,
                            onVolumeAddPoint = { t, g -> viewModel.addVolumePoint(t, g) },
                            onVolumeMovePoint = { i, t, g -> viewModel.moveVolumePoint(i, t, g) },
                            onVolumeRemovePoint = { i -> viewModel.removeVolumePoint(i) },
                            selectedVolumeIndex = uiState.selectedVolumeIndex,
                            onVolumeSelectPoint = { i -> viewModel.selectVolumePoint(i) },
                            freqZoom = uiState.displayFreqZoom,
                            useLogFreqAxis = uiState.useLogFreqAxis,
                            editMode = uiState.editMode,
                            onAnnotationBoundsChanged = { id, s, e, lo, hi ->
                                viewModel.updateAnnotationBounds(id, s, e, lo, hi)
                            },
                            normReferenceMaxDb = uiState.normReferenceMaxDb
                        )

                        // Kontextmenu bei Rechtsklick auf Annotation
                        DropdownMenu(
                            expanded = contextMenuAnnotationId != null,
                            onDismissRequest = { contextMenuAnnotationId = null },
                            offset = contextMenuOffset
                        ) {
                            val annId = contextMenuAnnotationId
                            val ann = annId?.let { id -> uiState.annotations.find { it.id == id } }

                            DropdownMenuItem(
                                text = { Text("BirdNET analysieren") },
                                onClick = {
                                    contextMenuAnnotationId = null
                                    if (annId != null) viewModel.scanBirdNetRegion(annId)
                                },
                                leadingIcon = { Icon(Icons.Default.Search, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Loeschen") },
                                onClick = {
                                    contextMenuAnnotationId = null
                                    if (annId != null) viewModel.deleteAnnotation(annId)
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null) }
                            )
                            if (ann != null) {
                                DropdownMenuItem(
                                    text = { Text("Hierhin zoomen") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        val pre = ch.etasystems.amsel.data.SettingsStore.load().eventPrerollSec
                                        val post = ch.etasystems.amsel.data.SettingsStore.load().eventPostrollSec
                                        viewModel.zoomToRange(
                                            (ann.startTimeSec - pre).coerceAtLeast(0f),
                                            ann.endTimeSec + post
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.ZoomIn, null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Vergleichen — Sonogramm laden") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        // Auf Annotation zoomen, dann Sonogramm-Bild laden
                                        viewModel.zoomToRange(ann.startTimeSec, ann.endTimeSec)
                                        val chooser = javax.swing.JFileChooser().apply {
                                            dialogTitle = "Sonogramm-Bild zum Vergleichen"
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Bilder (PNG, JPG)", "png", "jpg", "jpeg")
                                        }
                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            viewModel.importSonogramImage(chooser.selectedFile)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Image, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Vergleichen — Audio laden") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        // Auf Annotation zoomen, dann Audio-Vergleichsdatei laden
                                        viewModel.zoomToRange(ann.startTimeSec, ann.endTimeSec)
                                        val chooser = javax.swing.JFileChooser().apply {
                                            dialogTitle = "Audio zum Vergleichen"
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Audio (WAV, MP3, FLAC)", "wav", "mp3", "flac", "m4a")
                                        }
                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            viewModel.importCompareFile(chooser.selectedFile)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.AudioFile, null) }
                                )
                            }
                        }
                        } // Box
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Audio importieren um Sonogramm anzuzeigen\n(WAV, MP3, FLAC)",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                    }  // Surface

                    // Rechts: Endpunkt verschieben
                    Column(
                        modifier = Modifier.width(28.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val step = 0.5f
                        val total = uiState.overviewSpectrogramData?.durationSec ?: 0f
                        IconButton(
                            onClick = {
                                val newEnd = (uiState.viewEndSec - step).coerceAtLeast(uiState.viewStartSec + 0.5f)
                                viewModel.zoomToRange(uiState.viewStartSec, newEnd)
                            },
                            enabled = uiState.overviewSpectrogramData != null && uiState.viewEndSec - uiState.viewStartSec > 0.5f,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val newEnd = (uiState.viewEndSec + step).coerceAtMost(total)
                                viewModel.zoomToRange(uiState.viewStartSec, newEnd)
                            },
                            enabled = uiState.overviewSpectrogramData != null && uiState.viewEndSec < total,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(20.dp))
                        }
                    }
                }  // Row Zoom-Canvas

                // Vergleichsdatei-Sonogramm (unterhalb des Hauptsonogramms)
                if (uiState.compareSpectrogramData != null) {
                    HorizontalDivider(thickness = 2.dp, color = Color(0xFF4CAF50).copy(alpha = 0.5f))
                    Surface(
                        modifier = Modifier.weight(0.6f).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Vergleich: ${uiState.compareFile?.name ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50)
                                )
                                IconButton(
                                    onClick = { viewModel.clearCompareFile() },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Schliessen",
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            ZoomedCanvas(
                                spectrogramData = uiState.compareSpectrogramData,
                                selection = null,
                                onSelectionChanged = {},
                                selectionMode = false,
                                viewStartSec = uiState.viewStartSec,
                                viewEndSec = uiState.viewEndSec,
                                annotations = emptyList(),
                                activeAnnotationId = null,
                                onAnnotationClicked = {},
                                paletteVersion = uiState.paletteVersion,
                                displayDbRange = uiState.displayDbRange,
                                displayGamma = uiState.displayGamma,
                                useLogFreqAxis = uiState.useLogFreqAxis
                            )
                        }
                    }
                }

                // Referenz-Sonogramm-Galerie (Thumbnails + grosses Bild)
                val activeResults = uiState.activeAnnotation?.matchResults ?: emptyList()
                if (activeResults.isNotEmpty() || uiState.selectedMatchResult != null) {
                    HorizontalSplitter(
                        onDrag = { delta ->
                            galleryHeight = (galleryHeight - delta).coerceIn(80f, 500f)
                        }
                    )
                    SonogramGallery(
                        matchResults = activeResults,
                        selectedResult = uiState.selectedMatchResult,
                        onSelectResult = { result -> viewModel.selectMatchResult(result) },
                        onPlayAudio = { result -> viewModel.playReferenceAudio(result) },
                        onStopAudio = { viewModel.stopPlayback() },
                        onClose = { viewModel.clearMatchResult() },
                        isLoading = uiState.isSearching,
                        isPlayingAudio = uiState.isPlaying,
                        playingRecordingId = uiState.playingReferenceId,
                        downloadingRecordingId = uiState.downloadingReferenceId,
                        modifier = Modifier.fillMaxWidth().height(galleryHeight.dp)
                    )
                }
            }
        }
    }

    // Busy-Overlay: blockiert alle Klicks waehrend Berechnung laeuft
    if (isBusy) {
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(
                        uiState.statusText.ifEmpty { "Berechne..." },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
    } // Box
}


private var lastExportDir: File? = null

/** Prueft ob ffmpeg im PATH verfuegbar ist. */
private val ffmpegAvailable: Boolean by lazy {
    try {
        val p = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    } catch (_: Exception) { false }
}

/** Rueckgabe: Datei + Typ ("png", "wav", "flac", "mp3", "wav10x") */
private fun showExportDialog(suggestedName: String = "sonogramm_export.png", isBatMode: Boolean = false): Pair<File, String>? {
    val startDir = lastExportDir
        ?: javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory
    val pngFilter = FileNameExtensionFilter("Sonogramm (*.png)", "png")
    val wavFilter = FileNameExtensionFilter("Audio WAV (*.wav)", "wav")
    val mp3Filter = FileNameExtensionFilter("Audio MP3 (*.mp3)", "mp3")
    val wav10xFilter = FileNameExtensionFilter("Audio WAV 10x Zeitdehnung (*.wav)", "wav")

    // Filter-Typ-Zuordnung
    val filterToType = mutableMapOf(
        pngFilter to "png",
        wavFilter to "wav"
    )
    if (ffmpegAvailable) filterToType[mp3Filter] = "mp3"
    if (isBatMode) filterToType[wav10xFilter] = "wav10x"

    val chooser = JFileChooser(startDir).apply {
        dialogTitle = "Region exportieren"
        addChoosableFileFilter(pngFilter)
        addChoosableFileFilter(wavFilter)
        if (ffmpegAvailable) addChoosableFileFilter(mp3Filter)
        if (isBatMode) addChoosableFileFilter(wav10xFilter)
        fileFilter = pngFilter
        isAcceptAllFileFilterUsed = false
        selectedFile = File(startDir, suggestedName)
    }
    chooser.addPropertyChangeListener("fileFilterChanged") {
        val current = chooser.selectedFile ?: return@addPropertyChangeListener
        val baseName = current.nameWithoutExtension
        val type = filterToType[chooser.fileFilter] ?: "png"
        val newExt = if (type == "wav10x") "wav" else type
        chooser.selectedFile = File(current.parentFile, "$baseName.$newExt")
    }
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var file = chooser.selectedFile
        val type = filterToType[chooser.fileFilter] ?: "png"
        val ext = if (type == "wav10x") "wav" else type
        if (!file.name.endsWith(".$ext")) {
            file = File(file.absolutePath + ".$ext")
        }
        lastExportDir = file.parentFile
        Pair(file, type)
    } else null
}

// ════════════════════════════════════════════════════════════════════
// Anzeige-Einstellungen (Dynamik + Helligkeit) als Panel im Ansicht-Tab
// ════════════════════════════════════════════════════════════════════

@Composable
private fun DisplaySettingsPanel(
    dbRange: Float,
    gamma: Float,
    onDbRangeChanged: (Float) -> Unit,
    onGammaChanged: (Float) -> Unit,
    currentPalette: Colormap.Palette,
    onPaletteChanged: (Colormap.Palette) -> Unit,
    exportBlackAndWhite: Boolean,
    onToggleExportBW: () -> Unit,
    useLogFreqAxis: Boolean = false,
    onToggleLogFreqAxis: () -> Unit = {},
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Dynamik + Helligkeit Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Dynamik-Slider (dB-Bereich)
                StepSlider(
                    label = "Dynamik",
                    value = dbRange,
                    valueRange = 3f..12f,
                    step = 0.5f,
                    format = { "${"%.1f".format(it)} dB" },
                    onValueChange = onDbRangeChanged,
                    modifier = Modifier.weight(1f),
                    tooltip = "Dynamikbereich der Anzeige in dB. Weniger = mehr Kontrast."
                )
                // Helligkeit-Slider (Gamma) — invertiert: links=dunkel(3.0), rechts=hell(0.2)
                StepSlider(
                    label = "Helligkeit",
                    value = 3.2f - gamma,
                    valueRange = 0.2f..3f,
                    step = 0.1f,
                    format = { "${"%.1f".format(3.2f - it)}" },
                    onValueChange = { onGammaChanged(3.2f - it) },
                    modifier = Modifier.weight(1f),
                    tooltip = "Helligkeit der Anzeige. Links = dunkel, rechts = hell."
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Farbpalette als FilterChip-Reihe
            Text(
                "Farbpalette",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (p in Colormap.Palette.entries) {
                    val label = when (p) {
                        Colormap.Palette.MAGMA -> "Magma"
                        Colormap.Palette.VIRIDIS -> "Viridis"
                        Colormap.Palette.INFERNO -> "Inferno"
                        Colormap.Palette.GRAYSCALE -> "Graustufen"
                        Colormap.Palette.BW_PRINT -> "S/W Druck"
                    }
                    FilterChip(
                        selected = currentPalette == p,
                        onClick = { onPaletteChanged(p) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // S/W Export Toggle
                FilterChip(
                    selected = exportBlackAndWhite,
                    onClick = onToggleExportBW,
                    label = { Text("S/W Export", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Frequenzachse: Linear / Logarithmisch
                FilterChip(
                    selected = useLogFreqAxis,
                    onClick = onToggleLogFreqAxis,
                    label = {
                        Text(
                            if (useLogFreqAxis) "Freq: Logarithmisch" else "Freq: Linear",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}
