package ch.etasystems.amsel.core.audio

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bandbegrenzter Resampler (gefensterter Sinc, Kaiser-Fenster).
 *
 * Vor dem Umtakten wird auf die kleinere der beiden Nyquist-Frequenzen tiefpassgefiltert.
 * Ohne diesen Tiefpass wuerden beim Heruntertakten (z.B. 48 kHz -> 32 kHz) alle Anteile
 * ueber der neuen Nyquist-Frequenz in den hoerbaren Bereich gespiegelt.
 *
 * Auslegung: Sperrdaempfung 70 dB, Uebergangsband = 12 % der kleineren Nyquist-Frequenz
 * (48 -> 32 kHz: Durchlass bis ca. 14 kHz, Sperrbereich ab 16 kHz).
 */
object AudioResampler {

    private const val SPERRDAEMPFUNG_DB = 70.0
    private const val UEBERGANG_ANTEIL = 0.12
    /** Hoechstzahl Teilphasen der Filtertabelle; krumme Verhaeltnisse werden darauf gerundet. */
    private const val MAX_PHASEN = 2048
    private const val BLOCK = 65_536

    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples.copyOf()

        val outputLength = (samples.size.toLong() * toRate / fromRate).toInt()
        val output = FloatArray(outputLength)
        if (outputLength == 0) return output

        // --- Filterentwurf (alle Groessen bezogen auf die Eingangsrate) ---
        val nyquistTief = min(fromRate, toRate) / 2.0
        val uebergangHz = UEBERGANG_ANTEIL * nyquistTief
        val grenzHz = nyquistTief - uebergangHz / 2.0
        val deltaOmega = 2.0 * PI * uebergangHz / fromRate
        val halb = ceil((SPERRDAEMPFUNG_DB - 8.0) / (2.285 * deltaOmega) / 2.0).toInt()
        val beta = 0.1102 * (SPERRDAEMPFUNG_DB - 8.7)
        val fcNorm = 2.0 * grenzHz / fromRate   // Grenzfrequenz relativ zur Eingangs-Nyquist (0..1)

        // --- Tabelle: je Teilphase die Gewichte fuer die Eingangssamples n0-halb .. n0+halb ---
        val teiler = ggt(fromRate, toRate)
        val phasenExakt = toRate / teiler
        val phasen = min(phasenExakt, MAX_PHASEN)
        val taps = 2 * halb + 1
        val tabelle = FloatArray(phasen * taps)
        val i0Beta = besselI0(beta)
        for (p in 0 until phasen) {
            val bruch = p.toDouble() / phasen
            var summe = 0.0
            val zeile = DoubleArray(taps)
            for (k in -halb..halb) {
                val tau = bruch - k   // Abstand Ausgabezeitpunkt - Eingangssample
                val x = tau / (halb + 1)
                val fenster = if (x <= -1.0 || x >= 1.0) 0.0 else besselI0(beta * sqrt(1.0 - x * x)) / i0Beta
                val wert = fcNorm * sinc(fcNorm * tau) * fenster
                zeile[k + halb] = wert
                summe += wert
            }
            // Auf Summe 1 normieren: Gleichanteil und Pegel bleiben exakt erhalten
            for (j in 0 until taps) tabelle[p * taps + j] = (zeile[j] / summe).toFloat()
        }

        // --- Faltung, blockweise parallel (jeder Block schreibt nur seinen eigenen Bereich) ---
        val schritt = fromRate.toDouble() / toRate
        val n = samples.size
        val bloecke = (outputLength + BLOCK - 1) / BLOCK
        IntStream.range(0, bloecke).parallel().forEach { b ->
            val von = b * BLOCK
            val bis = min(von + BLOCK, outputLength)
            for (i in von until bis) {
                val pos = i * schritt
                var n0 = floor(pos).toInt()
                var p = ((pos - n0) * phasen).roundToInt()
                if (p == phasen) { p = 0; n0++ }
                val basis = p * taps
                val start = n0 - halb
                val kVon = if (start < 0) -start else 0
                val kBis = if (start + taps > n) n - start else taps
                var acc = 0f
                for (j in kVon until kBis) acc += samples[start + j] * tabelle[basis + j]
                output[i] = acc
            }
        }

        return output
    }

    /**
     * Resample ein AudioSegment auf die Ziel-Samplerate.
     */
    fun resample(segment: AudioSegment, toRate: Int): AudioSegment {
        if (segment.sampleRate == toRate) return segment
        val resampled = resample(segment.samples, segment.sampleRate, toRate)
        return AudioSegment(samples = resampled, sampleRate = toRate)
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Modifizierte Bessel-Funktion erster Art, Ordnung 0 (Reihenentwicklung). */
    private fun besselI0(x: Double): Double {
        var summe = 1.0
        var term = 1.0
        var k = 1
        while (term > 1e-12 * summe) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k))
            summe += term
            k++
        }
        return summe
    }

    private fun ggt(a: Int, b: Int): Int = if (b == 0) a else ggt(b, a % b)
}
