package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioPlayer
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.FilteredAudio
import ch.etasystems.amsel.core.audio.PcmCacheFile
import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.model.VolumePoint
import ch.etasystems.amsel.core.model.gainAtTime
import ch.etasystems.amsel.core.model.gainDbToLinear
import ch.etasystems.amsel.data.reference.ReferenceDownloader
import ch.etasystems.amsel.data.reference.ReferenceLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Verwaltet Audio-Wiedergabe: Play/Pause/Stop, Positions-Tracking, Referenz-Audio.
 * Besitzt die AudioPlayer-Instanz und deren Lifecycle.
 */
class PlaybackManager(
    private val onStateChanged: () -> Unit = {},
    private val onStatusUpdate: (String) -> Unit = {},
    private val onViewportFollow: (newStart: Float, newEnd: Float) -> Unit = { _, _ -> },
    private val onPlaybackStopped: (viewportChanged: Boolean) -> Unit = {}
) {

    data class State(
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val playbackPositionSec: Float = 0f,
        val playingReferenceId: String = "",
        val downloadingReferenceId: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val audioPlayer = AudioPlayer()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var positionCollectorJob: Job? = null

    /** Flag: Viewport wurde waehrend Playback verschoben (Follow-Modus) */
    private var viewportChangedDuringPlayback = false
    /** Merkt sich den letzten isPlaying-Zustand fuer Transition-Detection */
    private var wasPlaying = false

    init {
        // AudioPlayer-State → PlaybackManager-State synchronisieren
        scope.launch {
            audioPlayer.state.collect { playbackState ->
                val isNowPlaying = playbackState == AudioPlayer.PlaybackState.PLAYING
                val isNowPaused = playbackState == AudioPlayer.PlaybackState.PAUSED

                _state.update {
                    it.copy(
                        isPlaying = isNowPlaying,
                        isPaused = isNowPaused,
                        // Referenz-ID zuruecksetzen wenn Playback endet
                        playingReferenceId = if (!isNowPlaying && !isNowPaused) "" else it.playingReferenceId
                    )
                }
                onStateChanged()

                // Transition: war am Spielen → jetzt gestoppt
                // Wenn Viewport sich waehrend Playback verschoben hat → Spektrogramm nachberechnen
                if (wasPlaying && !isNowPlaying && viewportChangedDuringPlayback) {
                    viewportChangedDuringPlayback = false
                    onPlaybackStopped(true)
                }
                wasPlaying = isNowPlaying
            }
        }

        // Playback-Position mit Viewport-Follow
        positionCollectorJob = scope.launch {
            audioPlayer.positionSec.collect { pos ->
                _state.update { it.copy(playbackPositionSec = pos) }
                onStateChanged()

                // Viewport-Follow: nur wenn gerade abgespielt wird
                val s = _state.value
                if (s.isPlaying && pos > 0f) {
                    // Viewport-Daten werden als Parameter uebergeben bei togglePlayPause
                    // Follow-Logik wird vom ViewModel gesteuert via onViewportFollow
                }
            }
        }
    }

    // ====================================================================
    // Playback-Steuerung
    // ====================================================================

    /**
     * Play/Pause umschalten.
     * Audio-Daten und Viewport werden als Parameter uebergeben (keine direkte Manager-Kopplung).
     */
    fun togglePlayPause(
        audioSegment: AudioSegment?,
        pcmCache: PcmCacheFile?,
        audioFile: File?,
        filterConfig: FilterConfig,
        maxFreqHz: Float,
        viewStartSec: Float,
        viewEndSec: Float,
        volumeEnvelope: List<VolumePoint>,
        volumeEnvelopeActive: Boolean,
        totalDurationSec: Float
    ) {
        // Pause/Resume: egal ob gross oder klein
        if (audioPlayer.state.value == AudioPlayer.PlaybackState.PAUSED) {
            audioPlayer.resume()
            return
        }
        if (audioPlayer.state.value == AudioPlayer.PlaybackState.PLAYING) {
            audioPlayer.pause()
            return
        }

        // Viewport-Follow-Tracking starten
        viewportChangedDuringPlayback = false

        scope.launch {
            try {
                // Audio-Segment fuer den Viewport-Bereich holen
                val playSegment = when {
                    // Kleine Datei mit Segment im RAM
                    audioSegment != null -> {
                        if (filterConfig.isActive) {
                            onStatusUpdate("Bereite Wiedergabe vor...")
                            val filtered = FilteredAudio.apply(audioSegment, filterConfig, maxFreqHz, viewStartSec, viewEndSec)
                            AudioSegment(filtered, audioSegment.sampleRate)
                        } else {
                            audioSegment.subRangeSec(viewStartSec, viewEndSec)
                        }
                    }
                    // Grosse Datei mit PCM-Cache
                    pcmCache != null -> {
                        onStatusUpdate("Lade Abschnitt...")
                        val raw = pcmCache.readRange(viewStartSec, viewEndSec)
                        if (filterConfig.isActive) {
                            onStatusUpdate("Bereite gefilterte Wiedergabe vor...")
                            val filtered = FilteredAudio.apply(raw, filterConfig, maxFreqHz, 0f, raw.durationSec)
                            AudioSegment(filtered, raw.sampleRate)
                        } else raw
                    }
                    // Fallback: On-demand dekodieren
                    audioFile != null -> {
                        onStatusUpdate("Dekodiere Abschnitt...")
                        val raw = AudioDecoder.decodeRange(audioFile, viewStartSec, viewEndSec)
                        if (filterConfig.isActive) {
                            val filtered = FilteredAudio.apply(raw, filterConfig, maxFreqHz, 0f, raw.durationSec)
                            AudioSegment(filtered, raw.sampleRate)
                        } else raw
                    }
                    else -> return@launch
                }

                // Volume Envelope auf Playback-Samples anwenden
                val finalSegment = if (volumeEnvelopeActive && volumeEnvelope.isNotEmpty()) {
                    val samples = playSegment.samples.copyOf()
                    val sr = playSegment.sampleRate
                    for (i in samples.indices) {
                        val timeSec = viewStartSec + i.toFloat() / sr
                        val gainDb = volumeEnvelope.gainAtTime(timeSec)
                        samples[i] *= gainDbToLinear(gainDb)
                    }
                    AudioSegment(samples, sr)
                } else playSegment

                onStatusUpdate("")
                audioPlayer.playWithOffset(finalSegment, viewStartSec)

            } catch (e: Exception) {
                onStatusUpdate("Wiedergabe-Fehler: ${e.message}")
            }
        }
    }

    /** Wiedergabe stoppen. */
    fun stopPlayback() {
        audioPlayer.stop()
    }

    /**
     * Spielt Audio einer Referenz-Aufnahme ab.
     * Laedt das Audio on-demand herunter falls noch nicht gecacht.
     */
    fun playReferenceAudio(
        result: ch.etasystems.amsel.core.annotation.MatchResult,
        referenceLibrary: ReferenceLibrary,
        referenceDownloader: ReferenceDownloader
    ) {
        // Wenn gleiche Referenz schon spielt → stoppen
        if (_state.value.playingReferenceId == result.recordingId && _state.value.isPlaying) {
            stopPlayback()
            return
        }

        scope.launch {
            onStatusUpdate("Lade Audio fuer ${result.scientificName}...")

            // Referenz-Aufnahme aus Library suchen
            val recording = referenceLibrary.getRecordingsForSpecies(result.scientificName)
                .find { it.id == result.recordingId }

            // Audio direkt verfuegbar oder on-demand herunterladen
            val audioFile = if (recording?.wavFile?.exists() == true) {
                recording.wavFile
            } else if (recording != null) {
                _state.update { it.copy(downloadingReferenceId = result.recordingId) }
                val downloaded = referenceDownloader.downloadAudioOnDemand(recording)
                _state.update { it.copy(downloadingReferenceId = "") }
                downloaded
            } else {
                null
            }
            if (audioFile == null) {
                onStatusUpdate("Audio nicht verfuegbar")
                _state.update { it.copy(downloadingReferenceId = "", playingReferenceId = "") }
                return@launch
            }

            try {
                val decoded = AudioDecoder.decode(audioFile)
                val segment = AudioSegment(decoded.samples, decoded.sampleRate)
                _state.update { it.copy(playingReferenceId = result.recordingId) }
                onStatusUpdate("Spiele: ${result.species} (${result.type})")
                audioPlayer.play(segment, 0f, null)
            } catch (e: Exception) {
                _state.update { it.copy(playingReferenceId = "") }
                onStatusUpdate("Fehler: ${e.message}")
            }
        }
    }

    /**
     * Viewport-Follow: wird vom ViewModel aufgerufen wenn Position-Updates eintreffen.
     * Prueft ob die aktuelle Position ausserhalb des sichtbaren Bereichs liegt.
     */
    fun checkViewportFollow(viewStartSec: Float, viewEndSec: Float, totalDurationSec: Float) {
        val pos = _state.value.playbackPositionSec
        if (!_state.value.isPlaying || pos <= 0f) return

        val viewDuration = viewEndSec - viewStartSec

        // Position ist rechts aus dem Viewport gelaufen
        if (pos > viewEndSec) {
            val newStart = pos
            val newEnd = (pos + viewDuration).coerceAtMost(totalDurationSec)
            viewportChangedDuringPlayback = true
            onViewportFollow(newStart, newEnd)
        }
        // Position ist links aus dem Viewport gelaufen
        else if (pos < viewStartSec) {
            val newStart = (pos - viewDuration * 0.1f).coerceAtLeast(0f)
            val newEnd = (newStart + viewDuration).coerceAtMost(totalDurationSec)
            viewportChangedDuringPlayback = true
            onViewportFollow(newStart, newEnd)
        }
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** State zuruecksetzen (bei Datei-Schliessung). */
    fun reset() {
        audioPlayer.stop()
        viewportChangedDuringPlayback = false
        wasPlaying = false
        _state.value = State()
    }

    /** Ressourcen freigeben. */
    fun dispose() {
        audioPlayer.dispose()
        scope.cancel()
    }
}
