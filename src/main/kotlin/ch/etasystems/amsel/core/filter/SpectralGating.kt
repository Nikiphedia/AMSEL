package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlin.math.sqrt

/**
 * Spectral Gating: Frequenzband-individuelle Rauschunterdrückung.
 *
 * Workflow:
 * 1. Rauschprofil aus einer stillen Passage berechnen (Mittelwert + Stddev pro Mel-Band)
 * 2. Pro Band: Schwelle = Mittel + sensitivity * Stddev
 * 3. Werte unter Schwelle werden gedämpft (weicher Sigmoid-Übergang)
 */
object SpectralGating {

    // Sigmoid Lookup-Table: 1024 Eintraege fuer Bereich [-8..8]
    private const val LUT_SIZE = 1024
    private const val LUT_RANGE = 8f
    private val sigmoidLut = FloatArray(LUT_SIZE) { i ->
        val x = -LUT_RANGE + 2f * LUT_RANGE * i / (LUT_SIZE - 1)
        1f / (1f + kotlin.math.exp(-x.toDouble()).toFloat())
    }

    private fun sigmoidFast(x: Float): Float {
        if (x <= -LUT_RANGE) return 0f
        if (x >= LUT_RANGE) return 1f
        val idx = ((x + LUT_RANGE) / (2f * LUT_RANGE) * (LUT_SIZE - 1)).toInt()
        return sigmoidLut[idx]
    }

    /**
     * Berechnet Rauschprofil aus einer bestimmten Zeitregion des Spektrogramms.
     */
    fun computeNoiseProfile(
        data: SpectrogramData,
        startFrame: Int = 0,
        endFrame: Int = minOf(data.nFrames, 50)
    ): NoiseProfile {
        val nMels = data.nMels
        val nFrames = (endFrame - startFrame).coerceAtLeast(1)

        val means = FloatArray(nMels)
        val stddevs = FloatArray(nMels)

        for (mel in 0 until nMels) {
            var sum = 0.0
            for (frame in startFrame until endFrame) {
                sum += data.valueAt(mel, frame)
            }
            val mean = sum / nFrames
            means[mel] = mean.toFloat()

            var varianceSum = 0.0
            for (frame in startFrame until endFrame) {
                val diff = data.valueAt(mel, frame) - mean
                varianceSum += diff * diff
            }
            stddevs[mel] = sqrt(varianceSum / nFrames).toFloat()
        }

        return NoiseProfile(means, stddevs)
    }

    /**
     * Spectral Gating mit manuellem Threshold.
     * @param thresholdDb Schwelle in dB unter dem Peak (-60..-5). Alles unterhalb wird gedaempft.
     * @param softness Uebergangsbreite in dB (0 = hart, 5 = sehr weich)
     */
    fun applyManualThreshold(
        data: SpectrogramData,
        thresholdDb: Float = -30f,
        softness: Float = 2f
    ): SpectrogramData {
        val nMels = data.nMels
        val nFrames = data.nFrames
        if (nFrames == 0) return data

        val result = FloatArray(nMels * nFrames)
        val minVal = data.minValue
        // Threshold in log10: Peak + thresholdDb/10 (weil thresholdDb negativ)
        val thresholdLog10 = data.maxValue + thresholdDb / 10f
        // Uebergangsbreite in log10-Einheiten (softness in dB / 10)
        val transitionWidth = (softness.coerceAtLeast(0.1f))  // direkt in dB als Breite
        val invScale = 10f / transitionWidth  // 10/dB → log10-Einheiten

        for (mel in 0 until nMels) {
            val baseIdx = mel * nFrames
            if (softness <= 0f) {
                // Harter Gate
                for (frame in 0 until nFrames) {
                    val value = data.valueAt(mel, frame)
                    result[baseIdx + frame] = if (value >= thresholdLog10) value else minVal
                }
            } else {
                for (frame in 0 until nFrames) {
                    val value = data.valueAt(mel, frame)
                    val gain = sigmoidFast((value - thresholdLog10) * invScale)
                    result[baseIdx + frame] = minVal + (value - minVal) * gain
                }
            }
        }

        return data.copy(matrix = result)
    }

