package ch.etasystems.amsel.ui.compare

import androidx.compose.ui.geometry.Rect
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.MatchResult
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.similarity.CosineSimilarityMetric
import ch.etasystems.amsel.core.similarity.DtwSimilarityMetric
import ch.etasystems.amsel.core.similarity.EmbeddingSimilarityMetric
import ch.etasystems.amsel.core.similarity.OnnxSimilarityMetric
import ch.etasystems.amsel.core.similarity.SimilarityEngine
import ch.etasystems.amsel.core.similarity.SimilarityMetric
import ch.etasystems.amsel.data.ComparisonAlgorithm
import ch.etasystems.amsel.data.api.XenoCantoRecordingProvider
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.reference.ReferenceDownloader
import ch.etasystems.amsel.data.reference.ReferenceLibrary
import ch.etasystems.amsel.data.reference.ReferenceLibraryFeatureProvider
import org.slf4j.LoggerFactory
import ch.etasystems.amsel.data.api.XenoCantoApi
import ch.etasystems.amsel.core.model.AuditEntry
import ch.etasystems.amsel.core.model.VolumePoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.io.File

data class CompareUiState(
    val audioFile: File? = null,
    val audioSegment: AudioSegment? = null,
    val pcmCache: ch.etasystems.amsel.core.audio.PcmCacheFile? = null,  // Random-Access Cache fuer grosse Dateien

    // Spektrogramme
    val overviewSpectrogramData: SpectrogramData? = null,
    val originalOverviewData: SpectrogramData? = null,
    val zoomedSpectrogramData: SpectrogramData? = null,
    val originalZoomedData: SpectrogramData? = null,

    // Zeitnavigation
    val viewStartSec: Float = 0f,
    val viewEndSec: Float = 0f,
    val totalDurationSec: Float = 0f,

    // Auswahl
    val selection: Rect? = null,
    val selectionMode: Boolean = false,

    // Annotationen
    val annotations: List<Annotation> = emptyList(),
    val activeAnnotationId: String? = null,
    val editingLabelId: String? = null,
    val selectedAnnotationIds: Set<String> = emptySet(),

    // Playback
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val playbackPositionSec: Float = 0f,

    // Status
    val isProcessing: Boolean = false,
    val isSearching: Boolean = false,
    val isComputingZoom: Boolean = false,
    val statusText: String = "",
    val sidebarStatus: String = "",  // Fortschritt in der Seitenleiste anzeigen
    val detectedMode: String = "",
    val searchProgress: Float = 0f,

    // Filter
    val filterConfig: FilterConfig = FilterConfig(),
    val showFilterPanel: Boolean = false,

    // Export
    val lastExportFile: File? = null,
    val exportBlackAndWhite: Boolean = false,

    // API-Key + Download
    val showApiKeyDialog: Boolean = false,
    val hasApiKey: Boolean = false,
    val showDownloadDialog: Boolean = false,
    val downloadProgress: ReferenceDownloader.DownloadProgress = ReferenceDownloader.DownloadProgress(),
    val referenceSpeciesCount: Int = 0,
    val referenceRecordingCount: Int = 0,

    // Referenz-Sonogramm (ausgewaehlter Treffer)
    val selectedMatchResult: MatchResult? = null,
    val playingReferenceId: String = "",
    val downloadingReferenceId: String = "",

    // Vergleichsdatei (zweites Sonogramm)
    val compareFile: File? = null,
    val compareSpectrogramData: SpectrogramData? = null,
    val compareOriginalData: SpectrogramData? = null,

    // Palette-Version: wird bei jedem Farbwechsel erhoeht → Bitmaps invalidieren
    val paletteVersion: Int = 0,

    // Dynamik-Anzeige: einstellbarer dB-Bereich und Gamma
    val displayDbRange: Float = 10f,     // Dynamikbereich in dB (3..12, 0.5er Schritte), Default 10
    val displayGamma: Float = 1.0f,     // Gamma-Korrektur (0.3..3.0, 1.0 = linear)
    val isNormalized: Boolean = false,       // Normalisierung aktiv (Toggle)
    val normGainDb: Float = 0f,              // Angewandter Gain in dB (fuer Anzeige)
    val normReferenceMaxDb: Float = 0f,      // Eingefrorener maxValue bei Normalisierung (0 = nicht eingefroren)

    // Lautstaerke-Automation (Volume Envelope)
    val volumeEnvelope: List<VolumePoint> = emptyList(),  // Breakpoints, sortiert nach Zeit
    val volumeEnvelopeActive: Boolean = false,            // Envelope aktiv
    val selectedVolumeIndex: Int = -1,                    // Selektierter Punkt (-1 = keiner)
    val displayFreqZoom: Float = 1.0f,  // Frequenz-Zoom: 1.0 = normal, 2.0 = doppelt (halber Bereich)
    val useLogFreqAxis: Boolean = false, // Frequenzachse: false = linear (Hz), true = logarithmisch (Mel-Bins direkt)
    val fullView: Boolean = false,      // Vollansicht: nur Sonogramm, kein Overview/Timeline/Ergebnisse
    val editMode: Boolean = false,       // Edit-Modus: Annotations-Raender per Drag anpassbar

    // Grosse-Dateien-Modus: Samples werden NICHT im RAM gehalten
    val isLargeFile: Boolean = false,    // true wenn Datei > 60 Sekunden
    val audioDurationSec: Float = 0f,    // Echte Audio-Dauer (auch wenn audioSegment null)
    val audioSampleRate: Int = 0,        // Sample-Rate (auch wenn audioSegment null)
    val audioOffsetSec: Float = 0f,      // Zeitversatz: wo Audio im virtuellen Zeitfenster startet (kurze Aufnahmen)

    // Audit-Trail: Protokolliert alle Verarbeitungsschritte fuer wissenschaftliche Reproduzierbarkeit
    val auditLog: List<AuditEntry> = emptyList(),

    // Chunk-Verarbeitung
    val chunkManager: ch.etasystems.amsel.core.audio.ChunkManager? = null,  // null = kein Chunking
    val activeChunkIndex: Int = 0,

    // Projekt
    val projectFile: File? = null,
    val projectDirty: Boolean = false
) {
    val activeAnnotation: Annotation?
        get() = annotations.find { it.id == activeAnnotationId }

    val hasResults: Boolean
        get() = annotations.any { it.matchResults.isNotEmpty() }

    val playbackPositionText: String
        get() {
            if (!isPlaying && !isPaused) return ""
            val s = playbackPositionSec
            val m = (s / 60).toInt()
            val sec = (s % 60).toInt()
            // Millisekunden-Praezision (3 Dezimalstellen) fuer wissenschaftliche Genauigkeit
            val millis = ((s - s.toInt()) * 1000).toInt().coerceIn(0, 999)
            return "$m:${sec.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
        }
}

