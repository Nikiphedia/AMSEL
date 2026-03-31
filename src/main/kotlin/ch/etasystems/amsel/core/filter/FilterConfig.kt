package ch.etasystems.amsel.core.filter

/**
 * Konfigurierbare Filter-Pipeline.
 * Alle Felder serialisierbar (primitiv) fuer Preset-Speicherung.
 */
data class FilterConfig(
    // Globaler Bypass: alle Filter deaktiviert, Einstellungen bleiben
    val bypass: Boolean = false,
    // Noise-Filter: entfernt untere X% des Dynamikbereichs
    val noiseFilter: Boolean = false,
    val noiseFilterPercent: Float = 30f,  // 0..95%, 0.5er Schritte
    // Legacy: Kontrast (Spektrale Subtraktion) — bleibt fuer alte Presets
    val spectralSubtraction: Boolean = false,
    val spectralSubtractionAlpha: Float = 1.5f,
    val noiseEstimationFrames: Int = 10,
    // Expander/Gate
    val expanderGate: Boolean = false,
    val expanderThreshold: Float = 0f,
    val expanderRatio: Float = 2f,
    val expanderMode: ExpanderGate.Mode = ExpanderGate.Mode.EXPANDER,
    val expanderRangeDb: Float = -80f,
    val expanderKneeDb: Float = 0f,
    val expanderHysteresisDb: Float = 0f,
    val expanderAttackFrames: Int = 0,
    val expanderReleaseFrames: Int = 0,
    val expanderHoldFrames: Int = 0,
    // Limiter
    val limiter: Boolean = false,
    val limiterThresholdDb: Float = 0f,
    // Bandpass
    val bandpass: Boolean = false,
    val bandpassLowHz: Float = 1000f,
    val bandpassHighHz: Float = 16000f,
    // Median
    val medianFilter: Boolean = false,
    val medianKernelSize: Int = 3,
    // Spectral Gating: frequenzbandindividuelle Rauschunterdrückung
    val spectralGating: Boolean = false,
    val spectralGatingSensitivity: Float = 1.5f,  // Legacy (wird ignoriert wenn ThresholdDb gesetzt)
    val spectralGatingThresholdDb: Float = -30f,  // Manueller Threshold in dB unter Peak (-80..-5)
    val spectralGatingSoftness: Float = 2f,        // Uebergangsbreite in dB (0 = hart, 5 = sehr weich)
    // Normalisierung: wird in der Pipeline NACH Bandpass, VOR Spectral Gate angewendet
    val normalize: Boolean = false,
    val normalizeGainLog10: Float = 0f             // Gain in log10-Einheiten (= gainDb / 10)
) {
    /** true wenn mindestens ein Filter aktiviert ist (und nicht auf Bypass) */
    val isActive: Boolean
        get() = !bypass && (noiseFilter || spectralSubtraction || expanderGate || limiter || bandpass || medianFilter || spectralGating || normalize)
}
