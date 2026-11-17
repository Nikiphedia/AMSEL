package ch.etasystems.amsel.core.annotation

import ch.etasystems.amsel.core.spectrogram.SpectrogramData
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Messwerte einer Annotation: SNR gegen eine unabhaengige Rechnung, und die Laufzeit fuer viele Annotationen. */
class AnnotationMetricsAnalyzerTest {

    /** Rauschen mit bekanntem Pegel, darin ein lauteres Band von 2 bis 3 s. */
    private fun daten(nMels: Int, nFrames: Int, hop: Int = 512, rate: Int = 48_000): SpectrogramData {
        val rng = java.util.Random(7)
        val m = FloatArray(nMels * nFrames) { -6f + rng.nextFloat() }  // log10-Energie -6..-5
        val f0 = 2 * rate / hop
        val f1 = 3 * rate / hop
        for (mel in nMels / 2 until nMels / 2 + 4) for (f in f0 until f1.coerceAtMost(nFrames)) m[mel * nFrames + f] = -2f
        return SpectrogramData(m, nMels, nFrames, rate, hop, 0f, 16_000f)
    }

    @Test
    fun `SNR entspricht Spitze gegen das 10-Prozent-Quantil des Umfelds`() {
        val d = daten(nMels = 64, nFrames = 1000)
        val metriken = AnnotationMetricsAnalyzer.compute(d, 2f, 3f, 0f, 16_000f)
        assertTrue(metriken.isComputed)
        // Unabhaengig gerechnet: Umfeld = je 2 s vor und nach dem Fenster, alle Baender
        val f0 = (2f * d.sampleRate / d.hopSize).toInt()
        val f1 = (3f * d.sampleRate / d.hopSize).toInt()
        val k = (2f * d.sampleRate / d.hopSize).toInt()
        val umfeld = ArrayList<Float>()
        for (mel in 0 until d.nMels) {
            for (f in (f0 - k).coerceAtLeast(0) until f0) umfeld.add(10f.pow(d.valueAt(mel, f)))
            for (f in f1 until (f1 + k).coerceAtMost(d.nFrames)) umfeld.add(10f.pow(d.valueAt(mel, f)))
        }
        umfeld.sort()
        val boden = umfeld[(umfeld.size * 0.1f).toInt()]
        val erwartet = 10f * log10(10f.pow(-2f)) - 10f * log10(boden)
        assertTrue(abs(metriken.snrDb - erwartet) < 0.01f, "SNR ${metriken.snrDb}, erwartet $erwartet")
    }

    @Test
    fun `Ausschnitt mit Anfang rechnet an der richtigen Stelle`() {
        // Derselbe Inhalt, einmal ab Aufnahmebeginn, einmal als Ausschnitt ab Sekunde 100
        val d = daten(nMels = 64, nFrames = 1000)
        val ausschnitt = d.copy(startSec = 100f)
        val erwartet = AnnotationMetricsAnalyzer.compute(d, 2f, 3f, 0f, 16_000f)
        val metriken = AnnotationMetricsAnalyzer.compute(ausschnitt, 102f, 103f, 0f, 16_000f)
        assertTrue(metriken.isComputed)
        assertTrue(metriken == erwartet, "Ausschnitt $metriken, ab Beginn $erwartet")
        // Ohne den Anfang fiele dieselbe Annotation auf das Rauschen bei Sekunde 2 des Ausschnitts
        val lautesBand = AnnotationMetricsAnalyzer.compute(d, 2f, 3f, 0f, 16_000f).peakFreqHz
        assertTrue(abs(metriken.peakFreqHz - lautesBand) < 1f)
        // Annotation vor oder nach dem Ausschnitt: keine Messwerte statt falscher
        assertTrue(!AnnotationMetricsAnalyzer.compute(ausschnitt, 2f, 3f, 0f, 16_000f).isComputed)
        assertTrue(!AnnotationMetricsAnalyzer.compute(ausschnitt, 500f, 501f, 0f, 16_000f).isComputed)
    }

    @Test
    fun `Filter behalten den Anfang des Ausschnitts`() {
        val ausschnitt = daten(nMels = 16, nFrames = 200).copy(startSec = 42f)
        val gefiltert = kotlinx.coroutines.runBlocking {
            ch.etasystems.amsel.core.filter.FilterPipeline.apply(
                ausschnitt, ch.etasystems.amsel.core.filter.FilterConfig(medianFilter = true)
            )
        }
        assertTrue(gefiltert.startSec == 42f)
    }

    private fun ann(start: Float, ende: Float) =
        Annotation(startTimeSec = start, endTimeSec = ende, lowFreqHz = 1000f, highFreqHz = 8000f)

