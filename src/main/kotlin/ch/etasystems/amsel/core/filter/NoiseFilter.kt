package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData

/**
 * Noise-Filter: Entfernt alles UNTER einem prozentualen Schwellenwert
 * des Dynamikbereichs.
 *
 * Threshold 0% = nichts entfernt
 * Threshold 30% = untere 30% des Dynamikbereichs → Hintergrund
 * Threshold 80% = nur die lautesten 20% bleiben (aggressiv)
 *
 * Der Slider geht von 0-95% in 0.5%-Schritten.
 */
object NoiseFilter {

    /**
     * @param data Eingabe-Spektrogramm (log-skalierte Mel-Energien)
     * @param thresholdPercent Prozent des Dynamikbereichs der entfernt wird (0..95)
     */
    fun apply(
        data: SpectrogramData,
        thresholdPercent: Float = 30f
    ): SpectrogramData {
        if (data.nFrames == 0 || thresholdPercent <= 0f) return data

        val minVal = data.minValue
        val maxVal = data.maxValue
        val range = maxVal - minVal
        if (range <= 0f) return data

        // Cutoff: minVal + (percent/100) * range
        val cutoff = minVal + (thresholdPercent / 100f) * range

        val result = FloatArray(data.matrix.size)

        for (i in data.matrix.indices) {
            result[i] = if (data.matrix[i] < cutoff) {
                minVal
            } else {
                data.matrix[i]
            }
        }

        return data.copy(matrix = result)
    }
}
