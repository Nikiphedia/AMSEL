package ch.etasystems.amsel.core.audio

import org.slf4j.LoggerFactory
import kotlin.math.sqrt

/**
 * Erkennt stille Audio-Chunks anhand der RMS-Energie.
 *
 * Typische Feldaufnahmen haben ~70% Stille. Durch Ueberspringen dieser Chunks
 * spart BirdNET 40-60% Rechenzeit.
 */
object SilenceDetector {
    private val logger = LoggerFactory.getLogger(SilenceDetector::class.java)

    /**
     * Default-Schwelle: -50 dBFS.
     * Typische Werte:
     *   -60 dBFS = sehr empfindlich (wenig wird uebersprungen)
     *   -50 dBFS = guter Kompromiss fuer Feldaufnahmen
     *   -40 dBFS = aggressiv (Gefahr: leise Rufe werden uebersprungen)
     */
    const val DEFAULT_THRESHOLD_DBFS = -50f

    /**
     * Berechnet den RMS-Pegel eines Audio-Chunks in dBFS.
     * Samples muessen im Bereich [-1.0, 1.0] normalisiert sein.
     */
    fun rmsDbfs(samples: FloatArray, offset: Int = 0, length: Int = samples.size): Float {
        if (length <= 0) return Float.NEGATIVE_INFINITY

        var sumSquares = 0.0
        val end = minOf(offset + length, samples.size)
        for (i in offset until end) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }

        val rms = sqrt(sumSquares / (end - offset))
        return if (rms > 0.0) (20.0 * Math.log10(rms)).toFloat() else Float.NEGATIVE_INFINITY
    }

    /**
     * Prueft ob ein Chunk als "still" gilt.
     * @param samples Audio-Daten (normalisiert auf [-1, 1])
     * @param offset Start-Index im Array
     * @param length Anzahl Samples
     * @param thresholdDbfs Schwelle in dBFS (default: -50)
     * @return true wenn der Chunk unter der Schwelle liegt
     */
    fun isSilent(
        samples: FloatArray,
        offset: Int = 0,
        length: Int = samples.size,
        thresholdDbfs: Float = DEFAULT_THRESHOLD_DBFS
    ): Boolean {
        return rmsDbfs(samples, offset, length) < thresholdDbfs
    }
}
