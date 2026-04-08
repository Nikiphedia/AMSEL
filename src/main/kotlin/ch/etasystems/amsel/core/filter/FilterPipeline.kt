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
        // Eine einzige Arbeitskopie — Punkt-Operationen arbeiten in-place darauf
        val workMatrix = data.matrix.copyOf()
        var result = data.copy(matrix = workMatrix)

        // 0. Volume Fader: in-place Addition auf workMatrix
        if (volumeGainsLog10 != null) {
            val nFrames = result.nFrames
            for (mel in 0 until result.nMels) {
                val baseIdx = mel * nFrames
                for (frame in 0 until nFrames) {
                    val gain = if (frame < volumeGainsLog10.size) volumeGainsLog10[frame] else 0f
                    workMatrix[baseIdx + frame] += gain
                }
            }
        }

        // 1. Bandpass: zuerst irrelevante Frequenzen entfernen
        //    BandpassFilter.apply() erzeugt neues Array → result/workMatrix-Referenz aktualisieren
        if (config.bandpass) {
            result = BandpassFilter.apply(
                result,
                lowHz = config.bandpassLowHz,
                highHz = config.bandpassHighHz
            )
        }

        // 2. Limiter: in-place Clamping auf result.matrix
        if (config.limiter && config.limiterThresholdDb < 0f && result.nFrames > 0) {
            val m = result.matrix
            val ceiling = result.maxValue + config.limiterThresholdDb / 10f
            for (i in m.indices) {
                if (m[i] > ceiling) m[i] = ceiling
            }
        }

        // 3. Normalisierung: in-place Addition auf result.matrix
        if (config.normalize && config.normalizeGainLog10 != 0f) {
            val gain = config.normalizeGainLog10
            val m = result.matrix
            for (i in m.indices) {
                m[i] += gain
            }
        }

        // 4. Spectral Gating: Nachbar-Operation → erzeugt neues Array
        if (config.spectralGating) {
            result = SpectralGating.applyManualThreshold(
                result,
                thresholdDb = config.spectralGatingThresholdDb,
                softness = config.spectralGatingSoftness
            )
        }

        // 5. Noise-Filter: Nachbar-Operation → erzeugt neues Array
        if (config.noiseFilter) {
            result = NoiseFilter.apply(result, thresholdPercent = config.noiseFilterPercent)
        } else if (config.spectralSubtraction) {
            // Legacy-Fallback fuer alte Presets
            result = SpectralSubtraction(
                noiseEstimationFrames = config.noiseEstimationFrames,
                alpha = config.spectralSubtractionAlpha
            ).apply(result)
        }

        // 6. Expander/Gate: Nachbar-Operation → erzeugt neues Array
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

        // 7. Median: Nachbar-Operation → erzeugt neues Array
        if (config.medianFilter) {
            result = MedianFilter.apply(result, kernelSize = config.medianKernelSize)
        }

        return result
    }
}
