package ch.etasystems.amsel.core.spectrogram

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.PcmCacheFile
import java.io.File

/**
 * Berechnet Spektrogramme für lange Audiodateien (bis 2h+).
 * - computeOverview: Niedrig aufgelöste Übersicht (max. targetFrames Frames)
 * - computeRegion: Volle Auflösung für einen Zeitbereich
 */
object ChunkedSpectrogram {

    /** Frames je Leseblock der lueckenlosen Uebersicht (bei FFT 2048: ca. 4,2 Mio. Samples = 17 MB). */
    private const val BLOCK_FRAMES = 4096

    /**
     * Berechnet ein Übersichts-Spektrogramm mit reduzierter Zeitauflösung.
     * Dekodiert die ganze Datei in den Speicher. Fuer lange Dateien stattdessen
     * die Variante mit [PcmCacheFile] verwenden.
     *
     * @param file Audio-Datei
     * @param targetFrames Maximale Anzahl Frames im Ergebnis (default 4000)
     * @param onProgress Fortschritts-Callback (0.0 .. 1.0)
     * @return OverviewResult mit dekodiertem Segment und heruntergesampletem SpectrogramData
     */
    suspend fun computeOverview(
        file: File,
        targetFrames: Int = 4000,
        maxFreqHz: Float = 16000f,
        onProgress: ((Float) -> Unit)? = null
    ): OverviewResult {
        val segment = AudioDecoder.decode(file)
        onProgress?.invoke(0.3f)
        val result = computeOverview(segment, targetFrames, maxFreqHz)
        onProgress?.invoke(1.0f)
        return result
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
        val samples = segment.samples
        val data = overviewBerechnen(samples.size.toLong(), segment.sampleRate, targetFrames, maxFreqHz) { start, count ->
            // Ganzer Bereich: das Array selbst (keine Kopie), sonst ein Ausschnitt
            if (start == 0L && count == samples.size) samples
            else samples.copyOfRange(start.toInt(), start.toInt() + count)
        }
        return OverviewResult(
            spectrogramData = data, sampleRate = segment.sampleRate,
            totalSamples = samples.size.toLong(), durationSec = segment.durationSec,
            mode = modus(segment.sampleRate), audioSegment = segment
        )
    }

    /**
     * Berechnet ein Uebersichts-Spektrogramm aus einem PCM-Cache, blockweise.
     * Das Audio liegt dabei nie als Ganzes im Speicher. `audioSegment` im Ergebnis ist null.
     */
    suspend fun computeOverview(
        cache: PcmCacheFile,
        targetFrames: Int = 4000,
        maxFreqHz: Float = 16000f
    ): OverviewResult {
        val data = overviewBerechnen(cache.totalSamples, cache.sampleRate, targetFrames, maxFreqHz) { start, count ->
            cache.readSamples(start, count)
        }
        return OverviewResult(
            spectrogramData = data, sampleRate = cache.sampleRate,
            totalSamples = cache.totalSamples, durationSec = cache.durationSec,
            mode = modus(cache.sampleRate), audioSegment = null
        )
    }

    private fun modus(sampleRate: Int): String = if (sampleRate > 96000) "bat" else "bird"

