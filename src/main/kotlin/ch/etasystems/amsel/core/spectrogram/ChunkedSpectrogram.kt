package ch.etasystems.amsel.core.spectrogram

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import java.io.File

/**
 * Berechnet Spektrogramme für lange Audiodateien (bis 2h+).
 * - computeOverview: Niedrig aufgelöste Übersicht (max. targetFrames Frames)
 * - computeRegion: Volle Auflösung für einen Zeitbereich
 */
object ChunkedSpectrogram {

    /**
     * Berechnet ein Übersichts-Spektrogramm mit reduzierter Zeitauflösung.
     * Strategie: Gesamte Datei streaming dekodieren, Spektrogramm in Chunks berechnen,
     * dann auf targetFrames heruntersamplen.
     *
     * @param file Audio-Datei
     * @param targetFrames Maximale Anzahl Frames im Ergebnis (default 4000)
     * @param onProgress Fortschritts-Callback (0.0 .. 1.0)
     * @return Paar aus AudioSegment-Metadaten und heruntergesampletem SpectrogramData
     */
    suspend fun computeOverview(
        file: File,
        targetFrames: Int = 4000,
        maxFreqHz: Float = 16000f,
        onProgress: ((Float) -> Unit)? = null
    ): OverviewResult {
        // Erst: Datei komplett dekodieren (streaming um OOM-Risk zu senken)
        // Für Dateien bis 2h bei 48kHz: ~345M Samples × 4 Bytes = 1.3 GB
        // Wir nutzen einen adaptiven hopSize um direkt weniger Frames zu erzeugen.
        val segment = AudioDecoder.decode(file)
        val sampleRate = segment.sampleRate
        val mode = if (sampleRate > 96000) "bat" else "bird"

        // Berechne adaptiven hopSize so dass ~targetFrames rauskommen
        val totalSamples = segment.samples.size
        val fftSize = if (mode == "bat") 1024 else 2048
        val nMels = if (mode == "bat") 80 else 120
        val fMin = if (mode == "bat") 15000f else 125f
        val fMax = if (mode == "bat") 125000f else maxFreqHz.coerceAtMost(sampleRate / 2f)

        // hop = totalSamples / targetFrames, aber mindestens fftSize/4
        val adaptiveHop = maxOf(totalSamples / targetFrames, fftSize / 4)

        val spectrogram = MelSpectrogram(
            fftSize = fftSize,
            hopSize = adaptiveHop,
            nMels = nMels,
            fMin = fMin,
            fMax = fMax,
            sampleRate = sampleRate
        )

        onProgress?.invoke(0.3f)
        val spectrogramData = spectrogram.compute(segment.samples)
        onProgress?.invoke(0.9f)

        // Falls immer noch zu viele Frames: zeitlich downsamlen
        val result = if (spectrogramData.nFrames > targetFrames) {
            downsampleTime(spectrogramData, targetFrames)
        } else {
            spectrogramData
        }

        onProgress?.invoke(1.0f)

        return OverviewResult(
            spectrogramData = result,
            sampleRate = sampleRate,
            totalSamples = totalSamples.toLong(),
            durationSec = segment.durationSec,
            mode = mode,
            audioSegment = segment
        )
    }

