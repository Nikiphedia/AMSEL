package ch.etasystems.amsel.core.annotation

import ch.etasystems.amsel.core.spectrogram.MelFilterbank
import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import org.slf4j.LoggerFactory
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Berechnet akustische Messwerte (Peak-Freq, Center-Freq, -3dB-Grenzen, SNR)
 * fuer einen Zeit-Frequenz-Bereich im Mel-Spektrogramm.
 *
 * Reine Rechenfunktionen ohne State, kein Cache, kein Manager.
 */
object AnnotationMetricsAnalyzer {

    private val logger = LoggerFactory.getLogger(AnnotationMetricsAnalyzer::class.java)

    /**
     * Berechnet akustische Messwerte fuer einen Zeitbereich im Spektrogramm.
     * Gibt AnnotationMetrics() (alles 0) zurueck wenn keine Berechnung moeglich.
     *
     * @param data      Mel-Spektrogramm-Daten
     * @param startSec  Startzeit der Annotation in Sekunden (absolut)
     * @param endSec    Endzeit der Annotation in Sekunden (absolut)
     * @param fMinHz    Untere Frequenzgrenze der Annotation in Hz
     * @param fMaxHz    Obere Frequenzgrenze der Annotation in Hz
     */
    fun compute(
        data: SpectrogramData,
        startSec: Float,
        endSec: Float,
        fMinHz: Float,
        fMaxHz: Float
    ): AnnotationMetrics {
        // Edge Cases: ungueltige Range oder leere Daten
        if (endSec <= startSec) return AnnotationMetrics()
        if (data.nFrames == 0) return AnnotationMetrics()

        // 1. Frame-Range bestimmen
        val fStart = (startSec * data.sampleRate / data.hopSize).toInt().coerceAtLeast(0)
        val fEnd = (endSec * data.sampleRate / data.hopSize).toInt().coerceAtMost(data.nFrames)
        if (fEnd <= fStart) return AnnotationMetrics()
        val nFramesInRange = fEnd - fStart

        // 2. Mel-Bin-Range aus Frequenzgrenzen bestimmen
        val melMin = MelFilterbank.hzToMel(data.fMin)
        val melMax = MelFilterbank.hzToMel(data.fMax)
        val clampedFMin = fMinHz.coerceAtLeast(data.fMin).coerceAtMost(data.fMax)
        val clampedFMax = fMaxHz.coerceAtLeast(data.fMin).coerceAtMost(data.fMax)
        val mBinStart = freqToMelBin(clampedFMin, melMin, melMax, data.nMels)
        val mBinEnd = freqToMelBin(clampedFMax, melMin, melMax, data.nMels)
        if (mBinEnd < mBinStart) return AnnotationMetrics()
        val nBins = mBinEnd - mBinStart + 1

        // 3. Mittlere lineare Energie pro Mel-Bin im Zeit-Fenster
        // data.valueAt liefert log10-Energie → 10^value fuer lineare Skala
        val avgEnergy = FloatArray(nBins)
        for (m in mBinStart..mBinEnd) {
            var sum = 0.0
            for (f in fStart until fEnd) {
                sum += 10.0.pow(data.valueAt(m, f).toDouble())
            }
            avgEnergy[m - mBinStart] = (sum / nFramesInRange).toFloat()
        }

        // 4. Peak-Frequency: Bin mit maximaler mittlerer Energie
        val peakIdx = avgEnergy.indices.maxByOrNull { avgEnergy[it] } ?: 0
        val peakEnergy = avgEnergy[peakIdx]

        if (peakEnergy <= 0f) return AnnotationMetrics()

        val peakFreqHz = melBinCenterHz(mBinStart + peakIdx, melMin, melMax, data.nMels)

        // 5. Center-Frequency: spektraler Schwerpunkt (energie-gewichtet)
        var weightedFreqSum = 0.0
        var energySum = 0.0
        for (i in 0 until nBins) {
            val freq = melBinCenterHz(mBinStart + i, melMin, melMax, data.nMels).toDouble()
            weightedFreqSum += freq * avgEnergy[i]
            energySum += avgEnergy[i]
        }
        val centerFreqHz = if (energySum > 0.0) (weightedFreqSum / energySum).toFloat() else peakFreqHz

        // Sonderfall: nur ein Bin
        if (nBins == 1) {
            val snrDb = computeSnr(data, fStart, fEnd, mBinStart, mBinEnd, peakEnergy)
            return AnnotationMetrics(
                peakFreqHz = peakFreqHz,
                centerFreqHz = centerFreqHz,
                lowFreq3dbHz = peakFreqHz,
                highFreq3dbHz = peakFreqHz,
                bandwidth3dbHz = 0f,
                snrDb = snrDb
            )
        }

        // 6. -3 dB Grenzen: vom Peak aus nach aussen wandern bis Energie unter Schwelle
        val threshold = peakEnergy / 2f  // -3 dB in linearer Energie

        // Untere Grenze: vom Peak-Bin abwaerts suchen
        var lowCrossIdx = -1
        for (i in peakIdx - 1 downTo 0) {
            if (avgEnergy[i] < threshold) {
                lowCrossIdx = i  // erster Bin unter Schwelle
                break
            }
        }
        val lowFreq3dbHz = if (lowCrossIdx < 0) {
            // Nie unter Schwelle: unterer Rand als Grenze
            melBinCenterHz(mBinStart, melMin, melMax, data.nMels)
        } else {
            // Lineare Interpolation zwischen lowCrossIdx und lowCrossIdx+1
            interpolateBinHz(lowCrossIdx, lowCrossIdx + 1, avgEnergy, mBinStart, threshold, melMin, melMax, data.nMels)
        }

        // Obere Grenze: vom Peak-Bin aufwaerts suchen
        var highCrossIdx = -1
        for (i in peakIdx + 1 until nBins) {
            if (avgEnergy[i] < threshold) {
                highCrossIdx = i
                break
            }
        }
        val highFreq3dbHz = if (highCrossIdx < 0) {
            melBinCenterHz(mBinEnd, melMin, melMax, data.nMels)
        } else {
            interpolateBinHz(highCrossIdx - 1, highCrossIdx, avgEnergy, mBinStart, threshold, melMin, melMax, data.nMels)
        }

        val bandwidth = (highFreq3dbHz - lowFreq3dbHz).coerceAtLeast(0f)

        // 7. SNR berechnen
        val snrDb = computeSnr(data, fStart, fEnd, mBinStart, mBinEnd, peakEnergy)

        return AnnotationMetrics(
            peakFreqHz = peakFreqHz,
            centerFreqHz = centerFreqHz,
            lowFreq3dbHz = lowFreq3dbHz,
            highFreq3dbHz = highFreq3dbHz,
            bandwidth3dbHz = bandwidth,
            snrDb = snrDb
        )
    }

