package ch.etasystems.amsel.core.audio

import ch.etasystems.amsel.core.filter.FilterConfig
import ch.etasystems.amsel.core.filter.FilterPipeline
import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import org.jtransforms.fft.DoubleFFT_1D

/**
 * Wendet Spektrogramm-Filter auf Audio-Samples an.
 *
 * Methode: STFT → Gain-Maske aus gefiltertem vs. originalem Mel-Spektrogramm → iSTFT.
 * Die Maske bestimmt pro Zeit-Frequenz-Kachel wie viel Signal durchkommt.
 */
object FilteredAudio {

    /**
     * Erzeugt gefilterte Audio-Samples.
     * @param segment Original-Audio
     * @param config Aktive Filter-Konfiguration
     * @param maxFreqHz Maximale Frequenz fuer Mel-Berechnung
     * @param startSec Startzeit (für Teilbereich)
     * @param endSec Endzeit (für Teilbereich)
     * @return Neue Samples mit angewendeten Filtern
     */
    fun apply(
        segment: AudioSegment,
        config: FilterConfig,
        maxFreqHz: Float = 16000f,
        startSec: Float = 0f,
        endSec: Float? = null
    ): FloatArray {
        if (!config.isActive) return segment.samples

        val sampleRate = segment.sampleRate
        val startSample = (startSec * sampleRate).toInt().coerceIn(0, segment.samples.size)
        val endSample = if (endSec != null) {
            (endSec * sampleRate).toInt().coerceIn(startSample, segment.samples.size)
        } else {
            segment.samples.size
        }

        val samples = segment.samples.copyOfRange(startSample, endSample)
        if (samples.size < 1024) return samples

        // STFT-Parameter (identisch zum Anzeige-Spektrogramm)
        val fftSize = 4096
        val hopSize = 256
        val nMels = 160
        val fMax = maxFreqHz.coerceAtMost(sampleRate / 2f)

        // 1. Mel-Spektrogramm berechnen
        val melSpec = MelSpectrogram(
            fftSize = fftSize,
            hopSize = hopSize,
            nMels = nMels,
            sampleRate = sampleRate,
            fMin = 0f,
            fMax = fMax
        )
        val originalData = melSpec.compute(samples)
        if (originalData.nFrames == 0) return samples

        // 2. Filter anwenden
        val filteredData = FilterPipeline.apply(originalData, config)

        // 3. Gain-Maske berechnen (gefiltert / original)
        val gainMask = computeGainMask(originalData, filteredData)

        // 4. STFT → Maske anwenden → iSTFT
        return applyStftMask(samples, sampleRate, fftSize, hopSize, gainMask, nMels, fMax)
    }

    /**
     * Berechnet Gain-Maske aus dem Unterschied zwischen Original- und gefiltertem Spektrogramm.
     *
     * Die Matrix speichert log10(energy). Wenn ein Filter die Energie von E_orig auf E_filt
     * reduziert, ist der Amplituden-Gain:
     *   gain = sqrt(E_filt / E_orig) = 10^((filtVal - origVal) / 2)
     *
     * Werte 0..1, wobei 0 = komplett entfernt, 1 = unveraendert.
     */
    private fun computeGainMask(
        original: SpectrogramData,
        filtered: SpectrogramData
    ): FloatArray {
        val nMels = original.nMels
        val nFrames = original.nFrames
        val mask = FloatArray(nMels * nFrames)

        for (mel in 0 until nMels) {
            for (frame in 0 until nFrames) {
                val origVal = original.valueAt(mel, frame)
                val filtVal = filtered.valueAt(mel, frame)

                // Kein Unterschied oder Verstaerkung: Gain = 1
                if (filtVal >= origVal - 0.001f) {
                    mask[mel * nFrames + frame] = 1f
                } else {
                    // Korrekter Amplituden-Gain aus log10-Differenz:
                    // log10(E_filt) - log10(E_orig) = log10(E_filt/E_orig)
                    // Amplituden-Gain = sqrt(power ratio) = 10^(diff/2)
                    val diff = filtVal - origVal  // negativ (Reduktion)
                    val gain = Math.pow(10.0, diff.toDouble() / 2.0).toFloat()
                    mask[mel * nFrames + frame] = gain.coerceIn(0f, 1f)
                }
            }
        }

        return mask
    }

    /**
     * Wendet die Mel-Gain-Maske im STFT-Bereich an.
     * Für jeden STFT-Frame: FFT → Mel-Gain pro Bin interpolieren → multiplizieren → iFFT.
     */
    private fun applyStftMask(
        samples: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        hopSize: Int,
        gainMask: FloatArray,
        nMels: Int,
        fMax: Float
    ): FloatArray {
        val nFrames = (samples.size - fftSize) / hopSize + 1
        if (nFrames <= 0) return samples

        val output = FloatArray(samples.size)
        val windowSum = FloatArray(samples.size)
        val fft = DoubleFFT_1D(fftSize.toLong())
        val window = hannWindow(fftSize)
        val numBins = fftSize / 2 + 1

        val hzPerBin = sampleRate.toFloat() / fftSize
        val maskNFrames = gainMask.size / nMels

        // Vorberechnung: FFT-Bin → Mel-Band Zuordnung (Mel-Skala, nicht linear!)
        val melMin = MelFilterbank.hzToMel(0f)
        val melMax = MelFilterbank.hzToMel(fMax)
        val melRange = melMax - melMin
        val binToMel = IntArray(numBins) { bin ->
            val freqHz = bin * hzPerBin
            val mel = MelFilterbank.hzToMel(freqHz.coerceAtMost(fMax))
            val melFrac = if (melRange > 0f) (mel - melMin) / melRange else 0f
            (melFrac * (nMels - 1)).toInt().coerceIn(0, nMels - 1)
        }

        for (frame in 0 until nFrames) {
            val offset = frame * hopSize
            val fftBuffer = DoubleArray(fftSize)

            // Fenster anwenden
            for (i in 0 until fftSize) {
                fftBuffer[i] = (samples[offset + i] * window[i]).toDouble()
            }

            // FFT
            fft.realForward(fftBuffer)

            // Gain pro Bin anwenden (korrekte Mel-Skala Zuordnung)
            val maskFrame = frame.coerceIn(0, maskNFrames - 1)

            for (bin in 0 until numBins) {
                val melBand = binToMel[bin]
                val gain = gainMask[melBand * maskNFrames + maskFrame]

                // Real/Imag-Teile skalieren
                if (bin == 0) {
                    fftBuffer[0] *= gain
                } else if (bin == numBins - 1 && fftSize % 2 == 0) {
                    fftBuffer[1] *= gain
                } else {
                    val realIdx = 2 * bin
                    val imagIdx = 2 * bin + 1
                    fftBuffer[realIdx] *= gain
                    fftBuffer[imagIdx] *= gain
                }
            }

            // iFFT
            fft.realInverse(fftBuffer, true)

            // Overlap-Add mit Hann-Fenster
            for (i in 0 until fftSize) {
                if (offset + i < output.size) {
                    output[offset + i] += (fftBuffer[i] * window[i]).toFloat()
                    windowSum[offset + i] += window[i] * window[i]
                }
            }
        }

        // Normalisierung (Overlap-Add-Kompensation)
        for (i in output.indices) {
            if (windowSum[i] > 1e-6f) {
                output[i] /= windowSum[i]
            }
        }

        return output
    }

    private fun hannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
        }
    }
}
