package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.MatchResult
import ch.etasystems.amsel.core.annotation.SpeciesCandidate
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.PerformanceLog
import ch.etasystems.amsel.core.audio.PcmCacheFile
import ch.etasystems.amsel.core.classifier.BirdNetBridge
import ch.etasystems.amsel.core.classifier.ClassifierResult
import ch.etasystems.amsel.core.classifier.OnnxBirdNetV3
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.model.VolumePoint
import ch.etasystems.amsel.core.model.gainAtTime
import ch.etasystems.amsel.core.model.gainDbToLinear
import ch.etasystems.amsel.core.similarity.SimilarityEngine
import ch.etasystems.amsel.core.similarity.SimilarityMetric
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.RegionSetRegistry
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.api.XenoCantoApi
import ch.etasystems.amsel.data.reference.ReferenceDownloader
import ch.etasystems.amsel.data.reference.ReferenceLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Lazy-Zugriff auf Audio- und Spektrogramm-Daten fuer Klassifizierung.
 * Vermeidet direkte Abhaengigkeiten zu AudioManager/SpectrogramManager.
 */
data class ClassificationAudioProvider(
    val audioFile: () -> File?,
    val audioSegment: () -> AudioSegment?,
    val pcmCache: () -> PcmCacheFile?,
    val volumeEnvelope: () -> List<VolumePoint>,
    val volumeEnvelopeActive: () -> Boolean,
    val filterConfig: () -> FilterConfig,
    val maxFreqHz: () -> Float,
    val totalDurationSec: () -> Float,
    val viewStartSec: () -> Float,
    val viewEndSec: () -> Float,
    val zoomedSpectrogramData: () -> SpectrogramData?,
    val annotations: () -> List<Annotation>,
    val activeAnnotation: () -> Annotation?,
    val activeAnnotationId: () -> String?,
    val sliceManager: () -> ch.etasystems.amsel.core.audio.SliceManager?,
    val audioSampleRate: () -> Int,
    val activeAudioFileId: () -> String = { "" }
)

/**
 * Verwaltet BirdNET-Klassifizierung, Similarity-Suche, Event-Detection und Download-Management.
 * Erstellt Annotationen ueber den AnnotationManager (direkte Referenz).
 *
 * Callbacks:
 * - onStateChanged: State-Bridge in CompareUiState aktualisieren
 * - onStatusUpdate: statusText und/oder sidebarStatus aktualisieren (null = unveraendert)
 * - onProcessingChanged: isProcessing-Flag im ViewModel setzen
 * - onAuditEntry: Eintrag im Audit-Trail erstellen
 * - onDirtyChanged: Projekt als geaendert markieren
 * - onZoomToRange: Viewport auf Zeitbereich zoomen
 */
