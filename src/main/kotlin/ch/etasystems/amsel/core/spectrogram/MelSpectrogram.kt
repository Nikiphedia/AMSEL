package ch.etasystems.amsel.core.spectrogram

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.log10
import kotlin.math.sqrt

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
    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val filterbank = MelFilterbank.build(nMels, fftSize, sampleRate, fMin, fMax)
    private val window = hannWindow(fftSize)

    companion object {
        /**
         * Voegel-Preset: Maximale Aufloesung (Ornitho-Qualitaet).
         * FFT 4096 → 4x so feine Frequenzaufloesung wie vorher
         * 160 Mel-Bins → doppelte Frequenz-Detailtiefe
         * Hop 256 → 4x hoehere Zeitaufloesung
         */
        fun bird(sampleRate: Int = 48000, maxFreqHz: Float = 16000f) = MelSpectrogram(
            fftSize = 4096,
            hopSize = 128,        // feinere Zeitaufloesung fuer Zoom-Ansicht
            nMels = 256,          // hoehere Frequenzaufloesung (vorher 160)
            fMin = 100f,          // etwas tiefer fuer tiefe Rufe (Uhu, Rohrdommel)
            fMax = maxFreqHz.coerceAtMost(sampleRate / 2f),  // nie ueber Nyquist
            sampleRate = sampleRate
        )

        /** Fledermaus-Preset (Ultraschall) */
        fun bat(sampleRate: Int = 256000) = MelSpectrogram(
            fftSize = 1024,
            hopSize = 256,
            nMels = 80,
            fMin = 15000f,
            fMax = 125000f,
            sampleRate = sampleRate
        )

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
     * @param samples PCM-Audiodaten (Mono, Float [-1..1])
     * @return SpectrogramData mit log-skalierten Mel-Energien
     */
    fun compute(samples: FloatArray): SpectrogramData {
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
        val fftBuffer = DoubleArray(fftSize * 2)  // JTransforms braucht 2*N für reale FFT
        val powerSpectrum = FloatArray(numBins)

        for (frame in 0 until nFrames) {
            val offset = frame * hopSize

            // Hann-Fenster anwenden + in FFT-Buffer kopieren
            for (i in 0 until fftSize) {
                val sampleIdx = offset + i
                fftBuffer[i] = if (sampleIdx < samples.size) {
                    (samples[sampleIdx] * window[i]).toDouble()
                } else {
                    0.0
                }
            }
            // Imaginärteil auf 0
            for (i in fftSize until fftSize * 2) {
                fftBuffer[i] = 0.0
            }

            // FFT berechnen
            fft.realForwardFull(fftBuffer)

            // Power-Spektrum: |X(k)|² = Re² + Im²
            for (k in 0 until numBins) {
                val re = fftBuffer[2 * k].toFloat()
                val im = fftBuffer[2 * k + 1].toFloat()
                powerSpectrum[k] = re * re + im * im
            }

            // Mel-Filterbank anwenden
            for (m in 0 until nMels) {
                var energy = 0f
                for (k in 0 until numBins) {
                    energy += filterbank[m][k] * powerSpectrum[k]
                }
                // Log-Skalierung: log10(energy + epsilon)
                matrix[m * nFrames + frame] = log10(energy + 1e-10f)
            }
        }

        // 0 dBFS Referenz: log10 der max. Mel-Energie bei Vollaussteuerung.
        // Fuer Sinus mit Amplitude 1.0: FFT-Bin-Magnitude ≈ N/2 (Hann-Fenster),
        // Power ≈ (N/2)², Mel-Filterbank-Gewicht ≈ 1 am Peak.
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
