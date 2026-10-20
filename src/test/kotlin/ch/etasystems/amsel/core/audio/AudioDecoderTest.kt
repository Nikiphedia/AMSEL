package ch.etasystems.amsel.core.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AudioDecoderTest {

    private val dir: File = createTempDirectory("amsel-decoder-test").toFile()

    @AfterTest
    fun aufraeumen() {
        dir.deleteRecursively()
    }

    /** Schreibt eine WAV-Datei ueber die JDK-API (WAVE_FORMAT_PCM bzw. Float). */
    private fun schreibeWav(name: String, format: AudioFormat, data: ByteArray): File {
        val file = File(dir, name)
        val frames = data.size / format.frameSize
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(data), format, frames.toLong()), AudioFileFormat.Type.WAVE, file)
        return file
    }

    /**
     * Schreibt eine WAV-Datei im WAVE_FORMAT_EXTENSIBLE-Format mit LIST/INFO-Chunk
     * vor den Daten (so schreiben ffmpeg und viele Aufnahmegeraete 24-Bit-Dateien).
     * Regressionstest: mp3spi hielt solche Dateien faelschlich fuer MPEG.
     */
    private fun schreibeWavExtensible(name: String, sampleRate: Int, channels: Int, bits: Int, data: ByteArray): File {
        val blockAlign = channels * bits / 8
        val software = "Lavf60.16.100\u0000".toByteArray(Charsets.US_ASCII)  // 14 Bytes
        val listSize = 4 + 8 + software.size
        val header = ByteBuffer.allocate(68 + 8 + listSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(60 + 8 + listSize + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(40)
            putShort(0xFFFE.toShort()); putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * blockAlign); putShort(blockAlign.toShort()); putShort(bits.toShort())
            putShort(22); putShort(bits.toShort()); putInt(if (channels == 1) 0x4 else 0x3)
            // KSDATAFORMAT_SUBTYPE_PCM {00000001-0000-0010-8000-00AA00389B71}
            putInt(1); putShort(0); putShort(0x10)
            put(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
            put("LIST".toByteArray()); putInt(listSize); put("INFO".toByteArray())
            put("ISFT".toByteArray()); putInt(software.size); put(software)
            put("data".toByteArray()); putInt(data.size)
        }.array()
        return File(dir, name).apply { writeBytes(header + data) }
    }

    private fun pcm24(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 3)
        values.forEachIndexed { i, v ->
            out[i * 3] = v.toByte(); out[i * 3 + 1] = (v shr 8).toByte(); out[i * 3 + 2] = (v shr 16).toByte()
        }
        return out
    }

    @Test
    fun `16 Bit Mono wird exakt dekodiert`() {
        val werte = shortArrayOf(0, 1, -1, 32767, -32768, 12345)
        val bytes = ByteBuffer.allocate(werte.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { werte.forEach { putShort(it) } }.array()
        val file = schreibeWav("m16.wav", AudioFormat(48000f, 16, 1, true, false), bytes)

        val seg = AudioDecoder.decode(file)

        assertEquals(48000, seg.sampleRate)
        assertContentEquals(FloatArray(werte.size) { werte[it] / 32768f }, seg.samples)
    }

    @Test
    fun `24 Bit behaelt volle Praezision`() {
        // Werte, die sich nur in den unteren 8 Bit unterscheiden — bei 16 Bit gleich
        val werte = intArrayOf(0, 1, 2, 255, -1, -256, 8388607, -8388608)
        val file = schreibeWav("m24.wav", AudioFormat(48000f, 24, 1, true, false), pcm24(werte))

        val seg = AudioDecoder.decode(file)

        assertContentEquals(FloatArray(werte.size) { werte[it] / 8388608f }, seg.samples)
    }

    @Test
    fun `24 Bit WAV-Extensible wird erkannt (nicht als MPEG)`() {
        // Leises Rauschen wie bei einer ruhigen Feldaufnahme — genau das erkannte mp3spi
        // zuverlaessig als MPEG (0 Samples bei falscher Sample-Rate)
        val rnd = java.util.Random(1)
        val werte = IntArray(96000) { (rnd.nextGaussian() * 300).toInt() }
        val file = schreibeWavExtensible("ext24.wav", 96000, 1, 24, pcm24(werte))

        val seg = AudioDecoder.decode(file)

        assertEquals(96000, seg.sampleRate)
        assertContentEquals(FloatArray(werte.size) { werte[it] / 8388608f }, seg.samples)
    }

    @Test
    fun `Stereo wird zum Mittelwert gemischt`() {
        // Frames: (L, R)
        val links = intArrayOf(1000, -2000, 8388607)
        val rechts = intArrayOf(3000, 2000, 8388607)
        val interleaved = IntArray(6) { if (it % 2 == 0) links[it / 2] else rechts[it / 2] }
        val file = schreibeWav("s24.wav", AudioFormat(44100f, 24, 2, true, false), pcm24(interleaved))

        val seg = AudioDecoder.decode(file)

        assertContentEquals(FloatArray(3) { (links[it] / 8388608f + rechts[it] / 8388608f) / 2 }, seg.samples)
    }

    @Test
    fun `8 Bit unsigned und 32 Bit Float`() {
        val u8 = byteArrayOf(0, 128.toByte(), 255.toByte())
        val f8 = AudioDecoder.decode(schreibeWav("u8.wav", AudioFormat(8000f, 8, 1, false, false), u8))
        assertContentEquals(floatArrayOf(-1f, 0f, 127 / 128f), f8.samples)

        val floats = floatArrayOf(0.25f, -0.5f, 0.123456f)
        val fBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply { floats.forEach { putFloat(it) } }.array()
        val fFormat = AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48000f, 32, 1, 4, 48000f, false)
        assertContentEquals(floats, AudioDecoder.decode(schreibeWav("f32.wav", fFormat, fBytes)).samples)
    }

    @Test
    fun `decodeRange und decodeStreaming stimmen mit decode ueberein`() {
        val werte = IntArray(48000 * 3) { ((it * 7919) % 200000) - 100000 }
        val file = schreibeWav("lang.wav", AudioFormat(48000f, 24, 1, true, false), pcm24(werte))
        val voll = AudioDecoder.decode(file)

        val bereich = AudioDecoder.decodeRange(file, 1.25f, 2.0f)
        val start = (1.25f * 48000).toInt()
        assertContentEquals(voll.samples.copyOfRange(start, start + bereich.samples.size), bereich.samples)
        assertEquals(36000, bereich.samples.size)

        val gestreamt = ArrayList<Float>()
        val result = AudioDecoder.decodeStreaming(file, chunkSizeSamples = 10_000) { chunk, offset ->
            assertEquals(gestreamt.size.toLong(), offset)
            chunk.forEach { gestreamt.add(it) }
        }
        assertEquals(voll.samples.size.toLong(), result.totalSamples)
        assertContentEquals(voll.samples, gestreamt.toFloatArray())
    }

    @Test
    fun `PCM-Cache aus Datei und aus Segment liefern dieselben Samples`() {
        val werte = IntArray(48000 * 2) { (it % 5000) * 1000 - 2_500_000 }
        val file = schreibeWav("cache.wav", AudioFormat(48000f, 24, 1, true, false), pcm24(werte))
        val seg = AudioDecoder.decode(file)

        val ausDatei = PcmCacheFile.createFromAudioFile(file)
        val ausSegment = PcmCacheFile.createFromSegment(seg, "test")
        try {
            assertEquals(seg.samples.size.toLong(), ausDatei.totalSamples)
            assertContentEquals(seg.samples, ausDatei.readAll().samples)
            assertContentEquals(seg.samples, ausSegment.readAll().samples)
            assertContentEquals(seg.subRangeSec(0.5f, 1.5f).samples, ausDatei.readRange(0.5f, 1.5f).samples)

            // Wiederoeffnen liest Header korrekt
            val wieder = PcmCacheFile.open(ausDatei.cacheFile)
            assertEquals(48000, wieder.sampleRate)
            assertEquals(seg.samples.size.toLong(), wieder.totalSamples)
            wieder.close()
        } finally {
            ausDatei.delete()
            ausSegment.delete()
        }
    }

    /** Kopiert eine Test-Ressource in das Temp-Verzeichnis (Decoder braucht eine Datei). */
    private fun ressource(name: String): File {
        val ziel = File(dir, name)
        javaClass.getResourceAsStream("/audio/$name")!!.use { input -> ziel.outputStream().use { input.copyTo(it) } }
        return ziel
    }

    /** Prueft grob: ~1 s Laenge, hoerbarer Pegel, Grundfrequenz ~1 kHz (Nulldurchgaenge). */
    private fun pruefe1kHzTon(seg: AudioSegment, erwarteteRate: Int) {
        assertEquals(erwarteteRate, seg.sampleRate)
        kotlin.test.assertTrue(seg.durationSec in 0.95f..1.15f, "Dauer ${seg.durationSec}")
        // Mittleres Drittel (Encoder-Ein-/Ausschwingen ausblenden)
        val mitte = seg.samples.copyOfRange(seg.samples.size / 3, seg.samples.size * 2 / 3)
        val rms = kotlin.math.sqrt(mitte.sumOf { (it * it).toDouble() } / mitte.size)
        kotlin.test.assertTrue(rms > 0.05, "RMS $rms")
        var nulldurchgaenge = 0
        for (i in 1 until mitte.size) if ((mitte[i - 1] < 0f) != (mitte[i] < 0f)) nulldurchgaenge++
        val freq = nulldurchgaenge / 2.0 / (mitte.size.toDouble() / seg.sampleRate)
        kotlin.test.assertTrue(freq in 980.0..1020.0, "Frequenz $freq Hz")
    }

    @Test
    fun `Stereo-MP3 wird dekodiert (frueher Absturz in mp3spi)`() {
        pruefe1kHzTon(AudioDecoder.decode(ressource("stereo_1khz.mp3")), 44100)
    }

    @Test
    fun `M4A wird dekodiert (ffmpeg)`() {
        // Ohne ffmpeg faellt der Decoder auf JCodec zurueck, das hier nur Stille liefert
        org.junit.jupiter.api.Assumptions.assumeTrue(
            ch.etasystems.amsel.core.export.AudioExporter.ffmpegAvailable, "ffmpeg nicht im PATH"
        )
        pruefe1kHzTon(AudioDecoder.decode(ressource("stereo_1khz.m4a")), 48000)
    }
}