/** Lokaler ViewModel-State: Felder die keinem Manager gehoeren. */
data class LocalState(
    val isProcessing: Boolean = false,
    val statusText: String = "",
    val sidebarStatus: String = "",
    val detectedMode: String = ""
)

/**
 * ViewModel für den Hauptbildschirm.
 * Orchestriert Audio-Import, Playback, Filter (debounced), Annotationen, Vergleich, Export und Event-Detection.
 *
 * WICHTIG: Viewport-Navigation ist in zwei Schichten getrennt:
 * - updateViewRange(): billig, nur Koordinaten updaten → UI reagiert sofort
 * - computeZoomedSpectrogramDebounced(): teuer, FFT → 400ms Debounce
 *
 * State-Propagation via combine(): 7 Manager-StateFlows + LocalState → CompareUiState (reaktiv).
 */
class CompareViewModel {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val _localState = MutableStateFlow(LocalState())
    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Manager: Audio (Import, Decoding, Chunking, Datei-Management)
    private val audioManager = AudioManager(
        onProgressUpdate = { msg -> _localState.update { it.copy(sidebarStatus = msg) } }
    )

    // Manager: Volume Envelope
    private val volumeManager = VolumeManager(
        onStateChanged = { spectrogramManager.incrementPaletteVersion(); projectManager.markDirty() },
        onEnvelopeModified = { spectrogramManager.applyFilterDebounced(spectrogramManager.state.value.filterConfig) }
    )
    private val xenoCantoApi = XenoCantoApi()
    private val referenceLibrary = ReferenceLibrary()
    private val featureCacheProvider = ReferenceLibraryFeatureProvider(referenceLibrary)
    private val recordingProvider = XenoCantoRecordingProvider(xenoCantoApi)
    private var similarityEngine = SimilarityEngine(
        recordings = recordingProvider,
        cache = featureCacheProvider,
        metric = createMetric(SettingsStore.load().comparisonAlgorithm)
    )
    private val referenceDownloader = ReferenceDownloader(referenceLibrary, xenoCantoApi)

    // Manager: Spektrogramm (FFT, Viewport, Zoom, Filter, Display)
    private val spectrogramManager = SpectrogramManager(
        audioData = AudioDataProvider(
            audioFile = { _uiState.value.audioFile },
            audioSegment = { _uiState.value.audioSegment },
            pcmCache = { _uiState.value.pcmCache },
            volumeEnvelope = { _uiState.value.volumeEnvelope },
            volumeEnvelopeActive = { _uiState.value.volumeEnvelopeActive }
        ),
        onStatusUpdate = { statusText, sidebarStatus ->
            _localState.update {
                it.copy(
                    statusText = statusText ?: it.statusText,
                    sidebarStatus = sidebarStatus ?: it.sidebarStatus
                )
            }
        },
        onProcessingChanged = { processing ->
            _localState.update { it.copy(isProcessing = processing) }
        },
        onAuditEntry = { action, details -> projectManager.addAuditEntry(action, details) },
        onDirtyChanged = { projectManager.markDirty() },
        onCompareFileImported = { file -> audioManager.setCompareFile(file) },
        onAudioSegmentNormalized = { segment ->
            audioManager.setNormalizedSegment(segment)
        }
    )

    // Manager: Annotationen (CRUD, Selektion, Labels, Edit-Mode, Rubber-Band)
    private val annotationManager = AnnotationManager(
        viewport = AnnotationViewportProvider(
            viewStartSec = { _uiState.value.viewStartSec },
            viewEndSec = { _uiState.value.viewEndSec },
            totalDurationSec = { _uiState.value.totalDurationSec },
            zoomedSpectrogramData = { _uiState.value.zoomedSpectrogramData },
            overviewSpectrogramData = { _uiState.value.overviewSpectrogramData }
        ),
        onDirtyChanged = { projectManager.markDirty() },
        onZoomToRange = { start, end -> spectrogramManager.zoomToRange(start, end) },
        onStatusUpdate = { msg -> _localState.update { it.copy(statusText = msg) } }
    )

    // Manager: Klassifizierung (BirdNET, Similarity, Event-Detection, Download)
    private val classificationManager = ClassificationManager(
        audioData = ClassificationAudioProvider(
            audioFile = { _uiState.value.audioFile },
            audioSegment = { _uiState.value.audioSegment },
            pcmCache = { _uiState.value.pcmCache },
            volumeEnvelope = { _uiState.value.volumeEnvelope },
            volumeEnvelopeActive = { _uiState.value.volumeEnvelopeActive },
            filterConfig = { _uiState.value.filterConfig },
            maxFreqHz = { spectrogramManager.maxFreqHz },
            totalDurationSec = { _uiState.value.totalDurationSec },
            viewStartSec = { _uiState.value.viewStartSec },
            viewEndSec = { _uiState.value.viewEndSec },
            zoomedSpectrogramData = { _uiState.value.zoomedSpectrogramData },
            annotations = { _uiState.value.annotations },
            activeAnnotation = { _uiState.value.activeAnnotation },
            activeAnnotationId = { _uiState.value.activeAnnotationId },
            chunkManager = { _uiState.value.chunkManager },
            audioSampleRate = { _uiState.value.audioSampleRate }
        ),
        annotationManager = annotationManager,
        xenoCantoApi = xenoCantoApi,
        referenceLibrary = referenceLibrary,
        referenceDownloader = referenceDownloader,
        similarityEngine = similarityEngine,
        onStatusUpdate = { statusText, sidebarStatus ->
            _localState.update {
                it.copy(
                    statusText = statusText ?: it.statusText,
                    sidebarStatus = sidebarStatus ?: it.sidebarStatus
                )
            }
        },
        onProcessingChanged = { processing ->
            _localState.update { it.copy(isProcessing = processing) }
        },
        onAuditEntry = { action, details -> projectManager.addAuditEntry(action, details) },
        onDirtyChanged = { projectManager.markDirty() },
        onZoomToRange = { start, end -> spectrogramManager.zoomToRange(start, end) }
    )

