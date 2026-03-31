package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.ChunkManager
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
 * Verwaltet Audio-Import, Decoding, Chunking und Datei-Management.
 * Grundbaustein fuer PlaybackManager, SpectrogramManager und ClassificationManager.
 */
class AudioManager(
    private val onStateChanged: () -> Unit = {},
    private val onProgressUpdate: (String) -> Unit = {}
) {

    data class State(
        val audioFile: File? = null,
        val audioSegment: AudioSegment? = null,
        val pcmCache: PcmCacheFile? = null,
        val compareFile: File? = null,
        val isLargeFile: Boolean = false,
        val audioDurationSec: Float = 0f,
        val audioSampleRate: Int = 0,
        val audioOffsetSec: Float = 0f,
        val chunkManager: ChunkManager? = null,
        val activeChunkIndex: Int = 0
    )

    /** Ergebnis des Audio-Imports — enthaelt Spektrogramm-Daten fuer ViewModel-Orchestrierung. */
    data class ImportResult(
        val overviewSpectrogramData: SpectrogramData,
        val mode: String,
        val initialViewEnd: Float,
        val zoomedSpectrogramData: SpectrogramData,
        val duration: Float,
        val sampleRate: Int,
        val hasChunks: Boolean
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Importiert eine Audio-Datei: Dekodierung, Padding kurzer Aufnahmen,
     * Overview-Spektrogramm, PCM-Cache fuer grosse Dateien, Chunk-Manager.
     *
     * @return ImportResult mit Spektrogramm-Daten fuer ViewModel-Orchestrierung
     */
    suspend fun importAudio(
        file: File,
        maxFreqHz: Float,
        minDisplayDurationSec: Float,
        shortFileStartPct: Float,
        chunkLengthMin: Float,
        chunkOverlapSec: Float,
        initialZoomDurationSec: Float
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

        // 5. Chunk-Manager erstellen wenn Datei laenger als eingestellte Chunk-Laenge
        val chunkLengthSec = chunkLengthMin * 60f
        val chunkManager = if (duration > chunkLengthSec) {
            ChunkManager(duration, chunkLengthSec, chunkOverlapSec)
        } else null

        // 6. Initialen Zoom-Bereich berechnen
        val initialEnd = if (duration <= initialZoomDurationSec * 1.5f) {
            duration
        } else if (chunkManager != null) {
            chunkManager.chunks.first().endSec.coerceAtMost(initialZoomDurationSec)
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

        // 8. Internen State setzen
        _state.value = State(
            audioFile = file,
            audioSegment = if (isLargeFile) null else actualOverview.audioSegment,
            pcmCache = pcmCache,
            isLargeFile = isLargeFile,
            audioDurationSec = duration,
            audioSampleRate = actualOverview.sampleRate,
            audioOffsetSec = audioOffsetSec,
            chunkManager = chunkManager,
            activeChunkIndex = 0
        )
        onStateChanged()

        return ImportResult(
            overviewSpectrogramData = actualOverview.spectrogramData,
            mode = mode,
            initialViewEnd = initialEnd,
            zoomedSpectrogramData = zoomedData,
            duration = duration,
            sampleRate = actualOverview.sampleRate,
            hasChunks = chunkManager != null
        )
    }

    // ====================================================================
    // Chunk-Navigation
    // ====================================================================

    /** Wechselt zum angegebenen Chunk. Gibt Zeitbereich (startSec, endSec) zurueck oder null. */
    fun selectChunk(chunkIndex: Int): Pair<Float, Float>? {
        val cm = _state.value.chunkManager ?: return null
        val chunk = cm.chunks.getOrNull(chunkIndex) ?: return null
        _state.update { it.copy(activeChunkIndex = chunkIndex) }
        onStateChanged()
        return Pair(chunk.startSec, chunk.endSec)
    }

    /** Naechster Chunk. Gibt Zeitbereich zurueck oder null wenn am Ende. */
    fun nextChunk(): Pair<Float, Float>? {
        val s = _state.value
        val cm = s.chunkManager ?: return null
        if (s.activeChunkIndex < cm.chunkCount - 1) {
            return selectChunk(s.activeChunkIndex + 1)
        }
        return null
    }

    /** Vorheriger Chunk. Gibt Zeitbereich zurueck oder null wenn am Anfang. */
    fun previousChunk(): Pair<Float, Float>? {
        val s = _state.value
        if (s.activeChunkIndex > 0) {
            return selectChunk(s.activeChunkIndex - 1)
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

    /** Raeumt PCM-Cache auf und setzt Audio-State zurueck. */
    fun closeAudio() {
        _state.value.pcmCache?.delete()
        _state.value = State()
        onStateChanged()
    }

    /** State komplett zuruecksetzen (inkl. PCM-Cache aufraumen). */
    fun reset() {
        _state.value.pcmCache?.delete()
        _state.value = State()
    }

    /** Setzt audioSegment nach Normalisierung (SpectrogramManager liefert neues Segment). */
    fun setNormalizedSegment(segment: AudioSegment) {
        _state.update { it.copy(audioSegment = segment) }
    }

    /** Setzt audioFile fuer Sonogramm-Bild-Import (ohne Audio-Dekodierung). */
    fun setImageFile(file: File) {
        _state.update { State(audioFile = file) }
    }
}
