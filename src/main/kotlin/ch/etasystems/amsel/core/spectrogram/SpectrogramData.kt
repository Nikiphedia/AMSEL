package ch.etasystems.amsel.core.spectrogram

/**
 * 2D Spektrogramm-Matrix (Mel-Bins × Zeitframes).
 * Werte sind log-skalierte Mel-Energien.
 */
data class SpectrogramData(
    val matrix: FloatArray,     // row-major [nMels × nFrames]
    val nMels: Int,
    val nFrames: Int,
    val sampleRate: Int,
    val hopSize: Int,
    val fMin: Float,
    val fMax: Float,
    /** 0-dBFS-Referenzwert in log10-Einheiten (berechnet aus FFT-Groesse).
     *  Wird als festes Maximum fuer die Colormap verwendet, damit leise
     *  Aufnahmen optisch leiser erscheinen. 0 = nicht gesetzt (Legacy). */
    val ref0dBFS: Float = 0f
) {
    fun valueAt(mel: Int, frame: Int): Float = matrix[mel * nFrames + frame]

    /** Zeit in Sekunden für einen Frame-Index */
    fun frameToTime(frame: Int): Float = frame * hopSize.toFloat() / sampleRate

    /** Gesamtdauer in Sekunden */
    val durationSec: Float get() = frameToTime(nFrames)

    /** Min/Max Werte für Normalisierung */
    val minValue: Float by lazy { matrix.min() }
    val maxValue: Float by lazy { matrix.max() }

    /** Normalisierter Wert [0..1] */
    fun normalizedValueAt(mel: Int, frame: Int): Float {
        val range = maxValue - minValue
        if (range == 0f) return 0f
        return (valueAt(mel, frame) - minValue) / range
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrogramData) return false
        return nMels == other.nMels && nFrames == other.nFrames && matrix.contentEquals(other.matrix)
    }

    override fun hashCode(): Int = 31 * matrix.contentHashCode() + nMels * 17 + nFrames
}
