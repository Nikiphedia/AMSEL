package ch.etasystems.amsel.data

import ch.etasystems.amsel.ui.annotation.kanalHinweis
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Der geladene Kanal wird im Projekt gespeichert; alte Projektdateien ohne das Feld laden weiter. */
class GeladenerKanalTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `alte Projektdatei ohne Kanal-Feld laedt mit null`() {
        val alt = """{"id":"a","originalFileName":"x.wav","durationSec":10.0,"sampleRate":48000}"""
        assertNull(json.decodeFromString(AudioReference.serializer(), alt).geladenerKanal)
    }

    @Test
    fun `geladener Kanal uebersteht Speichern und Laden`() {
        val ref = AudioReference(originalFileName = "x.wav", durationSec = 10f, sampleRate = 48000, geladenerKanal = 3)
        val text = json.encodeToString(AudioReference.serializer(), ref)
        assertEquals(3, json.decodeFromString(AudioReference.serializer(), text).geladenerKanal)
    }

    @Test
    fun `Hinweis in der Dateiliste`() {
        assertEquals("", kanalHinweis(0, 1))
        assertEquals("  -  Kanal 3 von 4", kanalHinweis(3, 4))
        assertEquals("  -  Mittel aus 2 Kanaelen", kanalHinweis(0, 2))
    }
}
