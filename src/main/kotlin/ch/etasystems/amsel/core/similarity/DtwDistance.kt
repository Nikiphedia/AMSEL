package ch.etasystems.amsel.core.similarity

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dynamic Time Warping (DTW) mit Sakoe-Chiba Band-Constraint.
 * Berechnet die optimale Ausrichtung zweier Frame-Sequenzen.
 */
object DtwDistance {

    /**
     * DTW-Distanz zwischen zwei Frame-Sequenzen.
     * @param a Erste Sequenz [nFramesA][nFeatures]
     * @param b Zweite Sequenz [nFramesB][nFeatures]
     * @param bandWidth Sakoe-Chiba Band-Breite (begrenzt Warping-Pfad)
     * @return Normalisierte DTW-Distanz (durch Pfadlaenge geteilt)
     */
    fun compute(a: Array<FloatArray>, b: Array<FloatArray>, bandWidth: Int = 50): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Float.MAX_VALUE

        // Kostenmatrix (nur zwei Zeilen noetig fuer Speichereffizienz)
        val prev = FloatArray(m + 1) { Float.MAX_VALUE }
        val curr = FloatArray(m + 1) { Float.MAX_VALUE }
        prev[0] = 0f

        for (i in 1..n) {
            curr.fill(Float.MAX_VALUE)
            // Sakoe-Chiba Band: j muss in [i*m/n - bandWidth, i*m/n + bandWidth] liegen
            val center = (i.toLong() * m / n).toInt()
            val jStart = (center - bandWidth).coerceAtLeast(1)
            val jEnd = (center + bandWidth).coerceAtMost(m)

            for (j in jStart..jEnd) {
                val cost = euclideanDistance(a[i - 1], b[j - 1])
                val min = minOf(
                    prev[j],       // Einfuegung
                    curr[j - 1],   // Loeschung
                    prev[j - 1]    // Uebereinstimmung
                )
                if (min < Float.MAX_VALUE) {
                    curr[j] = cost + min
                }
            }

            // Zeilen tauschen
            System.arraycopy(curr, 0, prev, 0, m + 1)
        }

        // Durch Pfadlaenge normalisieren (Approximation: n + m)
        val pathLength = n + m
        return if (prev[m] < Float.MAX_VALUE) prev[m] / pathLength else Float.MAX_VALUE
    }

    /**
     * Konvertiert DTW-Distanz in Aehnlichkeitswert [0, 1].
     * @param distance DTW-Distanz (normalisiert)
     * @param scale Skalierungsfaktor — groessere Werte machen die Kurve weicher
     */
    fun distanceToSimilarity(distance: Float, scale: Float = 10f): Float {
        return 1f / (1f + distance / scale)
    }

    /** Euklidische Distanz zwischen zwei Feature-Vektoren */
    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0.0
        for (i in a.indices) {
            if (i < b.size) {
                val diff = a[i] - b[i]
                sum += diff * diff
            }
        }
        return sqrt(sum).toFloat()
    }
}
