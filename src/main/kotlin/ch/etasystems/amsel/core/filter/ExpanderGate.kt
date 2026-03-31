package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData

/**
 * Erweiterter Expander/Gate Filter fuer Sonogramme.
 *
 * Gate-Modus: Bins unter Threshold auf Minimum setzen (hart)
 * Expander-Modus: Bins unter Threshold exponentiell abschwaechen (weich)
 *
 * WICHTIG: Die Spektrogramm-Matrix speichert log10(energy). Alle dB-Parameter
 * werden intern durch 10 geteilt (1 log10-Einheit = 10 dB fuer Power).
 *
 * Erweiterte Parameter:
 * - threshold: Schwelle relativ zum Median (dB)
 * - ratio: Expansions-Verhaeltnis (1:2 bis 1:inf)
 * - range/depth: Maximale Absenkung in dB (begrenzt wie weit das Gate schliesst)
 * - knee: Uebergangsbreite in dB (0 = hart, >0 = weicher Uebergang)
 * - hysteresis: Differenz zwischen Oeffnen und Schliessen (verhindert Flattern)
 * - attack: Wie schnell das Gate oeffnet (in Frames)
 * - release: Wie schnell das Gate schliesst (in Frames)
 * - holdFrames: Mindest-Haltezeit in offener Position (Frames)
 */
object ExpanderGate {

    enum class Mode { GATE, EXPANDER }

    /**
     * @param data Eingabe-Spektrogramm
     * @param threshold Schwelle in dB relativ zum Median (0 = Median, -10 = 10dB unter Median)
     * @param ratio Expander-Ratio (2.0 = 1:2, 4.0 = 1:4, Float.MAX = Gate)
     * @param mode GATE oder EXPANDER
     * @param rangeDb Maximale Absenkung in dB (z.B. -60 = Gate schliesst max 60dB)
     * @param kneeDb Uebergangsbreite in dB (0 = hart, 6 = weich)
     * @param hysteresisDb Differenz zwischen Open/Close Threshold
     * @param attackFrames Anstiegszeit in Frames (schnelles Oeffnen)
     * @param releaseFrames Abklingzeit in Frames (langsames Schliessen)
     * @param holdFrames Mindest-Haltezeit in offener Position
     */
    fun apply(
        data: SpectrogramData,
        threshold: Float = 0f,
        ratio: Float = 2f,
        mode: Mode = Mode.EXPANDER,
        rangeDb: Float = -80f,
        kneeDb: Float = 0f,
        hysteresisDb: Float = 0f,
        attackFrames: Int = 0,
        releaseFrames: Int = 0,
        holdFrames: Int = 0
    ): SpectrogramData {
        if (data.nFrames == 0) return data

        // Median via Sampling (O(n) statt O(n log n) fuer grosse Spektrogramme)
        val median = approximateMedian(data.matrix)

        // dB-Parameter in log10-Einheiten umrechnen (1 log10 = 10 dB Power)
        val threshLog10 = threshold / 10f
        val rangeLog10 = rangeDb / 10f         // z.B. -80 dB → -8 log10
        val kneeLog10 = kneeDb / 10f
        val hystLog10 = hysteresisDb / 10f

        val absThreshold = median + threshLog10
        val absThresholdClose = absThreshold - hystLog10
        val halfKnee = kneeLog10 / 2f
        val minValue = data.minValue
        val nFrames = data.nFrames
        val nMels = data.nMels
        val matrix = data.matrix

        val result = FloatArray(nMels * nFrames)

        for (mel in 0 until nMels) {
            var currentGain = 1f
            var holdCounter = 0
            val baseIdx = mel * nFrames

            for (frame in 0 until nFrames) {
                val value = matrix[baseIdx + frame]

                val targetGain = computeGain(
                    value = value,
                    threshold = absThreshold,
                    thresholdClose = absThresholdClose,
                    currentOpen = currentGain > 0.5f,
                    ratio = ratio,
                    mode = mode,
                    rangeLog10 = rangeLog10,
                    halfKnee = halfKnee,
                    minValue = minValue
                )

                // Attack/Release Smoothing
                if (attackFrames > 0 || releaseFrames > 0) {
                    if (targetGain > currentGain) {
                        holdCounter = holdFrames
                        if (attackFrames > 0) {
                            val step = 1f / attackFrames.coerceAtLeast(1)
                            currentGain = (currentGain + step).coerceAtMost(targetGain)
                        } else {
                            currentGain = targetGain
                        }
                    } else {
                        if (holdCounter > 0) {
                            holdCounter--
                        } else {
                            if (releaseFrames > 0) {
                                val step = 1f / releaseFrames.coerceAtLeast(1)
                                currentGain = (currentGain - step).coerceAtLeast(targetGain)
                            } else {
                                currentGain = targetGain
                            }
                        }
                    }
                } else {
                    currentGain = targetGain
                }

                // Gain anwenden (alles in log10-Einheiten)
                if (currentGain >= 0.99f) {
                    result[baseIdx + frame] = value
                } else {
                    val reduction = (1f - currentGain) * (-rangeLog10)  // in log10
                    result[baseIdx + frame] = (value - reduction).coerceAtLeast(minValue)
                }
            }
        }

        return data.copy(matrix = result)
    }

