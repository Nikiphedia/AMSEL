package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData

object FilterPipeline {

    /**
     * Signal-Chain:
     * 0. Volume Fader      → Lautstaerke-Kurve (Breakpoints), erster Schritt
     * 1. Bandpass          → Irrelevante Frequenzen weg (beeinflusst alles Nachfolgende)
     * 2. Limiter           → Pegel begrenzen (nach Bandpass, vor Normalisierung)
     * 3. Normalisierung    → Pegel anheben auf -2dBFS
     * 4. Spectral Gating   → Intelligente Rauschunterdrückung pro Band
     * 5. NoiseFilter       → Globaler Dynamik-Threshold (Feintuning)
     * 6. Expander/Gate     → Leise Bereiche dämpfen/stumm
     * 7. Median            → Glättung als letzter Schritt
     *
     * (Normalisierung/Dynamik-Mapping erfolgt erst bei der Anzeige in Colormap.toPixels)
     */
    /**
     * @param volumeGainsLog10 Optionale Volume-Envelope Gains pro Frame (in log10).
     *   Wird als allererster Schritt angewendet (vor Bandpass).
     */
    fun apply(data: SpectrogramData, config: FilterConfig, volumeGainsLog10: FloatArray? = null): SpectrogramData {
        var result = data

        // 0. Volume Fader: Lautstaerke-Kurve als erstes in der Kette
        if (volumeGainsLog10 != null) {
            val shifted = FloatArray(result.matrix.size)
            val nFrames = result.nFrames
            for (mel in 0 until result.nMels) {
                val baseIdx = mel * nFrames
                for (frame in 0 until nFrames) {
                    val gain = if (frame < volumeGainsLog10.size) volumeGainsLog10[frame] else 0f
                    shifted[baseIdx + frame] = result.matrix[baseIdx + frame] + gain
                }
            }
            result = result.copy(matrix = shifted)
        }

        // 1. Bandpass: zuerst irrelevante Frequenzen entfernen
        if (config.bandpass) {
            result = BandpassFilter.apply(
                result,
                lowHz = config.bandpassLowHz,
                highHz = config.bandpassHighHz
            )
        }

        // 2. Limiter: Pegel begrenzen (nach Bandpass, vor Normalisierung)
        if (config.limiter) {
            result = Limiter.apply(result, thresholdDb = config.limiterThresholdDb)
        }

        // 3. Normalisierung: Pegel anheben auf -2dBFS
        if (config.normalize && config.normalizeGainLog10 != 0f) {
            val gain = config.normalizeGainLog10
            val shifted = FloatArray(result.matrix.size) { i -> result.matrix[i] + gain }
            result = result.copy(matrix = shifted)
        }

        // 4. Spectral Gating: manueller Threshold relativ zum Peak
        if (config.spectralGating) {
            result = SpectralGating.applyManualThreshold(
                result,
                thresholdDb = config.spectralGatingThresholdDb,
                softness = config.spectralGatingSoftness
            )
        }

        // 5. Noise-Filter: globaler Dynamik-Threshold
        if (config.noiseFilter) {
            result = NoiseFilter.apply(result, thresholdPercent = config.noiseFilterPercent)
        } else if (config.spectralSubtraction) {
            // Legacy-Fallback fuer alte Presets
            result = SpectralSubtraction(
                noiseEstimationFrames = config.noiseEstimationFrames,
                alpha = config.spectralSubtractionAlpha
            ).apply(result)
        }

        // 6. Expander/Gate: leise Bereiche dämpfen
        if (config.expanderGate) {
            result = ExpanderGate.apply(
                result,
                threshold = config.expanderThreshold,
                ratio = config.expanderRatio,
                mode = config.expanderMode,
                rangeDb = config.expanderRangeDb,
                kneeDb = config.expanderKneeDb,
                hysteresisDb = config.expanderHysteresisDb,
                attackFrames = config.expanderAttackFrames,
                releaseFrames = config.expanderReleaseFrames,
                holdFrames = config.expanderHoldFrames
            )
        }

        // 7. Median: Glättung als letzter Schritt
        if (config.medianFilter) {
            result = MedianFilter.apply(result, kernelSize = config.medianKernelSize)
        }

        return result
    }
}