    /**
     * Konvertiert eine Frequenz in Hz zum naechsten Mel-Bin-Index (0-basiert).
     * Mel-Bin-Zentren liegen bei: melMin + (m+1)*(melMax-melMin)/(nMels+1) fuer m in 0..nMels-1.
     */
    private fun freqToMelBin(hz: Float, melMin: Float, melMax: Float, nMels: Int): Int {
        val mel = MelFilterbank.hzToMel(hz)
        // Inverse der Bin-Zentrum-Formel: m = (mel-melMin)*(nMels+1)/(melMax-melMin) - 1
        val idx = ((mel - melMin) * (nMels + 1) / (melMax - melMin) - 1).roundToInt()
        return idx.coerceIn(0, nMels - 1)
    }

    /**
     * Gibt die Mittelfrequenz (Hz) des Mel-Bins mit dem gegebenen absoluten Index zurueck.
     */
    private fun melBinCenterHz(binIdx: Int, melMin: Float, melMax: Float, nMels: Int): Float {
        val melCenter = melMin + (binIdx + 1).toFloat() * (melMax - melMin) / (nMels + 1)
        return MelFilterbank.melToHz(melCenter)
    }

    /**
     * Lineare Interpolation zwischen zwei Bins um die -3dB-Grenzfrequenz zu schaetzen.
     * lowerRelIdx und upperRelIdx sind relativ zum avgEnergy-Array.
     */
    private fun interpolateBinHz(
        lowerRelIdx: Int,
        upperRelIdx: Int,
        avgEnergy: FloatArray,
        mBinStart: Int,
        threshold: Float,
        melMin: Float,
        melMax: Float,
        nMels: Int
    ): Float {
        val lIdx = lowerRelIdx.coerceIn(0, avgEnergy.size - 1)
        val uIdx = upperRelIdx.coerceIn(0, avgEnergy.size - 1)
        val e1 = avgEnergy[lIdx]
        val e2 = avgEnergy[uIdx]
        val f1 = melBinCenterHz(mBinStart + lIdx, melMin, melMax, nMels)
        val f2 = melBinCenterHz(mBinStart + uIdx, melMin, melMax, nMels)
        if (e1 == e2) return (f1 + f2) / 2f
        val t = ((threshold - e1) / (e2 - e1)).coerceIn(0f, 1f)
        return f1 + t * (f2 - f1)
    }

