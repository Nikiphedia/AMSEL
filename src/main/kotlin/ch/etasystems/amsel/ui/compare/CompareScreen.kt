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
import ch.etasystems.amsel.ui.annotation.AudiofilesPanel
import ch.etasystems.amsel.ui.reference.ReferenceEditorScreen
import ch.etasystems.amsel.ui.results.SonogramGallery
import ch.etasystems.amsel.ui.settings.AudioMetadataDialog
import ch.etasystems.amsel.ui.settings.NewProjectDialog
import ch.etasystems.amsel.ui.settings.UnifiedSettingsDialog
import ch.etasystems.amsel.core.spectrogram.Colormap
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import ch.etasystems.amsel.ui.layout.UndockPanelState
import ch.etasystems.amsel.ui.layout.UndockablePanel
import ch.etasystems.amsel.ui.layout.VerticalSplitter
import ch.etasystems.amsel.ui.sonogram.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import ch.etasystems.amsel.core.export.AudioExporter
import ch.etasystems.amsel.core.export.SpeciesCsvExporter
import ch.etasystems.amsel.core.classifier.ClassifierResult
import ch.etasystems.amsel.data.resolvedExportDir
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity

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
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var pendingMetadataFileId by remember { mutableStateOf<String?>(null) }
    var pendingMetadataFileName by remember { mutableStateOf("") }
    var showZeitstempelDialog by remember { mutableStateOf(false) }
    val candidatePanelState = remember { UndockPanelState("Kandidaten", initialWidth = 400, initialHeight = 600) }
    val audiofilesPanelState = remember { UndockPanelState("Audiofiles", initialWidth = 350, initialHeight = 400) }

    /** 0 = kein Panel fokussiert, 1 = Annotations, 2 = Kandidaten, 3 = Files/Slices */
    var activePanelIndex by remember { mutableIntStateOf(0) }

    /** CapsLock-Status: true = Referenz-Modus aktiv */
    var isCapsLockActive by remember { mutableStateOf(false) }

    /** Space-gehalten State fuer Space+Klick im Sonogramm (AP-52) */
    var isSpaceHeld by remember { mutableStateOf(false) }
    var spaceClickUsed by remember { mutableStateOf(false) }

    // Volume-Gains werden jetzt in der FilterPipeline angewendet (Schritt 0),
    // nicht mehr separat in der Colormap.

    // Draggable Splitter States — persistent aus Settings laden
    val savedSettings = remember { ch.etasystems.amsel.data.SettingsStore.load() }
    var sidebarWidth by remember { mutableStateOf(savedSettings.sidebarWidth) }
    var galleryHeight by remember { mutableStateOf(savedSettings.galleryHeight) }
    val speciesLocale = when (savedSettings.speciesLanguage) {
        "EN" -> "en"
        "SCIENTIFIC" -> "scientific"
        else -> "de"
    }

    // Sidebar + Gallery Groesse persistent speichern (debounced)
    LaunchedEffect(sidebarWidth, galleryHeight) {
        kotlinx.coroutines.delay(1000)
        val current = ch.etasystems.amsel.data.SettingsStore.load()
        if (current.sidebarWidth != sidebarWidth || current.galleryHeight != galleryHeight) {
            ch.etasystems.amsel.data.SettingsStore.save(current.copy(
                sidebarWidth = sidebarWidth,
                galleryHeight = galleryHeight
            ))
        }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dispose() }
    }

    // Drag & Drop via AWT DropTarget (ausgelagert nach DragDropHandler.kt)
    DragDropHandler(
        awtWindow = awtWindow,
        onImportAudioFiles = { files ->
            for (file in files) {
                viewModel.importAudio(file)
            }
        },
        onImportCompare = viewModel::importCompareFile,
        onImportImage = viewModel::importSonogramImage
    )

    // Metadaten-Dialog nach Audio-Import oeffnen
    // Beobachte activeAudioFileId: wenn sich die ID aendert und ein Dateiname pending ist, Dialog zeigen
    LaunchedEffect(uiState.activeAudioFileId) {
        if (pendingMetadataFileName.isNotEmpty() && uiState.activeAudioFileId != null) {
            pendingMetadataFileId = uiState.activeAudioFileId
        }
    }

    if (pendingMetadataFileId != null) {
        AudioMetadataDialog(
            fileName = pendingMetadataFileName,
            onDismiss = {
                pendingMetadataFileId = null
                pendingMetadataFileName = ""
            },
            onConfirm = { meta ->
                viewModel.setAudioMetadata(pendingMetadataFileId!!, meta)
                pendingMetadataFileId = null
                pendingMetadataFileName = ""
            }
        )
    }

    // Zeitstempel-Kette Dialog
    if (showZeitstempelDialog) {
        // Bestehende Metadaten aus Projektdatei laden
        val audioRefMap = remember {
            try {
                viewModel.loadAudioReferencesMap()
            } catch (_: Exception) { emptyMap() }
        }
        // Eintraege zusammenstellen: alle geladenen Files, sortiert nach Dateiname
        val eintraege = uiState.loadedAudioFiles.entries
            .sortedBy { it.value.audioFile.name }
            .map { (id, fileState) ->
                ch.etasystems.amsel.ui.settings.ZeitstempelEintrag(
                    fileId = id,
                    fileName = fileState.audioFile.name,
                    durationSec = fileState.durationSec,
                    bestehendesMeta = audioRefMap[id]?.recordingMeta
                )
            }
        if (eintraege.isNotEmpty()) {
            ch.etasystems.amsel.ui.settings.ZeitstempelDialog(
                eintraege = eintraege,
                onDismiss = { showZeitstempelDialog = false },
                onConfirm = { metadatenMap ->
                    viewModel.setMultiFileTimestamps(metadatenMap)
                    showZeitstempelDialog = false
                }
            )
        }
    }

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

    if (showNewProjectDialog) {
        NewProjectDialog(
            settings = savedSettings,
            onDismiss = { showNewProjectDialog = false },
            onProjectCreated = { dir, name, meta ->
                showNewProjectDialog = false
                viewModel.createNewProject(dir, name, meta)
            }
        )
    }

    val isBusy = uiState.isProcessing || uiState.isComputingZoom

    // Gehaltene Modifier-Taste fuer Navigation (S = 5s Sprung)
    // Kein mutableStateOf — Recomposition waere zu langsam zwischen S-KeyDown und Arrow-KeyDown
    val heldSRef = remember { booleanArrayOf(false) }
    val heldRRef = remember { booleanArrayOf(false) }

    // U1: Globale Tastaturkuerzel — Focus auf Root-Container
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)  // Frame abwarten
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // CapsLock-Erkennung: Toggle bei CapsLock-Taste (KeyUp = kein Repeat-Problem)
                if (event.key == Key.CapsLock) {
                    if (event.type == KeyEventType.KeyUp) {
                        isCapsLockActive = !isCapsLockActive
                        viewModel.switchPlaybackMode(
                            if (isCapsLockActive) PlaybackMode.REFERENCE else PlaybackMode.MAIN
                        )
                    }
                    return@onPreviewKeyEvent true
                }

                val isEditing = uiState.editingLabelId != null

                // Space gehalten tracken fuer Space+Klick (AP-52)
                if (event.key == Key.Spacebar) {
                    isSpaceHeld = event.type == KeyEventType.KeyDown
                }

                // S als Navigation-Modifier tracken (S + Pfeil = 5s Seek)
                if (event.key == Key.S && !event.isCtrlPressed && !isEditing) {
                    heldSRef[0] = event.type == KeyEventType.KeyDown
                }

                // R als Modifier fuer Solo-Modus tracken (R + Tab = vorheriger Chunk)
                if (event.key == Key.R && !isEditing && uiState.isSoloMode) {
                    heldRRef[0] = event.type == KeyEventType.KeyDown
                }

                // Solo-Modus: Tab = naechster Chunk, R gehalten + Tab = vorheriger Chunk
                if (uiState.isSoloMode && event.type == KeyEventType.KeyDown && !isEditing) {
                    when (event.key) {
                        Key.Tab -> {
                            if (heldRRef[0]) {
                                viewModel.soloPreviousAnnotation()
                            } else {
                                viewModel.soloNextAnnotation()
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }

                // S + Pfeiltasten = 5s Seek im Playback (auf Preview-Ebene abfangen)
                if (heldSRef[0] && event.type == KeyEventType.KeyDown && !event.isCtrlPressed) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            viewModel.seekPlayback(5f)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft -> {
                            viewModel.seekPlayback(-5f)
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }

                // CapsLock ON: Pfeiltasten links/rechts = Referenz-Navigation (ohne abspielen)
                if (isCapsLockActive && event.type == KeyEventType.KeyDown && !event.isCtrlPressed && !isEditing) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            viewModel.selectNextReference()
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft -> {
                            viewModel.selectPreviousReference()
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }

                // Panel-Fokus mit Zifferntasten (1/2/3)
                if (event.type == KeyEventType.KeyDown && !isEditing && !event.isCtrlPressed) {
                    when (event.key) {
                        Key.One -> { activePanelIndex = if (activePanelIndex == 1) 0 else 1; return@onPreviewKeyEvent true }
                        Key.Two -> { activePanelIndex = if (activePanelIndex == 2) 0 else 2; return@onPreviewKeyEvent true }
                        Key.Three -> { activePanelIndex = if (activePanelIndex == 3) 0 else 3; return@onPreviewKeyEvent true }
                        else -> {}
                    }
                }

                // Panel-Navigation mit Pfeiltasten hoch/runter (onPreviewKeyEvent damit LazyColumn/TextField sie nicht konsumiert)
                if (event.type == KeyEventType.KeyDown && !isEditing && !event.isCtrlPressed && activePanelIndex > 0) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            when (activePanelIndex) {
                                1 -> viewModel.previousAnnotation()
                                2 -> viewModel.previousCandidate()
                                3 -> viewModel.navigateFilesPanelUp()
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            when (activePanelIndex) {
                                1 -> viewModel.nextAnnotation()
                                2 -> viewModel.nextCandidate()
                                3 -> viewModel.navigateFilesPanelDown()
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }

                // Escape setzt Panel-Fokus zurueck
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && activePanelIndex > 0) {
                    activePanelIndex = 0
                    return@onPreviewKeyEvent true
                }

                // Space = Play/Pause (AP-29), aber nur wenn kein Textfeld editiert wird
                when {
                    event.key == Key.Spacebar && event.type == KeyEventType.KeyUp -> {
                        isSpaceHeld = false
                        if (spaceClickUsed) {
                            // Space+Klick wurde benutzt — kein Play/Pause (AP-52)
                            spaceClickUsed = false
                            true
                        } else if (!isEditing) {
                            if (isCapsLockActive) {
                                // CapsLock ON: Play/Pause aktuelle Referenz
                                if (uiState.isPlaying) {
                                    viewModel.stopPlayback()
                                } else {
                                    val results = uiState.activeAnnotation?.matchResults ?: emptyList()
                                    val idx = uiState.activeReferenceIndex
                                    if (idx >= 0 && idx < results.size) {
                                        viewModel.playReferenceAudio(results[idx])
                                    } else if (results.isNotEmpty()) {
                                        viewModel.playReferenceAudio(results[0])
                                    }
                                }
                            } else {
                                viewModel.togglePlayPause()
                            }
                            true
                        } else false
                    }
                    event.key == Key.Spacebar -> !isEditing
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                handleGlobalShortcut(event, viewModel, uiState)
            }
    ) {
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
                        pendingMetadataFileName = file.name
                        viewModel.importAudio(file)
                    }
                }
            },
            onClose = { viewModel.closeFile() },
            onToggleSelection = viewModel::toggleSelectionMode,
            onCreateAnnotation = viewModel::createAnnotationFromSelection,
            onToggleFilter = { selectedTab = if (selectedTab == 0) -1 else 0 },
            onSearch = { viewModel.searchSimilar() },
            onSync = { viewModel.toggleSyncMode() },
            isSyncMode = uiState.isSyncMode,
            hasSelectedReference = uiState.selectedMatchResult != null,
            editMode = uiState.editMode,
            onToggleEditMode = { viewModel.toggleEditMode() },
            isSoloMode = uiState.isSoloMode,
            onToggleSoloMode = { viewModel.toggleSoloMode() },
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
                        "flac" -> viewModel.exportAudio(file, format = "flac")
                        "m4a" -> viewModel.exportAudio(file, format = "m4a")
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
            isLooping = uiState.isLooping,
            isReferenceLooping = uiState.isReferenceLooping,
            playbackMode = uiState.playbackMode,
            onToggleLoop = { viewModel.toggleLoop() },
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
            onNewProject = { showNewProjectDialog = true },
            onOpenProject = {
                val chooser = javax.swing.JFileChooser(
                    savedSettings.let { if (it.projectDir.isNotBlank()) File(it.projectDir) else null }
                )
                chooser.fileFilter = FileNameExtensionFilter("AMSEL Projekt (*.amsel.json)", "json")
                chooser.dialogTitle = "Projekt oeffnen"
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    viewModel.loadProject(chooser.selectedFile)
                }
            },
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
            projectDirty = uiState.projectDirty,
            onExportReport = viewModel::exportReport,
            speciesCsvEnabled = uiState.annotations.any { it.isBirdNetDetection },
            onExportSpeciesCsv = {
                val annotations = uiState.annotations.filter { it.isBirdNetDetection }
                if (annotations.isNotEmpty()) {
                    val results = annotations.mapNotNull { ann ->
                        val best = ann.candidates.maxByOrNull { it.confidence } ?: return@mapNotNull null
                        ClassifierResult(
                            species = ann.label,
                            scientificName = best.scientificName,
                            confidence = best.confidence,
                            startTime = ann.startTimeSec,
                            endTime = ann.endTimeSec
                        )
                    }
                    val audioName = uiState.audioFile?.name ?: "unbekannt"
                    val csvSettings = ch.etasystems.amsel.data.SettingsStore.load()
                    val csvStartDir = lastExportDir
                        ?: csvSettings.resolvedExportDir()
                        ?: javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory
                    val chooser = javax.swing.JFileChooser(csvStartDir).apply {
                        dialogTitle = "Arten-CSV exportieren"
                        fileFilter = FileNameExtensionFilter("CSV (*.csv)", "csv")
                        selectedFile = File(
                            csvStartDir,
                            audioName.substringBeforeLast('.') + "_arten.csv"
                        )
                    }
                    if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        var file = chooser.selectedFile
                        if (!file.name.endsWith(".csv")) file = File(file.absolutePath + ".csv")
                        lastExportDir = file.parentFile
                        SpeciesCsvExporter.export(file, results, audioName)
                    }
                }
            }
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
                // Fokus-Indikator links an der Seitenleiste
                if (activePanelIndex > 0) {
                    Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
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
                            val pre = savedSettings.eventPrerollSec
                            val post = savedSettings.eventPostrollSec
                            viewModel.selectAnnotation(ann.id)
                            viewModel.zoomToRange(
                                (ann.startTimeSec - pre).coerceAtLeast(0f),
                                ann.endTimeSec + post
                            )
                        },
                        speciesLanguage = ch.etasystems.amsel.core.i18n.SpeciesTranslations.Language.valueOf(
                            savedSettings.speciesLanguage
                        ),
                        showScientificNames = savedSettings.showScientificNames,
                        // Multi-Select (U3)
                        selectedAnnotationIds = uiState.selectedAnnotationIds,
                        onToggleSelection = viewModel::toggleMultiSelection,
                        onSelectAll = viewModel::selectAllAnnotations,
                        onClearSelection = viewModel::clearMultiSelection,
                        onDeleteSelected = viewModel::deleteSelectedAnnotations,
                        onExportReport = viewModel::exportReport,
                        onShiftClick = viewModel::selectRange,
                        isSoloMode = uiState.isSoloMode,
                        viewStartSec = uiState.viewStartSec,
                        viewEndSec = uiState.viewEndSec,
                        modifier = if (activePanelIndex == 0 || activePanelIndex == 1)
                            Modifier.weight(1f)
                        else
                            Modifier.heightIn(min = 60.dp, max = 120.dp)
                    )
                    // Kandidatenliste fuer aktive BirdNET-Annotation (undockbar)
                    val activeAnn = uiState.activeAnnotation
                    if (activeAnn != null) {
                        HorizontalDivider()
                        val candidateModifier = if (activePanelIndex == 2)
                            Modifier.weight(1f)
                        else
                            Modifier
                        Box(modifier = candidateModifier) {
                            UndockablePanel(state = candidatePanelState) {
                                CandidatePanel(
                                    annotation = activeAnn,
                                    onAdoptCandidate = { candidate ->
                                        viewModel.adoptCandidate(activeAnn.id, candidate)
                                    },
                                    onVerifyCandidate = { candidate ->
                                        viewModel.verifyCandidateAndSearch(activeAnn.id, candidate)
                                    },
                                    onRejectCandidate = { candidate ->
                                        viewModel.rejectCandidateInAnnotation(activeAnn.id, candidate)
                                    },
                                    onResetCandidate = { candidate ->
                                        viewModel.resetCandidateInAnnotation(activeAnn.id, candidate)
                                    },
                                    onUncertainCandidate = { candidate ->
                                        viewModel.uncertainCandidateInAnnotation(activeAnn.id, candidate)
                                    },
                                    onAddCandidate = { sciName, displayLabel ->
                                        viewModel.addManualCandidate(activeAnn.id, sciName, displayLabel)
                                    },
                                    onUpdateNotes = { notes -> viewModel.updateAnnotationNotes(activeAnn.id, notes) },
                                    onUpdateLabel = { label -> viewModel.updateAnnotationLabel(activeAnn.id, label) }
                                )
                            }
                        }
                    } else if (candidatePanelState.isUndocked) {
                        // Wenn abgedockt aber keine Annotation aktiv: Platzhalter zeigen
                        HorizontalDivider()
                        UndockablePanel(state = candidatePanelState) {
                            Box(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Keine Annotation ausgewaehlt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    // Audiofiles-Panel (sichtbar sobald Dateien geladen, mit integrierten Slices)
                    if (uiState.loadedAudioFiles.isNotEmpty()) {
                        HorizontalDivider()
                        val audiofilesModifier = if (activePanelIndex == 3)
                            Modifier.weight(1f)
                        else
                            Modifier
                        Box(modifier = audiofilesModifier) {
                            UndockablePanel(state = audiofilesPanelState) {
                                AudiofilesPanel(
                                    loadedFiles = uiState.loadedAudioFiles,
                                    activeFileId = uiState.activeAudioFileId,
                                    activeSliceIndex = uiState.activeSliceIndex,
                                    onSelectFile = { fileId -> viewModel.switchAudioFile(fileId) },
                                    onSelectSlice = { index -> viewModel.selectSlice(index) },
                                    onRemoveFile = { fileId -> viewModel.removeAudioFile(fileId) },
                                    annotationCount = uiState.annotations.size,
                                    onAddFile = {
                                        val chooser = JFileChooser()
                                        chooser.fileFilter = FileNameExtensionFilter("Audio", "wav", "mp3", "flac", "ogg", "m4a")
                                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            viewModel.importAudio(chooser.selectedFile)
                                        }
                                    },
                                    onShowZeitstempel = { showZeitstempelDialog = true },
                                    isFocused = activePanelIndex == 3
                                )
                            }
                        }
                    }

                    // Preset-Name + Dateiname + Status unten in der Seitenleiste
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Preset-Name (Vogel/Fledermaus/Insekt)
                        if (uiState.detectedMode.isNotEmpty()) {
                            val presetName = savedSettings.let { s ->
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
                        // Live-Scan Indikator: dezenter Spinner + Event-Zaehler
                        if (uiState.isBackgroundScanning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp
                                )
                                Text(
                                    "Scan: ${uiState.scanDetectionCount} Events",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
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

                        // U2b: Kontextmenu-State fuer Rechtsklick auf leere Canvas-Stelle
                        var showCanvasContextMenu by remember { mutableStateOf(false) }
                        var canvasContextMenuOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
                        var canvasContextMenuTimeSec by remember { mutableStateOf(0f) }

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
                            onCanvasRightClicked = { timeSec, _, px, py ->
                                canvasContextMenuTimeSec = timeSec
                                canvasContextMenuOffset = with(density) {
                                    androidx.compose.ui.unit.DpOffset(px.toDp(), py.toDp())
                                }
                                showCanvasContextMenu = true
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
                            normReferenceMaxDb = uiState.normReferenceMaxDb,
                            // Space+Klick: Play ab Position (AP-52)
                            isSpaceHeld = isSpaceHeld,
                            onClickToSeek = { timeSec ->
                                spaceClickUsed = true
                                viewModel.seekToPositionAndPlay(timeSec)
                            }
                        )

                        // U2a: Erweitertes Kontextmenu bei Rechtsklick auf Annotation
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
                                text = { Text("Umbenennen (F2)") },
                                onClick = {
                                    contextMenuAnnotationId = null
                                    if (annId != null) viewModel.startEditingLabel(annId)
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Loeschen (Del)") },
                                onClick = {
                                    contextMenuAnnotationId = null
                                    if (annId != null) viewModel.deleteAnnotation(annId)
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null) }
                            )

                            HorizontalDivider()

                            if (ann != null) {
                                DropdownMenuItem(
                                    text = { Text("Hierhin zoomen") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        val zoomSettings = ch.etasystems.amsel.data.SettingsStore.load()
                                        val pre = zoomSettings.eventPrerollSec
                                        val post = zoomSettings.eventPostrollSec
                                        viewModel.zoomToRange(
                                            (ann.startTimeSec - pre).coerceAtLeast(0f),
                                            ann.endTimeSec + post
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.ZoomIn, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Abspielen") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        viewModel.playRange(ann.startTimeSec, ann.endTimeSec)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                )

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text("Region als WAV exportieren") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        viewModel.exportRegionWav(ann.startTimeSec, ann.endTimeSec)
                                    },
                                    leadingIcon = { Icon(Icons.Default.AudioFile, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Region als PNG exportieren") },
                                    onClick = {
                                        contextMenuAnnotationId = null
                                        viewModel.exportRegionPng(ann.startTimeSec, ann.endTimeSec)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Image, null) }
                                )

                                HorizontalDivider()

                                DropdownMenuItem(
                                    text = { Text("Vergleichen — Sonogramm laden") },
                                    onClick = {
                                        contextMenuAnnotationId = null
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
                                        viewModel.zoomToRange(ann.startTimeSec, ann.endTimeSec)
                                        val chooser = javax.swing.JFileChooser().apply {
                                            dialogTitle = "Audio zum Vergleichen"
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Audio (WAV, MP3, FLAC, M4A)", "wav", "mp3", "flac", "m4a", "m4p")
                                        }
                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            viewModel.importCompareFile(chooser.selectedFile)
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.AudioFile, null) }
                                )
                            }
                        }

                        // U2b: Canvas-Kontextmenue (Rechtsklick auf leere Stelle)
                        DropdownMenu(
                            expanded = showCanvasContextMenu,
                            onDismissRequest = { showCanvasContextMenu = false },
                            offset = canvasContextMenuOffset
                        ) {
                            DropdownMenuItem(
                                text = { Text("Neue Annotation hier") },
                                onClick = {
                                    viewModel.createAnnotationAt(canvasContextMenuTimeSec)
                                    showCanvasContextMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Hierhin zoomen") },
                                onClick = {
                                    viewModel.zoomToTime(canvasContextMenuTimeSec)
                                    showCanvasContextMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.ZoomIn, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Ab hier abspielen") },
                                onClick = {
                                    viewModel.playFromTime(canvasContextMenuTimeSec)
                                    showCanvasContextMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Search, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("BirdNET: Sichtbaren Bereich scannen") },
                                onClick = {
                                    viewModel.scanBirdNetVisible()
                                    showCanvasContextMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Search, null) }
                            )
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
                    // Draggable Splitter mit detectVerticalDragGestures fuer korrekte Pointer-Capture
                    // (HorizontalSplitter nutzt raw awaitPointerEvent, das keine Pointer-Capture macht —
                    //  bei 4dp Hoehe verliert der Handler sofort die Events)
                    val splitterInteraction = remember { MutableInteractionSource() }
                    val splitterHovered by splitterInteraction.collectIsHoveredAsState()
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .hoverable(splitterInteraction)
                            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    val deltaDp = dragAmount / density.density
                                    galleryHeight = (galleryHeight - deltaDp).coerceIn(80f, 500f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (splitterHovered) Color(0xFF4CAF50) else Color(0xFF333333))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isCapsLockActive) Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small
                                ) else Modifier
                            )
                    ) {
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
                            referencePlaybackPositionSec = if (uiState.playbackMode == PlaybackMode.REFERENCE) uiState.playbackPositionSec else 0f,
                            referenceAudioDurationSec = uiState.referenceAudioDurationSec,
                            speciesLocale = speciesLocale,
                            isSyncMode = uiState.isSyncMode,
                            refViewOffsetSec = uiState.refViewOffsetSec,
                            visibleDurationSec = uiState.viewEndSec - uiState.viewStartSec,
                            onRefOffsetChange = viewModel::setRefViewOffset,
                            modifier = Modifier.fillMaxWidth().height(galleryHeight.dp)
                        )
                    }
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


// ====================================================================
// U1: Globale Tastaturkuerzel
// ====================================================================

private fun handleGlobalShortcut(
    event: KeyEvent,
    viewModel: CompareViewModel,
    uiState: CompareUiState
): Boolean {
    val ctrl = event.isCtrlPressed
    return when {
        // === Datei ===
        ctrl && event.key == Key.S -> { viewModel.saveProjectManual(); true }
        ctrl && event.key == Key.Z -> { viewModel.undo(); true }

        // === Wiedergabe ===
        event.key == Key.Escape -> {
            when {
                uiState.isPlaying -> { viewModel.stopPlayback(); true }
                uiState.selectedAnnotationIds.isNotEmpty() -> { viewModel.clearMultiSelection(); true }
                uiState.activeAnnotationId != null -> { viewModel.clearSelection(); true }
                else -> false
            }
        }

        // === Navigation (S + Pfeil wird in onPreviewKeyEvent behandelt) ===
        event.key == Key.DirectionLeft && !ctrl -> { viewModel.navigateLeft(); true }
        event.key == Key.DirectionRight && !ctrl -> { viewModel.navigateRight(); true }
        event.key == Key.DirectionLeft && ctrl -> { viewModel.previousAnnotation(); true }
        event.key == Key.DirectionRight && ctrl -> { viewModel.nextAnnotation(); true }

        // === Zoom ===
        event.key == Key.Plus || event.key == Key.Equals -> { viewModel.zoomIn(); true }
        event.key == Key.Minus -> { viewModel.zoomOut(); true }
        event.key == Key.Zero && ctrl -> { viewModel.zoomReset(); true }

        // === Multi-Select (U3) ===
        ctrl && event.key == Key.A -> { viewModel.selectAllAnnotations(); true }

        // === Annotation ===
        event.key == Key.Delete || event.key == Key.Backspace -> {
            if (uiState.selectedAnnotationIds.isNotEmpty()) {
                viewModel.deleteSelectedAnnotations(); true
            } else {
                val annId = uiState.activeAnnotationId
                if (annId != null) { viewModel.deleteAnnotation(annId); true } else false
            }
        }

        // === Loop-Toggle (L-Taste, AP-29 / AP-47: CapsLock = Referenz-Loop) ===
        event.key == Key.L && !ctrl && uiState.editingLabelId == null -> {
            if (uiState.playbackMode == PlaybackMode.REFERENCE) viewModel.toggleReferenceLoop() else viewModel.toggleLoop()
            true
        }

        // === Analyse ===
        event.key == Key.F5 -> { viewModel.fullScanBirdNet(); true }
        event.key == Key.F2 -> {
            val annId = uiState.activeAnnotationId
            if (annId != null) { viewModel.startEditingLabel(annId); true } else false
        }

        else -> false
    }
}


private var lastExportDir: File? = null

/** Delegiert an AudioExporter.ffmpegAvailable. */
private val ffmpegAvailable: Boolean get() = AudioExporter.ffmpegAvailable

/** Rueckgabe: Datei + Typ ("png", "wav", "flac", "mp3", "wav10x") */
private fun showExportDialog(suggestedName: String = "sonogramm_export.png", isBatMode: Boolean = false): Pair<File, String>? {
    val exportSettings = ch.etasystems.amsel.data.SettingsStore.load()
    val startDir = lastExportDir
        ?: exportSettings.resolvedExportDir()
        ?: javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory
    val pngFilter = FileNameExtensionFilter("Sonogramm (*.png)", "png")
    val wavFilter = FileNameExtensionFilter("Audio WAV (*.wav)", "wav")
    val mp3Filter = FileNameExtensionFilter("Audio MP3 (*.mp3)", "mp3")
    val flacFilter = FileNameExtensionFilter("Audio FLAC (*.flac)", "flac")
    val m4aFilter = FileNameExtensionFilter("Audio M4A/AAC (*.m4a)", "m4a")
    val wav10xFilter = FileNameExtensionFilter("Audio WAV 10x Zeitdehnung (*.wav)", "wav")

    // Filter-Typ-Zuordnung
    val filterToType = mutableMapOf(
        pngFilter to "png",
        wavFilter to "wav"
    )
    if (ffmpegAvailable) {
        filterToType[mp3Filter] = "mp3"
        filterToType[flacFilter] = "flac"
        filterToType[m4aFilter] = "m4a"
    }
    if (isBatMode) filterToType[wav10xFilter] = "wav10x"

    val chooser = JFileChooser(startDir).apply {
        dialogTitle = "Region exportieren"
        addChoosableFileFilter(pngFilter)
        addChoosableFileFilter(wavFilter)
        if (ffmpegAvailable) {
            addChoosableFileFilter(flacFilter)
            addChoosableFileFilter(m4aFilter)
            addChoosableFileFilter(mp3Filter)
        }
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
