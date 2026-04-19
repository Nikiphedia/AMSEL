package ch.etasystems.amsel.core.annotation

import kotlinx.serialization.Serializable

/**
 * Akustische Messwerte einer Annotation.
 * Alle Felder 0 = nicht berechnet (Backward-Compat fuer alte Projekte).
 */
@Serializable
data class AnnotationMetrics(
    /** Frequenz mit hoechster Energie im Annotation-Bereich (Hz) */
    val peakFreqHz: Float = 0f,
    /** Spektraler Schwerpunkt, energie-gewichtet (Hz) */
    val centerFreqHz: Float = 0f,
    /** Untere Grenzfrequenz bei -3 dB relativ zum Peak (Hz) */
    val lowFreq3dbHz: Float = 0f,
    /** Obere Grenzfrequenz bei -3 dB relativ zum Peak (Hz) */
    val highFreq3dbHz: Float = 0f,
    /** Bandbreite bei -3 dB (Hz) = highFreq3dbHz - lowFreq3dbHz */
    val bandwidth3dbHz: Float = 0f,
    /** Signal-Rausch-Verhaeltnis in dB */
    val snrDb: Float = 0f
) {
    /** True wenn Messwerte gesetzt sind (mindestens Peak vorhanden) */
    val isComputed: Boolean get() = peakFreqHz > 0f
}
