package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlin.math.pow

/**
 * Kontrast-Filter (ehemals Spektrale Subtraktion).
 * Wirkt wie ein Kontrastregler: leise Bereiche werden dunkler, laute heller.
 *
 * Algorithmus:
 * 1. Noise-Floor schätzen (Median über alle Bins)
 * 2. Werte unter Floor → exponentiell abschwächen
 * 3. Werte über Floor → Abstand vergrößern
 */
class SpectralSubtraction(
    private val noiseEstimationFrames: Int = 10,
    private val alpha: Float = 1.5f  // Kontrast-Stärke: 0.5 = mild, 3.0 = aggressiv
) {
    fun apply(data: SpectrogramData): SpectrogramData {
        if (data.nFrames == 0) return data

        // Noise-Floor per Mel-Bin schätzen (aus leisesten Frames)
        val frameEnergy = FloatArray(data.nFrames) { frame ->
            var sum = 0f
            for (mel in 0 until data.nMels) {
                val v = data.valueAt(mel, frame)
                sum += v * v
            }
            sum / data.nMels
        }

        val quietFrames = frameEnergy.indices
            .sortedBy { frameEnergy[it] }
            .take(noiseEstimationFrames.coerceAtMost(data.nFrames))

        val noiseFloor = FloatArray(data.nMels)
        for (mel in 0 until data.nMels) {
            var sum = 0f
            for (frameIdx in quietFrames) {
                sum += data.valueAt(mel, frameIdx)
            }
            noiseFloor[mel] = sum / quietFrames.size
        }

        // Kontrast anwenden
        val range = data.maxValue - data.minValue
        if (range == 0f) return data

        val contrastMatrix = FloatArray(data.nMels * data.nFrames)

        for (mel in 0 until data.nMels) {
            val floor = noiseFloor[mel]

            for (frame in 0 until data.nFrames) {
                val original = data.valueAt(mel, frame)
                val aboveFloor = original - floor

                val contrasted = if (aboveFloor > 0) {
                    // Über Noise-Floor: verstärken
                    val normalized = aboveFloor / range
                    floor + normalized.pow(1f / alpha) * range
                } else {
                    // Unter Noise-Floor: abschwächen (exponentiell Richtung Minimum drücken)
                    val depth = -aboveFloor / range
                    floor - depth.pow(alpha) * range
                }

                contrastMatrix[mel * data.nFrames + frame] =
                    contrasted.coerceIn(data.minValue, data.maxValue)
            }
        }

        return data.copy(matrix = contrastMatrix)
    }
}