    /** Median-Approximation via Sampling — O(n) statt O(n log n) */
    private fun approximateMedian(arr: FloatArray): Float {
        if (arr.size <= 512) {
            val copy = arr.copyOf()
            copy.sort()
            return copy[copy.size / 2]
        }
        // 2000 gleichverteilte Samples nehmen
        val sampleSize = 2000.coerceAtMost(arr.size)
        val step = arr.size.toFloat() / sampleSize
        val sample = FloatArray(sampleSize) { i -> arr[(i * step).toInt().coerceIn(0, arr.size - 1)] }
        sample.sort()
        return sample[sampleSize / 2]
    }

    /**
     * Berechnet den Ziel-Gain (0..1) fuer einen einzelnen Bin.
     * Alle Werte (value, threshold, halfKnee, rangeLog10) sind in log10-Einheiten.
     */
    private fun computeGain(
        value: Float,
        threshold: Float,
        thresholdClose: Float,
        currentOpen: Boolean,
        ratio: Float,
        mode: Mode,
        rangeLog10: Float,
        halfKnee: Float,
        minValue: Float
    ): Float {
        val activeThreshold = if (currentOpen) thresholdClose else threshold
        val below = activeThreshold - value  // positiv = Signal unter Schwelle

        // Signal ueber Schwelle: kein Effekt
        if (below <= -halfKnee) return 1f

        when (mode) {
            Mode.GATE -> {
                if (halfKnee > 0f && below < halfKnee) {
                    // Knee-Region: weicher Uebergang
                    val kneeFactor = (below + halfKnee) / (2f * halfKnee)
                    return (1f - kneeFactor).coerceIn(0f, 1f)
                }
                return 0f  // Signal deutlich unter Schwelle: Gate zu
            }
            Mode.EXPANDER -> {
                if (below <= 0f) return 1f  // ueber Schwelle: kein Effekt
                if (halfKnee > 0f && below < halfKnee) {
                    // Knee-Region: sanfter Einstieg
                    val kneeFactor = below / halfKnee
                    val expansion = below * (ratio - 1f) * kneeFactor * 0.5f
                    val effectiveRange = -rangeLog10  // in log10
                    return (1f - expansion / effectiveRange).coerceIn(0f, 1f)
                }
                // Volle Expansion: Signal below log10-Einheiten unter Schwelle
                val expansion = below * (ratio - 1f)  // in log10
                val effectiveRange = -rangeLog10       // in log10
                return (1f - expansion / effectiveRange).coerceIn(0f, 1f)
            }
        }
    }
}