    /**
     * Berechnet ein Uebersichts-Spektrogramm aus einem bereits dekodierten AudioSegment.
     * Fuer Normalisierung/Bearbeitung wo das Segment schon im Speicher liegt.
     */
    suspend fun computeOverview(
        segment: AudioSegment,
        targetFrames: Int = 4000,
        maxFreqHz: Float = 16000f
    ): OverviewResult {
        val sampleRate = segment.sampleRate
        val mode = if (sampleRate > 96000) "bat" else "bird"
        val totalSamples = segment.samples.size
        val fftSize = if (mode == "bat") 1024 else 2048
        val nMels = if (mode == "bat") 80 else 120
        val fMin = if (mode == "bat") 15000f else 125f
        val fMax = if (mode == "bat") 125000f else maxFreqHz.coerceAtMost(sampleRate / 2f)
        val adaptiveHop = maxOf(totalSamples / targetFrames, fftSize / 4)

        val spectrogram = MelSpectrogram(
            fftSize = fftSize, hopSize = adaptiveHop, nMels = nMels,
            fMin = fMin, fMax = fMax, sampleRate = sampleRate
        )
        val spectrogramData = spectrogram.compute(segment.samples)
        val result = if (spectrogramData.nFrames > targetFrames) {
            downsampleTime(spectrogramData, targetFrames)
        } else spectrogramData

        return OverviewResult(
            spectrogramData = result, sampleRate = sampleRate,
            totalSamples = totalSamples.toLong(), durationSec = segment.durationSec,
            mode = mode, audioSegment = segment
        )
    }

    /**
     * Berechnet ein Spektrogramm in voller Auflösung für einen Zeitbereich.
     * @param segment AudioSegment (bereits dekodiert, wird aus OverviewResult wiederverwendet)
     * @param startSec Startzeit in Sekunden
     * @param endSec Endzeit in Sekunden
     */
    suspend fun computeRegion(
        segment: AudioSegment,
        startSec: Float,
        endSec: Float,
        maxFreqHz: Float = 16000f
    ): SpectrogramData {
        val sub = segment.subRangeSec(startSec, endSec)
        val spectrogram = MelSpectrogram.auto(segment.sampleRate, maxFreqHz)
        return spectrogram.compute(sub.samples)
    }

    /**
     * Berechnet ein Spektrogramm für einen Zeitbereich aus einer Datei (ohne vorheriges Dekodieren).
     * Langsamer als die Variante mit AudioSegment, spart aber RAM.
     */
    suspend fun computeRegionFromFile(
        file: File,
        startSec: Float,
        endSec: Float,
        maxFreqHz: Float = 16000f
    ): SpectrogramData {
        val segment = AudioDecoder.decodeRange(file, startSec, endSec)
        val spectrogram = MelSpectrogram.auto(segment.sampleRate, maxFreqHz)
        return spectrogram.compute(segment.samples)
    }

    /**
     * Zeitliches Downsampling: Fasst benachbarte Frames zusammen (Maximum-Pooling).
     * Behält visuelle Signale besser als Averaging.
     */
    private fun downsampleTime(data: SpectrogramData, targetFrames: Int): SpectrogramData {
        if (data.nFrames <= targetFrames) return data

        val ratio = data.nFrames.toFloat() / targetFrames
        val matrix = FloatArray(data.nMels * targetFrames)

        for (mel in 0 until data.nMels) {
            for (outFrame in 0 until targetFrames) {
                val srcStart = (outFrame * ratio).toInt()
                val srcEnd = minOf(((outFrame + 1) * ratio).toInt(), data.nFrames)

                // Maximum-Pooling: behält Peaks (Vogelrufe) besser als Mean
                var maxVal = Float.NEGATIVE_INFINITY
                for (srcFrame in srcStart until srcEnd) {
                    val v = data.valueAt(mel, srcFrame)
                    if (v > maxVal) maxVal = v
                }
                matrix[mel * targetFrames + outFrame] = maxVal
            }
        }

        // Adaptiver hopSize für korrekte Zeitberechnung
        val effectiveHop = (data.hopSize * ratio).toInt()

        return SpectrogramData(
            matrix = matrix,
            nMels = data.nMels,
            nFrames = targetFrames,
            sampleRate = data.sampleRate,
            hopSize = effectiveHop,
            fMin = data.fMin,
            fMax = data.fMax,
            ref0dBFS = data.ref0dBFS
        )
    }

    data class OverviewResult(
        val spectrogramData: SpectrogramData,
        val sampleRate: Int,
        val totalSamples: Long,
        val durationSec: Float,
        val mode: String,
        val audioSegment: AudioSegment? = null  // null bei grossen Dateien (Streaming-Modus)
    )
}
