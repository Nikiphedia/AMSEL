package ch.etasystems.amsel.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class AudioResamplerTest {

    private fun sinus(freqHz: Double, rate: Int, sekunden: Double = 1.0): FloatArray =
        FloatArray((rate * sekunden).toInt()) { i -> sin(2.0 * PI * freqHz * i / rate).toFloat() }

    /** RMS-Pegel in dB ueber die mittleren 80 % (Raender mit Ein-/Ausschwingen ausgenommen). */
    private fun pegelDb(samples: FloatArray): Double {
        val von = samples.size / 10
        val bis = samples.size - von
        var summe = 0.0
        for (i in von until bis) summe += samples[i].toDouble() * samples[i]
        return 10.0 * log10(summe / (bis - von) + 1e-30)
    }

    /** Frequenz aus der Zahl der Nulldurchgaenge im mittleren Bereich. */
    private fun frequenzHz(samples: FloatArray, rate: Int): Double {
        val von = samples.size / 10
        val bis = samples.size - von
        var wechsel = 0
        for (i in von + 1 until bis) if ((samples[i - 1] < 0f) != (samples[i] < 0f)) wechsel++
        return wechsel / 2.0 * rate / (bis - von)
    }

    @Test
    fun `20 kHz wird beim Heruntertakten 48 auf 32 kHz um mindestens 60 dB gedaempft`() {
        val eingang = sinus(20_000.0, 48_000)
        val ausgang = AudioResampler.resample(eingang, 48_000, 32_000)
        val daempfung = pegelDb(eingang) - pegelDb(ausgang)
        assertTrue(daempfung >= 60.0, "Daempfung nur ${"%.1f".format(daempfung)} dB")
    }

    @Test
    fun `1 kHz bleibt beim Heruntertakten 48 auf 32 kHz in Pegel und Frequenz erhalten`() {
        val eingang = sinus(1_000.0, 48_000)
        val ausgang = AudioResampler.resample(eingang, 48_000, 32_000)
        assertTrue(abs(pegelDb(ausgang) - pegelDb(eingang)) <= 0.5, "Pegelabweichung zu gross")
        assertTrue(abs(frequenzHz(ausgang, 32_000) - 1_000.0) <= 2.0, "Frequenz ${frequenzHz(ausgang, 32_000)} Hz")
    }

    @Test
    fun `12 kHz bleibt beim Heruntertakten 48 auf 32 kHz erhalten`() {
        val eingang = sinus(12_000.0, 48_000)
        val ausgang = AudioResampler.resample(eingang, 48_000, 32_000)
        assertTrue(abs(pegelDb(ausgang) - pegelDb(eingang)) <= 0.5, "Pegelabweichung zu gross")
    }

    @Test
    fun `Ausgabelaenge entspricht dem Ratenverhaeltnis`() {
        for ((von, nach) in listOf(48_000 to 32_000, 44_100 to 32_000, 16_000 to 32_000, 256_000 to 32_000)) {
            val n = 12_345
            val erwartet = (n.toLong() * nach / von).toInt()
            val ist = AudioResampler.resample(FloatArray(n), von, nach).size
            assertTrue(abs(ist - erwartet) <= 1, "$von -> $nach: $ist statt $erwartet")
        }
    }

    @Test
    fun `gleiche Rate gibt die Eingabe unveraendert zurueck`() {
        val eingang = sinus(1_000.0, 32_000, 0.1)
        assertContentEquals(eingang, AudioResampler.resample(eingang, 32_000, 32_000))
    }

    @Test
    fun `1 kHz bleibt beim Hochtakten 16 auf 32 kHz erhalten`() {
        val eingang = sinus(1_000.0, 16_000)
        val ausgang = AudioResampler.resample(eingang, 16_000, 32_000)
        assertTrue(abs(pegelDb(ausgang) - pegelDb(eingang)) <= 0.5, "Pegelabweichung zu gross")
        assertTrue(abs(frequenzHz(ausgang, 32_000) - 1_000.0) <= 2.0, "Frequenz ${frequenzHz(ausgang, 32_000)} Hz")
    }

    @Test
    fun `1 kHz bleibt bei krummem Verhaeltnis 44100 auf 32000 erhalten`() {
        val eingang = sinus(1_000.0, 44_100)
        val ausgang = AudioResampler.resample(eingang, 44_100, 32_000)
        assertTrue(abs(pegelDb(ausgang) - pegelDb(eingang)) <= 0.5, "Pegelabweichung zu gross")
        assertTrue(abs(frequenzHz(ausgang, 32_000) - 1_000.0) <= 2.0, "Frequenz ${frequenzHz(ausgang, 32_000)} Hz")
    }
}