    /**
     * Wendet Spectral Gating mit Auto-Profil an (Legacy).
     */
    fun apply(
        data: SpectrogramData,
        profile: NoiseProfile,
        sensitivity: Float = 1.5f,
        softness: Float = 2f
    ): SpectrogramData {
        val nMels = data.nMels
        val nFrames = data.nFrames
        val result = FloatArray(nMels * nFrames)
        val minVal = data.minValue

        for (mel in 0 until nMels) {
            // Mindest-Stddev von 0.3 log10 (3 dB) verhindert, dass der Threshold
            // bei konsistentem Rauschen direkt am Rauschpegel klebt
            val effectiveStddev = profile.stddevs[mel].coerceAtLeast(0.3f)
            val threshold = profile.means[mel] + sensitivity * effectiveStddev
            val baseIdx = mel * nFrames

            if (softness <= 0f) {
                for (frame in 0 until nFrames) {
                    val value = data.valueAt(mel, frame)
                    result[baseIdx + frame] = if (value >= threshold) value else minVal
                }
            } else {
                // Uebergangsbreite = stddev * softness, aber mindestens 0.5 log10 (5 dB)
                // Damit ist der Uebergang auch bei sehr konsistentem Rauschen sanft
                val transitionWidth = (profile.stddevs[mel] * softness).coerceAtLeast(0.5f)
                val invScale = 1f / transitionWidth
                for (frame in 0 until nFrames) {
                    val value = data.valueAt(mel, frame)
                    val gain = sigmoidFast((value - threshold) * invScale)
                    result[baseIdx + frame] = minVal + (value - minVal) * gain
                }
            }
        }

        return data.copy(matrix = result)
    }

    /**
     * Auto-Profil: Nimmt die leisesten 10% aller Frames als Rauschreferenz.
     * Erkennt Padding (Stille oder Rauschen) und schliesst diese Frames aus.
     *
     * Padding-Erkennung via Gap-Detection: Sortiere Frames nach mittlerer Energie,
     * suche den groessten Sprung (> 2 log10 = 20 dB). Alles unterhalb des Sprungs
     * ist Padding. Funktioniert fuer Zero-Padding UND Rausch-Padding beliebiger Pegel.
     */
    fun autoProfile(data: SpectrogramData): NoiseProfile {
        // Mittlere Energie pro Frame
        val frameMeans = FloatArray(data.nFrames)
        for (frame in 0 until data.nFrames) {
            var sum = 0f
            for (mel in 0 until data.nMels) {
                sum += data.valueAt(mel, frame)
            }
            frameMeans[frame] = sum / data.nMels
        }

        // Sortierte Indizes nach mittlerer Energie
        val sortedIndices = frameMeans.indices.sortedBy { frameMeans[it] }

        // Gap-Detection: groessten Sprung (> 2 log10 = 20 dB) suchen
        // Alles unterhalb des Sprungs gilt als Padding
        var gapIdx = 0  // Index in sortedIndices AB dem echtes Audio beginnt
        val MIN_GAP = 2f  // 2 log10-Einheiten = 20 dB
        var maxGap = 0f
        for (i in 0 until sortedIndices.size - 1) {
            val gap = frameMeans[sortedIndices[i + 1]] - frameMeans[sortedIndices[i]]
            if (gap > maxGap && gap > MIN_GAP) {
                maxGap = gap
                gapIdx = i + 1
            }
        }

        // Nur Frames oberhalb des Gaps verwenden (echtes Audio)
        val realIndices = if (gapIdx > 0 && gapIdx < sortedIndices.size - 5) {
            sortedIndices.subList(gapIdx, sortedIndices.size)
        } else {
            sortedIndices  // kein Gap gefunden → kein Padding → alle verwenden
        }

        // Leiseste 10% der echten Audio-Frames als Rauschreferenz
        val sortedReal = realIndices.sortedBy { frameMeans[it] }
        val noiseFrameCount = (sortedReal.size * 0.1f).toInt().coerceIn(5, 100)

        val nMels = data.nMels
        val means = FloatArray(nMels)
        val stddevs = FloatArray(nMels)

        for (mel in 0 until nMels) {
            var sum = 0.0
            for (i in 0 until noiseFrameCount) {
                sum += data.valueAt(mel, sortedReal[i])
            }
            val mean = sum / noiseFrameCount
            means[mel] = mean.toFloat()

            var varianceSum = 0.0
            for (i in 0 until noiseFrameCount) {
                val diff = data.valueAt(mel, sortedReal[i]) - mean
                varianceSum += diff * diff
            }
            stddevs[mel] = sqrt(varianceSum / noiseFrameCount).toFloat()
        }

        return NoiseProfile(means, stddevs)
    }

    data class NoiseProfile(
        val means: FloatArray,
        val stddevs: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NoiseProfile) return false
            return means.contentEquals(other.means) && stddevs.contentEquals(other.stddevs)
        }
        override fun hashCode(): Int = means.contentHashCode() * 31 + stddevs.contentHashCode()
    }
}
