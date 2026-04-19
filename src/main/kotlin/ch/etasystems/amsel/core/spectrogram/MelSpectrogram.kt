package ch.etasystems.amsel.core.spectrogram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.log10

// ====================================================================
// Spektrogramm-Konfigurationstypen
// ====================================================================

/** Fenster-Funktion fuer FFT (beeinflusst Nebenkeulen-Unterdrueckung) */
@Serializable
enum class WindowFunction {
    HANN, HAMMING, BLACKMAN, GAUSS
}

/** FFT-Groesse als Zweierpotenzen-Enum */
@Serializable
enum class FftSize(val samples: Int) {
    FFT_1024(1024), FFT_2048(2048), FFT_4096(4096), FFT_8192(8192)
}

/** Hop-Groesse als Bruch der FFT-Groesse */
@Serializable
enum class HopFraction(val divisor: Int) {
    HOP_1_32(32), HOP_1_16(16), HOP_1_8(8), HOP_1_4(4), HOP_1_2(2)
}

/**
 * Berechnet ein Mel-Spektrogramm aus rohem PCM-Audio.
 * Konfigurierbar fuer Voegel (125-7500 Hz) und Fledermaeuse (15000-125000 Hz).
 */
class MelSpectrogram(
    val fftSize: Int = 1024,
    val hopSize: Int = 512,
    val nMels: Int = 64,
    val fMin: Float = 125f,
    val fMax: Float = 7500f,
    val sampleRate: Int = 16000,
    val windowType: WindowFunction = WindowFunction.HANN
) {
    private val filterbank = MelFilterbank.build(nMels, fftSize, sampleRate, fMin, fMax)
    private val window = buildWindow(fftSize, windowType)

    companion object {
        /** Cache fuer MelSpectrogram-Instanzen: key = Preset + Parameter */
        private val instanceCache = mutableMapOf<String, MelSpectrogram>()

        /**
         * Voegel-Preset: Maximale Aufloesung (Ornitho-Qualitaet).
         * FFT 4096 → 4x so feine Frequenzaufloesung wie vorher
         * 160 Mel-Bins → doppelte Frequenz-Detailtiefe
         * Hop als Bruch von FFT-Size → konfigurierbare Zeitaufloesung
         */
        fun bird(
            sampleRate: Int = 48000,
            maxFreqHz: Float = 16000f,
            windowType: WindowFunction = WindowFunction.HANN,
            fftSize: FftSize = FftSize.FFT_4096,
            hopFraction: HopFraction = HopFraction.HOP_1_32
        ): MelSpectrogram {
            val fMax = maxFreqHz.coerceAtMost(sampleRate / 2f)
            val hopSize = fftSize.samples / hopFraction.divisor
            val key = "bird_${sampleRate}_${fMax.toInt()}_${windowType}_${fftSize.samples}_${hopFraction.divisor}"
            return instanceCache.getOrPut(key) {
                MelSpectrogram(
                    fftSize = fftSize.samples,
                    hopSize = hopSize,
                    nMels = 256,
                    fMin = 100f,
                    fMax = fMax,
                    sampleRate = sampleRate,
                    windowType = windowType
                )
            }
        }

        /** Fledermaus-Preset (Ultraschall) */
        fun bat(sampleRate: Int = 256000): MelSpectrogram {
            val key = "bat_$sampleRate"
            return instanceCache.getOrPut(key) {
                MelSpectrogram(
                    fftSize = 1024,
                    hopSize = 256,
                    nMels = 80,
                    fMin = 15000f,
                    fMax = 125000f,
                    sampleRate = sampleRate
                )
            }
        }

        /** Automatisch: Vogel oder Fledermaus basierend auf Sample-Rate */
        fun auto(
            sampleRate: Int,
            maxFreqHz: Float = 16000f,
            windowType: WindowFunction = WindowFunction.HANN,
            fftSize: FftSize = FftSize.FFT_4096,
            hopFraction: HopFraction = HopFraction.HOP_1_32
        ): MelSpectrogram {
            return if (sampleRate > 96000) bat(sampleRate)
            else bird(sampleRate, maxFreqHz, windowType, fftSize, hopFraction)
        }

        /** Erzeugt Fenster-Array der angegebenen Groesse und Funktion */
        private fun buildWindow(size: Int, type: WindowFunction): FloatArray {
            val n = size - 1
            return FloatArray(size) { i ->
                when (type) {
                    WindowFunction.HANN ->
                        (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / n))).toFloat()
                    WindowFunction.HAMMING ->
                        (0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * i / n)).toFloat()
                    WindowFunction.BLACKMAN ->
                        (0.42 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)
                            + 0.08 * kotlin.math.cos(4.0 * Math.PI * i / n)).toFloat()
                    WindowFunction.GAUSS -> {
                        val sigma = 0.4
                        val center = n / 2.0
                        val x = (i - center) / (sigma * center)
                        kotlin.math.exp(-0.5 * x * x).toFloat()
                    }
                }
            }
        }
    }

    /**
     * Berechnet das Mel-Spektrogramm.
     * Nutzt Coroutines fuer parallele FFT-Berechnung auf mehreren Cores.
     *
     * @param samples PCM-Audiodaten (Mono, Float [-1..1])
     * @return SpectrogramData mit log-skalierten Mel-Energien
     */
    suspend fun compute(samples: FloatArray): SpectrogramData {
        val nFrames = (samples.size - fftSize) / hopSize + 1
        if (nFrames <= 0) {
            return SpectrogramData(
                matrix = FloatArray(0),
                nMels = nMels,
                nFrames = 0,
                sampleRate = sampleRate,
                hopSize = hopSize,
                fMin = fMin,
                fMax = fMax,
                ref0dBFS = (2.0 * log10(fftSize.toDouble() / 2.0)).toFloat()
            )
        }

        val numBins = fftSize / 2 + 1
        val matrix = FloatArray(nMels * nFrames)

        // Parallele Verarbeitung: Frames in Batches aufteilen
        val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val batchSize = (nFrames + numCores - 1) / numCores  // Ceiling-Division

        coroutineScope {
            (0 until nFrames step batchSize).map { batchStart ->
                async(Dispatchers.Default) {
                    val batchEnd = minOf(batchStart + batchSize, nFrames)
                    // Jeder Thread bekommt eigene Arbeitsbuffer (nicht geteilt!)
                    val localFft = DoubleFFT_1D(fftSize.toLong())
                    val localFftBuffer = DoubleArray(fftSize * 2)
                    val localPowerSpectrum = FloatArray(numBins)

                    for (frame in batchStart until batchEnd) {
                        val offset = frame * hopSize

                        // Fenster anwenden + in FFT-Buffer kopieren
                        for (i in 0 until fftSize) {
                            val sampleIdx = offset + i
                            localFftBuffer[i] = if (sampleIdx < samples.size) {
                                (samples[sampleIdx] * window[i]).toDouble()
                            } else {
                                0.0
                            }
                        }
                        // Imaginaerteil auf 0
                        for (i in fftSize until fftSize * 2) {
                            localFftBuffer[i] = 0.0
                        }

                        // FFT berechnen
                        localFft.realForwardFull(localFftBuffer)

                        // Power-Spektrum
                        for (k in 0 until numBins) {
                            val re = localFftBuffer[2 * k].toFloat()
                            val im = localFftBuffer[2 * k + 1].toFloat()
                            localPowerSpectrum[k] = re * re + im * im
                        }

                        // Mel-Filterbank anwenden
                        for (m in 0 until nMels) {
                            var energy = 0f
                            for (k in 0 until numBins) {
                                energy += filterbank[m][k] * localPowerSpectrum[k]
                            }
                            // Matrix-Zugriff ist thread-safe: jeder Frame schreibt nur seine eigene Spalte
                            matrix[m * nFrames + frame] = log10(energy + 1e-10f)
                        }
                    }
                }
            }.awaitAll()
        }

        val ref0dBFS = (2.0 * log10(fftSize.toDouble() / 2.0)).toFloat()

        return SpectrogramData(
            matrix = matrix,
            nMels = nMels,
            nFrames = nFrames,
            sampleRate = sampleRate,
            hopSize = hopSize,
            fMin = fMin,
            fMax = fMax,
            ref0dBFS = ref0dBFS
        )
    }
}