    @Test
    fun `Bloecke enthalten jede Annotation samt Umfeld und bleiben kurz`() {
        val liste = listOf(ann(100f, 103f), ann(1f, 4f), ann(30f, 33f), ann(58f, 61f), ann(598f, 600f), ann(700f, 705f))
        val bloecke = AnnotationMetricsAnalyzer.bloecke(liste, dauerSec = 600f, maxSec = 60f)
        // Die Annotation hinter dem Dateiende faellt weg, alle anderen kommen genau einmal vor
        assertTrue(bloecke.sumOf { it.annotationen.size } == 5)
        for (b in bloecke) {
            assertTrue(b.startSec >= 0f && b.endSec <= 600f)
            assertTrue(b.endSec - b.startSec <= 60f, "Block zu lang: $b")
            for (a in b.annotationen) {
                assertTrue(b.startSec <= (a.startTimeSec - 2f).coerceAtLeast(0f))
                assertTrue(b.endSec >= (a.endTimeSec + 2f).coerceAtMost(600f))
            }
        }
        // 1-4 s und 30-33 s teilen sich einen Block, 58-61 s passt nicht mehr dazu
        assertTrue(bloecke.first().annotationen.size == 2)
        // Eine einzelne lange Annotation bekommt einen eigenen, laengeren Block
        val lang = AnnotationMetricsAnalyzer.bloecke(listOf(ann(10f, 200f)), 600f, 60f)
        assertTrue(lang.size == 1 && lang[0].startSec == 8f && lang[0].endSec == 202f)
    }

    @Test
    fun `Messwerte aus einem Block entsprechen denen aus der ganzen Aufnahme`() {
        // 20 s Rauschen, darin 7 bis 9 s ein Ton bei 4 kHz
        val rate = 48_000
        val rng = java.util.Random(11)
        val audio = FloatArray(20 * rate) { 0.01f * (rng.nextFloat() - 0.5f) }
        for (i in 7 * rate until 9 * rate) audio[i] += 0.3f * kotlin.math.sin(2.0 * Math.PI * 4000.0 * i / rate).toFloat()
        val spec = ch.etasystems.amsel.core.spectrogram.MelSpectrogram.auto(rate)
        val (ganz, block) = kotlinx.coroutines.runBlocking {
            spec.compute(audio) to spec.compute(audio.copyOfRange(5 * rate, 12 * rate)).copy(startSec = 5f)
        }
        val a = AnnotationMetricsAnalyzer.compute(ganz, 7f, 9f, 1000f, 8000f)
        val b = AnnotationMetricsAnalyzer.compute(block, 7f, 9f, 1000f, 8000f)
        assertTrue(a.isComputed && b.isComputed)
        assertTrue(abs(a.peakFreqHz - 4000f) < 150f, "Peak ${a.peakFreqHz}")
        assertTrue(abs(a.peakFreqHz - b.peakFreqHz) < 1f, "Peak ${a.peakFreqHz} / ${b.peakFreqHz}")
        assertTrue(abs(a.bandwidth3dbHz - b.bandwidth3dbHz) < 5f)
        assertTrue(abs(a.snrDb - b.snrDb) < 0.2f, "SNR ${a.snrDb} / ${b.snrDb}")
    }

    @Test
    fun `Quantil-Auswahl liefert denselben Wert wie Sortieren`() {
        val rng = java.util.Random(3)
        repeat(50) {
            val n = 1 + rng.nextInt(400)
            // Wenige verschiedene Werte: viele Gleichstaende
            val werte = FloatArray(n) { if (rng.nextBoolean()) rng.nextInt(5).toFloat() else rng.nextFloat() }
            val sortiert = werte.sortedArray()
            val k = rng.nextInt(n)
            assertTrue(AnnotationMetricsAnalyzer.kleinstes(werte.copyOf(), k) == sortiert[k])
        }
    }

    @Test
    fun `viele Annotationen auf einem langen Spektrogramm bleiben schnell`() {
        // Aufloesung der Zoom-Ansicht fuer Vogelaufnahmen: 256 Mel-Baender, Hop 128 bei 48 kHz (375 Frames/s)
        val d = daten(nMels = 256, nFrames = 40_000, hop = 128)
        val start = System.nanoTime()
        var gerechnet = 0
        for (i in 0 until 200) {
            val t = i * 0.5f
            if (AnnotationMetricsAnalyzer.compute(d, t, t + 3f, 1500f, 8000f).isComputed) gerechnet++
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        println("Metriken fuer 200 Annotationen: $ms ms, $gerechnet gerechnet")
        assertTrue(gerechnet == 200)
        assertTrue(ms < 2500, "zu langsam: $ms ms")
    }
}
