package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.OrtungsDaten
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sitta-Ortungstabellen decken eine ganze Mission ab. Der Import nimmt nur die Zeilen der
 * geladenen Audiodatei, rechnet auf deren Zeitachse um und behaelt alle Zusatzspalten.
 *
 * Testdaten: Kopfzeile und fuenf Zeilen einer echten Tabelle (eine vor Aufnahmebeginn,
 * drei aus dem Chunk 163156Z, eine aus dem Chunk 040156Z).
 */
class SittaOrtungImportTest {

    private val chunk = "20261110T163156Z-401005.wav"

    private fun tabelle(): File {
        val text = javaClass.getResource("/raven/sitta_ortung.txt")!!.readText()
        return File.createTempFile("sitta", ".txt").apply { deleteOnExit(); writeText(text) }
    }

    private fun nah(erwartet: Float, ist: Float) = assertTrue(abs(erwartet - ist) < 1e-3f, "erwartet $erwartet, ist $ist")

    @Test
    fun `nur Zeilen der geladenen Datei, Zeit ab Dateianfang`() {
        val e = RavenSelectionImporter.importiere(tabelle(), "id", chunk)
        assertEquals(3, e.annotationen.size)
        assertEquals(2, e.andereDatei)  // eine vor Aufnahmebeginn, eine aus anderem Chunk
        assertTrue(e.fehler.isEmpty())

        val erste = e.annotationen[0]
        nah(6.385063f, erste.startTimeSec)             // File Offset, nicht Begin Time 6.385539
        nah(6.385063f + 0.16f, erste.endTimeSec)       // Dauer aus End minus Begin
        nah(445.3f, erste.lowFreqHz)
        assertEquals("Unbekannt", erste.label)
        assertEquals("Strix aluco", e.annotationen[2].label)
    }

    @Test
    fun `alle Zusatzspalten kommen an`() {
        val ann = RavenSelectionImporter.importiere(tabelle(), "id", chunk).annotationen
        val o = assertNotNull(ann[0].ortung)
        assertEquals("T3157", o.tier)
        assertEquals(2, o.kanal)
        nah(7.06f, o.entfernungM!!)
        nah(0.06f, o.sigmaEntfernungM!!)
        nah(47.0f, o.kursGrad!!)
        nah(15.69f, o.hoehenwinkelGrad!!)
        nah(-67.1f, o.pegelDbfs!!)
        assertNull(o.pegelDbSpl)  // in der Tabelle leer
        assertEquals("4.65", o.spalten["X (m)"])
        assertEquals("-4.97", o.spalten["Y (m)"])
        assertEquals("3.47", o.spalten["Z (m)"])
        assertEquals("0.146", o.spalten["Sigma Azimuth (deg)"])
        assertEquals("1", o.spalten["Range Determined"])
        assertEquals("2026-11-10T16:32:02.649Z", o.spalten["UTC"])
        assertEquals("T3157  Kurs 47 Grad  7.1 +/- 0.06 m  -67.1 dBFS  Kanal 2", o.anzeigeZeile())

        // Entfernung nicht bestimmt: nur Richtung anzeigen
        assertEquals(
            "T1  Kurs 267 Grad",
            OrtungsDaten(mapOf("Target" to "T1", "Azimuth (deg)" to "267.15", "Distance (m)" to "1000.00", "Range Determined" to "0")).anzeigeZeile()
        )

        val kauz = assertNotNull(ann[2].ortung)
        assertEquals("birdnet-v2.4", kauz.spalten["Classifier"])
        assertEquals("tier", kauz.spalten["Species Source"])
        // Leere Zellen werden nicht gespeichert, nur bekannte Spalten uebernommen
        assertTrue(o.spalten.values.none { it.isEmpty() })
        assertTrue(OrtungsDaten.SPALTEN.containsAll(o.spalten.keys))
    }

    @Test
    fun `Import, Export, Import ergibt dieselben Werte`() {
        val erst = RavenSelectionImporter.importiere(tabelle(), "id", chunk).annotationen
        val ziel = File.createTempFile("sitta_export", ".txt").apply { deleteOnExit() }
        RavenSelectionExporter.export(
            ziel,
            ReportExporter.ReportConfig(
                audioFiles = listOf(ReportExporter.AudioFileInfo("id", chunk, 1800f)),
                annotations = erst
            ),
            emptyMap()
        )
        val kopf = ziel.readLines().first().split('\t')
        assertEquals(kopf.size, kopf.toSet().size, "doppelte Spaltennamen im Export")

        val zweit = RavenSelectionImporter.importiere(ziel, "id", chunk)
        assertEquals(0, zweit.andereDatei)
        assertEquals(erst.size, zweit.annotationen.size)
        for ((a, b) in erst.zip(zweit.annotationen)) {
            assertEquals(a.ortung, b.ortung)
            assertEquals(a.label, b.label)
            nah(a.startTimeSec, b.startTimeSec)
            nah(a.endTimeSec, b.endTimeSec)
        }
        // Die exportierte Tabelle gehoert zu genau einer Datei: fuer eine andere kommt nichts an
        assertEquals(0, RavenSelectionImporter.importiere(ziel, "id", "andere.wav").annotationen.size)
    }

    @Test
    fun `gewoehnliche Raven-Tabelle laedt wie bisher`() {
        val datei = File.createTempFile("raven", ".txt").apply {
            deleteOnExit()
            writeText(
                "Selection\tView\tChannel\tBegin Time (s)\tEnd Time (s)\tLow Freq (Hz)\tHigh Freq (Hz)\tSpecies\tBegin File\n" +
                    "1\tSpectrogram 1\t1\t12.5\t14.0\t2000\t6000\tKohlmeise\tirgendwas.wav\n"
            )
        }
        val e = RavenSelectionImporter.importiere(datei, "id", chunk)
        assertEquals(1, e.annotationen.size)
        assertEquals(0, e.andereDatei)
        nah(12.5f, e.annotationen[0].startTimeSec)
        assertEquals("Kohlmeise", e.annotationen[0].label)
        assertNull(e.annotationen[0].ortung)
    }

    @Test
    fun `Annotation ohne Ortung aus altem Projekt laedt, Ortung uebersteht Speichern`() {
        val json = Json { ignoreUnknownKeys = true }
        val alt = """{"startTimeSec":1.0,"endTimeSec":2.0,"lowFreqHz":100.0,"highFreqHz":200.0}"""
        assertNull(json.decodeFromString(Annotation.serializer(), alt).ortung)

        val mit = RavenSelectionImporter.importiere(tabelle(), "id", chunk).annotationen[0]
        val zurueck = json.decodeFromString(Annotation.serializer(), json.encodeToString(Annotation.serializer(), mit))
        assertEquals(mit.ortung, zurueck.ortung)
    }
}
