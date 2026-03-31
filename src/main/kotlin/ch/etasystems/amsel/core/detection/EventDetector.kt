package ch.etasystems.amsel.core.detection

import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlin.math.sqrt

/**
 * Energie-basierte Event-Erkennung auf Spektrogrammen.
 *
 * Algorithmus:
 * 1. RMS-Energie pro Frame berechnen
 * 2. Gleitender Mittelwert als Baseline
 * 3. Frames über Threshold (Baseline * factor) = aktiv
 * 4. Zusammenhängende aktive Frames → Events gruppieren
 * 5. Frequenzband pro Event bestimmen (wo liegt die Energie?)
 */
object EventDetector {

    data class DetectedEvent(
        val startSec: Float,
        val endSec: Float,
        val lowFreqHz: Float,
        val highFreqHz: Float,
        val peakEnergy: Float
    )

    /**
     * @param data Spektrogramm
     * @param viewStartSec Zeitoffset des Spektrogramms im Audio
     * @param viewEndSec Zeitende des Spektrogramms
     * @param thresholdFactor Faktor über gleitendem Mittelwert (1.5 = 50% lauter als Umgebung)
     * @param minDurationSec Minimale Event-Dauer (filtert Einzelimpulse)
     * @param maxDurationSec Maximale Event-Dauer (splittet Dauer-Rauschen)
     * @param smoothingFrames Fenstergröße für gleitenden Mittelwert
     */
    fun detect(
        data: SpectrogramData,
        viewStartSec: Float,
        viewEndSec: Float,
        thresholdFactor: Float = 1.8f,
        minDurationSec: Float = 0.05f,
        maxDurationSec: Float = 10f,
        smoothingFrames: Int = 20
    ): List<DetectedEvent> {
        if (data.nFrames < 3) return emptyList()

        val duration = viewEndSec - viewStartSec
        val secPerFrame = duration / data.nFrames

        // 1. RMS-Energie pro Frame
        val energy = FloatArray(data.nFrames) { frame ->
            var sumSq = 0f
            for (mel in 0 until data.nMels) {
                val v = data.valueAt(mel, frame) - data.minValue
                sumSq += v * v
            }
            sqrt(sumSq / data.nMels)
        }

        // 2. Gleitender Mittelwert (Baseline)
        val baseline = FloatArray(data.nFrames)
        val halfWin = smoothingFrames / 2
        for (i in energy.indices) {
            var sum = 0f
            var count = 0
            for (j in (i - halfWin).coerceAtLeast(0)..(i + halfWin).coerceAtMost(energy.size - 1)) {
                sum += energy[j]
                count++
            }
            baseline[i] = sum / count
        }

        // 3. Threshold-Aktivierung
        val active = BooleanArray(data.nFrames) { frame ->
            energy[frame] > baseline[frame] * thresholdFactor && energy[frame] > 0.01f
        }

        // 4. Events gruppieren
        val rawEvents = mutableListOf<IntRange>()
        var eventStart = -1
        for (i in active.indices) {
            if (active[i] && eventStart == -1) {
                eventStart = i
            } else if (!active[i] && eventStart != -1) {
                rawEvents.add(eventStart until i)
                eventStart = -1
            }
        }
        if (eventStart != -1) {
            rawEvents.add(eventStart until data.nFrames)
        }

        // 5. Filtern + Frequenzband bestimmen
        val minFrames = (minDurationSec / secPerFrame).toInt().coerceAtLeast(1)
        val maxFrames = (maxDurationSec / secPerFrame).toInt()

        return rawEvents
            .filter { it.count() >= minFrames }
            .flatMap { range ->
                // Bei zu langen Events: in Stücke aufteilen
                if (range.count() > maxFrames) {
                    range.chunked(maxFrames).map { chunk ->
                        IntRange(chunk.first(), chunk.last())
                    }
                } else {
                    listOf(range)
                }
            }
            .filter { it.count() >= minFrames }
            .map { range ->
                val startSec = viewStartSec + range.first * secPerFrame
                val endSec = viewStartSec + (range.last + 1) * secPerFrame

                // Frequenzband: Mel-Bins mit >50% der Peak-Energie finden
                val melEnergy = FloatArray(data.nMels)
                var peakE = 0f
                for (mel in 0 until data.nMels) {
                    var sum = 0f
                    for (frame in range) {
                        val v = data.valueAt(mel, frame) - data.minValue
                        sum += v
                    }
                    melEnergy[mel] = sum
                    if (sum > peakE) peakE = sum
                }

                val energyThreshold = peakE * 0.3f
                var lowMel = 0
                var highMel = data.nMels - 1
                for (mel in 0 until data.nMels) {
                    if (melEnergy[mel] > energyThreshold) {
                        lowMel = mel
                        break
                    }
                }
                for (mel in data.nMels - 1 downTo 0) {
                    if (melEnergy[mel] > energyThreshold) {
                        highMel = mel
                        break
                    }
                }

                // Mel-Index → Hz (linear innerhalb fMin..fMax)
                val freqRange = data.fMax - data.fMin
                val lowHz = data.fMin + (lowMel.toFloat() / data.nMels) * freqRange
                val highHz = data.fMin + ((highMel + 1).toFloat() / data.nMels) * freqRange

                DetectedEvent(
                    startSec = startSec,
                    endSec = endSec.coerceAtMost(viewEndSec),
                    lowFreqHz = lowHz.coerceAtLeast(data.fMin),
                    highFreqHz = highHz.coerceAtMost(data.fMax),
                    peakEnergy = energy.slice(range).max()
                )
            }
    }

    private fun IntRange.chunked(size: Int): List<List<Int>> {
        return this.toList().chunked(size)
    }
}
