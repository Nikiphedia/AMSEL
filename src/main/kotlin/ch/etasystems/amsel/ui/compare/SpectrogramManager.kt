package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.PcmCacheFile
import ch.etasystems.amsel.core.filter.ExpanderGate
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.filter.FilterPipeline
import ch.etasystems.amsel.core.model.VolumePoint
import ch.etasystems.amsel.core.model.gainAtTime
import ch.etasystems.amsel.core.model.gainDbToLinear
import ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

// ====================================================================
// Package-Level Utilities
// ====================================================================

/** Float mit N Dezimalstellen formatieren */
internal fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

/** Beschreibt aktive Filter als lesbaren Text */
fun describeFilter(config: FilterConfig): String {
    val parts = mutableListOf<String>()
    if (config.noiseFilter) parts.add("Noise=${"%.1f".format(config.noiseFilterPercent)}%")
    else if (config.spectralSubtraction) parts.add("Kontrast=${"%.1f".format(config.spectralSubtractionAlpha)}")
    if (config.expanderGate) {
        val mode = if (config.expanderMode == ExpanderGate.Mode.GATE) "Gate" else "Exp"
        parts.add("$mode=${"%.1f".format(config.expanderThreshold)}dB")
    }
    if (config.limiter) parts.add("Limiter=${"%.1f".format(config.limiterThresholdDb)}dB")
    if (config.bandpass) parts.add("BP=${config.bandpassLowHz.toInt()}-${config.bandpassHighHz.toInt()}Hz")
    if (config.medianFilter) parts.add("Median=${config.medianKernelSize}")
    return parts.joinToString(", ")
}

// ====================================================================
// Audio-Daten-Provider (Lese-Zugriff auf andere Manager)
// ====================================================================

/** Lese-Zugriff auf Audio-Daten aus AudioManager/VolumeManager */
data class AudioDataProvider(
    val audioFile: () -> File?,
    val audioSegment: () -> AudioSegment?,
    val pcmCache: () -> PcmCacheFile?,
    val volumeEnvelope: () -> List<VolumePoint>,
    val volumeEnvelopeActive: () -> Boolean
)

// ====================================================================
// SpectrogramState
// ====================================================================

data class SpectrogramState(
    // Spektrogramm-Daten
    val overviewSpectrogramData: SpectrogramData? = null,
    val originalOverviewData: SpectrogramData? = null,
    val zoomedSpectrogramData: SpectrogramData? = null,
    val originalZoomedData: SpectrogramData? = null,
    val compareSpectrogramData: SpectrogramData? = null,
    val compareOriginalData: SpectrogramData? = null,
    val isComputingZoom: Boolean = false,
    val paletteVersion: Int = 0,

    // Viewport
    val viewStartSec: Float = 0f,
    val viewEndSec: Float = 0f,
    val totalDurationSec: Float = 0f,
    val displayFreqZoom: Float = 1.0f,
    val useLogFreqAxis: Boolean = false,
    val fullView: Boolean = false,

    // Filter & Display
    val filterConfig: FilterConfig = FilterConfig(),
    val showFilterPanel: Boolean = false,
    val displayDbRange: Float = 10f,
    val displayGamma: Float = 1.0f,
    val isNormalized: Boolean = false,
    val normGainDb: Float = 0f,
    val normReferenceMaxDb: Float = 0f
)

// ====================================================================
// SpectrogramManager
// ====================================================================

/**
 * Manager fuer FFT-Berechnung, Viewport, Zoom, Filter und Display-Settings.
 * Eigener CoroutineScope fuer debounced Berechnungen.
 *
 * Callbacks:
 * - onStateChanged: State-Bridge in CompareUiState aktualisieren
 * - onStatusUpdate(statusText?, sidebarStatus?): UI-Meldungen (null = nicht aendern)
 * - onProcessingChanged: isProcessing Flag setzen
 * - onAuditEntry(action, details): Audit-Trail Eintrag
 * - onDirtyChanged: Projekt als geaendert markieren
 * - onCompareFileImported: Vergleichsdatei in AudioManager registrieren
 * - onAudioSegmentNormalized: Normalisiertes AudioSegment zurueckgeben
 */
