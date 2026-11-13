package ch.etasystems.amsel.core.spectrogram

import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.audio.PcmCacheFile
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Uebersicht langer Aufnahmen darf kein Audio ueberspringen:
 * ein kurzer Ruf muss sichtbar sein, egal wo er liegt.
 */
class OverviewLueckenlosTest {

    private val rate = 16_000
    private val zielFrames = 4000

    /** Stille mit einem einzelnen 20-ms-Ton (3 kHz) ab [impulsStart]. */
    private fun stilleMitImpuls(sekunden: Int, impulsStart: Int): FloatArray {
        val samples = FloatArray(rate * sekunden)
        for (i in 0 until rate / 50) {
            samples[impulsStart + i] = (0.5 * sin(2.0 * PI * 3000.0 * i / rate)).toFloat()
        }
        return samples
    }

    /** Lauteste Spalte der Uebersicht und ihr Hoechstwert. */
    private fun lautesteSpalte(data: SpectrogramData): Pair<Int, Float> {
        var besteSpalte = -1
        var bester = Float.NEGATIVE_INFINITY
        for (frame in 0 until data.nFrames) {
            for (mel in 0 until data.nMels) {
                val v = data.valueAt(mel, frame)
                if (v > bester) { bester = v; besteSpalte = frame }
            }
        }
        return besteSpalte to bester
    }

    @Test
    fun `kurzer Impuls zwischen zwei alten Frames ist in der Uebersicht sichtbar`() {
        // 10 min bei 16 kHz: der alte adaptive Hop war 2400 Samples bei 2048 Samples Fenster,
        // dazwischen blieben je 352 Samples ungesehen. Der Impuls liegt genau in einer solchen Luecke.
        val alterHop = rate * 600 / zielFrames
        val impulsStart = 2000 * alterHop + 2048 + 16
        val samples = stilleMitImpuls(600, impulsStart)

        val overview = runBlocking {
            ChunkedSpectrogram.computeOverview(AudioSegment(samples, rate), targetFrames = zielFrames)
        }.spectrogramData

        assertTrue(overview.nFrames <= zielFrames, "nFrames = ${overview.nFrames}")
        val (spalte, wert) = lautesteSpalte(overview)
        // Stille ergibt log10(1e-10) = -10; ein sichtbarer Impuls liegt viele Groessenordnungen darueber
        assertTrue(wert > -5f, "Impuls nicht sichtbar: Hoechstwert $wert")
        val erwarteteSpalte = (impulsStart.toLong() * overview.nFrames / samples.size).toInt()
        assertTrue(abs(spalte - erwarteteSpalte) <= 1, "Impuls in Spalte $spalte statt $erwarteteSpalte")
        // Zeitachse: Dauer der Uebersicht stimmt auf 1 % mit der Aufnahme ueberein
        assertEquals(600f, overview.durationSec, 6f)
    }

    @Test
    fun `Uebersicht aus dem PCM-Cache entspricht der aus dem Speicher`() {
        val samples = stilleMitImpuls(300, 1_234_567)
        val segment = AudioSegment(samples, rate)
        val cache = PcmCacheFile.createFromSegment(segment, "test_overview")
        try {
            val ausSpeicher = runBlocking { ChunkedSpectrogram.computeOverview(segment, targetFrames = zielFrames) }
            val ausCache = runBlocking { ChunkedSpectrogram.computeOverview(cache, targetFrames = zielFrames) }
            assertEquals(ausSpeicher.spectrogramData.nFrames, ausCache.spectrogramData.nFrames)
            assertEquals(ausSpeicher.spectrogramData.hopSize, ausCache.spectrogramData.hopSize)
            assertContentEquals(ausSpeicher.spectrogramData.matrix, ausCache.spectrogramData.matrix)
            assertEquals(null, ausCache.audioSegment)
        } finally {
            cache.delete()
        }
    }

    @Test
    fun `PCM-Cache liest samplegenau`() {
        val samples = FloatArray(100_000) { it.toFloat() }
        val cache = PcmCacheFile.createFromSegment(AudioSegment(samples, rate), "test_lesen")
        try {
            assertContentEquals(samples.copyOfRange(70_001, 70_011), cache.readSamples(70_001L, 10))
            // Ueber das Ende hinaus: nur die vorhandenen Samples
            assertEquals(5, cache.readSamples(99_995L, 10).size)
        } finally {
            cache.delete()
        }
    }
}