    /**
     * Gemeinsamer Kern der Uebersicht.
     *
     * Kurze Aufnahmen: ein Durchgang mit adaptivem Hop (wie bisher).
     * Lange Aufnahmen: der adaptive Hop waere groesser als das halbe FFT-Fenster, zwischen den
     * Frames bliebe Audio ungesehen. Dann wird lueckenlos mit halbem Fenster als Hop gerechnet
     * und per Maximum auf die Ziel-Frames verdichtet — ein kurzer Ruf bleibt sichtbar, egal wo er liegt.
     *
     * @param lies liefert [count] Samples ab [start]; wird nie ueber totalSamples hinaus aufgerufen
     */
    private suspend fun overviewBerechnen(
        totalSamples: Long,
        sampleRate: Int,
        targetFrames: Int,
        maxFreqHz: Float,
        lies: (start: Long, count: Int) -> FloatArray
    ): SpectrogramData {
        val bat = sampleRate > 96000
        val fftSize = if (bat) 1024 else 2048
        val nMels = if (bat) 80 else 120
        val fMin = if (bat) 15000f else 125f
        val fMax = if (bat) 125000f else maxFreqHz.coerceAtMost(sampleRate / 2f)

        // hop = totalSamples / targetFrames, aber mindestens fftSize/4
        val adaptiveHop = maxOf(totalSamples / targetFrames, fftSize / 4L)
        val halbesFenster = fftSize / 2

        if (adaptiveHop <= halbesFenster) {
            // Kurze Aufnahme: bisheriger Weg, die Fenster ueberlappen ohnehin
            val spectrogram = MelSpectrogram(
                fftSize = fftSize, hopSize = adaptiveHop.toInt(), nMels = nMels,
                fMin = fMin, fMax = fMax, sampleRate = sampleRate
            )
            val data = spectrogram.compute(lies(0L, totalSamples.toInt()))
            return if (data.nFrames > targetFrames) downsampleTime(data, targetFrames) else data
        }

        // Lange Aufnahme: lueckenlos, blockweise, Maximum je Ziel-Frame
        val hop = halbesFenster
        val spectrogram = MelSpectrogram(
            fftSize = fftSize, hopSize = hop, nMels = nMels,
            fMin = fMin, fMax = fMax, sampleRate = sampleRate
        )
        val nFramesTotal = (totalSamples - fftSize) / hop + 1
        val outFrames = minOf(targetFrames.toLong(), nFramesTotal).toInt()
        val matrix = FloatArray(nMels * outFrames) { Float.NEGATIVE_INFINITY }
        var ref0dBFS = 0f

        var frame0 = 0L
        while (frame0 < nFramesTotal) {
            val frames = minOf(BLOCK_FRAMES.toLong(), nFramesTotal - frame0).toInt()
            val block = spectrogram.compute(lies(frame0 * hop, (frames - 1) * hop + fftSize))
            ref0dBFS = block.ref0dBFS
            for (f in 0 until block.nFrames) {
                val out = ((frame0 + f) * outFrames / nFramesTotal).toInt()
                for (mel in 0 until nMels) {
                    val v = block.valueAt(mel, f)
                    val idx = mel * outFrames + out
                    if (v > matrix[idx]) matrix[idx] = v
                }
            }
            frame0 += frames
        }

        return SpectrogramData(
            matrix = matrix,
            nMels = nMels,
            nFrames = outFrames,
            sampleRate = sampleRate,
            // Effektiver Hop fuer die Zeitachse der verdichteten Uebersicht
            hopSize = (hop.toLong() * nFramesTotal / outFrames).toInt(),
            fMin = fMin,
            fMax = fMax,
            ref0dBFS = ref0dBFS
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
        maxFreqHz: Float = 16000f,
        windowType: WindowFunction = WindowFunction.HANN,
        fftSize: FftSize = FftSize.FFT_4096,
        hopFraction: HopFraction = HopFraction.HOP_1_8
    ): SpectrogramData {
        val sub = segment.subRangeSec(startSec, endSec)
        val spectrogram = MelSpectrogram.auto(segment.sampleRate, maxFreqHz, windowType, fftSize, hopFraction)
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
        maxFreqHz: Float = 16000f,
        windowType: WindowFunction = WindowFunction.HANN,
        fftSize: FftSize = FftSize.FFT_4096,
        hopFraction: HopFraction = HopFraction.HOP_1_8
    ): SpectrogramData {
        val segment = AudioDecoder.decodeRange(file, startSec, endSec)
        val spectrogram = MelSpectrogram.auto(segment.sampleRate, maxFreqHz, windowType, fftSize, hopFraction)
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
