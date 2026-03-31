package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData

/**
 * Hard-Clip Limiter fuer Sonogramme.
 *
 * Kappt Werte UEBER dem Ceiling auf den Ceiling-Wert.
 * Die Dynamik unterhalb des Ceilings bleibt vollstaendig erhalten.
 *
 * Die Matrix enthaelt log10-Werte. Das Ceiling wird relativ zum
 * Maximum (maxVal) berechnet: ceiling = maxVal + thresholdDb / 10.
 * (Faktor 10 weil log10(power), nicht 20 wie bei Amplitude.)
 *
 * Beispiel (log10-Werte, maxVal = 0.0):
 *   Input:  -6.0, -4.0, -2.0, -1.0, -0.5, -0.2, 0.0
 *   Ceiling -6 dB (= -0.6 log10):
 *   Output: -6.0, -4.0, -2.0, -1.0, -0.6, -0.6, -0.6
 *   → Dynamik unter -0.6 bleibt vollstaendig erhalten
 *
 * Threshold-Werte:
 * - 0 dB = kein Effekt
 * - -6 dB = moderate Spitzen-Begrenzung
 * - -20 dB = starke Begrenzung, aber Struktur bleibt sichtbar
 * - -40 dB = extreme Begrenzung
 */
object Limiter {

    /**
     * @param data Eingabe-Spektrogramm (log10-Werte)
     * @param thresholdDb Ceiling in dB relativ zum Maximum (0 = kein Effekt, negativ = Begrenzung)
     */
    fun apply(
        data: SpectrogramData,
        thresholdDb: Float = -6f
    ): SpectrogramData {
        if (data.nFrames == 0 || thresholdDb >= 0f) return data

        val maxVal = data.maxValue

        // dB → log10-Offset: -6 dB = -0.6 log10, -20 dB = -2.0 log10
        val ceiling = maxVal + thresholdDb / 10f

        val result = FloatArray(data.matrix.size)

        // Hard-Clip: Werte ueber Ceiling werden auf Ceiling gesetzt
        for (i in data.matrix.indices) {
            result[i] = if (data.matrix[i] > ceiling) ceiling else data.matrix[i]
        }

        return data.copy(matrix = result)
    }
}
