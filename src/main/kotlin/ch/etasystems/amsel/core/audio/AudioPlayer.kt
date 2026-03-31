package ch.etasystems.amsel.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Audio-Player fuer PCM-Wiedergabe via javax.sound.sampled.
 * Laeuft in eigener Coroutine, liefert Position-Updates via StateFlow.
 */
class AudioPlayer {

    enum class PlaybackState { STOPPED, PLAYING, PAUSED }

    private val _state = MutableStateFlow(PlaybackState.STOPPED)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _positionSec = MutableStateFlow(0f)
    val positionSec: StateFlow<Float> = _positionSec.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackJob: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var currentLine: SourceDataLine? = null

    @Volatile
    private var positionOffsetSec: Float = 0f

    /**
     * Spielt ein Segment ab und addiert positionOffset auf die angezeigte Position.
     * Fuer grosse Dateien: Segment ist ein Ausschnitt, Offset = globale Startzeit.
     */
    fun playWithOffset(segment: AudioSegment, positionOffset: Float) {
        stop()
        positionOffsetSec = positionOffset
        playInternal(segment, 0f, null)
    }

    /**
     * Spielt ein AudioSegment ab einem bestimmten Zeitpunkt ab.
     * @param segment Audio-Daten (Mono Float PCM)
     * @param startSec Startposition in Sekunden
     * @param endSec Endposition in Sekunden (null = bis Ende)
     */
    fun play(segment: AudioSegment, startSec: Float = 0f, endSec: Float? = null) {
        positionOffsetSec = 0f
        stop()
        playInternal(segment, startSec, endSec)
    }

    /**
     * Interne Playback-Logik: oeffnet SourceDataLine und schreibt PCM-Bytes.
     * positionOffsetSec muss VOR dem Aufruf gesetzt werden.
     */
    private fun playInternal(segment: AudioSegment, startSec: Float, endSec: Float?) {
        val sampleRate = segment.sampleRate
        val startSample = (startSec * sampleRate).toInt().coerceIn(0, segment.samples.size)
        val endSample = if (endSec != null) {
            (endSec * sampleRate).toInt().coerceIn(startSample, segment.samples.size)
        } else {
            segment.samples.size
        }

        paused = false
        stopped = false
        _state.value = PlaybackState.PLAYING

        playbackJob = scope.launch {
            // STEREO-Format: 2 Kanäle, 4 bytes/frame (2 Bytes × 2 Kanäle)
            val format = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate.toFloat(),
                16,
                2,     // STEREO (war: 1 Mono)
                4,     // Frame-Size: 2 Bytes × 2 Kanäle (war: 2)
                sampleRate.toFloat(),
                false  // Little-Endian
            )

            var line: SourceDataLine? = null
            try {
                line = AudioSystem.getSourceDataLine(format)
                val lineBufferSize = 2048 * 4  // 2048 Frames × 4 Bytes/Frame (war: × 2)
                line.open(format, lineBufferSize)
                currentLine = line
                line.start()

                val chunkSamples = sampleRate / 30  // ~33ms Chunks
                val buffer = ByteArray(chunkSamples * 4)  // 4 Bytes pro Frame: L16 + R16 (war: × 2)
                var pos = startSample

                while (pos < endSample && !stopped) {
                    while (paused && !stopped) {
                        delay(50)
                    }
                    if (stopped) break

                    val remaining = endSample - pos
                    val count = minOf(chunkSamples, remaining)

                    // Float → 16-Bit Stereo PCM (Mono dupliziert auf beide Kanäle)
                    for (i in 0 until count) {
                        val sample = segment.samples[pos + i]
                        val clamped = sample.coerceIn(-1f, 1f)
                        val pcm = (clamped * 32767f).toInt().toShort()
                        val lo = (pcm.toInt() and 0xFF).toByte()
                        val hi = (pcm.toInt() shr 8).toByte()
                        // Left channel
                        buffer[i * 4] = lo
                        buffer[i * 4 + 1] = hi
                        // Right channel (identisch)
                        buffer[i * 4 + 2] = lo
                        buffer[i * 4 + 3] = hi
                    }

                    line.write(buffer, 0, count * 4)  // (war: count * 2)

                    // Position aus Hardware-Frameposition (praeziser als geschriebene Position)
                    val playedFrames = line.longFramePosition
                    _positionSec.value = positionOffsetSec + startSample.toFloat() / sampleRate + playedFrames.toFloat() / sampleRate

                    pos += count
                }

                // Warten bis Buffer leer
                if (!stopped) {
                    line.drain()
                }

            } catch (e: Exception) {
                System.err.println("AudioPlayer: Wiedergabe-Fehler: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                line?.stop()
                line?.close()
                currentLine = null
                if (!stopped) {
                    _state.value = PlaybackState.STOPPED
                    _positionSec.value = 0f
                }
            }
        }
    }

    fun pause() {
        if (_state.value == PlaybackState.PLAYING) {
            paused = true
            _state.value = PlaybackState.PAUSED
        }
    }

    fun resume() {
        if (_state.value == PlaybackState.PAUSED) {
            paused = false
            _state.value = PlaybackState.PLAYING
        }
    }

    fun togglePlayPause(segment: AudioSegment, startSec: Float = 0f, endSec: Float? = null) {
        when (_state.value) {
            PlaybackState.STOPPED -> play(segment, startSec, endSec)
            PlaybackState.PLAYING -> pause()
            PlaybackState.PAUSED -> resume()
        }
    }

    fun stop() {
        stopped = true
        paused = false
        playbackJob?.cancel()
        playbackJob = null
        currentLine?.stop()
        currentLine?.close()
        currentLine = null
        _state.value = PlaybackState.STOPPED
        _positionSec.value = 0f
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
