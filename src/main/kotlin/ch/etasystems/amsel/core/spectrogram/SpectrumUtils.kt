package ch.etasystems.amsel.core.spectrogram

/**
 * Gemeinsame Bausteine fuer FFT-basierte Spektren (Klassifikation, Embeddings, MFCC).
 *
 * Hintergrund: `realForwardFull` auf einem Buffer der Laenge 2n berechnet das volle
 * komplexe Spektrum, obwohl fuer reelle Signale nur die Haelfte gebraucht wird.
 * `realForward` auf Laenge n liefert dieselben Werte mit etwa halbem Aufwand.
 */
object SpectrumUtils {

    /**
     * Power-Spektrum aus dem Ergebnis von `DoubleFFT_1D.realForward` (Buffer-Laenge n).
     * Layout von realForward: [0]=Re(0), [1]=Re(n/2), [2k]=Re(k), [2k+1]=Im(k).
     * Rechnet in Float (re/im vorher nach Float gewandelt) — identisch zur bisherigen
     * Berechnung aus realForwardFull.
     *
     * @param out Ziel mit mindestens n/2+1 Eintraegen
     */
    fun powerFromRealForward(buffer: DoubleArray, n: Int, out: FloatArray) {
        val half = n / 2
        val dc = buffer[0].toFloat()
        val nyquist = buffer[1].toFloat()
        out[0] = dc * dc
        out[half] = nyquist * nyquist
        for (k in 1 until half) {
            val re = buffer[2 * k].toFloat()
            val im = buffer[2 * k + 1].toFloat()
            out[k] = re * re + im * im
        }
    }

    /** Wie [powerFromRealForward], aber in Double-Praezision. */
    fun powerFromRealForward(buffer: DoubleArray, n: Int, out: DoubleArray) {
        val half = n / 2
        val dc = buffer[0]
        val nyquist = buffer[1]
        out[0] = dc * dc
        out[half] = nyquist * nyquist
        for (k in 1 until half) {
            val re = buffer[2 * k]
            val im = buffer[2 * k + 1]
            out[k] = re * re + im * im
        }
    }
}

/**
 * Duenn besetzte Darstellung einer Filterbank [nFilter x nBins].
 * Ein Dreiecksfilter deckt nur wenige Bins ab — gespeichert wird pro Filter nur
 * der Bereich mit Gewicht != 0 (`start` + `weights`).
 * Ergebnis ist identisch zur dichten Multiplikation (uebersprungene Terme sind exakt 0).
 */
class SparseFilterbank(dense: Array<FloatArray>) {
    val size: Int = dense.size
    val start = IntArray(dense.size)
    val weights: Array<FloatArray> = Array(dense.size) { m ->
        val row = dense[m]
        val first = row.indexOfFirst { it != 0f }
        if (first < 0) {
            FloatArray(0)
        } else {
            start[m] = first
            row.copyOfRange(first, row.indexOfLast { it != 0f } + 1)
        }
    }

    /** Energie von Filter m (Float-Akkumulation). */
    fun energy(m: Int, power: FloatArray): Float {
        val w = weights[m]
        val s = start[m]
        var sum = 0f
        for (j in w.indices) sum += w[j] * power[s + j]
        return sum
    }

    /** Energie von Filter m (Double-Akkumulation). */
    fun energy(m: Int, power: DoubleArray): Double {
        val w = weights[m]
        val s = start[m]
        var sum = 0.0
        for (j in w.indices) sum += w[j] * power[s + j]
        return sum
    }
}
