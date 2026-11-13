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
