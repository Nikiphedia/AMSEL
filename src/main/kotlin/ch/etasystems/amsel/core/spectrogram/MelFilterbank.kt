package ch.etasystems.amsel.core.spectrogram

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Erzeugt eine triangulare Mel-Filterbank-Matrix.
 * Unterstützt beliebige Frequenzbereiche (Vögel: 125-7500 Hz, Fledermäuse: 15000-125000 Hz).
 */
object MelFilterbank {

    fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

    fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    /**
     * Erzeugt Filterbank-Matrix [nMels × (fftSize/2 + 1)].
     * Jede Zeile ist ein triangularer Filter auf der Mel-Skala.
     */
    fun build(
        nMels: Int,
        fftSize: Int,
        sampleRate: Int,
        fMin: Float,
        fMax: Float
    ): Array<FloatArray> {
        val numBins = fftSize / 2 + 1
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // Gleichmäßig verteilte Mel-Punkte (nMels + 2 für Start/End)
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + i * (melMax - melMin) / (nMels + 1)
        }

        // Mel-Punkte → FFT-Bin-Indizes
        val binIndices = IntArray(nMels + 2) { i ->
            val hz = melToHz(melPoints[i])
            floor(((fftSize + 1) * hz) / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        // Triangulare Filter erstellen
        val filterbank = Array(nMels) { FloatArray(numBins) }
        for (m in 0 until nMels) {
            val start = binIndices[m]
            val center = binIndices[m + 1]
            val end = binIndices[m + 2]

            // Ansteigende Flanke
            for (k in start until center) {
                if (center > start) {
                    filterbank[m][k] = (k - start).toFloat() / (center - start)
                }
            }
            // Abfallende Flanke
            for (k in center until end) {
                if (end > center) {
                    filterbank[m][k] = (end - k).toFloat() / (end - center)
                }
            }
        }

        return filterbank
    }
}
