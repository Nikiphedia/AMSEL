package ch.etasystems.amsel.core.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Der Import dekodiert streaming in den PCM-Cache: das Ergebnis muss dem Dekodieren im Speicher gleichen. */
class PcmCacheStreamingTest {

    @Test
    fun `Streaming in den Cache liefert dieselben Samples wie decode`() {
        val datei = File(javaClass.getResource("/audio/stereo_1khz.mp3")!!.toURI())
        val erwartet = AudioDecoder.decode(datei)
        val cache = PcmCacheFile.createFromAudioFile(datei)
        try {
            assertEquals(erwartet.sampleRate, cache.sampleRate)
            assertEquals(erwartet.samples.size.toLong(), cache.totalSamples)
            assertContentEquals(erwartet.samples, cache.readAll().samples)
        } finally {
            cache.delete()
        }
    }
}