    /**
     * Berechnet den Signal-Rausch-Abstand in dB.
     *
     * Signal-Peak: bereits bekannte peakEnergy (lineare Skala).
     * Noise-Floor: 10. Perzentil der Energien im gleichen Mel-Band ausserhalb
     * des Zeit-Fensters (±2s Kontext). Fallback: 10. Perzentil ueber alle Frames
     * wenn zu wenig Kontext vorhanden.
     */
    private fun computeSnr(
        data: SpectrogramData,
        fStart: Int,
        fEnd: Int,
        mBinStart: Int,
        mBinEnd: Int,
        peakEnergy: Float
    ): Float {
        val signalDb = 10f * log10(peakEnergy.coerceAtLeast(1e-20f))

        // Kontext: ±2s ausserhalb des Zeit-Fensters
        val contextFrames = (2f * data.sampleRate / data.hopSize).toInt().coerceAtLeast(1)
        val noiseStart1 = (fStart - contextFrames).coerceAtLeast(0)
        val noiseEnd2 = (fEnd + contextFrames).coerceAtMost(data.nFrames)

        val noiseEnergies = mutableListOf<Float>()
        // Vorlauf
        for (m in mBinStart..mBinEnd) {
            for (f in noiseStart1 until fStart) {
                noiseEnergies.add(10f.pow(data.valueAt(m, f)))
            }
        }
        // Nachlauf
        for (m in mBinStart..mBinEnd) {
            for (f in fEnd until noiseEnd2) {
                noiseEnergies.add(10f.pow(data.valueAt(m, f)))
            }
        }

        // Fallback: alle Frames im Mel-Band wenn weniger als 10 Noise-Samples
        if (noiseEnergies.size < 10) {
            logger.debug("AnnotationMetricsAnalyzer: zu wenig Kontext ({} Samples), nutze alle Frames fuer Noise-Floor", noiseEnergies.size)
            noiseEnergies.clear()
            for (m in mBinStart..mBinEnd) {
                for (f in 0 until data.nFrames) {
                    noiseEnergies.add(10f.pow(data.valueAt(m, f)))
                }
            }
        }

        if (noiseEnergies.isEmpty()) {
            logger.warn("AnnotationMetricsAnalyzer: Noise-Floor nicht berechenbar, SNR = 0")
            return 0f
        }

        noiseEnergies.sort()
        val p10Idx = (noiseEnergies.size * 0.1f).toInt().coerceIn(0, noiseEnergies.size - 1)
        val noiseFloor = noiseEnergies[p10Idx].coerceAtLeast(1e-20f)
        val noiseDb = 10f * log10(noiseFloor)

        return (signalDb - noiseDb).coerceAtLeast(0f)
    }
}