    // Manager: Playback (Play/Pause/Stop, Position, Referenz-Audio)
    private val playbackManager = PlaybackManager(
        onStatusUpdate = { msg ->
            _localState.update { it.copy(sidebarStatus = if (msg.isNotBlank()) msg else it.sidebarStatus) }
        },
        onViewportFollow = { newStart, newEnd ->
            spectrogramManager.setViewportPosition(newStart, newEnd)
        },
        onPlaybackStopped = { viewportChanged ->
            if (viewportChanged) spectrogramManager.commitViewRange()
        }
    )

    // Manager: Projekt (Load/Save/AutoSave, Audit-Trail, Export-State)
    private val projectManager = ProjectManager(
        onStatusUpdate = { msg -> _localState.update { it.copy(statusText = msg) } }
    )

    // Manager: Export (Sonogramm-PNG + Audio-WAV/MP3)
    private val exportManager = ExportManager(
        scope = scope,
        maxFreqHz = { spectrogramManager.maxFreqHz },
        onAuditEntry = { action, details -> projectManager.addAuditEntry(action, details) },
        onExportFileChanged = { file -> projectManager.setLastExportFile(file) },
        onLocalStateUpdate = { processing, statusText, sidebarStatus ->
            _localState.update {
                it.copy(
                    isProcessing = processing ?: it.isProcessing,
                    statusText = statusText ?: it.statusText,
                    sidebarStatus = sidebarStatus ?: it.sidebarStatus
                )
            }
        }
    )

    companion object {
        private const val INITIAL_ZOOM_DURATION_SEC = 30f

        /** Erstellt die passende SimilarityMetric fuer den gewaehlten Algorithmus */
        fun createMetric(algorithm: ComparisonAlgorithm): SimilarityMetric = when (algorithm) {
            ComparisonAlgorithm.MFCC_BASIC -> CosineSimilarityMetric
            ComparisonAlgorithm.MFCC_DTW -> DtwSimilarityMetric
            ComparisonAlgorithm.ONNX_EFFICIENTNET -> OnnxSimilarityMetric
            ComparisonAlgorithm.EMBEDDING -> EmbeddingSimilarityMetric()
            ComparisonAlgorithm.BIRDNET -> EmbeddingSimilarityMetric() // Fallback-Metrik, BirdNET wird separat aufgerufen
            ComparisonAlgorithm.BIRDNET_V3 -> EmbeddingSimilarityMetric() // Fallback-Metrik, BirdNET V3 wird separat via ONNX aufgerufen
        }
    }

