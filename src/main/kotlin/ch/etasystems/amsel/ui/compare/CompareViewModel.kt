package ch.etasystems.amsel.ui.compare

import androidx.compose.ui.geometry.Rect
import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.MatchResult
import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.similarity.CosineSimilarityMetric
import ch.etasystems.amsel.core.similarity.DtwSimilarityMetric
import ch.etasystems.amsel.core.similarity.EmbeddingSimilarityMetric
import ch.etasystems.amsel.core.similarity.OnnxSimilarityMetric
import ch.etasystems.amsel.core.similarity.SimilarityEngine
import ch.etasystems.amsel.core.similarity.SimilarityMetric
import ch.etasystems.amsel.data.AmselProject
import ch.etasystems.amsel.data.AudioReference
import ch.etasystems.amsel.data.ComparisonAlgorithm
import ch.etasystems.amsel.data.FilterPreset
import ch.etasystems.amsel.data.ProjectMetadata
import ch.etasystems.amsel.data.ProjectStore
import ch.etasystems.amsel.data.RecordingMetadata
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
    val isLooping: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.MAIN,
    val isReferenceLooping: Boolean = false,
    val activeReferenceIndex: Int = -1,
    val referenceAudioDurationSec: Float = 0f,

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
    // Referenz-Sync-Modus
    val isSyncMode: Boolean = false,
    val refViewOffsetSec: Float = 0f,

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

    // Slice-Verarbeitung
    val sliceManager: ch.etasystems.amsel.core.audio.SliceManager? = null,  // null = kein Slicing
    val activeSliceIndex: Int = 0,

    // Projekt
    val projectFile: File? = null,
    val projectDir: java.io.File? = null,
    val projectDirty: Boolean = false,

    // Multi-Audio
    val loadedAudioFiles: Map<String, AudioManager.AudioFileState> = emptyMap(),
    val activeAudioFileId: String? = null,

    // Solo-Modus: Chunk in voller Breite mit Vor-/Nachlauf anzeigen
    val isSoloMode: Boolean = false,

    // Live-Scan: Hintergrund-Scan laeuft + laufende Event-Anzahl
    val isBackgroundScanning: Boolean = false,
    val scanDetectionCount: Int = 0
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
    val detectedMode: String = "",
    val isSoloMode: Boolean = false,
    val isBackgroundScanning: Boolean = false,
    val scanDetectionCount: Int = 0,
    val isSyncMode: Boolean = false,
    val refViewOffsetSec: Float = 0f,
    val referenceAudioDurationSec: Float = 0f
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

    // Manager: Audio (Import, Decoding, Slicing, Datei-Management)
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
        onStatusUpdate = { msg -> _localState.update { it.copy(statusText = msg) } },
        activeAudioFileId = { audioManager.state.value.activeFileId ?: "" }
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
            sliceManager = { _uiState.value.sliceManager },
            audioSampleRate = { _uiState.value.audioSampleRate },
            activeAudioFileId = { audioManager.state.value.activeFileId ?: "" }
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
    ).also { manager ->
        // Sync-Viewport-Provider fuer Referenz-Loop mit korrektem Offset (AP-75)
        manager.syncViewportProvider = {
            val state = _uiState.value
            if (state.isSyncMode && state.referenceAudioDurationSec > 0f) {
                Pair(state.refViewOffsetSec, state.viewEndSec - state.viewStartSec)
            } else null
        }
    }

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
                    sliceManager = audio.sliceManager,
                    activeSliceIndex = audio.activeSliceIndex,
                    // Volume (3 Felder)
                    volumeEnvelope = volume.volumeEnvelope,
                    volumeEnvelopeActive = volume.volumeEnvelopeActive,
                    selectedVolumeIndex = volume.selectedVolumeIndex,
                    // Playback (8 Felder)
                    isPlaying = playback.isPlaying,
                    isPaused = playback.isPaused,
                    playbackPositionSec = playback.playbackPositionSec,
                    isLooping = playback.isLooping,
                    playingReferenceId = playback.playingReferenceId,
                    downloadingReferenceId = playback.downloadingReferenceId,
                    playbackMode = playback.playbackMode,
                    isReferenceLooping = playback.isReferenceLooping,
                    activeReferenceIndex = playback.activeReferenceIndex,
                    referenceAudioDurationSec = local.referenceAudioDurationSec,
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
                    // Annotation (8 Felder) — nur Annotations der aktiven Audio-Datei anzeigen
                    annotations = annotation.annotations.filter {
                        it.audioFileId == (audio.activeFileId ?: "") || it.audioFileId.isEmpty()
                    },
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
                    // Projekt (6 Felder)
                    projectFile = project.projectFile,
                    projectDir = project.projectDir,
                    projectDirty = project.projectDirty,
                    auditLog = project.auditLog,
                    lastExportFile = project.lastExportFile,
                    exportBlackAndWhite = project.exportBlackAndWhite,
                    // Multi-Audio (2 Felder)
                    loadedAudioFiles = audio.loadedFiles,
                    activeAudioFileId = audio.activeFileId,
                    // Lokal (9 Felder)
                    isProcessing = local.isProcessing,
                    statusText = local.statusText,
                    sidebarStatus = local.sidebarStatus,
                    detectedMode = local.detectedMode,
                    isSoloMode = local.isSoloMode,
                    isBackgroundScanning = local.isBackgroundScanning,
                    scanDetectionCount = local.scanDetectionCount,
                    isSyncMode = local.isSyncMode,          // NEU
                    refViewOffsetSec = local.refViewOffsetSec  // NEU
                )
            }.collect { _uiState.value = it }
        }

        // Live-Scan Flows → LocalState (separate Flows, nicht in ClassificationManager.State)
        scope.launch {
            classificationManager.isBackgroundScanning.collect { scanning ->
                _localState.update { it.copy(isBackgroundScanning = scanning) }
            }
        }
        scope.launch {
            classificationManager.scanDetectionCount.collect { count ->
                _localState.update { it.copy(scanDetectionCount = count) }
            }
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
        val loadedFiles = audioManager.state.value.loadedFiles
        if (loadedFiles.isEmpty()) return

        val settings = SettingsStore.load()
        // Bestehende Metadaten aus Projektdatei laden, damit recordingMeta nicht verloren geht
        val existingMeta = try {
            proj.projectFile?.let { ProjectStore.load(it) }?.audioFiles
                ?.associateBy { it.id } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
        val audioFileRefs = loadedFiles.map { (fileId, fileState) ->
            AudioReference(
                id = fileId,
                originalFileName = fileState.audioFile.name,
                durationSec = fileState.durationSec,
                sampleRate = fileState.sampleRate,
                sliceLengthMin = settings.sliceLengthMin,
                sliceOverlapSec = settings.sliceOverlapSec,
                recordingMeta = existingMeta[fileId]?.recordingMeta
            )
        }

        val project = AmselProject(
            version = 2,
            metadata = ProjectMetadata(
                location = settings.locationName,
                latitude = settings.locationLat,
                longitude = settings.locationLon,
                operator = settings.operatorName,
                device = settings.deviceName
            ),
            audioFiles = audioFileRefs,
            annotations = annotationManager.state.value.annotations,  // ALLE Annotations (nicht gefiltert)
            volumeEnvelope = state.volumeEnvelope,
            volumeEnvelopeActive = state.volumeEnvelopeActive,
            filterPreset = if (state.filterConfig != FilterConfig()) FilterPreset.fromFilterConfig("project", state.filterConfig) else null,
            auditLog = proj.auditLog,
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

    /** Erstellt ein neues Projekt in einem dedizierten Ordner (Delegation an ProjectManager). */
    fun createNewProject(projectDir: File, projectName: String, metadata: ProjectMetadata) {
        projectManager.createProject(projectDir, projectName, metadata)
    }

    /** Laedt ein AMSEL-Projekt aus einer .amsel.json Datei. */
    fun loadProject(projectFile: File) {
        scope.launch {
            try {
                val project = projectManager.deserializeProject(projectFile) ?: return@launch

                // Alle Audio-Dateien laden
                val audioRefs = project.audioFiles
                if (audioRefs.isEmpty()) {
                    _localState.update { it.copy(statusText = "Projekt hat keine Audio-Dateien") }
                    return@launch
                }

                playbackManager.stopPlayback()

                for ((index, audioRef) in audioRefs.withIndex()) {
                    // Audio-Datei suchen: neben Projekt, dann in audio/ Unterordner
                    val beside = File(projectFile.parentFile, audioRef.originalFileName)
                    val inSubdir = File(projectFile.parentFile, "audio/${audioRef.originalFileName}")
                    val resolved = when {
                        beside.exists() -> beside
                        inSubdir.exists() -> inSubdir
                        else -> {
                            System.err.println("[ViewModel] Audio nicht gefunden: ${audioRef.originalFileName}")
                            continue
                        }
                    }
                    importAudioSuspend(resolved)
                }

                // Volume wiederherstellen
                volumeManager.restoreFromProject(project.volumeEnvelope, project.volumeEnvelopeActive)

                // Filter/Display wiederherstellen
                val restoredFilter = project.filterPreset?.toFilterConfig() ?: FilterConfig()
                spectrogramManager.restoreFromProject(
                    filterConfig = restoredFilter,
                    displayDbRange = project.displayDbRange,
                    displayGamma = project.displayGamma,
                    isNormalized = project.isNormalized,
                    normGainDb = project.normGainDb
                )

                // Annotations wiederherstellen (mit audioFileId aus Projekt)
                annotationManager.restoreFromProject(project.annotations)

                // Projekt-State setzen
                projectManager.setProjectLoaded(projectFile, project.auditLog)
            } catch (e: Exception) {
                _localState.update { it.copy(statusText = "Projekt-Fehler: ${e.message}") }
            }
        }
    }

    // ====================================================================
    // Slice-Navigation
    // ====================================================================

    /** Wechselt zum angegebenen Slice und aktualisiert Viewport. */
    fun selectSlice(sliceIndex: Int) {
        val range = audioManager.selectSlice(sliceIndex) ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    /** Naechster Slice. */
    fun nextSlice() {
        val range = audioManager.nextSlice() ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    /** Vorheriger Slice. */
    fun previousSlice() {
        val range = audioManager.previousSlice() ?: return
        spectrogramManager.updateViewRange(range.first, range.second)
    }

    // ====================================================================
    // Multi-Audio Datei-Verwaltung
    // ====================================================================

    /** Wechselt zur angegebenen Audio-Datei und berechnet Spektrogramm neu. */
    fun switchAudioFile(fileId: String) {
        val fileState = audioManager.switchActiveFile(fileId) ?: return
        playbackManager.stopPlayback()
        scope.launch {
            _localState.update { it.copy(isProcessing = true, sidebarStatus = "Wechsle Datei...") }
            try {
                val maxFreqHz = spectrogramManager.maxFreqHz
                val segment = fileState.audioSegment
                val pcmCache = fileState.pcmCache

                if (segment != null) {
                    val overview = ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
                        .computeOverview(segment, targetFrames = 4000, maxFreqHz = maxFreqHz)
                    val end = fileState.sliceManager?.slices?.firstOrNull()?.endSec
                        ?: fileState.durationSec.coerceAtMost(INITIAL_ZOOM_DURATION_SEC)
                    val zoomedData = ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
                        .computeRegion(segment, 0f, end, maxFreqHz)
                    spectrogramManager.setInitialSpectrograms(
                        overviewData = overview.spectrogramData,
                        zoomedData = zoomedData,
                        totalDurationSec = fileState.durationSec,
                        initialViewEnd = end
                    )
                } else if (pcmCache != null) {
                    val overviewSeg = pcmCache.readRange(0f, fileState.durationSec)
                    val overview = ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
                        .computeOverview(overviewSeg, targetFrames = 4000, maxFreqHz = maxFreqHz)
                    val end = fileState.sliceManager?.slices?.firstOrNull()?.endSec
                        ?: fileState.durationSec.coerceAtMost(INITIAL_ZOOM_DURATION_SEC)
                    val zoomedSeg = pcmCache.readRange(0f, end)
                    val spec = ch.etasystems.amsel.core.spectrogram.MelSpectrogram.auto(
                        zoomedSeg.sampleRate, maxFreqHz
                    )
                    val zoomedData = spec.compute(zoomedSeg.samples)
                    spectrogramManager.setInitialSpectrograms(
                        overviewData = overview.spectrogramData,
                        zoomedData = zoomedData,
                        totalDurationSec = fileState.durationSec,
                        initialViewEnd = end
                    )
                }

                _localState.update {
                    it.copy(
                        isProcessing = false,
                        statusText = "${fileState.audioFile.name} — ${fileState.durationSec.format(1)}s",
                        sidebarStatus = ""
                    )
                }
            } catch (e: Exception) {
                _localState.update {
                    it.copy(isProcessing = false, statusText = "Fehler: ${e.message}")
                }
            }
        }
    }

    /** Entfernt eine Audio-Datei aus dem Projekt. */
    fun removeAudioFile(fileId: String) {
        audioManager.removeFile(fileId)
        // Falls noch Dateien uebrig: Spektrogramm der neuen aktiven Datei laden
        val newActive = audioManager.state.value.activeFile
        if (newActive != null) {
            switchAudioFile(newActive.fileId)
        }
    }

    /** Importiert mehrere Audio-Dateien sequenziell. */
    fun importMultipleAudioFiles(files: List<File>) {
        playbackManager.stopPlayback()
        scope.launch {
            for (file in files) {
                importAudioSuspend(file)
            }
        }
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
            val isFirstFile = audioManager.isFirstFile()

            val result = audioManager.importAudio(
                file = file,
                maxFreqHz = spectrogramManager.maxFreqHz,
                minDisplayDurationSec = settings.minDisplayDurationSec,
                shortFileStartPct = settings.shortFileStartPct,
                sliceLengthMin = settings.sliceLengthMin,
                sliceOverlapSec = settings.sliceOverlapSec,
                initialZoomDurationSec = INITIAL_ZOOM_DURATION_SEC
            )

            // Spektrogramm setzen (immer fuer die aktive Datei)
            spectrogramManager.setInitialSpectrograms(
                overviewData = result.overviewSpectrogramData,
                zoomedData = result.zoomedSpectrogramData,
                totalDurationSec = result.duration,
                initialViewEnd = result.initialViewEnd
            )

            // Annotations nur bei ERSTER Datei zuruecksetzen
            if (isFirstFile) {
                annotationManager.reset()
                projectManager.resetForNewAudio()
            }

            _localState.update {
                it.copy(
                    isProcessing = false,
                    statusText = "${file.name} — ${result.duration.format(1)}s @ ${result.sampleRate} Hz",
                    sidebarStatus = "",
                    detectedMode = result.mode
                )
            }

            if (result.hasSlices && isFirstFile) {
                projectManager.autoCreateProject(file, result.duration, result.sampleRate, settings)
            }
        } catch (e: Exception) {
            _localState.update {
                it.copy(isProcessing = false, statusText = "Fehler: ${e.message}")
            }
        }
    }

    // ====================================================================
    // Audio-Metadaten
    // ====================================================================

    /** Setzt Aufnahme-Metadaten fuer eine Audio-Datei und speichert im Projekt. */
    fun setAudioMetadata(fileId: String, metadata: RecordingMetadata) {
        projectManager.updateAudioMetadata(fileId, metadata)
        projectManager.markDirty()
        autoSaveProject()
    }

    /**
     * Setzt Zeitstempel fuer mehrere Audio-Dateien gleichzeitig.
     * Laedt das Projektfile einmal, aktualisiert alle Eintraege, speichert einmal.
     */
    fun setMultiFileTimestamps(metadatenMap: Map<String, RecordingMetadata>) {
        if (metadatenMap.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val proj = projectManager.state.value
                val pf = proj.projectFile ?: return@launch
                val project = ProjectStore.load(pf)
                val updatedFiles = project.audioFiles.map { ref ->
                    val meta = metadatenMap[ref.id]
                    if (meta != null) ref.copy(recordingMeta = meta) else ref
                }
                ProjectStore.save(project.copy(audioFiles = updatedFiles), pf)
                projectManager.markDirty()
                autoSaveProject()
            } catch (e: Exception) {
                logger.error("Zeitstempel-Kette speichern fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** Laedt die AudioReferences aus dem Projektfile (fuer UI-Abfragen). */
    fun loadAudioReferencesMap(): Map<String, AudioReference> {
        val pf = projectManager.state.value.projectFile ?: return emptyMap()
        return try {
            ProjectStore.load(pf).audioFiles.associateBy { it.id }
        } catch (_: Exception) { emptyMap() }
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

    /** Startet Playback ab einer bestimmten Position (Space+Klick, AP-52). */
    fun seekToPositionAndPlay(positionSec: Float) {
        // Zuerst stoppen falls laeuft, dann ab neuer Position starten
        playbackManager.stopPlayback()
        val state = _uiState.value
        playbackManager.togglePlayPause(
            audioSegment = state.audioSegment,
            pcmCache = state.pcmCache,
            audioFile = state.audioFile,
            filterConfig = state.filterConfig,
            maxFreqHz = spectrogramManager.maxFreqHz,
            viewStartSec = positionSec,
            viewEndSec = state.viewEndSec,
            volumeEnvelope = state.volumeEnvelope,
            volumeEnvelopeActive = state.volumeEnvelopeActive,
            totalDurationSec = state.totalDurationSec
        )
    }

    fun toggleLoop() = playbackManager.toggleLoop()

    fun playReferenceAudio(result: ch.etasystems.amsel.core.annotation.MatchResult) {
        val results = _uiState.value.activeAnnotation?.matchResults ?: emptyList()
        playbackManager.setReferenceResults(results)
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
        // Solo-Modus: Viewport sofort auf die angeklickte Annotation zoomen
        if (_localState.value.isSoloMode) {
            // Direkt aus AnnotationManager lesen (nicht _uiState — der ist asynchron via combine())
            val ann = annotationManager.state.value.annotations.find { it.id == annotationId }
            if (ann != null) soloZoomToAnnotation(ann)
        }
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

    // ====================================================================
    // Solo-Modus: Chunk in voller Breite mit Vor-/Nachlauf
    // ====================================================================

    fun toggleSoloMode() {
        _localState.update { it.copy(isSoloMode = !it.isSoloMode) }
    }

    /** Solo: Zum naechsten Chunk springen und Viewport anpassen */
    fun soloNextAnnotation() {
        // Direkt aus AnnotationManager lesen (nicht _uiState — der ist asynchron via combine())
        val amState = annotationManager.state.value
        val anns = amState.annotations.sortedBy { it.startTimeSec }
        if (anns.isEmpty()) return
        val currentId = amState.activeAnnotationId
        val currentIdx = anns.indexOfFirst { it.id == currentId }
        val nextIdx = if (currentIdx < 0 || currentIdx >= anns.lastIndex) 0 else currentIdx + 1
        val ann = anns[nextIdx]
        selectAnnotation(ann.id)
        soloZoomToAnnotation(ann)
    }

    /** Solo: Zum vorherigen Chunk springen und Viewport anpassen */
    fun soloPreviousAnnotation() {
        // Direkt aus AnnotationManager lesen (nicht _uiState — der ist asynchron via combine())
        val amState = annotationManager.state.value
        val anns = amState.annotations.sortedBy { it.startTimeSec }
        if (anns.isEmpty()) return
        val currentId = amState.activeAnnotationId
        val currentIdx = anns.indexOfFirst { it.id == currentId }
        val prevIdx = if (currentIdx > 0) currentIdx - 1 else anns.lastIndex
        val ann = anns[prevIdx]
        selectAnnotation(ann.id)
        soloZoomToAnnotation(ann)
    }

    /** Solo: Viewport auf eine einzelne Annotation mit Vor-/Nachlauf zoomen */
    private fun soloZoomToAnnotation(ann: ch.etasystems.amsel.core.annotation.Annotation) {
        val settings = ch.etasystems.amsel.data.SettingsStore.load()
        val preroll = settings.soloPrerollSec
        val postroll = settings.soloPostrollSec
        val start = (ann.startTimeSec - preroll).coerceAtLeast(0f)
        val end = (ann.endTimeSec + postroll).coerceAtMost(_uiState.value.totalDurationSec)
        spectrogramManager.zoomToRange(start, end)
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

    /** Sync-Modus umschalten: Referenz-Bild an Haupt-Audio-Zeitbreite koppeln. */
    fun toggleSyncMode() {
        _localState.update { it.copy(
            isSyncMode = !it.isSyncMode,
            refViewOffsetSec = 0f
        ) }
    }

    /** Referenz-Viewport-Offset setzen (Sekunden ab Clip-Beginn). */
    fun setRefViewOffset(sec: Float) {
        val duration = _uiState.value.referenceAudioDurationSec
        val visible = _uiState.value.viewEndSec - _uiState.value.viewStartSec
        val maxOffset = (duration - visible).coerceAtLeast(0f)
        _localState.update { it.copy(refViewOffsetSec = sec.coerceIn(0f, maxOffset)) }
    }

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

    fun uncertainCandidateInAnnotation(annotationId: String, candidate: ch.etasystems.amsel.core.annotation.SpeciesCandidate) =
        annotationManager.uncertainCandidateInAnnotation(annotationId, candidate.species)

    fun addManualCandidate(annotationId: String, scientificName: String, displayLabel: String) =
        annotationManager.addManualCandidate(annotationId, scientificName, displayLabel)

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

            // RecordingMetadata aus Projektdatei laden
            val audioRefMap = try {
                projectManager.state.value.projectFile?.let { ProjectStore.load(it) }
                    ?.audioFiles?.associateBy { it.id } ?: emptyMap()
            } catch (_: Exception) { emptyMap() }

            // Audio-File-Infos zusammenstellen (mit Aufnahmedatum/zeit)
            val audioFileInfos = audioManager.state.value.loadedFiles.map { (id, fileState) ->
                val meta = audioRefMap[id]?.recordingMeta
                ch.etasystems.amsel.core.export.ReportExporter.AudioFileInfo(
                    fileId = id,
                    fileName = fileState.audioFile.name,
                    durationSec = fileState.durationSec,
                    recordingDate = meta?.date ?: "",
                    recordingTime = meta?.time ?: ""
                )
            }
            val totalDuration = audioFileInfos.sumOf { it.durationSec.toDouble() }.toFloat()

            // Alle Annotations sammeln (nicht nur aktive Datei)
            val allAnns = annotationManager.allAnnotations()
            val selectedAnns = annotationManager.getSelectedAnnotations()
            val reportAnnotations = (selectedAnns.ifEmpty { allAnns }).filter { !it.rejected }

            if (reportAnnotations.isEmpty()) {
                _localState.update { it.copy(statusText = "Keine Annotationen vorhanden") }
                return@launch
            }

            // Warnung bei unverifizierten Slices
            val unverifiedCount = reportAnnotations.count { !it.verified }
            if (unverifiedCount > 0) {
                val totalCount = reportAnnotations.size
                val userConfirmed = withContext(Dispatchers.IO) {
                    var confirmed = false
                    javax.swing.SwingUtilities.invokeAndWait {
                        val result = javax.swing.JOptionPane.showConfirmDialog(
                            null,
                            "$unverifiedCount von $totalCount Slices nicht verifiziert.\nTrotzdem exportieren?",
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
                audioFiles = audioFileInfos,
                operatorName = settings.operatorName,
                deviceName = settings.deviceName,
                locationName = settings.locationName,
                totalDurationSec = totalDuration,
                annotations = reportAnnotations,
                sortOrder = settings.reportSortOrder
            )

            val baseName = projectManager.state.value.projectFile?.nameWithoutExtension
                ?: audioManager.state.value.audioFile?.nameWithoutExtension
                ?: "AMSEL_Report"

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

    fun selectMatchResult(result: MatchResult) {
        annotationManager.selectMatchResult(result)
        // Dauer fuer Sync-Viewport: ueber eigenen AudioDecoder lesen (unterstuetzt MP3/FLAC/WAV)
        if (result.audioUrl.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val audioFile = java.io.File(result.audioUrl)
                    if (audioFile.exists()) {
                        val segment = AudioDecoder.decode(audioFile)
                        val durationSec = segment.durationSec
                        if (durationSec > 0f) {
                            _localState.update { it.copy(referenceAudioDurationSec = durationSec) }
                        }
                    }
                } catch (_: Exception) {
                    // noop — Dauer bleibt 0 wenn File nicht lesbar
                }
            }
        }
    }
    fun clearMatchResult() {
        annotationManager.clearMatchResult()
        _localState.update { it.copy(
            isSyncMode = false,
            refViewOffsetSec = 0f,
            referenceAudioDurationSec = 0f
        ) }
    }

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

    /** Playback-Position um deltaSec springen, Viewport bleibt (S + Pfeil, AP-29) */
    fun seekPlayback(deltaSec: Float) {
        val s = _uiState.value
        playbackManager.seekPlayback(deltaSec, s.audioSegment, s.pcmCache, s.totalDurationSec)
    }

    /** Wechselt Playback-Modus (MAIN/REFERENCE). Stoppt laufendes Audio. */
    fun switchPlaybackMode(mode: PlaybackMode) {
        playbackManager.switchMode(mode)
    }

    /** Loop fuer Referenz-Audio ein/aus. */
    fun toggleReferenceLoop() {
        playbackManager.toggleReferenceLoop()
    }

    /** Naechste Referenz auswaehlen und abspielen. */
    fun nextReference() {
        val idx = playbackManager.nextReference()
        if (idx >= 0) {
            val results = _uiState.value.activeAnnotation?.matchResults ?: return
            if (idx < results.size) {
                playReferenceAudio(results[idx])
            }
        }
    }

    /** Vorherige Referenz auswaehlen und abspielen. */
    fun previousReference() {
        val idx = playbackManager.previousReference()
        if (idx >= 0) {
            val results = _uiState.value.activeAnnotation?.matchResults ?: return
            if (idx < results.size) {
                playReferenceAudio(results[idx])
            }
        }
    }

    /** Naechste Referenz auswaehlen OHNE abzuspielen (fuer Pfeiltasten-Navigation). */
    fun selectNextReference() {
        playbackManager.nextReference()
    }

    /** Vorherige Referenz auswaehlen OHNE abzuspielen (fuer Pfeiltasten-Navigation). */
    fun selectPreviousReference() {
        playbackManager.previousReference()
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

    // ====================================================================
    // Panel-Keyboard-Navigation
    // ====================================================================

    /** Naechster Kandidat in der Kandidatenliste — TODO: eigenes Feature (kein activeCandidate-State). */
    fun nextCandidate() {
        // Noop — es gibt keinen "activeCandidate"-State im CandidatePanel
    }

    /** Vorheriger Kandidat in der Kandidatenliste — TODO: eigenes Feature (kein activeCandidate-State). */
    fun previousCandidate() {
        // Noop — es gibt keinen "activeCandidate"-State im CandidatePanel
    }

    /** Navigiert im Files-Panel aufwaerts (vorheriger Slice oder vorherige Datei). */
    fun navigateFilesPanelUp() {
        val activeId = audioManager.state.value.activeFileId ?: return
        val fileState = audioManager.state.value.loadedFiles[activeId] ?: return
        val sm = fileState.sliceManager

        if (sm != null && fileState.activeSliceIndex > 0) {
            // Vorheriger Slice
            selectSlice(fileState.activeSliceIndex - 1)
        } else {
            // Vorherige Datei
            val fileIds = audioManager.state.value.loadedFiles.keys.toList()
            val currentIdx = fileIds.indexOf(activeId)
            if (currentIdx > 0) {
                switchAudioFile(fileIds[currentIdx - 1])
            }
        }
    }

    /** Navigiert im Files-Panel abwaerts (naechster Slice oder naechste Datei). */
    fun navigateFilesPanelDown() {
        val activeId = audioManager.state.value.activeFileId ?: return
        val fileState = audioManager.state.value.loadedFiles[activeId] ?: return
        val sm = fileState.sliceManager

        if (sm != null && fileState.activeSliceIndex < sm.sliceCount - 1) {
            // Naechster Slice
            selectSlice(fileState.activeSliceIndex + 1)
        } else {
            // Naechste Datei
            val fileIds = audioManager.state.value.loadedFiles.keys.toList()
            val currentIdx = fileIds.indexOf(activeId)
            if (currentIdx < fileIds.size - 1) {
                switchAudioFile(fileIds[currentIdx + 1])
            }
        }
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
