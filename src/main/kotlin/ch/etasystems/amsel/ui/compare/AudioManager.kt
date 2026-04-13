package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.SliceManager
import ch.etasystems.amsel.core.audio.PcmCacheFile
import ch.etasystems.amsel.core.spectrogram.ChunkedSpectrogram
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Verwaltet Audio-Import, Decoding, Slicing und Datei-Management.
 * Grundbaustein fuer PlaybackManager, SpectrogramManager und ClassificationManager.
 *
 * Multi-File: Verwaltet mehrere geladene Audio-Dateien mit einem "aktiven" File.
 */
class AudioManager(
    private val onStateChanged: () -> Unit = {},
    private val onProgressUpdate: (String) -> Unit = {}
) {

    /** State einer einzelnen geladenen Audio-Datei. */
    data class AudioFileState(
        val fileId: String,
        val audioFile: File,
        val audioSegment: AudioSegment? = null,
        val pcmCache: PcmCacheFile? = null,
        val isLargeFile: Boolean = false,
        val durationSec: Float = 0f,
        val sampleRate: Int = 0,
        val audioOffsetSec: Float = 0f,
        val sliceManager: SliceManager? = null,
        val activeSliceIndex: Int = 0
    )

    data class State(
        val loadedFiles: Map<String, AudioFileState> = emptyMap(),
        val activeFileId: String? = null,
        val compareFile: File? = null
    ) {
        val activeFile: AudioFileState? get() = activeFileId?.let { loadedFiles[it] }

        // Backward-Compat Accessors (bestehender Code kompiliert weiter)
        val audioFile: File? get() = activeFile?.audioFile
        val audioSegment: AudioSegment? get() = activeFile?.audioSegment
        val pcmCache: PcmCacheFile? get() = activeFile?.pcmCache
        val isLargeFile: Boolean get() = activeFile?.isLargeFile ?: false
        val audioDurationSec: Float get() = activeFile?.durationSec ?: 0f
        val audioSampleRate: Int get() = activeFile?.sampleRate ?: 0
        val audioOffsetSec: Float get() = activeFile?.audioOffsetSec ?: 0f
        val sliceManager: SliceManager? get() = activeFile?.sliceManager
        val activeSliceIndex: Int get() = activeFile?.activeSliceIndex ?: 0
    }

    /** Ergebnis des Audio-Imports — enthaelt Spektrogramm-Daten fuer ViewModel-Orchestrierung. */
    data class ImportResult(
        val fileId: String,
        val overviewSpectrogramData: SpectrogramData,
        val mode: String,
        val initialViewEnd: Float,
        val zoomedSpectrogramData: SpectrogramData,
        val duration: Float,
        val sampleRate: Int,
        val hasSlices: Boolean
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Importiert eine Audio-Datei: Dekodierung, Padding kurzer Aufnahmen,
     * Overview-Spektrogramm, PCM-Cache fuer grosse Dateien, Slice-Manager.
     *
     * @return ImportResult mit Spektrogramm-Daten fuer ViewModel-Orchestrierung
     */
    suspend fun importAudio(
        file: File,
        maxFreqHz: Float,
        minDisplayDurationSec: Float,
        shortFileStartPct: Float,
        sliceLengthMin: Float,
        sliceOverlapSec: Float,
        initialZoomDurationSec: Float,
        fileId: String = java.util.UUID.randomUUID().toString()
    ): ImportResult {
        // 1. Dekodieren
        onProgressUpdate("Dekodiere Audio...")
        val rawSegment = AudioDecoder.decode(file)
        var duration = rawSegment.durationSec
        val isLargeFile = duration > 60f

        // 2. Kurze Aufnahmen: mit Mikro-Rauschen auffuellen
        val startPct = shortFileStartPct.coerceIn(0f, 1f)
        val isShortFile = duration < minDisplayDurationSec && !isLargeFile
        val audioOffsetSec = if (isShortFile) minDisplayDurationSec * startPct else 0f
        val segment: AudioSegment
        if (isShortFile) {
            val sr = rawSegment.sampleRate
            val preSamples = (audioOffsetSec * sr).toInt().coerceAtLeast(0)
            val postPadSec = (minDisplayDurationSec - duration - audioOffsetSec).coerceAtLeast(0f)
            val postSamples = (postPadSec * sr).toInt().coerceAtLeast(0)
            // Weisses Rauschen bei -123 dBFS (unter 24-Bit Rauschboden) statt digitaler Stille.
            // Damit arbeiten Spectral Gate und andere Filter korrekt auf gepaddetem Audio.
            val noiseAmplitude = 7.08e-7f  // 10^(-123/20) ≈ -123 dBFS
            val rng = java.util.Random(42)  // deterministisch fuer Reproduzierbarkeit
            val paddedSamples = FloatArray(preSamples + rawSegment.samples.size + postSamples) {
                noiseAmplitude * (rng.nextFloat() * 2f - 1f)
            }
            rawSegment.samples.copyInto(paddedSamples, preSamples)
            segment = AudioSegment(paddedSamples, sr)
            duration = segment.durationSec
            System.err.println("[AudioManager] Kurze Aufnahme: ${rawSegment.durationSec}s -> ${duration}s (offset=${audioOffsetSec}s)")
        } else {
            segment = rawSegment
        }

        // 3. Overview-Spektrogramm berechnen (liefert auch audioSegment)
        val actualOverview = ChunkedSpectrogram.computeOverview(segment, targetFrames = 4000, maxFreqHz = maxFreqHz)
        val mode = actualOverview.mode

        // 4. Grosse Dateien: PCM-Cache erstellen (einmalig, danach Random Access)
        var pcmCache: PcmCacheFile? = null
        if (isLargeFile) {
            onProgressUpdate("Erstelle Audio-Cache...")
            pcmCache = PcmCacheFile.createFromAudioFile(file) { p ->
                onProgressUpdate("Audio-Cache: ${(p * 100).toInt()}%")
            }
        }

        // 5. Slice-Manager erstellen wenn Datei laenger als eingestellte Slice-Laenge
        val sliceLengthSec = sliceLengthMin * 60f
        val sliceManager = if (duration > sliceLengthSec) {
            SliceManager(duration, sliceLengthSec, sliceOverlapSec)
        } else null

        // 6. Initialen Zoom-Bereich berechnen
        val initialEnd = if (duration <= initialZoomDurationSec * 1.5f) {
            duration
        } else if (sliceManager != null) {
            sliceManager.slices.first().endSec.coerceAtMost(initialZoomDurationSec)
        } else {
            initialZoomDurationSec
        }

        // 7. Zoom-Spektrogramm berechnen
        onProgressUpdate("Berechne Zoom-Ansicht...")
        val zoomedData = if (pcmCache != null) {
            val rangeSeg = pcmCache.readRange(0f, initialEnd)
            val spec = MelSpectrogram.auto(rangeSeg.sampleRate, maxFreqHz)
            spec.compute(rangeSeg.samples)
        } else {
            ChunkedSpectrogram.computeRegion(
                actualOverview.audioSegment!!, 0f, initialEnd, maxFreqHz
            )
        }

        // 8. Internen State setzen — Multi-File: hinzufuegen statt ersetzen
        val newFileState = AudioFileState(
            fileId = fileId,
            audioFile = file,
            audioSegment = if (isLargeFile) null else actualOverview.audioSegment,
            pcmCache = pcmCache,
            isLargeFile = isLargeFile,
            durationSec = duration,
            sampleRate = actualOverview.sampleRate,
            audioOffsetSec = audioOffsetSec,
            sliceManager = sliceManager,
            activeSliceIndex = 0
        )
        _state.update { s ->
            s.copy(
                loadedFiles = s.loadedFiles + (fileId to newFileState),
                activeFileId = fileId
            )
        }
        onStateChanged()

        return ImportResult(
            fileId = fileId,
            overviewSpectrogramData = actualOverview.spectrogramData,
            mode = mode,
            initialViewEnd = initialEnd,
            zoomedSpectrogramData = zoomedData,
            duration = duration,
            sampleRate = actualOverview.sampleRate,
            hasSlices = sliceManager != null
        )
    }

    // ====================================================================
    // Multi-File Verwaltung
    // ====================================================================

    /** Wechselt die aktive Audio-Datei. Gibt den FileState zurueck oder null. */
    fun switchActiveFile(fileId: String): AudioFileState? {
        val target = _state.value.loadedFiles[fileId] ?: return null
        _state.update { it.copy(activeFileId = fileId) }
        onStateChanged()
        return target
    }

    /** Entfernt eine Audio-Datei. Raeumt PCM-Cache auf. */
    fun removeFile(fileId: String) {
        val file = _state.value.loadedFiles[fileId]
        file?.pcmCache?.delete()
        _state.update { s ->
            val updated = s.loadedFiles - fileId
            val newActiveId = if (s.activeFileId == fileId) updated.keys.firstOrNull() else s.activeFileId
            s.copy(loadedFiles = updated, activeFileId = newActiveId)
        }
        onStateChanged()
    }

    /** Gibt true zurueck wenn dies die erste geladene Datei ist. */
    fun isFirstFile(): Boolean = _state.value.loadedFiles.isEmpty()

    // ====================================================================
    // Slice-Navigation
    // ====================================================================

    /** Wechselt zum angegebenen Slice. Gibt Zeitbereich (startSec, endSec) zurueck oder null. */
    fun selectSlice(sliceIndex: Int): Pair<Float, Float>? {
        val activeId = _state.value.activeFileId ?: return null
        val fileState = _state.value.loadedFiles[activeId] ?: return null
        val sm = fileState.sliceManager ?: return null
        val slice = sm.slices.getOrNull(sliceIndex) ?: return null
        _state.update { s ->
            val updatedFile = fileState.copy(activeSliceIndex = sliceIndex)
            s.copy(loadedFiles = s.loadedFiles + (activeId to updatedFile))
        }
        onStateChanged()
        return Pair(slice.startSec, slice.endSec)
    }

    /** Naechster Slice. Gibt Zeitbereich zurueck oder null wenn am Ende. */
    fun nextSlice(): Pair<Float, Float>? {
        val activeId = _state.value.activeFileId ?: return null
        val fileState = _state.value.loadedFiles[activeId] ?: return null
        if (fileState.activeSliceIndex < (fileState.sliceManager?.sliceCount ?: 0) - 1) {
            return selectSlice(fileState.activeSliceIndex + 1)
        }
        return null
    }

    /** Vorheriger Slice. Gibt Zeitbereich zurueck oder null wenn am Anfang. */
    fun previousSlice(): Pair<Float, Float>? {
        val activeId = _state.value.activeFileId ?: return null
        val fileState = _state.value.loadedFiles[activeId] ?: return null
        if (fileState.activeSliceIndex > 0) {
            return selectSlice(fileState.activeSliceIndex - 1)
        }
        return null
    }

    // ====================================================================
    // Vergleichsdatei
    // ====================================================================

    /** Setzt die Vergleichsdatei-Referenz. */
    fun setCompareFile(file: File) {
        _state.update { it.copy(compareFile = file) }
        onStateChanged()
    }

    /** Entfernt die Vergleichsdatei-Referenz. */
    fun clearCompareFile() {
        _state.update { it.copy(compareFile = null) }
        onStateChanged()
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** Raeumt PCM-Caches aller geladenen Dateien auf und setzt Audio-State zurueck. */
    fun closeAudio() {
        _state.value.loadedFiles.values.forEach { it.pcmCache?.delete() }
        _state.value = State()
        onStateChanged()
    }

    /** State komplett zuruecksetzen (inkl. PCM-Caches aufraumen). */
    fun reset() {
        _state.value.loadedFiles.values.forEach { it.pcmCache?.delete() }
        _state.value = State()
    }

    /** Setzt audioSegment nach Normalisierung (SpectrogramManager liefert neues Segment). */
    fun setNormalizedSegment(segment: AudioSegment) {
        val activeId = _state.value.activeFileId ?: return
        val fileState = _state.value.loadedFiles[activeId] ?: return
        _state.update { s ->
            val updatedFile = fileState.copy(audioSegment = segment)
            s.copy(loadedFiles = s.loadedFiles + (activeId to updatedFile))
        }
    }

    /** Setzt audioFile fuer Sonogramm-Bild-Import (ohne Audio-Dekodierung). */
    fun setImageFile(file: File) {
        val fileId = java.util.UUID.randomUUID().toString()
        val imageFileState = AudioFileState(fileId = fileId, audioFile = file)
        _state.update { State(loadedFiles = mapOf(fileId to imageFileState), activeFileId = fileId) }
    }
}