class ClassificationManager(
    private val audioData: ClassificationAudioProvider,
    private val annotationManager: AnnotationManager,
    private val xenoCantoApi: XenoCantoApi,
    private val referenceLibrary: ReferenceLibrary,
    private val referenceDownloader: ReferenceDownloader,
    var similarityEngine: SimilarityEngine,
    private val onStateChanged: () -> Unit = {},
    private val onStatusUpdate: (statusText: String?, sidebarStatus: String?) -> Unit = { _, _ -> },
    private val onProcessingChanged: (Boolean) -> Unit = {},
    private val onAuditEntry: (action: String, details: String) -> Unit = { _, _ -> },
    private val onDirtyChanged: () -> Unit = {},
    private val onZoomToRange: (startSec: Float, endSec: Float) -> Unit = { _, _ -> }
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** ONNX-first Classifier (BirdNET V3.0) — Fallback auf Python-Bridge */
    private val onnxClassifier = OnnxBirdNetV3()

    /** Laeuft gerade ein Hintergrund-Scan? */
    private val _isBackgroundScanning = MutableStateFlow(false)
    val isBackgroundScanning: StateFlow<Boolean> = _isBackgroundScanning.asStateFlow()

    /** Laufende Anzahl erkannter Events (fuer Live-Zaehler) */
    private val _scanDetectionCount = MutableStateFlow(0)
    val scanDetectionCount: StateFlow<Int> = _scanDetectionCount.asStateFlow()

    /** Job des aktuellen Scans (fuer Abbruch bei Neustart) */
    private var backgroundScanJob: Job? = null

    data class State(
        val isSearching: Boolean = false,
        val searchProgress: Float = 0f,
        val showApiKeyDialog: Boolean = false,
        val hasApiKey: Boolean = false,
        val showDownloadDialog: Boolean = false,
        val downloadProgress: ReferenceDownloader.DownloadProgress = ReferenceDownloader.DownloadProgress(),
        val referenceSpeciesCount: Int = 0,
        val referenceRecordingCount: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Download-Fortschritt beobachten
        scope.launch {
            referenceDownloader.progress.collect { progress ->
                _state.update {
                    it.copy(
                        downloadProgress = progress,
                        referenceRecordingCount = referenceLibrary.getRecordingCount(),
                        referenceSpeciesCount = referenceLibrary.getSpeciesCount()
                    )
                }
                onStateChanged()
            }
        }

        // Referenz-Statistik initial laden
        _state.update {
            it.copy(
                referenceRecordingCount = referenceLibrary.getRecordingCount(),
                referenceSpeciesCount = referenceLibrary.getSpeciesCount()
            )
        }
    }

    /** Initialisiert hasApiKey aus Settings (aufgerufen nach Konstruktion). */
    fun initApiKeyState(hasKey: Boolean) {
        _state.update { it.copy(hasApiKey = hasKey) }
        onStateChanged()
    }

    // ====================================================================
    // Referenz-Arten-Liste
    // ====================================================================

    /** Gibt die Liste aller Referenz-Arten zurueck (wissenschaftliche Namen) */
    fun getReferenceSpeciesList(): List<String> = referenceLibrary.getSpeciesList()

    // ====================================================================
    // Similarity-Suche (Referenz-Sonogramme)
    // ====================================================================

    /**
     * Vergleich: Sucht Referenz-Sonogramme in der Offline-DB fuer die aktive Annotation/Art.
     * Strategie: Art-Name aus Annotation extrahieren → Cache nach dieser Art durchsuchen
     * → Sonogramme als Referenz anzeigen.
     * Falls keine Art bekannt: BirdNET schnell auf den Bereich anwenden.
     */
    fun searchSimilar(speciesQuery: String = "") {
        val audioFile = audioData.audioFile()
        val audioSegment = audioData.audioSegment()
        if (audioFile == null && audioSegment == null) {
            onStatusUpdate("Kein Audio -- zuerst Datei importieren", null)
            return
        }

        scope.launch {
            _state.update { it.copy(isSearching = true, searchProgress = 0f) }
            onStateChanged()
            onStatusUpdate(null, "Suche Referenz-Sonogramme...")

            try {
                // 1. Art bestimmen: aus Annotation-Label oder per BirdNET
                val ann = audioData.activeAnnotation()
                var speciesName = speciesQuery

                // Art aus BirdNET-Label extrahieren ("Fringilla coelebs_Common Chaffinch (96%)")
                if (speciesName.isBlank() && ann != null) {
                    val label = ann.label
                    logger.debug("[searchSimilar] activeAnnotation: id={}, label='{}'", ann.id, label)
                    val isGeneric = label.startsWith("Markierung") || label.startsWith("Zoom") ||
                        label.startsWith("Singvogel") || label.startsWith("Grossvogel") ||
                        label.startsWith("Insekt") || label.startsWith("Fledermaus")
                    if (!isGeneric) {
                        if (label.contains("_")) {
                            // Format mit Common Name: "Fringilla coelebs_Common Chaffinch (96%)"
                            val sciName = label.substringBefore("_").trim()
                            val enName = label.substringAfter("_", "")
                                .replace(Regex("\\s*\\(\\d+%\\)"), "").trim()
                            speciesName = sciName.ifBlank { enName }
                        } else {
                            // Nur wissenschaftlicher Name: "Turdus philomelos (40%)"
                            val cleaned = label.replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "").trim()
                            if (cleaned.contains(" ")) {
                                speciesName = cleaned
                            }
                        }
                    }
                }

                logger.debug("[searchSimilar] extracted speciesName: '{}'", speciesName)

                // Falls keine Art aus Label: Quick BirdNET nur auf Annotation-Region
                if (speciesName.isBlank() && (onnxClassifier.isAvailable() || BirdNetBridge.isAvailable())) {
                    onStatusUpdate("BirdNET identifiziert Art...", null)
                    val fullSegment = audioData.audioSegment()
                    val settings = SettingsStore.load()
                    // Nur den Bereich der aktiven Annotation analysieren, nicht das gesamte Audio
                    val segment = if (ann != null && fullSegment != null) {
                        fullSegment.subRangeSec(ann.startTimeSec, ann.endTimeSec)
                    } else {
                        fullSegment
                    }
                    if (segment != null) {
                        val results = try {
                            if (onnxClassifier.isAvailable()) {
                                onnxClassifier.classify(
                                    samples = segment.samples,
                                    sampleRate = segment.sampleRate,
                                    minConfidence = settings.birdnetMinConf,
                                    topN = 5
                                )
                            } else { null }
                        } catch (e: Exception) {
                            logger.warn("ONNX Quick-ID fehlgeschlagen: {}", e.message)
                            null
                        }

                        if (results != null && results.isNotEmpty()) {
                            speciesName = results.first().scientificName.ifBlank { results.first().species }
                        } else if (BirdNetBridge.isAvailable() && audioFile != null) {
                            val pyResults = BirdNetBridge.classifyFile(audioFile, topN = 5,
                                lat = settings.locationLat, lon = settings.locationLon,
                                minConf = settings.birdnetMinConf)
                            if (pyResults.isNotEmpty()) {
                                speciesName = pyResults.first().scientificName.ifBlank { pyResults.first().species }
                            }
                        }
                    }
                }

                if (speciesName.isBlank()) {
                    _state.update { it.copy(isSearching = false) }
                    onStateChanged()
                    onStatusUpdate("Keine Art erkannt — zuerst BirdNET Scan durchfuehren", null)
                    return@launch
                }

                // Art-Name normalisieren: BirdNET V3 liefert "Parus major_Great Tit"
                val sciName = speciesName.substringBefore("_").trim()
                logger.debug("[Vergleich] sciName='{}'", sciName)
                logger.debug("[Vergleich] Referenzbibliothek hat {} Arten", referenceLibrary.getSpeciesCount())

                _state.update { it.copy(searchProgress = 0.3f) }
                onStateChanged()
                onStatusUpdate("Suche $sciName in Referenzbibliothek...", null)

                // 2. Referenzbibliothek nach Sonogrammen dieser Art durchsuchen (exakt + fuzzy)
                val qualitySettings = SettingsStore.load()
                val regionSetId = qualitySettings.activeRegionSet
                val rawRefCount = referenceLibrary.getRecordingsForSpecies(sciName).size
                logger.debug("[searchSimilar] referenceLibrary raw results for '{}': {}", sciName, rawRefCount)
                var refRecordings = referenceLibrary.getRecordingsForSpecies(sciName)
                    .filter { rec ->
                        // Artenset-Filter: nur Referenzen von Arten im aktiven Set anzeigen
                        RegionSetRegistry.isSpeciesInSet(regionSetId, rec.scientificName)
                    }
                    .filter { rec ->
                        val q = rec.qualitaet
                        if (q.isBlank()) true
                        else {
                            val qOrder = listOf("A", "B", "C", "D", "E")
                            val recIdx = qOrder.indexOf(q.uppercase())
                            val minIdx = qOrder.indexOf(qualitySettings.referenceMinQualityDisplay.uppercase())
                            recIdx in 0..minIdx
                        }
                    }
                if (refRecordings.isEmpty()) {
                    // Fuzzy: enthaelt-Suche
                    val matchingSpecies = referenceLibrary.searchSpecies(sciName)
                    refRecordings = matchingSpecies.flatMap { referenceLibrary.getRecordingsForSpecies(it) }
                    logger.debug("[Vergleich] Fuzzy: {} Eintraege", refRecordings.size)
                } else {
                    logger.debug("[Vergleich] Exakt: {} Eintraege", refRecordings.size)
                }
                _state.update { it.copy(searchProgress = 0.6f) }
                onStateChanged()

                if (refRecordings.isEmpty()) {
                    // Genus-Suche als Fallback
                    val genus = sciName.split(" ").firstOrNull() ?: sciName
                    val genusRecordings = referenceLibrary.searchSpecies(genus)
                        .flatMap { referenceLibrary.getRecordingsForSpecies(it) }

                    if (genusRecordings.isEmpty()) {
                        _state.update { it.copy(isSearching = false, searchProgress = 0f) }
                        onStateChanged()
                        onStatusUpdate(null, "Keine Referenz-Sonogramme fuer $sciName. Download starten?")
                        return@launch
                    }
                    refRecordings = genusRecordings
                }

                // 3. MatchResults erstellen (Sonogramm-Pfade als Referenz)
                logger.debug("[Vergleich] Erstelle {} MatchResults", refRecordings.size.coerceAtMost(15))
                val matchResults = refRecordings.take(15).mapIndexed { idx, rec ->
                    logger.debug("[Vergleich] #{}: {} pngFile={} exists={}", idx, rec.id, rec.pngFile?.absolutePath, rec.pngFile?.exists())
                    MatchResult(
                        recordingId = rec.id,
                        species = "",
                        scientificName = rec.scientificName,
                        sonogramUrl = rec.pngFile?.absolutePath ?: "",
                        audioUrl = rec.wavFile?.absolutePath ?: "",
                        quality = rec.qualitaet,
                        country = "",
                        similarity = 1f - (idx * 0.02f),
                        type = rec.typ
                    )
                }

                // 4. In Annotation speichern + Referenz anzeigen
                val targetId = ann?.id ?: audioData.activeAnnotationId()
                if (targetId != null) {
                    annotationManager.updateAnnotationMatchResults(targetId, matchResults)
                }
                if (matchResults.isNotEmpty()) {
                    annotationManager.selectMatchResult(matchResults.first())
                }
                _state.update { it.copy(isSearching = false, searchProgress = 0f) }
                onStateChanged()
                onStatusUpdate(audioData.audioFile()?.name ?: "", null)

            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false, searchProgress = 0f) }
                onStateChanged()
                onStatusUpdate("Vergleichsfehler: ${e.message}", null)
            }
        }
    }

    // ====================================================================
    // Full-File BirdNET Scan
    // ====================================================================

    /**
     * Sendet Audio an BirdNET und erstellt Annotationen.
     * Bei SliceManager: slice-weise Verarbeitung mit Overlap-Deduplizierung.
     * Bei aktiver Annotation: nur diesen Bereich analysieren.
     */
    fun fullScanBirdNet() = scanBirdNetInternal(regionAnnotationId = null)

    /**
     * BirdNET-Analyse fuer den Zeitbereich einer bestimmten Annotation.
     * Wird durch Rechtsklick auf eine Markierung ausgeloest.
     */
    fun scanBirdNetRegion(annotationId: String) = scanBirdNetInternal(regionAnnotationId = annotationId)

    private fun scanBirdNetInternal(regionAnnotationId: String?) {
        val audioFile = audioData.audioFile()
        val segment = audioData.audioSegment()
        if (audioFile == null && segment == null) {
            onStatusUpdate("Kein Audio -- zuerst Datei importieren", null)
            return
        }

        if (!onnxClassifier.isAvailable() && !BirdNetBridge.isAvailable()) {
            logger.debug("[FullScan] Kein Classifier verfuegbar (weder ONNX noch Python-Bridge)")
            onStatusUpdate("BirdNET nicht verfuegbar — ONNX-Modell in ~/Documents/AMSEL/models/ ablegen oder Python + birdnetlib installieren", null)
            return
        }

        // Laufenden Hintergrund-Scan abbrechen
        backgroundScanJob?.cancel()
        backgroundScanJob = null
        _isBackgroundScanning.value = false

        // Scan-Bereich bestimmen
        val annotations = audioData.annotations()
        val totalDurationSec = audioData.totalDurationSec()
        val scanStart: Float?
        val scanEnd: Float?
        if (regionAnnotationId != null) {
            val ann = annotations.find { it.id == regionAnnotationId } ?: return
            val annDuration = ann.endTimeSec - ann.startTimeSec
            if (annDuration >= 3f) {
                scanStart = ann.startTimeSec
                scanEnd = ann.endTimeSec
            } else {
                val pad = (3f - annDuration) / 2f
                scanStart = (ann.startTimeSec - pad).coerceAtLeast(0f)
                scanEnd = (ann.endTimeSec + pad).coerceAtMost(totalDurationSec)
            }
            annotationManager.selectAnnotation(regionAnnotationId)
        } else {
            val activeAnn = audioData.activeAnnotation()
            scanStart = activeAnn?.startTimeSec
            scanEnd = activeAnn?.endTimeSec
        }

        val sm = audioData.sliceManager()
        logger.debug("[FullScan] Start: file={}, slices={}, duration={}s", audioFile?.name, sm?.sliceCount, totalDurationSec)

        backgroundScanJob = scope.launch {
            onProcessingChanged(true)
            onStatusUpdate("BirdNET analysiert...", null)

            try {
                val settings = SettingsStore.load()
                val filterConfig = audioData.filterConfig()
                val useFiltered = settings.birdnetUseFiltered && filterConfig.isActive

                val allResults = mutableListOf<ClassifierResult>()

                if (sm != null && !sm.isSingleSlice && scanStart == null) {
                    // ============================================================
                    // Zwei-Phasen Viewport-First Scan
                    // ============================================================

                    // Viewport-Position ermitteln
                    val viewStart = audioData.viewStartSec()
                    val viewEnd = audioData.viewEndSec()

                    // Phase 1: Slice finden der den Viewport enthaelt
                    val viewportSliceIndex = sm.slices.indexOfFirst { slice ->
                        slice.startSec < viewEnd && slice.endSec > viewStart
                    }
                    val priorityIndex = if (viewportSliceIndex >= 0) viewportSliceIndex else 0

                    // Phase 1: Priority-Slice zuerst scannen
                    val prioritySlice = sm.slices[priorityIndex]
                    onStatusUpdate(
                        "BirdNET: ${prioritySlice.displayLabel()} (Viewport)...",
                        "Viewport-Slice..."
                    )
                    _scanDetectionCount.value = 0
                    logger.debug("[FullScan] Phase 1: Viewport-Slice {} ({}-{}s)", priorityIndex, prioritySlice.startSec, prioritySlice.endSec)

                    val priorityResults = analyzeAudioRange(
                        settings, useFiltered, filterConfig,
                        prioritySlice.startSec, prioritySlice.endSec
                    )
                    allResults.addAll(priorityResults)
                    _scanDetectionCount.value = priorityResults.size

                    // Phase 1: Sofort Ergebnisse anzeigen + UI freigeben
                    if (priorityResults.isNotEmpty()) {
                        val tempAnnotations = createAnnotationsFromResultsQuick(priorityResults, allResults)
                        annotationManager.mergeAnnotations(
                            keep = { ann -> !ann.isBirdNetDetection },
                            newAnnotations = tempAnnotations,
                            activeId = tempAnnotations.firstOrNull()?.id,
                            spectrogramData = audioData.zoomedSpectrogramData()
                        )
                        // Zoom auf erste Detektion
                        val first = tempAnnotations.first()
                        val margin = (first.endTimeSec - first.startTimeSec) * 0.5f
                        onZoomToRange(
                            (first.startTimeSec - margin).coerceAtLeast(0f),
                            (first.endTimeSec + margin).coerceAtMost(totalDurationSec)
                        )
                    }
                    onProcessingChanged(false)  // UI FREIGEBEN nach Phase 1
                    _isBackgroundScanning.value = true
                    logger.debug("[FullScan] Phase 1 fertig: {} Detektionen, UI freigegeben", priorityResults.size)

                    // Phase 2: Restliche Slices im Hintergrund
                    val remainingSlices = sm.slices.filterIndexed { idx, _ -> idx != priorityIndex }
                    for ((i, slice) in remainingSlices.withIndex()) {
                        // Coroutine-Cancellation pruefen
                        ensureActive()

                        onStatusUpdate(
                            "BirdNET (Hintergrund): ${_scanDetectionCount.value} Events, ${slice.displayLabel()} (${i + 1}/${remainingSlices.size})",
                            null
                        )
                        logger.debug("[FullScan] Phase 2: Slice {}: {}-{}s", slice.index, slice.startSec, slice.endSec)

                        val sliceResults = analyzeAudioRange(
                            settings, useFiltered, filterConfig,
                            slice.startSec, slice.endSec
                        )
                        allResults.addAll(sliceResults)
                        _scanDetectionCount.value = allResults.size

                        // Progressive Merge: Nach jedem Slice Annotations aktualisieren
                        if (sliceResults.isNotEmpty()) {
                            val dedupSoFar = deduplicateOverlapDetections(allResults, sm.overlapSec)
                            val filteredSoFar = applyRegionFilter(dedupSoFar, settings)
                            val candidateMap = buildCandidateMap(dedupSoFar)
                            val (progressiveAnnotations, _, _) = createAnnotationsFromResults(
                                filteredSoFar, candidateMap
                            )
                            annotationManager.mergeAnnotations(
                                keep = { ann -> !ann.isBirdNetDetection },
                                newAnnotations = progressiveAnnotations,
                                activeId = null,  // Aktive Annotation nicht aendern
                                spectrogramData = audioData.zoomedSpectrogramData()
                            )
                        }
                    }

                    _isBackgroundScanning.value = false

                    // Finale Deduplizierung
                    val deduplicated = deduplicateOverlapDetections(allResults, sm.overlapSec)
                    logger.debug("[FullScan] Dedupliziert: {} → {}", allResults.size, deduplicated.size)
                    allResults.clear()
                    allResults.addAll(deduplicated)
                } else {
                    // Einzel-Verarbeitung (Zone oder gesamte kurze Datei)
                    val rangeStart = scanStart ?: 0f
                    val rangeEnd = scanEnd ?: totalDurationSec
                    val scanRegion = if (scanStart != null) " (Region ${scanStart}s-${scanEnd}s)" else " (gesamt)"
                    onStatusUpdate("BirdNET analysiert$scanRegion...", null)

                    val results = analyzeAudioRange(
                        settings, useFiltered, filterConfig,
                        rangeStart, rangeEnd
                    )
                    allResults.addAll(results)
                }

                logger.debug("[FullScan] Gesamt: {} Detektionen", allResults.size)

                if (allResults.isEmpty()) {
                    onProcessingChanged(false)
                    onStatusUpdate("BirdNET: Keine Arten erkannt — min. Konfidenz: ${settings.birdnetMinConf}", "")
                    return@launch
                }

                // Post-Filter: Artenset auf BirdNET-Ergebnisse anwenden
                val filteredResults = applyRegionFilter(allResults, settings)

                logger.debug("[FullScan] Post-Filter ({}): {} → {} Detektionen",
                    settings.activeRegionSet, allResults.size, filteredResults.size)

                if (filteredResults.isEmpty() && allResults.isNotEmpty()) {
                    onProcessingChanged(false)
                    onStatusUpdate(
                        "BirdNET: ${allResults.size} Detektionen, aber keine im aktiven Artenset — Filter pruefen?",
                        ""
                    )
                    return@launch
                }

                // Kandidatenliste pro Slice aufbauen (Top-10 nach Konfidenz, ALLE Arten inkl. ausserhalb Set)
                val sliceCandidatesMap = buildCandidateMap(allResults)

                // Ergebnisse zu Annotationen konvertieren (nur gefilterte Arten)
                val (newAnnotations, speciesColorMap, speciesCounts) = createAnnotationsFromResults(filteredResults, sliceCandidatesMap)

                onAuditEntry(
                    "BirdNET Scan",
                    "${if (onnxClassifier.isAvailable()) "BirdNET V3.0 (ONNX)" else "BirdNET (Python)"} Scan: ${newAnnotations.size} Detektionen, " +
                        "${speciesColorMap.size} Arten" +
                        (if (speciesCounts.isNotEmpty()) " — " +
                            speciesCounts.entries.sortedByDescending { it.value }
                                .take(5).joinToString(", ") { "${it.key} (${it.value}x)" }
                        else "")
                )

                // Finale Annotations mergen (ueberschreibt progressive Zwischenstaende)
                annotationManager.mergeAnnotations(
                    keep = { ann ->
                        !ann.isBirdNetDetection || (scanStart != null && scanEnd != null &&
                            (ann.endTimeSec < scanStart || ann.startTimeSec > scanEnd))
                    },
                    newAnnotations = newAnnotations,
                    activeId = newAnnotations.firstOrNull()?.id,
                    spectrogramData = audioData.zoomedSpectrogramData()
                )
                onProcessingChanged(false)
                onStatusUpdate(
                    "BirdNET: ${newAnnotations.size} Detektionen, " +
                        "${speciesColorMap.size} Arten — " +
                        speciesCounts.entries.sortedByDescending { it.value }
                            .take(5).joinToString(", ") { "${it.key} (${it.value}x)" },
                    ""
                )

                // Zoom auf erste Detektion + Referenz-Suche ausloesen
                if (newAnnotations.isNotEmpty()) {
                    val first = newAnnotations.first()
                    val margin = (first.endTimeSec - first.startTimeSec) * 0.5f
                    onZoomToRange(
                        (first.startTimeSec - margin).coerceAtLeast(0f),
                        (first.endTimeSec + margin).coerceAtMost(totalDurationSec)
                    )
                    // Referenz-Suche mit explizitem Artnamen (nicht von aktiver Annotation abhaengig)
                    val firstSpecies = first.label
                        .replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "")
                        .substringBefore("_").trim()
                    if (firstSpecies.contains(" ")) {
                        searchSimilar(firstSpecies)
                    } else {
                        searchSimilar()
                    }
                }

                onDirtyChanged()
            } catch (e: CancellationException) {
                logger.debug("[FullScan] Scan abgebrochen (neuer Scan gestartet)")
                _isBackgroundScanning.value = false
            } catch (e: Exception) {
                logger.debug("[FullScan] EXCEPTION", e)
                _isBackgroundScanning.value = false
                onProcessingChanged(false)
                onStatusUpdate("BirdNET Fehler: ${e.message}", "")
            }
        }
    }

    /**
     * Analysiert einen Zeitbereich mit BirdNET. Laedt Audio aus Segment oder PcmCache.
     */
    private suspend fun analyzeAudioRange(
        settings: AppSettings,
        useFiltered: Boolean,
        filterConfig: FilterConfig,
        startSec: Float,
        endSec: Float
    ): List<ClassifierResult> {
        val startNano = System.nanoTime()

        val pcmCache = audioData.pcmCache()
        val audioSeg = if (pcmCache != null) {
            pcmCache.readRange(startSec, endSec)
        } else {
            val full = audioData.audioSegment() ?: return emptyList()
            full.subRangeSec(startSec, endSec)
        }

        val sr = audioSeg.sampleRate
        // Nur klonen wenn in-place Modifikation noetig
        val needsVolumeEnvelope = audioData.volumeEnvelopeActive() && audioData.volumeEnvelope().isNotEmpty()
        val needsFilter = useFiltered
        var samples = if (needsVolumeEnvelope || needsFilter) {
            audioSeg.samples.clone()
        } else {
            audioSeg.samples  // Keine Kopie noetig
        }

        // Volume Envelope anwenden
        val volumeEnvelope = audioData.volumeEnvelope()
        if (needsVolumeEnvelope) {
            for (i in samples.indices) {
                val timeSec = startSec + i.toFloat() / sr
                samples[i] *= gainDbToLinear(volumeEnvelope.gainAtTime(timeSec))
            }
        }

        // Spectral Filter
        if (useFiltered) {
            val seg = AudioSegment(samples, sr)
            samples = ch.etasystems.amsel.core.audio.FilteredAudio.apply(seg, filterConfig, audioData.maxFreqHz())
        }

        // ONNX-first: BirdNET V3.0 direkt, Python-Bridge als Fallback
        val rawResults = try {
            if (onnxClassifier.isAvailable()) {
                onnxClassifier.classify(
                    samples = samples,
                    sampleRate = sr,
                    minConfidence = settings.birdnetMinConf,
                    topN = 0
                )
            } else {
                throw IllegalStateException("ONNX nicht verfuegbar")
            }
        } catch (e: Exception) {
            if (onnxClassifier.isAvailable()) {
                logger.warn("ONNX-Klassifikation fehlgeschlagen, Fallback auf Python-Bridge: {}", e.message)
            }
            if (BirdNetBridge.isAvailable()) {
                BirdNetBridge.classify(
                    samples = samples,
                    sampleRate = sr,
                    topN = 0,
                    lat = settings.locationLat,
                    lon = settings.locationLon,
                    minConf = settings.birdnetMinConf
                ) ?: emptyList()
            } else {
                logger.error("Weder ONNX noch Python-Bridge verfuegbar")
                emptyList()
            }
        }

        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
        val audioDurationSec = endSec - startSec
        val stats = onnxClassifier.lastClassifyStats
        PerformanceLog.summary(
            totalMs = elapsedMs,
            audioDurationSec = audioDurationSec,
            totalChunks = stats?.totalChunks ?: -1,
            skippedChunks = stats?.skippedChunks ?: -1,
            classifiedChunks = stats?.classifiedChunks ?: -1
        )

        // Zeitstempel auf absolute Position verschieben
        return rawResults.map { r ->
            ClassifierResult(
                species = r.species,
                confidence = r.confidence,
                startTime = r.startTime + startSec,
                endTime = r.endTime + startSec,
                scientificName = r.scientificName
            )
        }
    }

    /**
     * Dedupliziert BirdNET-Detektionen aus Overlap-Zonen.
     * Gleiche Art + ueberlappende Zeitbereiche → nur die mit hoechster Konfidenz behalten.
     */
    private fun deduplicateOverlapDetections(
        results: List<ClassifierResult>,
        overlapSec: Float
    ): List<ClassifierResult> {
        if (overlapSec <= 0f) return results

        val bySpecies = results.groupBy { it.species.ifEmpty { it.scientificName } }
        val deduplicated = mutableListOf<ClassifierResult>()

        for ((_, speciesResults) in bySpecies) {
            val sorted = speciesResults.sortedBy { it.startTime }
            val kept = mutableListOf<ClassifierResult>()

            for (result in sorted) {
                val last = kept.lastOrNull()
                if (last != null && result.startTime < last.endTime + overlapSec) {
                    if (result.confidence > last.confidence) {
                        kept[kept.lastIndex] = result
                    }
                } else {
                    kept.add(result)
                }
            }
            deduplicated.addAll(kept)
        }

        return deduplicated.sortedBy { it.startTime }
    }

    /**
     * Konvertiert ClassifierResults in Annotationen mit Farbzuweisung pro Art.
     */
    private fun createAnnotationsFromResults(
        results: List<ClassifierResult>,
        sliceCandidates: Map<Pair<Float, Float>, List<SpeciesCandidate>> = emptyMap()
    ): Triple<List<Annotation>, Map<String, Int>, Map<String, Int>> {
        val speciesColorMap = mutableMapOf<String, Int>()
        var colorIdx = 0
        val newAnnotations = mutableListOf<Annotation>()
        val speciesCounts = mutableMapOf<String, Int>()

        for (result in results) {
            val species = result.species.ifEmpty { result.scientificName }
            if (species.isBlank()) continue

            val color = speciesColorMap.getOrPut(species) {
                val c = colorIdx % 8
                colorIdx++
                c
            }

            val count = (speciesCounts[species] ?: 0) + 1
            speciesCounts[species] = count

            val (lowHz, highHz) = estimateFreqRange(species)

            val ann = Annotation(
                label = "${species} (${(result.confidence * 100).toInt()}%)",
                isBirdNetDetection = true,
                startTimeSec = result.startTime,
                endTimeSec = result.endTime,
                lowFreqHz = lowHz,
                highFreqHz = highHz,
                colorIndex = color,
                candidates = sliceCandidates[Pair(result.startTime, result.endTime)] ?: emptyList(),
                audioFileId = audioData.activeAudioFileId()
            )
            newAnnotations.add(ann)
        }

        return Triple(newAnnotations, speciesColorMap, speciesCounts)
    }

    /**
     * Post-Filter: Artenset auf BirdNET-Ergebnisse anwenden.
     * Gibt ungefilterte Liste zurueck wenn regionSetId == "all".
     */
    private fun applyRegionFilter(
        results: List<ClassifierResult>,
        settings: AppSettings
    ): List<ClassifierResult> {
        val regionSetId = settings.activeRegionSet
        if (regionSetId == "all") return results
        return results.filter { result ->
            val sciName = result.scientificName.ifBlank {
                result.species.substringBefore("_").trim()
            }
            RegionSetRegistry.isSpeciesInSet(regionSetId, sciName)
        }
    }

    /**
     * Kandidatenliste pro Zeitslot aufbauen (Top-10 nach Konfidenz, ALLE Arten inkl. ausserhalb Set).
     */
    private fun buildCandidateMap(
        results: List<ClassifierResult>
    ): Map<Pair<Float, Float>, List<SpeciesCandidate>> {
        return results
            .groupBy { Pair(it.startTime, it.endTime) }
            .mapValues { (_, sliceResults) ->
                sliceResults.sortedByDescending { it.confidence }
                    .take(10)
                    .map { r ->
                        SpeciesCandidate(
                            species = r.species,
                            scientificName = r.scientificName,
                            confidence = r.confidence
                        )
                    }
            }
    }

    /**
     * Schnelle Annotation-Erstellung fuer Phase-1-Ergebnisse (Viewport-Slice).
     * Nutzt die bestehende createAnnotationsFromResults() mit einer temporaeren Kandidaten-Map.
     */
    private fun createAnnotationsFromResultsQuick(
        priorityResults: List<ClassifierResult>,
        allResults: List<ClassifierResult>
    ): List<Annotation> {
        val candidateMap = buildCandidateMap(allResults)
        val settings = SettingsStore.load()
        val filtered = applyRegionFilter(priorityResults, settings)
        val (annotations, _, _) = createAnnotationsFromResults(filtered, candidateMap)
        return annotations
    }

    /** Frequenzbereich fuer eine Art schaetzen (grob nach Vogelgruppe) */
    private fun estimateFreqRange(species: String): Pair<Float, Float> {
        val lower = species.lowercase()
        return when {
            lower.contains("owl") || lower.contains("eagle") || lower.contains("heron") ||
            lower.contains("pigeon") || lower.contains("dove") || lower.contains("bittern") ||
            lower.contains("kauz") || lower.contains("eule") || lower.contains("uhu") ->
                Pair(200f, 3000f)
            lower.contains("bat") || lower.contains("pipistrelle") || lower.contains("myotis") ->
                Pair(15000f, 80000f)
            lower.contains("goldcrest") || lower.contains("firecrest") || lower.contains("treecreeper") ||
            lower.contains("goldhaehnchen") ->
                Pair(5000f, 10000f)
            else -> Pair(1500f, 8000f)
        }
    }

    // ====================================================================
    // Kandidat uebernehmen
    // ====================================================================

    /**
     * Uebernimmt einen Alternativ-Kandidaten als Label fuer eine bestehende Annotation.
     * Aktualisiert das Label und loest eine Referenz-Suche fuer die neue Art aus.
     */
    fun adoptCandidate(annotationId: String, candidate: SpeciesCandidate) {
        val newLabel = "${candidate.species} (${(candidate.confidence * 100).toInt()}%)"
        annotationManager.updateAnnotationLabel(annotationId, newLabel)
        onDirtyChanged()
        // Referenz-Sonogramme fuer die neue Art laden
        searchSimilar(candidate.scientificName)
    }

    /** Verifiziert einen Kandidaten UND laedt passende Referenz-Sonogramme. */
    fun verifyCandidateAndSearch(annotationId: String, candidate: SpeciesCandidate) {
        annotationManager.verifyCandidateInAnnotation(annotationId, candidate.species)
        onDirtyChanged()
        searchSimilar(candidate.scientificName)
    }

    // ====================================================================
    // Event-Detection (Energie-basiert)
    // ====================================================================

    /**
     * Schnelle Event-Erkennung: Energie-Profil pro Zeitfenster.
     * Kein Flood-Fill, kein Tracking — einfach Zeitfenster mit hoher Energie finden.
     */
    fun detectEvents() {
        val data = audioData.zoomedSpectrogramData() ?: run {
            onStatusUpdate("Kein Spektrogramm -- zuerst Audio importieren", null)
            return
        }
        val viewStartSec = audioData.viewStartSec()
        val viewEndSec = audioData.viewEndSec()

        scope.launch {
            onProcessingChanged(true)
            onStatusUpdate("Erkenne Events...", null)

            try {
                val nMels = data.nMels
                val nFrames = data.nFrames
                val minVal = data.minValue
                val maxVal = data.maxValue
                val viewDuration = viewEndSec - viewStartSec
                val melMin = MelFilterbank.hzToMel(data.fMin)
                val melMax = MelFilterbank.hzToMel(data.fMax)

                val step = maxOf(1, nFrames / 1000)
                val aFrames = nFrames / step

                val gateFloor = minVal + 0.02f * (maxVal - minVal)
                val energy = FloatArray(aFrames)
                val freqLow = IntArray(aFrames) { nMels }
                val freqHigh = IntArray(aFrames) { 0 }

                for (af in 0 until aFrames) {
                    val srcFrame = (af * step).coerceIn(0, nFrames - 1)
                    var sum = 0f
                    for (mel in 0 until nMels) {
                        val v = data.valueAt(mel, srcFrame)
                        if (v > gateFloor) {
                            sum += (v - gateFloor)
                            if (mel < freqLow[af]) freqLow[af] = mel
                            if (mel > freqHigh[af]) freqHigh[af] = mel
                        }
                    }
                    energy[af] = sum
                }

                val nonZeroArray = energy.filter { it > 0f }.toFloatArray()
                if (nonZeroArray.isEmpty()) {
                    onProcessingChanged(false)
                    onStatusUpdate("Kein Signal erkannt", null)
                    return@launch
                }
                nonZeroArray.sort()
                val energyMedian = nonZeroArray[nonZeroArray.size / 2]
                val energyThreshold = energyMedian * 0.5f

                data class RawEvent(var startAF: Int, var endAF: Int, var lowMel: Int, var highMel: Int)
                val events = mutableListOf<RawEvent>()
                var inEvent = false
                var currentEvent: RawEvent? = null
                val mergeGap = maxOf(1, (0.5f / (viewDuration / aFrames)).toInt())

                for (af in 0 until aFrames) {
                    if (energy[af] > energyThreshold) {
                        if (!inEvent) {
                            val last = events.lastOrNull()
                            if (last != null && af - last.endAF <= mergeGap) {
                                currentEvent = last
                                events.removeAt(events.size - 1)
                            } else {
                                currentEvent = RawEvent(af, af, freqLow[af], freqHigh[af])
                            }
                            inEvent = true
                        }
                        currentEvent!!.endAF = af
                        if (freqLow[af] < currentEvent!!.lowMel) currentEvent!!.lowMel = freqLow[af]
                        if (freqHigh[af] > currentEvent!!.highMel) currentEvent!!.highMel = freqHigh[af]
                    } else {
                        if (inEvent) {
                            events.add(currentEvent!!)
                            inEvent = false
                        }
                    }
                }
                if (inEvent && currentEvent != null) events.add(currentEvent!!)

                logger.debug("[EVENT] {} rohe Events, threshold={}, median={}, mergeGap={}", events.size, energyThreshold, energyMedian, mergeGap)

                val minDurAF = maxOf(1, (0.1f / (viewDuration / aFrames)).toInt())
                val filtered = events.filter { it.endAF - it.startAF >= minDurAF }

                logger.debug("[EVENT] {} Events nach Filter (minDur={} AF)", filtered.size, minDurAF)

                if (filtered.isEmpty()) {
                    val fallback = Annotation(
                        label = "Markierung_${audioData.annotations().size + 1}",
                        startTimeSec = viewStartSec, endTimeSec = viewEndSec,
                        lowFreqHz = data.fMin, highFreqHz = data.fMax,
                        colorIndex = annotationManager.allocateColor(),
                        audioFileId = audioData.activeAudioFileId()
                    )
                    annotationManager.addAnnotation(fallback, spectrogramData = data)
                    onProcessingChanged(false)
                    onStatusUpdate("Gleichmaessiges Signal -- gesamter Bereich markiert", null)
                    return@launch
                }

                val capped = if (filtered.size > 30) filtered.sortedByDescending { it.endAF - it.startAF }.take(30) else filtered

                val categoryCounts = mutableMapOf<String, Int>()
                val startColor = annotationManager.allocateColors(capped.size)
                val newAnnotations = capped.mapIndexed { index, ev ->
                    val startSec = viewStartSec + (ev.startAF.toFloat() * step / nFrames) * viewDuration
                    val endSec = viewStartSec + ((ev.endAF + 1).toFloat() * step / nFrames) * viewDuration
                    val lowHz = melBinToHz(ev.lowMel, nMels, melMin, melMax)
                    val highHz = melBinToHz(ev.highMel, nMels, melMin, melMax)
                    val centerHz = (lowHz + highHz) / 2f
                    val freqRange = highHz - lowHz

                    val category = when {
                        freqRange > 10000f -> "Breitband"
                        centerHz > 15000f -> "Fledermaus"
                        centerHz > 10000f -> "Insekt"
                        centerHz < 800f -> "Amphibie"
                        centerHz < 1500f -> "Grossvogel"
                        else -> "Singvogel"
                    }
                    val count = categoryCounts.getOrDefault(category, 0) + 1
                    categoryCounts[category] = count

                    Annotation(
                        label = "${category}_$count",
                        startTimeSec = startSec, endTimeSec = endSec,
                        lowFreqHz = lowHz.coerceAtLeast(data.fMin),
                        highFreqHz = highHz.coerceAtMost(data.fMax),
                        colorIndex = (startColor + index) % 8,
                        audioFileId = audioData.activeAudioFileId()
                    )
                }

                val summary = categoryCounts.entries.joinToString(", ") { "${it.value} ${it.key}" }
                annotationManager.addAnnotations(newAnnotations, activeId = newAnnotations.firstOrNull()?.id, spectrogramData = data)
                onProcessingChanged(false)
                onStatusUpdate("${capped.size} Events: $summary", null)

            } catch (e: Exception) {
                onProcessingChanged(false)
                onStatusUpdate("Detection-Fehler: ${e.message}", null)
            }
        }
    }

    private fun melBinToHz(melBin: Int, nMels: Int, melMin: Float, melMax: Float): Float {
        val melVal = melMin + (melBin.toFloat() / (nMels - 1).coerceAtLeast(1).toFloat()) * (melMax - melMin)
        return MelFilterbank.melToHz(melVal)
    }

    // ====================================================================
    // API-Key Verwaltung
    // ====================================================================

    fun showApiKeyDialog() {
        _state.update { it.copy(showApiKeyDialog = true) }
        onStateChanged()
    }

    fun dismissApiKeyDialog() {
        _state.update { it.copy(showApiKeyDialog = false) }
        onStateChanged()
    }

    fun saveApiKey(key: String) {
        xenoCantoApi.apiKey = key
        val existing = SettingsStore.load()
        SettingsStore.save(existing.copy(xenoCantoApiKey = key))
        _state.update { it.copy(showApiKeyDialog = false, hasApiKey = key.isNotBlank()) }
        onStateChanged()
    }

    fun getApiKey(): String = xenoCantoApi.apiKey

    // ====================================================================
    // Download-Verwaltung
    // ====================================================================

    fun showDownloadDialog() {
        _state.update { it.copy(showDownloadDialog = true) }
        onStateChanged()
    }

    fun dismissDownloadDialog() {
        _state.update { it.copy(showDownloadDialog = false) }
        onStateChanged()
    }

    fun startDownload(speciesList: List<String>, maxPerSpecies: Int) {
        if (!xenoCantoApi.hasApiKey()) {
            _state.update { it.copy(showApiKeyDialog = true) }
            onStateChanged()
            onStatusUpdate("API-Key benoetigt fuer Download", null)
            return
        }
        referenceDownloader.startDownload(speciesList, maxPerSpecies)
    }

    fun cancelDownload() {
        referenceDownloader.cancelDownload()
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** Bricht laufende Suche ab (z.B. wenn Label-Editing beginnt). */
    fun stopSearching() {
        _state.update { it.copy(isSearching = false) }
    }

    fun reset() {
        backgroundScanJob?.cancel()
        backgroundScanJob = null
        _isBackgroundScanning.value = false
        _scanDetectionCount.value = 0
        _state.update { State() }
        // hasApiKey aus Settings wiederherstellen
        val settings = SettingsStore.load()
        if (settings.xenoCantoApiKey.isNotBlank()) {
            _state.update { it.copy(hasApiKey = true) }
        }
        onStateChanged()
    }

    fun dispose() {
        backgroundScanJob?.cancel()
        onnxClassifier.close()
        referenceDownloader.dispose()
        scope.cancel()
        xenoCantoApi.close()
    }
}
