package ch.etasystems.amsel.core.spectrogram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.log10

/**
 * Berechnet ein Mel-Spektrogramm aus rohem PCM-Audio.
 * Konfigurierbar für Vögel (125-7500 Hz) und Fledermäuse (15000-125000 Hz).
 */
class MelSpectrogram(
    val fftSize: Int = 1024,
    val hopSize: Int = 512,
    val nMels: Int = 64,
    val fMin: Float = 125f,
    val fMax: Float = 7500f,
    val sampleRate: Int = 16000
) {
    private val filterbank = MelFilterbank.build(nMels, fftSize, sampleRate, fMin, fMax)
    private val window = hannWindow(fftSize)

    companion object {
        /** Cache fuer MelSpectrogram-Instanzen: key = Preset + Parameter */
        private val instanceCache = mutableMapOf<String, MelSpectrogram>()

        /**
         * Voegel-Preset: Maximale Aufloesung (Ornitho-Qualitaet).
         * FFT 4096 → 4x so feine Frequenzaufloesung wie vorher
         * 160 Mel-Bins → doppelte Frequenz-Detailtiefe
         * Hop 256 → 4x hoehere Zeitaufloesung
         */
        fun bird(sampleRate: Int = 48000, maxFreqHz: Float = 16000f): MelSpectrogram {
            val fMax = maxFreqHz.coerceAtMost(sampleRate / 2f)
            val key = "bird_${sampleRate}_${fMax.toInt()}"
            return instanceCache.getOrPut(key) {
                MelSpectrogram(
                    fftSize = 4096,
                    hopSize = 128,
                    nMels = 256,
                    fMin = 100f,
                    fMax = fMax,
                    sampleRate = sampleRate
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
        fun auto(sampleRate: Int, maxFreqHz: Float = 16000f): MelSpectrogram {
            return if (sampleRate > 96000) bat(sampleRate) else bird(sampleRate, maxFreqHz)
        }

        private fun hannWindow(size: Int): FloatArray {
            return FloatArray(size) { i ->
                (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
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

                        // Hann-Fenster anwenden + in FFT-Buffer kopieren
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
