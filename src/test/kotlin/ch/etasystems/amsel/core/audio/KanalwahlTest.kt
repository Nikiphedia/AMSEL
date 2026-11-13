package ch.etasystems.amsel.core.audio

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mehrkanal-Aufnahmen (z.B. vier raeumlich getrennte Mikrofone) duerfen nicht einfach gemittelt
 * werden: dabei loeschen sich Frequenzen gegenseitig aus. Der Decoder muss einen Kanal waehlen koennen.
 */
class KanalwahlTest {

    private val rate = 48_000
    private val frames = rate  // 1 Sekunde

    // Je Kanal eine andere Frequenz und Amplitude. Kanal 3 ist der lauteste.
    private val frequenzen = doubleArrayOf(500.0, 1000.0, 2000.0, 4000.0)
    private val amplituden = doubleArrayOf(0.1, 0.2, 0.5, 0.1)

    private fun wert(kanal: Int, i: Int): Double =
        amplituden[kanal] * sin(2.0 * PI * frequenzen[kanal] * i / rate)

    /** Schreibt eine WAV-Datei (16 Bit) mit den ersten [kanaele] Kanaelen. */
    private fun wavDatei(kanaele: Int): File {
        val bytes = ByteArray(frames * kanaele * 2)
        for (i in 0 until frames) {
            for (k in 0 until kanaele) {
                val v = (wert(k, i) * 32767.0).toInt()
                val o = (i * kanaele + k) * 2
                bytes[o] = (v and 0xFF).toByte()
                bytes[o + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        val format = AudioFormat(rate.toFloat(), 16, kanaele, true, false)
        val datei = File.createTempFile("kanalwahl", ".wav").apply { deleteOnExit() }
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()), AudioFileFormat.Type.WAVE, datei)
        return datei
    }

    private fun assertSignal(erwartet: (Int) -> Double, ist: FloatArray) {
        assertEquals(frames, ist.size)
        var maxAbweichung = 0.0
        for (i in ist.indices) maxAbweichung = maxOf(maxAbweichung, abs(ist[i] - erwartet(i)))
        assertTrue(maxAbweichung < 1e-3, "groesste Abweichung $maxAbweichung")
    }

    private val mittelwert: (Int) -> Double = { i -> (0 until 4).sumOf { wert(it, i) } / 4.0 }

    @Test
    fun `bestimmter Kanal liefert genau dessen Signal`() {
        val datei = wavDatei(4)
        assertSignal({ wert(2, it) }, AudioDecoder.decode(datei, kanal = 3).samples)
        assertSignal({ wert(0, it) }, AudioDecoder.decode(datei, kanal = 1).samples)
    }

    @Test
    fun `Mittelwert liefert alle Kanaele mit einem Viertel der Amplitude`() {
        assertSignal(mittelwert, AudioDecoder.decode(wavDatei(4), kanal = AudioDecoder.KANAL_MITTEL).samples)
    }

    @Test
    fun `lautester Kanal wird gefunden`() {
        assertSignal({ wert(2, it) }, AudioDecoder.decode(wavDatei(4), kanal = AudioDecoder.KANAL_LAUTESTER).samples)
    }

    @Test
    fun `Automatik nimmt bei mehr als zwei Kanaelen den lautesten`() {
        assertSignal({ wert(2, it) }, AudioDecoder.decode(wavDatei(4)).samples)
    }

    @Test
    fun `Streaming meldet den gewaehlten Kanal`() {
        val vier = wavDatei(4)
        assertEquals(3, AudioDecoder.decodeStreaming(vier) { _, _ -> }.kanal)  // Automatik: lautester
        assertEquals(0, AudioDecoder.decodeStreaming(vier, kanal = AudioDecoder.KANAL_MITTEL) { _, _ -> }.kanal)
        val stereo = AudioDecoder.decodeStreaming(wavDatei(2)) { _, _ -> }
        assertEquals(0, stereo.kanal)
        assertEquals(2, stereo.kanaele)
    }

    /** 6 s, 4 Kanaele: Rufe (Sinus 2 kHz) nur in jeder dritten halben Sekunde, dazu leises Rauschen. */
    private fun wavMitRufen(rufAmplitude: DoubleArray, rauschen: DoubleArray): File {
        val n = rate * 6
        val zufall = java.util.Random(1)
        val bytes = ByteArray(n * 4 * 2)
        for (i in 0 until n) {
            val ruf = (i / (rate / 2)) % 3 == 0
            for (k in 0 until 4) {
                val v = (if (ruf) rufAmplitude[k] * sin(2.0 * PI * 2000.0 * i / rate) else 0.0) +
                    rauschen[k] * zufall.nextGaussian()
                val w = (v.coerceIn(-1.0, 1.0) * 32767.0).toInt()
                val o = (i * 4 + k) * 2
                bytes[o] = (w and 0xFF).toByte()
                bytes[o + 1] = ((w shr 8) and 0xFF).toByte()
            }
        }
        val datei = File.createTempFile("kanalstoerung", ".wav").apply { deleteOnExit() }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(rate.toFloat(), 16, 4, true, false), n.toLong()),
            AudioFileFormat.Type.WAVE, datei
        )
        return datei
    }

    @Test
    fun `gestoerter Kanal wird ausgelassen, danach der lauteste genommen`() {
        // Kanal 2 rauscht stark und waere nach Energie der lauteste; Kanal 4 hat die lautesten Rufe
        val datei = wavMitRufen(doubleArrayOf(0.1, 0.1, 0.2, 0.4), doubleArrayOf(0.001, 0.2, 0.001, 0.001))
        assertEquals(4, AudioDecoder.decodeStreaming(datei) { _, _ -> }.kanal)
    }

    @Test
    fun `lauter Ruf allein gilt nicht als Stoerung`() {
        // Kanal 4 ist viel lauter, aber nur waehrend der Rufe: Grundrauschen gleich, also nicht gestoert
        val datei = wavMitRufen(doubleArrayOf(0.02, 0.02, 0.02, 0.8), doubleArrayOf(0.001, 0.001, 0.001, 0.001))
        assertEquals(4, AudioDecoder.decodeStreaming(datei) { _, _ -> }.kanal)
    }

    @Test
    fun `Grundrauschen innerhalb 6 dB gilt nicht als Stoerung`() {
        // Kanal 2 rauscht 1,5-fach (3,5 dB) staerker und bleibt waehlbar
        val bloecke = List(30) { i -> doubleArrayOf(1.0, 2.25, 1.0, 1.0).map { it * (1 + i % 3) }.toDoubleArray() }
        assertTrue(AudioDecoder.gestoerteKanaele(bloecke, 4).none { it })
        val stark = List(30) { i -> doubleArrayOf(1.0, 5.0, 1.0, 1.0).map { it * (1 + i % 3) }.toDoubleArray() }
        assertEquals(listOf(false, true, false, false), AudioDecoder.gestoerteKanaele(stark, 4).toList())
        // Zu wenige Bloecke oder nur zwei Kanaele: keine Aussage
        assertTrue(AudioDecoder.gestoerteKanaele(stark.take(5), 4).none { it })
        assertTrue(AudioDecoder.gestoerteKanaele(List(30) { doubleArrayOf(1.0, 9.0) }, 2).none { it })
    }

    @Test
    fun `Automatik mittelt Stereo wie bisher`() {
        assertSignal({ i -> (wert(0, i) + wert(1, i)) / 2.0 }, AudioDecoder.decode(wavDatei(2)).samples)
    }

    @Test
    fun `nicht vorhandener Kanal faellt auf die Automatik zurueck`() {
        assertSignal({ wert(2, it) }, AudioDecoder.decode(wavDatei(4), kanal = 7).samples)
    }

    @Test
    fun `Streaming liefert denselben Kanal wie decode`() {
        val datei = wavDatei(4)
        val teile = mutableListOf<FloatArray>()
        val ergebnis = AudioDecoder.decodeStreaming(datei, chunkSizeSamples = 10_000, kanal = 4) { chunk, _ -> teile.add(chunk) }
        assertEquals(4, ergebnis.kanal)
        assertEquals(4, ergebnis.kanaele)
        val alle = FloatArray(teile.sumOf { it.size })
        var pos = 0
        for (t in teile) { t.copyInto(alle, pos); pos += t.size }
        assertSignal({ wert(3, it) }, alle)
    }
}