    init {
        // ReferenceLibrary initialisieren (Ordner-Scan oder JSON-Index laden)
        scope.launch { referenceLibrary.initialize() }

        // ================================================================
        // Reaktive State-Propagation: combine() ersetzt die 7 syncXxxState()-Bridges
        // Option A: Verschachtelte combine() (typsicher, kein Casting)
        // ================================================================
        scope.launch {
            combine(
                combine(volumeManager.state, audioManager.state, playbackManager.state) { v, a, p -> Triple(v, a, p) },
                combine(spectrogramManager.state, annotationManager.state) { s, an -> Pair(s, an) },
                combine(classificationManager.state, projectManager.state, _localState) { c, pr, local -> Triple(c, pr, local) }
            ) { vap, san, cpl ->
                val (volume, audio, playback) = vap
                val (spectro, annotation) = san
                val (classification, project, local) = cpl
                CompareUiState(
                    // Audio (10 Felder)
                    audioFile = audio.audioFile,
                    audioSegment = audio.audioSegment,
                    pcmCache = audio.pcmCache,
                    compareFile = audio.compareFile,
                    isLargeFile = audio.isLargeFile,
                    audioDurationSec = audio.audioDurationSec,
                    audioSampleRate = audio.audioSampleRate,
                    audioOffsetSec = audio.audioOffsetSec,
                    chunkManager = audio.chunkManager,
                    activeChunkIndex = audio.activeChunkIndex,
                    // Volume (3 Felder)
                    volumeEnvelope = volume.volumeEnvelope,
                    volumeEnvelopeActive = volume.volumeEnvelopeActive,
                    selectedVolumeIndex = volume.selectedVolumeIndex,
                    // Playback (5 Felder)
                    isPlaying = playback.isPlaying,
                    isPaused = playback.isPaused,
                    playbackPositionSec = playback.playbackPositionSec,
                    playingReferenceId = playback.playingReferenceId,
                    downloadingReferenceId = playback.downloadingReferenceId,
                    // Spektrogramm (21 Felder)
                    overviewSpectrogramData = spectro.overviewSpectrogramData,
                    originalOverviewData = spectro.originalOverviewData,
                    zoomedSpectrogramData = spectro.zoomedSpectrogramData,
                    originalZoomedData = spectro.originalZoomedData,
                    compareSpectrogramData = spectro.compareSpectrogramData,
                    compareOriginalData = spectro.compareOriginalData,
                    isComputingZoom = spectro.isComputingZoom,
                    paletteVersion = spectro.paletteVersion,
                    viewStartSec = spectro.viewStartSec,
                    viewEndSec = spectro.viewEndSec,
                    totalDurationSec = spectro.totalDurationSec,
                    displayFreqZoom = spectro.displayFreqZoom,
                    useLogFreqAxis = spectro.useLogFreqAxis,
                    fullView = spectro.fullView,
                    filterConfig = spectro.filterConfig,
                    showFilterPanel = spectro.showFilterPanel,
                    displayDbRange = spectro.displayDbRange,
                    displayGamma = spectro.displayGamma,
                    isNormalized = spectro.isNormalized,
                    normGainDb = spectro.normGainDb,
                    normReferenceMaxDb = spectro.normReferenceMaxDb,
                    // Annotation (8 Felder)
                    annotations = annotation.annotations,
                    activeAnnotationId = annotation.activeAnnotationId,
                    editingLabelId = annotation.editingLabelId,
                    selectedAnnotationIds = annotation.selectedAnnotationIds,
                    selection = annotation.selection,
                    selectionMode = annotation.selectionMode,
                    editMode = annotation.editMode,
                    selectedMatchResult = annotation.selectedMatchResult,
                    // Klassifizierung (9 Felder)
                    isSearching = classification.isSearching,
                    searchProgress = classification.searchProgress,
                    showApiKeyDialog = classification.showApiKeyDialog,
                    hasApiKey = classification.hasApiKey,
                    showDownloadDialog = classification.showDownloadDialog,
                    downloadProgress = classification.downloadProgress,
                    referenceSpeciesCount = classification.referenceSpeciesCount,
                    referenceRecordingCount = classification.referenceRecordingCount,
                    // Projekt (5 Felder)
                    projectFile = project.projectFile,
                    projectDirty = project.projectDirty,
                    auditLog = project.auditLog,
                    lastExportFile = project.lastExportFile,
                    exportBlackAndWhite = project.exportBlackAndWhite,
                    // Lokal (4 Felder)
                    isProcessing = local.isProcessing,
                    statusText = local.statusText,
                    sidebarStatus = local.sidebarStatus,
                    detectedMode = local.detectedMode
                )
            }.collect { _uiState.value = it }
        }

        // Side-Effect: Zoom-Aenderung invalidiert die Rubber-Band-Selektion (Pixel-Koordinaten veraltet)
        scope.launch {
            var prevZoomedData: SpectrogramData? = null
            spectrogramManager.state.collect { sp ->
                if (prevZoomedData != null && prevZoomedData !== sp.zoomedSpectrogramData) {
                    annotationManager.clearSelection()
                }
                prevZoomedData = sp.zoomedSpectrogramData
            }
        }

        // Settings laden
        val settings = SettingsStore.load()
        spectrogramManager.maxFreqHz = settings.maxFrequencyHz.toFloat()
        if (settings.xenoCantoApiKey.isNotBlank()) {
            xenoCantoApi.apiKey = settings.xenoCantoApiKey
            classificationManager.initApiKeyState(true)
        }

        // Letztes Projekt automatisch laden (verzoegert, nur wenn nichts offen)
        if (settings.lastProjectPath.isNotBlank()) {
            val lastProject = File(settings.lastProjectPath)
            if (lastProject.exists()) {
                scope.launch {
                    delay(1000)
                    // Nur laden wenn der User in der Zwischenzeit nichts geoeffnet hat
                    if (_uiState.value.audioFile == null) {
                        _localState.update { it.copy(statusText = "Lade letztes Projekt: ${lastProject.name}...") }
                        loadProject(lastProject)
                    }
                }
            }
        }

        // Auto-Save alle 5 Minuten (still, nur wenn dirty)
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                val proj = projectManager.state.value
                if (proj.projectDirty && proj.projectFile != null) {
                    autoSaveProject()
                }
            }
        }
    }

    // ====================================================================
    // Datei schliessen
    // ====================================================================

    /** Gibt die Liste aller gecachten Arten zurueck — delegiert an ClassificationManager */
    fun getReferenceSpeciesList(): List<String> = classificationManager.getReferenceSpeciesList()

    fun closeFile() {
        playbackManager.reset()
        // Offenes Projekt speichern bevor wir schliessen
        autoSaveProject()
        // lastProjectPath leeren
        projectManager.clearLastProjectPath()
        // Audio-State aufraumen (inkl. PCM-Cache)
        audioManager.closeAudio()
        volumeManager.reset()
        spectrogramManager.reset()
        annotationManager.reset()
        classificationManager.reset()
        projectManager.reset()
        _localState.value = LocalState()
        System.gc()  // RAM freigeben
    }

    // ====================================================================
    // Projekt — Auto-Save + Laden (Orchestrierung im ViewModel, Serialisierung im ProjectManager)
    // ====================================================================

    /** Speichert den aktuellen Zustand in die Projektdatei (wenn vorhanden). */
    fun autoSaveProject() {
        val proj = projectManager.state.value
        if (proj.projectFile == null || !proj.projectDirty) return
        val state = _uiState.value
        val audioFile = state.audioFile ?: return

        val project = projectManager.buildProject(
            audioFile = audioFile,
            audioDurationSec = state.audioDurationSec,
            audioSampleRate = state.audioSampleRate,
            annotations = state.annotations,
            volumeEnvelope = state.volumeEnvelope,
            volumeEnvelopeActive = state.volumeEnvelopeActive,
            filterConfig = state.filterConfig,
            displayDbRange = state.displayDbRange,
            displayGamma = state.displayGamma,
            isNormalized = state.isNormalized,
            normGainDb = state.normGainDb
        )
        projectManager.serializeProject(project)
    }

    /**
     * Manuell Projekt speichern (fuer kurze Dateien ohne Auto-Projekt).
     * @param chosenFile wenn null, wird automatisch neben Audio-Datei gespeichert
     */
    fun saveProjectManual(chosenFile: File? = null) {
        val state = _uiState.value
        val audioFile = state.audioFile ?: run {
            _localState.update { it.copy(statusText = "Kein Audio -- zuerst Datei importieren") }
            return
        }
        val pf = projectManager.prepareManualSave(chosenFile, audioFile) ?: return
        autoSaveProject()
        _localState.update { it.copy(statusText = "Projekt gespeichert: ${pf.name}") }
    }

    /** Laedt ein AMSEL-Projekt aus einer .amsel.json Datei. */
    fun loadProject(projectFile: File) {
        scope.launch {
            try {
                val project = projectManager.deserializeProject(projectFile) ?: return@launch
                // Audio-Datei neben der Projektdatei suchen
                val audioFile = File(projectFile.parentFile, project.audio.originalFileName)
                if (!audioFile.exists()) {
                    _localState.update { it.copy(statusText = "Audio nicht gefunden: ${project.audio.originalFileName}") }
                    return@launch
                }

                // Audio importieren — direkt als suspend aufrufen, damit setInitialSpectrograms()
                // garantiert VOR restoreFromProject() abgeschlossen ist (kein separater scope.launch)
                playbackManager.stopPlayback()
                importAudioSuspend(audioFile)

                // Volume-State im Manager wiederherstellen
                volumeManager.restoreFromProject(project.volumeEnvelope, project.volumeEnvelopeActive)

                // Spektrogramm-State im Manager wiederherstellen
                val restoredFilter = project.filterPreset?.toFilterConfig() ?: FilterConfig()
                spectrogramManager.restoreFromProject(
                    filterConfig = restoredFilter,
                    displayDbRange = project.displayDbRange,
                    displayGamma = project.displayGamma,
                    isNormalized = project.isNormalized,
                    normGainDb = project.normGainDb
                )

                // Annotations-State im Manager wiederherstellen
                annotationManager.restoreFromProject(project.annotations)

                // Projekt-State im ProjectManager wiederherstellen
                projectManager.setProjectLoaded(projectFile, project.auditLog)
            } catch (e: Exception) {
                _localState.update { it.copy(statusText = "Projekt-Fehler: ${e.message}") }
            }
        }
    }

    // ====================================================================
    // Chunk-Navigation
    // ====================================================================

    /** Wechselt zum angegebenen Chunk und aktualisiert Viewport. */
    fun selectChunk(chunkIndex: Int) {
        val range = audioManager.selectChunk(chunkIndex) ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    /** Naechster Chunk. */
    fun nextChunk() {
        val range = audioManager.nextChunk() ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    /** Vorheriger Chunk. */
    fun previousChunk() {
        val range = audioManager.previousChunk() ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    // ====================================================================
    // Audio-Import
    // ====================================================================

    /**
     * Laedt ein Sonogramm-Bild (PNG/JPG) als visuelles Spektrogramm (ohne Audio).
     * Liest PNG tEXt-Chunks fuer AMSEL-Metadaten wenn vorhanden.
     */
    fun importSonogramImage(file: File) {
        scope.launch {
            _localState.update { it.copy(isProcessing = true, sidebarStatus = "Lade Sonogramm-Bild...") }
            try {
                val image = javax.imageio.ImageIO.read(file) ?: throw Exception("Bild konnte nicht gelesen werden")
                val width = image.width
                val height = image.height

                // Bild in SpectrogramData konvertieren (Grauwert → log10-Wert)
                val nMels = height
                val nFrames = width
                val matrix = FloatArray(nMels * nFrames)

                for (y in 0 until height) {
                    val mel = height - 1 - y  // Flip: oben = hohe Frequenz
                    for (x in 0 until width) {
                        val rgb = image.getRGB(x, y)
                        val r = (rgb shr 16) and 0xFF
                        val g = (rgb shr 8) and 0xFF
                        val b = rgb and 0xFF
                        val brightness = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                        matrix[mel * nFrames + x] = -10f + brightness * 10f
                    }
                }

                val spectrogramData = SpectrogramData(
                    matrix = matrix,
                    nMels = nMels,
                    nFrames = nFrames,
                    sampleRate = 48000,
                    hopSize = 512,
                    fMin = 0f,
                    fMax = 16000f
                )

                val durationSec = nFrames.toFloat() / 100f

                // Alle Manager zuruecksetzen + audioFile fuer Bild-Import setzen
                audioManager.closeAudio()
                volumeManager.reset()
                spectrogramManager.reset()
                annotationManager.reset()
                classificationManager.reset()
                projectManager.reset()
                audioManager.setImageFile(file)

                _localState.update {
                    LocalState(
                        statusText = "${file.name} — Sonogramm-Bild (${width}x${height})",
                        detectedMode = "image"
                    )
                }
                spectrogramManager.setImageSpectrograms(spectrogramData, durationSec)
            } catch (e: Exception) {
                _localState.update { it.copy(isProcessing = false, statusText = "Bild-Fehler: ${e.message}") }
            }
        }
    }

    fun importAudio(file: File) {
        playbackManager.stopPlayback()
        scope.launch { importAudioSuspend(file) }
    }

    /**
     * Suspend-Variante des Audio-Imports. Wird direkt von loadProject() aufgerufen
     * damit kein separater scope.launch die Reihenfolge mit restoreFromProject() zerstoert.
     */
    private suspend fun importAudioSuspend(file: File) {
        _localState.update { it.copy(isProcessing = true, sidebarStatus = "Dekodiere Audio...") }

        try {
            val settings = SettingsStore.load()

            // Audio-Import delegieren an AudioManager
            val result = audioManager.importAudio(
                file = file,
                maxFreqHz = spectrogramManager.maxFreqHz,
                minDisplayDurationSec = settings.minDisplayDurationSec,
                shortFileStartPct = settings.shortFileStartPct,
                chunkLengthMin = settings.chunkLengthMin,
                chunkOverlapSec = settings.chunkOverlapSec,
                initialZoomDurationSec = INITIAL_ZOOM_DURATION_SEC
            )

            // Spektrogramm-Daten im Manager setzen (resettet auch Filter/Display)
            spectrogramManager.setInitialSpectrograms(
                overviewData = result.overviewSpectrogramData,
                zoomedData = result.zoomedSpectrogramData,
                totalDurationSec = result.duration,
                initialViewEnd = result.initialViewEnd
            )

            // Annotations zuruecksetzen
            annotationManager.reset()
            // Projekt-Referenz zuruecksetzen
            projectManager.resetForNewAudio()

            // Orchestrierung: lokale UI-Felder aktualisieren
            _localState.update {
                it.copy(
                    isProcessing = false,
                    statusText = "${file.name} — ${result.duration.format(1)}s @ ${result.sampleRate} Hz",
                    sidebarStatus = "",
                    detectedMode = result.mode
                )
            }

            // Lange Dateien: Projekt automatisch erstellen
            if (result.hasChunks) {
                projectManager.autoCreateProject(file, result.duration, result.sampleRate, settings)
            }
        } catch (e: Exception) {
            _localState.update {
                it.copy(isProcessing = false, statusText = "Fehler: ${e.message}")
            }
        }
    }

    // ====================================================================
    // Playback
    // ====================================================================

    fun togglePlayPause() {
        val state = _uiState.value
        playbackManager.togglePlayPause(
            audioSegment = state.audioSegment,
            pcmCache = state.pcmCache,
            audioFile = state.audioFile,
            filterConfig = state.filterConfig,
            maxFreqHz = spectrogramManager.maxFreqHz,
            viewStartSec = state.viewStartSec,
            viewEndSec = state.viewEndSec,
            volumeEnvelope = state.volumeEnvelope,
            volumeEnvelopeActive = state.volumeEnvelopeActive,
            totalDurationSec = state.totalDurationSec
        )
    }

    fun stopPlayback() {
        playbackManager.stopPlayback()
    }

    fun playReferenceAudio(result: ch.etasystems.amsel.core.annotation.MatchResult) {
        playbackManager.playReferenceAudio(result, referenceLibrary, referenceDownloader)
    }

    // ====================================================================
    // Zoom-Navigation — delegiert an SpectrogramManager
    // ====================================================================

    fun updateViewRange(startSec: Float, endSec: Float) = spectrogramManager.updateViewRange(startSec, endSec)
    fun updateViewRangeLive(startSec: Float, endSec: Float) = spectrogramManager.updateViewRangeLive(startSec, endSec)
    fun commitViewRange() = spectrogramManager.commitViewRange()
    fun zoomToRange(startSec: Float, endSec: Float) = spectrogramManager.zoomToRange(startSec, endSec)
    fun zoomIn() = spectrogramManager.zoomIn()
    fun zoomOut() = spectrogramManager.zoomOut()
    fun zoomReset() = spectrogramManager.zoomReset()
    fun freqZoomIn() = spectrogramManager.freqZoomIn()
    fun freqZoomOut() = spectrogramManager.freqZoomOut()
    fun toggleFullView() = spectrogramManager.toggleFullView()
    fun toggleLogFreqAxis() = spectrogramManager.toggleLogFreqAxis()

    // ====================================================================
    // Vergleichsdatei (zweites Sonogramm)
    // ====================================================================

    fun importCompareFile(file: File) = spectrogramManager.importCompareFile(file)

    // ====================================================================
    // Palette & Display — delegiert an SpectrogramManager
    // ====================================================================

    fun refreshAfterPaletteChange() = spectrogramManager.refreshAfterPaletteChange()
    fun setDisplayDbRange(dbRange: Float) = spectrogramManager.setDisplayDbRange(dbRange)
    fun setDisplayGamma(gamma: Float) = spectrogramManager.setDisplayGamma(gamma)

    // ====================================================================
    // Auswahl — delegiert an AnnotationManager
    // ====================================================================

    fun updateSelection(rect: Rect?) = annotationManager.updateSelection(rect)
    fun toggleSelectionMode() = annotationManager.toggleSelectionMode()

    // ====================================================================
    // Annotationen — delegiert an AnnotationManager
    // ====================================================================

    fun createAnnotationFromSelection() = annotationManager.createAnnotationFromSelection()

    fun selectAnnotation(annotationId: String) {
        val annotation = annotationManager.selectAnnotation(annotationId)
        // Orchestrierung: bei nicht-generischem Label automatisch Referenz-Sonogramme suchen
        if (annotation != null && annotation.matchResults.isEmpty()) {
            val label = annotation.label
            val isGeneric = label.isBlank() || label.startsWith("Markierung_") ||
                label.startsWith("Zoom-Bereich") ||
                label.startsWith("Singvogel") || label.startsWith("Breitband") ||
                label.startsWith("Fledermaus") || label.startsWith("Insekt") ||
                label.startsWith("Amphibie") || label.startsWith("Grossvogel")
            if (!isGeneric) {
                classificationManager.searchSimilar()
            }
        }
    }

    fun zoomToEvent(annotationId: String) = annotationManager.zoomToEvent(annotationId)
    fun deleteAnnotation(annotationId: String) = annotationManager.deleteAnnotation(annotationId)
    fun updateAnnotationLabel(annotationId: String, label: String) = annotationManager.updateAnnotationLabel(annotationId, label)

    fun startEditingLabel(annotationId: String) {
        annotationManager.startEditingLabel(annotationId)
        classificationManager.stopSearching()
    }

    fun stopEditingLabel() = annotationManager.stopEditingLabel()
    fun toggleEditMode() = annotationManager.toggleEditMode()

    fun updateAnnotationBounds(
        id: String,
        startTimeSec: Float? = null,
        endTimeSec: Float? = null,
        lowFreqHz: Float? = null,
        highFreqHz: Float? = null
    ) = annotationManager.updateAnnotationBounds(id, startTimeSec, endTimeSec, lowFreqHz, highFreqHz)

    fun syncReferenceToEvent() = annotationManager.syncReferenceToEvent()

    // Multi-Select (U3)
    fun toggleMultiSelection(annotationId: String) = annotationManager.toggleMultiSelection(annotationId)
    fun selectAllAnnotations() = annotationManager.selectAll()
    fun clearMultiSelection() = annotationManager.clearMultiSelection()
    fun selectRange(toAnnotationId: String) = annotationManager.selectRange(toAnnotationId)
    fun deleteSelectedAnnotations() = annotationManager.deleteSelected()
    fun getSelectedAnnotations(): List<Annotation> = annotationManager.getSelectedAnnotations()

    // ====================================================================
    // Filter & Display — delegiert an SpectrogramManager
    // ====================================================================

    fun toggleFilterPanel() = spectrogramManager.toggleFilterPanel()
    fun toggleFilterBypass() = spectrogramManager.toggleFilterBypass()
    fun resetDisplaySettings() = spectrogramManager.resetDisplaySettings()
    fun applyFilterDebounced(config: FilterConfig) = spectrogramManager.applyFilterDebounced(config)

    // ====================================================================
    // Klassifizierung — delegiert an ClassificationManager
    // ====================================================================

    fun searchSimilar(speciesQuery: String = "") = classificationManager.searchSimilar(speciesQuery)
    fun fullScanBirdNet() = classificationManager.fullScanBirdNet()
    fun scanBirdNetRegion(annotationId: String) = classificationManager.scanBirdNetRegion(annotationId)
    fun detectEvents() = classificationManager.detectEvents()
    fun adoptCandidate(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        classificationManager.adoptCandidate(annotationId, candidate)

    fun verifyAnnotation(id: String) = annotationManager.verifyAnnotation(id)
    fun rejectAnnotation(id: String) = annotationManager.rejectAnnotation(id)
    fun unrejectAnnotation(id: String) = annotationManager.unrejectAnnotation(id)
    fun updateAnnotationNotes(id: String, notes: String) = annotationManager.updateAnnotationNotes(id, notes)
    fun verifySelected() = annotationManager.verifySelected()
    fun rejectSelected() = annotationManager.rejectSelected()

    fun verifyCandidateInAnnotation(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        annotationManager.verifyCandidateInAnnotation(annotationId, candidate.species)
    fun verifyCandidateAndSearch(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        classificationManager.verifyCandidateAndSearch(annotationId, candidate)
    fun rejectCandidateInAnnotation(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        annotationManager.rejectCandidateInAnnotation(annotationId, candidate.species)
    fun resetCandidateInAnnotation(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        annotationManager.resetCandidateInAnnotation(annotationId, candidate.species)

    // ====================================================================
    // Export — delegiert an ExportManager
    // ====================================================================

    fun exportAnnotation(outputFile: File) {
        exportManager.exportAnnotation(outputFile, _uiState.value)
    }

    fun toggleExportBlackAndWhite() = projectManager.toggleExportBlackAndWhite()

    /** Report exportieren (U4): PDF + CSV der selektierten (oder aller) Annotationen */
    fun exportReport() {
        scope.launch {
            val settings = ch.etasystems.amsel.data.SettingsStore.load()
            val allAnnotations = annotationManager.getSelectedAnnotations().ifEmpty {
                _uiState.value.annotations
            }
            val annotations = allAnnotations.filter { !it.rejected }
            if (annotations.isEmpty()) {
                _localState.update { it.copy(statusText = "Keine Annotationen vorhanden") }
                return@launch
            }

            // Warnung bei unverifizierten Chunks
            val unverifiedCount = annotations.count { !it.verified }
            if (unverifiedCount > 0) {
                val totalCount = annotations.size
                val userConfirmed = withContext(Dispatchers.IO) {
                    var confirmed = false
                    javax.swing.SwingUtilities.invokeAndWait {
                        val result = javax.swing.JOptionPane.showConfirmDialog(
                            null,
                            "$unverifiedCount von $totalCount Chunks nicht verifiziert.\nTrotzdem exportieren?",
                            "Export-Warnung",
                            javax.swing.JOptionPane.YES_NO_OPTION,
                            javax.swing.JOptionPane.WARNING_MESSAGE
                        )
                        confirmed = result == javax.swing.JOptionPane.YES_OPTION
                    }
                    confirmed
                }
                if (!userConfirmed) {
                    return@launch
                }
            }

            val config = ch.etasystems.amsel.core.export.ReportExporter.ReportConfig(
                audioFileName = audioManager.state.value.audioFile?.name ?: "",
                operatorName = settings.operatorName,
                deviceName = settings.deviceName,
                locationName = settings.locationName,
                audioDurationSec = spectrogramManager.state.value.totalDurationSec,
                annotations = annotations
            )

            val baseName = audioManager.state.value.audioFile?.nameWithoutExtension ?: "AMSEL_Report"

            // PDF Speichern-Dialog
            val pdfFile = showReportSaveDialog("PDF Report speichern", "$baseName.pdf", "pdf")
            if (pdfFile != null) {
                try {
                    ch.etasystems.amsel.core.export.ReportExporter.exportPdf(pdfFile, config)
                    _localState.update { it.copy(statusText = "PDF exportiert: ${pdfFile.name}") }
                } catch (e: Exception) {
                    _localState.update { it.copy(statusText = "PDF-Fehler: ${e.message}") }
                }
            }

            // CSV Speichern-Dialog
            val csvFile = showReportSaveDialog("CSV Export speichern", "$baseName.csv", "csv")
            if (csvFile != null) {
                try {
                    ch.etasystems.amsel.core.export.ReportExporter.exportCsv(csvFile, config)
                    _localState.update { it.copy(statusText = "CSV exportiert: ${csvFile.name}") }
                } catch (e: Exception) {
                    _localState.update { it.copy(statusText = "CSV-Fehler: ${e.message}") }
                }
            }
        }
    }

    private fun showReportSaveDialog(title: String, defaultName: String, extension: String): File? {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = title
        chooser.selectedFile = File(defaultName)
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "$extension-Dateien (*.${extension})", extension
        )
        val result = chooser.showSaveDialog(null)
        if (result != javax.swing.JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile
        if (!file.name.endsWith(".$extension")) file = File(file.parent, "${file.name}.$extension")
        return file
    }

    fun exportAudio(outputFile: File, format: String = "wav", timeStretch: Int = 1) {
        exportManager.exportAudio(outputFile, _uiState.value, format, timeStretch)
    }

    /** Settings neu laden (z.B. nach Export-Settings- oder Algorithmus-Aenderung) */
    fun reloadSettings() {
        val settings = SettingsStore.load()
        spectrogramManager.maxFreqHz = settings.maxFrequencyHz.toFloat()

        // Vergleichs-Algorithmus ggf. aktualisieren
        val newMetric = createMetric(settings.comparisonAlgorithm)
        if (similarityEngine.metric.cacheKey != newMetric.cacheKey) {
            similarityEngine = SimilarityEngine(recordings = recordingProvider, cache = featureCacheProvider, metric = newMetric)
            classificationManager.similarityEngine = similarityEngine
        }

        // Wenn Datei geladen: bei geaenderter Mindestdauer neu aufbauen
        val state = _uiState.value
        val file = state.audioFile
        if (file != null) {
            importAudio(file)
        } else {
            _localState.update {
                it.copy(statusText = "Einstellungen geladen (${newMetric.displayName}, max ${settings.maxFrequencyHz} Hz)")
            }
        }
    }

    // ====================================================================
    // Normalisierung — delegiert an SpectrogramManager
    // ====================================================================

    fun toggleNormalization(targetDbfs: Float = -2f) {
        val state = _uiState.value
        spectrogramManager.toggleNormalization(
            targetDbfs = targetDbfs,
            audioOffsetSec = state.audioOffsetSec,
            activeAnnotation = state.activeAnnotation
        )
    }

    // ====================================================================
    // Lautstaerke-Automation (Volume Envelope) — delegiert an VolumeManager
    // ====================================================================

    fun addVolumePoint(timeSec: Float, gainDb: Float) = volumeManager.addVolumePoint(timeSec, gainDb)
    fun moveVolumePoint(index: Int, timeSec: Float, gainDb: Float) = volumeManager.moveVolumePoint(index, timeSec, gainDb)
    fun removeVolumePoint(index: Int) = volumeManager.removeVolumePoint(index)
    fun clearVolumeEnvelope() = volumeManager.clearVolumeEnvelope()
    fun selectVolumePoint(index: Int) = volumeManager.selectVolumePoint(index)
    fun toggleVolumeEnvelope() = volumeManager.toggleVolumeEnvelope()

    // ====================================================================
    // Gummiband-Auswahl aus Overview — delegiert an AnnotationManager
    // ====================================================================

    fun rubberBandSelect(startSec: Float, endSec: Float) = annotationManager.rubberBandSelect(startSec, endSec)

    // ====================================================================
    // Referenz-Sonogramm — delegiert an AnnotationManager
    // ====================================================================

    fun selectMatchResult(result: MatchResult) = annotationManager.selectMatchResult(result)
    fun clearMatchResult() = annotationManager.clearMatchResult()

    fun clearCompareFile() {
        audioManager.clearCompareFile()
        spectrogramManager.clearCompareSpectrograms()
    }

    // ====================================================================
    // API-Key & Download — delegiert an ClassificationManager
    // ====================================================================

    fun showApiKeyDialog() = classificationManager.showApiKeyDialog()
    fun dismissApiKeyDialog() = classificationManager.dismissApiKeyDialog()
    fun saveApiKey(key: String) = classificationManager.saveApiKey(key)
    fun getApiKey(): String = classificationManager.getApiKey()
    fun showDownloadDialog() = classificationManager.showDownloadDialog()
    fun dismissDownloadDialog() = classificationManager.dismissDownloadDialog()
    fun startDownload(speciesList: List<String>, maxPerSpecies: Int) = classificationManager.startDownload(speciesList, maxPerSpecies)
    fun cancelDownload() = classificationManager.cancelDownload()

    fun startAudioBatchDownload(
        onProgress: (current: Int, total: Int, species: String) -> Unit,
        onComplete: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        referenceDownloader.startAudioBatchDownload(onProgress, onComplete, onCancel)
    }

    fun cancelAudioBatchDownload() = referenceDownloader.cancelAudioBatchDownload()

    fun getAudioStats(regionSetId: String): Pair<Int, Int> =
        referenceDownloader.getAudioStats(regionSetId)

    suspend fun rescanReferences() {
        referenceLibrary.rescan()
    }

    // ====================================================================
    // U1: Tastaturkuerzel — Navigation & Annotation
    // ====================================================================

    /** Viewport um 25% der sichtbaren Breite nach links scrollen */
    fun navigateLeft() {
        val s = _uiState.value
        val range = s.viewEndSec - s.viewStartSec
        if (range <= 0f) return
        val shift = range * 0.25f
        val newStart = (s.viewStartSec - shift).coerceAtLeast(0f)
        spectrogramManager.zoomToRange(newStart, newStart + range)
    }

    /** Viewport um 25% der sichtbaren Breite nach rechts scrollen */
    fun navigateRight() {
        val s = _uiState.value
        val range = s.viewEndSec - s.viewStartSec
        if (range <= 0f) return
        val shift = range * 0.25f
        val duration = s.totalDurationSec
        val newEnd = (s.viewEndSec + shift).coerceAtMost(duration)
        spectrogramManager.zoomToRange(newEnd - range, newEnd)
    }

    /** Zur vorherigen Annotation springen (zeitlich sortiert, zyklisch) */
    fun previousAnnotation() {
        val anns = _uiState.value.annotations.sortedBy { it.startTimeSec }
        if (anns.isEmpty()) return
        val currentId = _uiState.value.activeAnnotationId
        val currentIdx = anns.indexOfFirst { it.id == currentId }
        val prevIdx = if (currentIdx > 0) currentIdx - 1 else anns.lastIndex
        val ann = anns[prevIdx]
        selectAnnotation(ann.id)
        val settings = ch.etasystems.amsel.data.SettingsStore.load()
        spectrogramManager.zoomToRange(
            (ann.startTimeSec - settings.eventPrerollSec).coerceAtLeast(0f),
            ann.endTimeSec + settings.eventPostrollSec
        )
    }

    /** Zur naechsten Annotation springen (zeitlich sortiert, zyklisch) */
    fun nextAnnotation() {
        val anns = _uiState.value.annotations.sortedBy { it.startTimeSec }
        if (anns.isEmpty()) return
        val currentId = _uiState.value.activeAnnotationId
        val currentIdx = anns.indexOfFirst { it.id == currentId }
        val nextIdx = if (currentIdx < 0 || currentIdx >= anns.lastIndex) 0 else currentIdx + 1
        val ann = anns[nextIdx]
        selectAnnotation(ann.id)
        val settings = ch.etasystems.amsel.data.SettingsStore.load()
        spectrogramManager.zoomToRange(
            (ann.startTimeSec - settings.eventPrerollSec).coerceAtLeast(0f),
            ann.endTimeSec + settings.eventPostrollSec
        )
    }

    /** Auswahl aufheben */
    fun clearSelection() = annotationManager.clearSelection()

    /** Undo (Platzhalter — TODO: vollstaendige Undo-Implementierung) */
    fun undo() {
        logger.info("TODO: Undo ist noch nicht implementiert")
    }

    // ====================================================================
    // U2: Kontextmenue-Aktionen
    // ====================================================================

    /** Neue Annotation an der angegebenen Zeitposition erstellen (±1s Breite) */
    fun createAnnotationAt(timeSec: Float) {
        val s = _uiState.value
        val data = s.zoomedSpectrogramData ?: return
        val startSec = (timeSec - 1f).coerceAtLeast(0f)
        val endSec = (timeSec + 1f).coerceAtMost(s.totalDurationSec)
        annotationManager.createAnnotationAtRange(startSec, endSec, data.fMin, data.fMax)
    }

    /** Viewport auf eine bestimmte Zeit zentrieren */
    fun zoomToTime(timeSec: Float) {
        val s = _uiState.value
        val range = s.viewEndSec - s.viewStartSec
        if (range <= 0f) return
        val halfRange = range / 2f
        val newStart = (timeSec - halfRange).coerceAtLeast(0f)
        val newEnd = (newStart + range).coerceAtMost(s.totalDurationSec)
        spectrogramManager.zoomToRange(newEnd - range, newEnd)
    }

    /** Wiedergabe ab einer bestimmten Position starten — zoomt hin und startet Play */
    fun playFromTime(timeSec: Float) {
        // Viewport auf die Position zentrieren, dann Play
        zoomToTime(timeSec)
        if (!_uiState.value.isPlaying) {
            togglePlayPause()
        }
    }

    /** BirdNET-Scan fuer den aktuell sichtbaren Bereich */
    fun scanBirdNetVisible() {
        // Vollscan nutzt den sichtbaren Bereich wenn keine Annotation aktiv
        val prevActiveId = _uiState.value.activeAnnotationId
        if (prevActiveId != null) {
            annotationManager.clearSelection()
        }
        classificationManager.fullScanBirdNet()
    }

    /** Zeitbereich einer Annotation abspielen — zoomt hin und startet Play */
    fun playRange(startSec: Float, endSec: Float) {
        spectrogramManager.zoomToRange(startSec, endSec)
        if (!_uiState.value.isPlaying) {
            togglePlayPause()
        }
    }

    /** Region als WAV exportieren */
    fun exportRegionWav(startSec: Float, endSec: Float) {
        logger.info("TODO: exportRegionWav($startSec, $endSec) — muss in ExportManager implementiert werden")
    }

    /** Region als PNG exportieren */
    fun exportRegionPng(startSec: Float, endSec: Float) {
        logger.info("TODO: exportRegionPng($startSec, $endSec) — muss in ExportManager implementiert werden")
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    fun dispose() {
        autoSaveProject()
        playbackManager.dispose()
        spectrogramManager.dispose()
        classificationManager.dispose()
        scope.cancel()
    }

}