class SpectrogramManager(
    private val audioData: AudioDataProvider,
    var maxFreqHz: Float = 16000f,
    private val onStateChanged: () -> Unit = {},
    private val onStatusUpdate: (statusText: String?, sidebarStatus: String?) -> Unit = { _, _ -> },
    private val onProcessingChanged: (Boolean) -> Unit = {},
    private val onAuditEntry: (String, String) -> Unit = { _, _ -> },
    private val onDirtyChanged: () -> Unit = {},
    private val onCompareFileImported: (File) -> Unit = {},
    private val onAudioSegmentNormalized: (AudioSegment) -> Unit = {}
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(SpectrogramState())
    val state: StateFlow<SpectrogramState> = _state.asStateFlow()

    private var filterDebounceJob: Job? = null
    private var zoomDebounceJob: Job? = null

    companion object {
        private const val FILTER_DEBOUNCE_MS = 500L
        private const val ZOOM_DEBOUNCE_MS = 400L
    }

    // ====================================================================
    // Initiales Setup
    // ====================================================================

    /** Setzt Spektrogramm-Daten nach einem Audio-Import */
    fun setInitialSpectrograms(
        overviewData: SpectrogramData,
        zoomedData: SpectrogramData,
        totalDurationSec: Float,
        initialViewEnd: Float
    ) {
        filterDebounceJob?.cancel()
        zoomDebounceJob?.cancel()
        _state.update {
            SpectrogramState(
                overviewSpectrogramData = overviewData,
                originalOverviewData = overviewData,
                zoomedSpectrogramData = zoomedData,
                originalZoomedData = zoomedData,
                viewStartSec = 0f,
                viewEndSec = initialViewEnd,
                totalDurationSec = totalDurationSec
            )
        }
        onStateChanged()
    }

    /** Setzt Spektrogramm-Daten fuer Sonogramm-Bild-Import */
    fun setImageSpectrograms(data: SpectrogramData, durationSec: Float) {
        filterDebounceJob?.cancel()
        zoomDebounceJob?.cancel()
        _state.update {
            SpectrogramState(
                overviewSpectrogramData = data,
                originalOverviewData = data,
                zoomedSpectrogramData = data,
                originalZoomedData = data,
                viewStartSec = 0f,
                viewEndSec = durationSec,
                totalDurationSec = durationSec
            )
        }
        onStateChanged()
    }

    /** Stellt Filter/Display-State aus Projekt wieder her */
    fun restoreFromProject(
        filterConfig: FilterConfig,
        displayDbRange: Float,
        displayGamma: Float,
        isNormalized: Boolean,
        normGainDb: Float
    ) {
        // Laufende Debounce-Jobs canceln damit sie den restored State nicht ueberschreiben
        filterDebounceJob?.cancel()
        zoomDebounceJob?.cancel()

        _state.update {
            it.copy(
                filterConfig = filterConfig,
                displayDbRange = displayDbRange,
                displayGamma = displayGamma,
                isNormalized = isNormalized,
                normGainDb = normGainDb
            )
        }
        onStateChanged()

        // Filter sofort anwenden (ohne Debounce), damit das Spektrogramm korrekt ist
        if (filterConfig.isActive) {
            filterDebounceJob = scope.launch {
                applyFilterNow(filterConfig)
            }
        }
    }

    /** Reset auf Ausgangszustand */
    fun reset() {
        filterDebounceJob?.cancel()
        zoomDebounceJob?.cancel()
        _state.update { SpectrogramState() }
        onStateChanged()
    }

    // ====================================================================
    // Viewport & Navigation (PERFORMANCE-OPTIMIERT)
    // ====================================================================

    /**
     * Billige Viewport-Aktualisierung — nur Koordinaten + debounced Recompute.
     * Fuer Button-getriggerte Aenderungen (Timeline-Klick etc.).
     */
    fun updateViewRange(startSec: Float, endSec: Float) {
        val s = _state.value
        val duration = s.totalDurationSec
        if (duration <= 0f) return

        val start = startSec.coerceIn(0f, duration)
        val end = endSec.coerceIn(start + 0.1f, duration)

        _state.update { it.copy(viewStartSec = start, viewEndSec = end) }
        onStateChanged()
        computeZoomedSpectrogramDebounced(start, end)
    }

    /**
     * Nur Koordinaten updaten OHNE Recompute.
     * Fuer Drag-Operationen: Bild einfrieren, nur Viewport-Indikator bewegen.
     * Nach Drag-Ende commitViewRange() aufrufen.
     */
    fun updateViewRangeLive(startSec: Float, endSec: Float) {
        val s = _state.value
        val duration = s.totalDurationSec
        if (duration <= 0f) return

        val start = startSec.coerceIn(0f, duration)
        val end = endSec.coerceIn(start + 0.1f, duration)

        zoomDebounceJob?.cancel()
        _state.update { it.copy(viewStartSec = start, viewEndSec = end) }
        onStateChanged()
    }

    /** Nach Drag-Ende: Spektrogramm fuer aktuelle Viewport-Position berechnen */
    fun commitViewRange() {
        val s = _state.value
        if (s.totalDurationSec <= 0f) return
        zoomToRange(s.viewStartSec, s.viewEndSec)
    }

    /** Setzt Viewport-Position ohne Recompute (fuer Playback-Follow) */
    fun setViewportPosition(startSec: Float, endSec: Float) {
        _state.update { it.copy(viewStartSec = startSec, viewEndSec = endSec) }
        onStateChanged()
    }

    /**
     * Debounced Spektrogramm-Berechnung.
     * Wird nach jeder Viewport-Aenderung getriggert, aber erst nach
     * 400ms ohne weitere Aenderung tatsaechlich ausgefuehrt.
     */
    private fun computeZoomedSpectrogramDebounced(startSec: Float, endSec: Float) {
        zoomDebounceJob?.cancel()
        zoomDebounceJob = scope.launch {
            delay(ZOOM_DEBOUNCE_MS)

            val segment = audioData.audioSegment()
            val cache = audioData.pcmCache()
            val file = audioData.audioFile()
            if (segment == null && cache == null && file == null) return@launch

            _state.update { it.copy(isComputingZoom = true) }
            onStateChanged()

            try {
                val zoomedData = computeRegion(segment, cache, file, startSec, endSec)
                val s = _state.value
                val filtered = if (s.filterConfig.isActive) {
                    FilterPipeline.apply(zoomedData, s.filterConfig)
                } else {
                    zoomedData
                }

                _state.update {
                    it.copy(
                        zoomedSpectrogramData = filtered,
                        originalZoomedData = zoomedData,
                        isComputingZoom = false
                    )
                }
                onStateChanged()
            } catch (e: Exception) {
                _state.update { it.copy(isComputingZoom = false) }
                onStateChanged()
                onStatusUpdate("Zoom-Fehler: ${e.message}", null)
            }
        }
    }

    /** Sofortige Zoom-Berechnung (fuer Button-Klicks, nicht fuer Drag) */
    fun zoomToRange(startSec: Float, endSec: Float) {
        val segment = audioData.audioSegment()
        val cache = audioData.pcmCache()
        val file = audioData.audioFile()
        if (segment == null && cache == null && file == null) return

        val s = _state.value
        val duration = s.totalDurationSec
        val start = startSec.coerceIn(0f, duration)
        val end = endSec.coerceIn(start + 0.1f, duration)

        _state.update { it.copy(viewStartSec = start, viewEndSec = end) }
        onStateChanged()
        onProcessingChanged(true)
        onStatusUpdate("Berechne Zoom-Ansicht...", null)

        scope.launch {
            try {
                val zoomedData = computeRegion(segment, cache, file, start, end)
                val currentFilter = _state.value.filterConfig
                val filtered = if (currentFilter.isActive) {
                    FilterPipeline.apply(zoomedData, currentFilter)
                } else {
                    zoomedData
                }

                _state.update {
                    it.copy(
                        zoomedSpectrogramData = filtered,
                        originalZoomedData = zoomedData
                    )
                }
                onStateChanged()
                onProcessingChanged(false)
                val audioFile = audioData.audioFile()
                onStatusUpdate("${audioFile?.name} — ${start.format(1)}s – ${end.format(1)}s", null)
            } catch (e: Exception) {
                onProcessingChanged(false)
                onStatusUpdate("Zoom-Fehler: ${e.message}", null)
            }
        }
    }

    /** Berechnet Spektrogramm fuer eine Region aus verfuegbarer Audio-Quelle */
    private suspend fun computeRegion(
        segment: AudioSegment?, cache: PcmCacheFile?, file: File?,
        startSec: Float, endSec: Float
    ): SpectrogramData {
        return if (segment != null) {
            ChunkedSpectrogram.computeRegion(segment, startSec, endSec, maxFreqHz)
        } else if (cache != null) {
            val rangeSeg = cache.readRange(startSec, endSec)
            val spec = MelSpectrogram.auto(rangeSeg.sampleRate, maxFreqHz)
            spec.compute(rangeSeg.samples)
        } else {
            ChunkedSpectrogram.computeRegionFromFile(file!!, startSec, endSec, maxFreqHz)
        }
    }

    fun zoomIn() {
        val s = _state.value
        val center = (s.viewStartSec + s.viewEndSec) / 2f
        val halfRange = (s.viewEndSec - s.viewStartSec) / 4f
        zoomToRange(center - halfRange, center + halfRange)
    }

    fun zoomOut() {
        val s = _state.value
        val duration = s.totalDurationSec
        if (duration <= 0f) return

        val currentRange = s.viewEndSec - s.viewStartSec
        if (currentRange >= duration * 0.99f) return

        val newRange = (currentRange * 2f).coerceAtMost(duration)
        val center = (s.viewStartSec + s.viewEndSec) / 2f
        val newStart = (center - newRange / 2f).coerceIn(0f, duration - newRange)
        zoomToRange(newStart, newStart + newRange)
    }

    fun zoomReset() {
        zoomToRange(0f, _state.value.totalDurationSec)
    }

    /** Frequenz-Zoom: Faktor verdoppeln (halber Frequenzbereich = doppelte Vergroesserung) */
    fun freqZoomIn() {
        _state.update { it.copy(displayFreqZoom = (it.displayFreqZoom * 2f).coerceAtMost(8f)) }
        onStateChanged()
    }

    /** Frequenz-Zoom: Faktor halbieren (zurueck zum vollen Bereich) */
    fun freqZoomOut() {
        _state.update { it.copy(displayFreqZoom = (it.displayFreqZoom / 2f).coerceAtLeast(1f)) }
        onStateChanged()
    }

    /** Full View: Sonogramm nimmt ganze Fensterhoehe ein */
    fun toggleFullView() {
        _state.update { it.copy(fullView = !it.fullView) }
        onStateChanged()
    }

    /** Frequenzachse umschalten: Linear (Hz) vs Logarithmisch (Mel-Bins direkt) */
    fun toggleLogFreqAxis() {
        _state.update { it.copy(useLogFreqAxis = !it.useLogFreqAxis) }
        onStateChanged()
    }

    // ====================================================================
    // Vergleichsdatei (zweites Sonogramm)
    // ====================================================================

    fun importCompareFile(file: File) {
        scope.launch {
            onStatusUpdate(null, "Lade Vergleichsdatei...")
            try {
                val s = _state.value
                val overviewResult = ChunkedSpectrogram.computeOverview(
                    file, targetFrames = 4000, maxFreqHz = maxFreqHz
                )
                val startSec = s.viewStartSec
                val endSec = minOf(s.viewEndSec, overviewResult.durationSec)
                val zoomedData = if (overviewResult.audioSegment != null) {
                    ChunkedSpectrogram.computeRegion(overviewResult.audioSegment, startSec, endSec, maxFreqHz)
                } else {
                    ChunkedSpectrogram.computeRegionFromFile(file, startSec, endSec, maxFreqHz)
                }
                val filtered = if (s.filterConfig.isActive) {
                    FilterPipeline.apply(zoomedData, s.filterConfig)
                } else zoomedData

                onCompareFileImported(file)
                _state.update {
                    it.copy(
                        compareSpectrogramData = filtered,
                        compareOriginalData = zoomedData
                    )
                }
                onStateChanged()
                onStatusUpdate("Vergleich: ${file.name}", null)
            } catch (e: Exception) {
                onStatusUpdate("Vergleichsdatei-Fehler: ${e.message}", null)
            }
        }
    }

    /** Vergleichs-Spektrogramm-Daten loeschen */
    fun clearCompareSpectrograms() {
        _state.update { it.copy(compareSpectrogramData = null, compareOriginalData = null) }
        onStateChanged()
    }

    // ====================================================================
    // Palette & Display
    // ====================================================================

    /**
     * Farbpalette gewechselt: Spektrogramme muessen mit neuer Palette neu gerendert werden.
     * Nur Counter erhoehen → Bitmaps werden in der UI sofort neu erzeugt.
     */
    fun refreshAfterPaletteChange() {
        _state.update { it.copy(paletteVersion = it.paletteVersion + 1) }
        onStateChanged()
    }

    /** Dynamik-Bereich der Anzeige (nur oberste N dB sichtbar) */
    fun setDisplayDbRange(dbRange: Float) {
        _state.update {
            it.copy(
                displayDbRange = dbRange.coerceIn(3f, 12f),
                paletteVersion = it.paletteVersion + 1
            )
        }
        onStateChanged()
    }

    /** Gamma-Korrektur der Anzeige (< 1 = mehr Detail in leisen Bereichen) */
    fun setDisplayGamma(gamma: Float) {
        _state.update {
            it.copy(
                displayGamma = gamma.coerceIn(0.2f, 3f),
                paletteVersion = it.paletteVersion + 1
            )
        }
        onStateChanged()
    }

    /** Ansicht-Bypass: Dynamik/Gamma/Frequenzachse auf Default zuruecksetzen */
    fun resetDisplaySettings() {
        _state.update {
            it.copy(
                displayDbRange = 10f,
                displayGamma = 1.0f,
                useLogFreqAxis = false,
                paletteVersion = it.paletteVersion + 1
            )
        }
        onStateChanged()
    }

    /** Inkrementiert paletteVersion (z.B. nach Volume-Envelope-Aenderung) */
    fun incrementPaletteVersion() {
        _state.update { it.copy(paletteVersion = it.paletteVersion + 1) }
        onStateChanged()
    }

    // ====================================================================
    // Filter (Live mit Debounce)
    // ====================================================================

    fun toggleFilterPanel() {
        _state.update { it.copy(showFilterPanel = !it.showFilterPanel) }
        onStateChanged()
    }

    /** Bypass toggling: alle Filter aus/ein ohne Einstellungen zu verlieren */
    fun toggleFilterBypass() {
        val current = _state.value.filterConfig
        val toggled = current.copy(bypass = !current.bypass)
        applyFilterDebounced(toggled)
    }

    fun applyFilterDebounced(config: FilterConfig) {
        val previousConfig = _state.value.filterConfig
        _state.update { it.copy(filterConfig = config) }
        onStateChanged()

        // Audit nur wenn sich der Filter tatsaechlich geaendert hat
        if (config != previousConfig) {
            val filterDesc = describeFilter(config)
            if (filterDesc.isNotEmpty()) {
                onAuditEntry("Filter geaendert", filterDesc)
            } else {
                onAuditEntry("Filter geaendert", "Alle Filter deaktiviert")
            }
        }

        filterDebounceJob?.cancel()
        filterDebounceJob = scope.launch {
            delay(FILTER_DEBOUNCE_MS)
            applyFilterNow(config)
        }
        onDirtyChanged()
    }

    /** Berechnet Volume-Gains (log10) fuer ein SpectrogramData */
    private fun computeVolumeGains(data: SpectrogramData, offsetSec: Float = 0f): FloatArray? {
        val envelope = audioData.volumeEnvelope()
        val active = audioData.volumeEnvelopeActive()
        if (!active || envelope.isEmpty()) return null
        return FloatArray(data.nFrames) { frame ->
            val timeSec = offsetSec + data.frameToTime(frame)
            envelope.gainAtTime(timeSec) / 10f
        }
    }

    private suspend fun applyFilterNow(config: FilterConfig) {
        val s = _state.value
        val originalZoomed = s.originalZoomedData ?: return
        val originalOverview = s.originalOverviewData

        onProcessingChanged(true)
        onStatusUpdate("Wende Filter an...", null)

        // Volume-Gains berechnen (fuer Pipeline Schritt 0)
        val zoomedVolGains = computeVolumeGains(originalZoomed, s.viewStartSec)
        val overviewVolGains = originalOverview?.let { computeVolumeGains(it) }

        val hasEffect = config.isActive || zoomedVolGains != null

        val filteredZoomed = if (hasEffect) {
            FilterPipeline.apply(originalZoomed, config, zoomedVolGains)
        } else {
            originalZoomed
        }

        val filteredOverview = if (hasEffect && originalOverview != null) {
            FilterPipeline.apply(originalOverview, config, overviewVolGains)
        } else {
            originalOverview
        }

        val filteredCompare = if (config.isActive && s.compareOriginalData != null) {
            FilterPipeline.apply(s.compareOriginalData, config)
        } else {
            s.compareOriginalData
        }

        _state.update {
            it.copy(
                zoomedSpectrogramData = filteredZoomed,
                overviewSpectrogramData = filteredOverview,
                compareSpectrogramData = filteredCompare
            )
        }
        onStateChanged()
        onProcessingChanged(false)
        val audioFile = audioData.audioFile()
        onStatusUpdate(
            audioFile?.let { f ->
                if (config.isActive) "${f.name} — gefiltert" else f.name
            } ?: "",
            null
        )
    }

    // ====================================================================
    // Normalisierung auf -2 dBFS
    // ====================================================================

    /**
     * Normalisierung ein-/ausschalten.
     * AN: Berechnet Gain so dass Peak im Zoom-Bereich/Annotation auf -6 dBFS liegt.
     *     Gain wird in FilterConfig gespeichert und von der Pipeline NACH Bandpass,
     *     VOR Spectral Gate angewendet. PCM wird ebenfalls normalisiert (Playback/Export).
     * AUS: Datei neu laden (Original wiederherstellen).
     *
     * @param audioOffsetSec Zeitversatz fuer kurze Aufnahmen (Padding-Rekonstruktion)
     * @param activeAnnotation Aktive Annotation fuer Bereichs-Bestimmung (optional)
     */
    fun toggleNormalization(
        targetDbfs: Float = -2f,
        audioOffsetSec: Float = 0f,
        activeAnnotation: Annotation? = null
    ) {
        val s = _state.value
        if (s.isNormalized) {
            // AUS: Normalisierung entfernen, andere Filter beibehalten
            val file = audioData.audioFile() ?: return
            val newConfig = s.filterConfig.copy(normalize = false, normalizeGainLog10 = 0f)
            scope.launch {
                onProcessingChanged(true)
                onStatusUpdate("Stelle Original wieder her...", null)
                try {
                    val rawSegment = ch.etasystems.amsel.core.audio.AudioDecoder.decode(file)
                    val segment = if (audioOffsetSec > 0f) {
                        val sr = rawSegment.sampleRate
                        val noiseAmplitude = 7.08e-7f
                        val rng = java.util.Random(42)
                        val preSamples = (audioOffsetSec * sr).toInt()
                        val totalSamples = (s.totalDurationSec * sr).toInt()
                        val padded = FloatArray(totalSamples.coerceAtLeast(rawSegment.samples.size)) {
                            noiseAmplitude * (rng.nextFloat() * 2f - 1f)
                        }
                        rawSegment.samples.copyInto(padded, preSamples.coerceAtLeast(0))
                        ch.etasystems.amsel.core.audio.AudioSegment(padded, sr)
                    } else rawSegment

                    // Filter-Pipeline OHNE Normalisierung auf Originaldaten anwenden
                    val zoomedOrig = s.originalZoomedData ?: return@launch
                    val overviewOrig = s.originalOverviewData
                    val filteredZoomed = if (newConfig.isActive) FilterPipeline.apply(zoomedOrig, newConfig) else zoomedOrig
                    val filteredOverview = if (newConfig.isActive && overviewOrig != null) FilterPipeline.apply(overviewOrig, newConfig) else overviewOrig

                    onAudioSegmentNormalized(segment)
                    _state.update {
                        it.copy(
                            overviewSpectrogramData = filteredOverview,
                            zoomedSpectrogramData = filteredZoomed,
                            filterConfig = newConfig,
                            isNormalized = false,
                            normGainDb = 0f,
                            normReferenceMaxDb = 0f,
                            paletteVersion = it.paletteVersion + 1
                        )
                    }
                    onStateChanged()
                    onProcessingChanged(false)
                    onStatusUpdate("Normalisierung entfernt", null)
                    onDirtyChanged()
                } catch (e: Exception) {
                    onProcessingChanged(false)
                    onStatusUpdate("Fehler: ${e.message}", null)
                }
            }
            return
        }

        // AN: Gain berechnen
        scope.launch {
            onProcessingChanged(true)
            onStatusUpdate("Normalisiere auf $targetDbfs dBFS...", null)

            try {
                val rawAudio = audioData.audioSegment()
                    ?: ch.etasystems.amsel.core.audio.AudioDecoder.decode(audioData.audioFile() ?: return@launch)
                val rangeStart = activeAnnotation?.startTimeSec ?: s.viewStartSec
                val rangeEnd = activeAnnotation?.endTimeSec ?: s.viewEndSec

                // Peak aus gefiltertem Audio wenn Filter aktiv (ohne Normalisierung)
                val configWithoutNorm = s.filterConfig.copy(normalize = false, normalizeGainLog10 = 0f)
                val audio = if (configWithoutNorm.isActive) {
                    onStatusUpdate(null, "Berechne gefilterten Peak...")
                    val filtered = ch.etasystems.amsel.core.audio.FilteredAudio.apply(rawAudio, configWithoutNorm, maxFreqHz, rangeStart, rangeEnd)
                    ch.etasystems.amsel.core.audio.AudioSegment(filtered, rawAudio.sampleRate)
                } else rawAudio

                val startIdx = if (configWithoutNorm.isActive) 0
                    else (rangeStart * audio.sampleRate).toInt().coerceIn(0, audio.samples.size)
                val endIdx = if (configWithoutNorm.isActive) audio.samples.size
                    else (rangeEnd * audio.sampleRate).toInt().coerceIn(startIdx, audio.samples.size)

                var peak = 0f
                for (i in startIdx until endIdx) {
                    val a = abs(audio.samples[i])
                    if (a > peak) peak = a
                }

                if (peak < 1e-10f) {
                    onProcessingChanged(false)
                    onStatusUpdate("Normalisierung: Kein Signal im Bereich", null)
                    return@launch
                }

                val targetPeak = 10.0.pow(targetDbfs.toDouble() / 20.0).toFloat()
                val gain = targetPeak / peak
                val gainDb = (20.0 * log10(gain.toDouble())).toFloat()
                val gainLog10 = gainDb / 10f

                // PCM-Samples normalisieren (Playback + Export) — immer auf Roh-Audio
                val normalizedSamples = FloatArray(rawAudio.samples.size) { i ->
                    (rawAudio.samples[i] * gain).coerceIn(-1f, 1f)
                }
                val normalizedSegment = ch.etasystems.amsel.core.audio.AudioSegment(normalizedSamples, rawAudio.sampleRate)

                // Gain in FilterConfig speichern → Pipeline wendet ihn an
                val newConfig = s.filterConfig.copy(normalize = true, normalizeGainLog10 = gainLog10)

                // Filter-Pipeline mit Normalisierung auf Originaldaten anwenden
                val zoomedOrig = s.originalZoomedData ?: return@launch
                val overviewOrig = s.originalOverviewData

                val filteredZoomed = FilterPipeline.apply(zoomedOrig, newConfig)
                val filteredOverview = overviewOrig?.let { FilterPipeline.apply(it, newConfig) }

                val peakDbfs = (20.0 * log10(peak.toDouble())).toFloat()
                onAuditEntry("Normalisierung",
                    "Normalisiert auf $targetDbfs dBFS (Gain: ${"%+.1f".format(gainDb)} dB)")

                onAudioSegmentNormalized(normalizedSegment)
                _state.update {
                    it.copy(
                        overviewSpectrogramData = filteredOverview,
                        zoomedSpectrogramData = filteredZoomed,
                        filterConfig = newConfig,
                        isNormalized = true,
                        normGainDb = gainDb,
                        normReferenceMaxDb = filteredZoomed.maxValue,
                        paletteVersion = it.paletteVersion + 1
                    )
                }
                onStateChanged()
                onProcessingChanged(false)
                onStatusUpdate(
                    "Normalisiert: ${"%+.1f".format(gainDb)} dB (Peak ${"%+.1f".format(peakDbfs)} → $targetDbfs dBFS)",
                    null
                )
                onDirtyChanged()
            } catch (e: Exception) {
                onProcessingChanged(false)
                onStatusUpdate("Normalisierung-Fehler: ${e.message}", null)
            }
        }
    }

    // ====================================================================
    // Hilfsfunktionen
    // ====================================================================

    /**
     * Uebertraegt die Filter-Maske von Mel-Spektrogramm auf lineares Spektrogramm.
     *
     * Fuer jeden Pixel im linearen Spektrogramm:
     * 1. Berechne die Hz-Frequenz und die relative Zeit-Position
     * 2. Finde den entsprechenden Mel-Bin und Frame
     * 3. Vergleiche Original-Mel vs. Gefiltertes-Mel:
     *    - Wenn der Wert reduziert wurde → gleiche Reduktion auf den linearen Wert anwenden
     *    - Wenn der Wert bei minValue ist (Gate/NoiseFilter) → auch im linearen auf minValue setzen
     */
    fun applyMelMaskToLinear(
        melOriginal: SpectrogramData,
        melFiltered: SpectrogramData,
        linearData: SpectrogramData
    ): SpectrogramData {
        val result = linearData.matrix.copyOf()
        val linearMinVal = linearData.minValue
        val melMinVal = melOriginal.minValue

        val melFreqMin = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(melOriginal.fMin)
        val melFreqMax = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(melOriginal.fMax)
        val melFreqRange = melFreqMax - melFreqMin

        val linearBinToMelBin = FloatArray(linearData.nMels) { linBin ->
            val freqHz = linearData.fMin + (linBin.toFloat() / linearData.nMels) * (linearData.fMax - linearData.fMin)
            val melVal = ch.etasystems.amsel.core.spectrogram.MelFilterbank.hzToMel(freqHz)
            val melBinPos = ((melVal - melFreqMin) / melFreqRange) * melOriginal.nMels
            melBinPos.coerceIn(0f, (melOriginal.nMels - 1).toFloat())
        }

        for (linBin in 0 until linearData.nMels) {
            val melBinPos = linearBinToMelBin[linBin]
            val melBinLo = melBinPos.toInt().coerceIn(0, melOriginal.nMels - 1)
            val melBinHi = (melBinLo + 1).coerceAtMost(melOriginal.nMels - 1)
            val melBinFrac = melBinPos - melBinLo

            for (frame in 0 until linearData.nFrames) {
                val melFramePos = (frame.toFloat() / linearData.nFrames) * melOriginal.nFrames
                val melFrameLo = melFramePos.toInt().coerceIn(0, melOriginal.nFrames - 1)
                val melFrameHi = (melFrameLo + 1).coerceAtMost(melOriginal.nFrames - 1)
                val melFrameFrac = melFramePos - melFrameLo

                val origVal = bilinearLookup(melOriginal, melBinLo, melBinHi, melBinFrac,
                    melFrameLo, melFrameHi, melFrameFrac)
                val filtVal = bilinearLookup(melFiltered, melBinLo, melBinHi, melBinFrac,
                    melFrameLo, melFrameHi, melFrameFrac)

                val origRange = origVal - melMinVal
                val idx = linBin * linearData.nFrames + frame
                if (origRange > 0.001f) {
                    val reduction = (origVal - filtVal) / origRange
                    if (reduction > 0.01f) {
                        val linVal = result[idx]
                        val linRange = linVal - linearMinVal
                        result[idx] = (linVal - reduction * linRange).coerceAtLeast(linearMinVal)
                    }
                } else {
                    result[idx] = linearMinVal
                }
            }
        }

        return linearData.copy(matrix = result)
    }

    /** Bilineare Interpolation in SpectrogramData */
    private fun bilinearLookup(
        data: SpectrogramData,
        binLo: Int, binHi: Int, binFrac: Float,
        frameLo: Int, frameHi: Int, frameFrac: Float
    ): Float {
        val v00 = data.valueAt(binLo, frameLo)
        val v10 = data.valueAt(binHi, frameLo)
        val v01 = data.valueAt(binLo, frameHi)
        val v11 = data.valueAt(binHi, frameHi)
        val top = v00 + (v10 - v00) * binFrac
        val bot = v01 + (v11 - v01) * binFrac
        return top + (bot - top) * frameFrac
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    fun dispose() {
        filterDebounceJob?.cancel()
        zoomDebounceJob?.cancel()
        scope.cancel()
    }
}
