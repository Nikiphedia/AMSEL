package ch.etasystems.amsel.core.similarity

import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

/**
 * Extrahiert MFCC (Mel-Frequency Cepstral Coefficients) aus Audio-Samples.
 * MFCCs sind der Standard-Feature-Vektor für Audio-Similarity.
 *
 * Pipeline: Audio → Frames → Hann → FFT → Power → Mel-Filter → Log → DCT → MFCC
 */
class MfccExtractor(
    val sampleRate: Int = 48000,
    val fftSize: Int = 2048,
    val hopSize: Int = 1024,
    val nMels: Int = 40,
    val nMfcc: Int = 13,
    val fMin: Float = 125f,
    val fMax: Float = 16000f,
    val computeDeltas: Boolean = false,
    val applyCmvn: Boolean = false
) {
    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val filterbank = MelFilterbank.build(nMels, fftSize, sampleRate, fMin, fMax)
    private val window = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * Math.PI * i / (fftSize - 1)))).toFloat()
    }

    companion object {
        fun bird(sampleRate: Int = 48000) = MfccExtractor(
            sampleRate = sampleRate,
            fftSize = 2048,
            hopSize = 1024,
            nMels = 40,
            nMfcc = 13,
            fMin = 125f,
            fMax = 16000f
        )

        fun bat(sampleRate: Int = 256000) = MfccExtractor(
            sampleRate = sampleRate,
            fftSize = 1024,
            hopSize = 256,
            nMels = 40,
            nMfcc = 13,
            fMin = 15000f,
            fMax = 125000f
        )

        fun auto(sampleRate: Int) =
            if (sampleRate > 96000) bat(sampleRate) else bird(sampleRate)

        /** Enhanced-Konfiguration fuer Voegel: 64 Mel-Baender, Deltas, CMVN */
        fun birdEnhanced(sampleRate: Int = 48000) = MfccExtractor(
            sampleRate = sampleRate,
            fftSize = 2048,
            hopSize = 512,
            nMels = 64,
            nMfcc = 13,
            fMin = 125f,
            fMax = 16000f,
            computeDeltas = true,
            applyCmvn = true
        )

        /** Enhanced-Konfiguration fuer Fledermaeuse */
        fun batEnhanced(sampleRate: Int = 256000) = MfccExtractor(
            sampleRate = sampleRate,
            fftSize = 1024,
            hopSize = 256,
            nMels = 64,
            nMfcc = 13,
            fMin = 15000f,
            fMax = 125000f,
            computeDeltas = true,
            applyCmvn = true
        )

        /** Enhanced auto: waehlt anhand der Sample-Rate */
        fun autoEnhanced(sampleRate: Int) =
            if (sampleRate > 96000) batEnhanced(sampleRate) else birdEnhanced(sampleRate)
    }

    /**
     * Extrahiert MFCC-Feature-Matrix aus Audio-Samples.
     * @return Array[nFrames][nMfcc] — pro Frame ein MFCC-Vektor
     */
    fun extract(samples: FloatArray): Array<FloatArray> {
        val nFrames = (samples.size - fftSize) / hopSize + 1
        if (nFrames <= 0) return emptyArray()

        val numBins = fftSize / 2 + 1
        val fftBuffer = DoubleArray(fftSize * 2)
        val result = Array(nFrames) { FloatArray(nMfcc) }

        for (frame in 0 until nFrames) {
            val offset = frame * hopSize

            // Hann-Fenster
            for (i in 0 until fftSize) {
                val idx = offset + i
                fftBuffer[i] = if (idx < samples.size) {
                    (samples[idx] * window[i]).toDouble()
                } else 0.0
                if (i + fftSize < fftBuffer.size) fftBuffer[i + fftSize] = 0.0
            }

            // FFT
            fft.realForwardFull(fftBuffer)

            // Power-Spektrum
            val melEnergies = FloatArray(nMels)
            for (m in 0 until nMels) {
                var energy = 0.0
                for (k in 0 until numBins) {
                    val re = fftBuffer[2 * k]
                    val im = fftBuffer[2 * k + 1]
                    val power = re * re + im * im
                    energy += filterbank[m][k] * power
                }
                melEnergies[m] = ln(energy.coerceAtLeast(1e-10)).toFloat()
            }

            // DCT-II (Type-II Discrete Cosine Transform) → MFCC
            for (c in 0 until nMfcc) {
                var sum = 0.0
                for (m in 0 until nMels) {
                    sum += melEnergies[m] * cos(Math.PI * c * (m + 0.5) / nMels)
                }
                result[frame][c] = (sum * sqrt(2.0 / nMels)).toFloat()
            }
        }

        return result
    }

    /**
     * Berechnet einen zusammengefassten Feature-Vektor aus der MFCC-Matrix.
     * Mean + StdDev pro Koeffizient → 2*nMfcc Dimensionen.
     */
    fun summarize(mfccs: Array<FloatArray>): FloatArray {
        if (mfccs.isEmpty()) return FloatArray(nMfcc * 2)

        val nFrames = mfccs.size
        val summary = FloatArray(nMfcc * 2)

        for (c in 0 until nMfcc) {
            // Mittelwert
            var mean = 0.0
            for (frame in mfccs) mean += frame[c]
            mean /= nFrames
            summary[c] = mean.toFloat()

            // Standardabweichung
            var variance = 0.0
            for (frame in mfccs) {
                val diff = frame[c] - mean
                variance += diff * diff
            }
            summary[nMfcc + c] = sqrt(variance / nFrames).toFloat()
        }

        return summary
    }

    /**
     * Extrahiert und fasst in einem Schritt zusammen.
     */
    fun extractSummary(samples: FloatArray): FloatArray {
        return summarize(extract(samples))
    }

    // ================================================================
    // Enhanced MFCC: Deltas + CMVN fuer praezisere Vergleiche
    // ================================================================

    /**
     * Berechnet Delta-Koeffizienten (Regression ueber Nachbar-Frames).
     * @param mfccs Input-Matrix [nFrames][nFeatures]
     * @param width Regression-Breite (Standard 2)
     * @return Delta-Matrix [nFrames][nFeatures]
     */
    fun computeDeltas(mfccs: Array<FloatArray>, width: Int = 2): Array<FloatArray> {
        val nFrames = mfccs.size
        if (nFrames == 0) return emptyArray()
        val nFeatures = mfccs[0].size

        val deltas = Array(nFrames) { FloatArray(nFeatures) }
        val denominator = 2.0 * (1..width).sumOf { it * it }

        for (t in 0 until nFrames) {
            for (f in 0 until nFeatures) {
                var sum = 0.0
                for (n in 1..width) {
                    val tPlus = (t + n).coerceAtMost(nFrames - 1)
                    val tMinus = (t - n).coerceAtLeast(0)
                    sum += n * (mfccs[tPlus][f] - mfccs[tMinus][f])
                }
                deltas[t][f] = (sum / denominator).toFloat()
            }
        }
        return deltas
    }

    /**
     * Cepstral Mean and Variance Normalization (CMVN).
     * Zieht den Mittelwert ab und normalisiert die Varianz pro Koeffizient.
     */
    fun applyCmvn(mfccs: Array<FloatArray>): Array<FloatArray> {
        val nFrames = mfccs.size
        if (nFrames == 0) return emptyArray()
        val nFeatures = mfccs[0].size

        // Mittelwert berechnen
        val mean = FloatArray(nFeatures)
        for (frame in mfccs) {
            for (f in 0 until nFeatures) mean[f] += frame[f]
        }
        for (f in 0 until nFeatures) mean[f] /= nFrames

        // Varianz berechnen
        val variance = FloatArray(nFeatures)
        for (frame in mfccs) {
            for (f in 0 until nFeatures) {
                val diff = frame[f] - mean[f]
                variance[f] += diff * diff
            }
        }
        for (f in 0 until nFeatures) {
            variance[f] = sqrt(variance[f] / nFrames).coerceAtLeast(1e-10f)
        }

        // Normalisieren
        val result = Array(nFrames) { FloatArray(nFeatures) }
        for (t in 0 until nFrames) {
            for (f in 0 until nFeatures) {
                result[t][f] = (mfccs[t][f] - mean[f]) / variance[f]
            }
        }
        return result
    }

    /**
     * Erweiterte Feature-Extraktion: MFCC + Delta + Delta-Delta.
     * Ergibt [nFrames][nMfcc * 3] = z.B. [nFrames][39] bei 13 MFCCs.
     * Optional mit CMVN-Normalisierung.
     */
    fun extractEnhanced(samples: FloatArray): Array<FloatArray> {
        var mfccs = extract(samples)
        if (mfccs.isEmpty()) return emptyArray()

        // CMVN auf Basis-MFCCs anwenden (vor Delta-Berechnung)
        if (applyCmvn) {
            mfccs = applyCmvn(mfccs)
        }

        if (!computeDeltas) return mfccs

        val deltas = computeDeltas(mfccs)
        val deltaDeltas = computeDeltas(deltas)
        val nFrames = mfccs.size
        val nFeatures = nMfcc * 3

        return Array(nFrames) { t ->
            FloatArray(nFeatures).also { combined ->
                System.arraycopy(mfccs[t], 0, combined, 0, nMfcc)
                System.arraycopy(deltas[t], 0, combined, nMfcc, nMfcc)
                System.arraycopy(deltaDeltas[t], 0, combined, nMfcc * 2, nMfcc)
            }
        }
    }

    /**
     * Zusammenfassung der Enhanced-Features: Mean + StdDev pro Dimension.
     * Bei 13 MFCCs + Delta + Delta-Delta: 39 * 2 = 78 Floats.
     */
    fun summarizeEnhanced(enhanced: Array<FloatArray>): FloatArray {
        if (enhanced.isEmpty()) return FloatArray(nMfcc * 6)

        val nFrames = enhanced.size
        val nFeatures = enhanced[0].size
        val summary = FloatArray(nFeatures * 2)

        for (f in 0 until nFeatures) {
            var mean = 0.0
            for (frame in enhanced) mean += frame[f]
            mean /= nFrames
            summary[f] = mean.toFloat()

            var variance = 0.0
            for (frame in enhanced) {
                val diff = frame[f] - mean
                variance += diff * diff
            }
            summary[nFeatures + f] = sqrt(variance / nFrames).toFloat()
        }

        return summary
    }
}
