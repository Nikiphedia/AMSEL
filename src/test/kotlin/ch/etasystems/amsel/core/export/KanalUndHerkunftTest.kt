package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.classifier.EmbeddingDatabase
import ch.etasystems.amsel.core.classifier.ModellAdapterTabelle
import ch.etasystems.amsel.ui.annotation.kanalHinweis
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kanal in den Exporten, Herkunft eines Kandidaten, Hinweis auf gestoerte Kanaele, Art-Filter der Embedding-Suche. */
class KanalUndHerkunftTest {

    private fun ann(dateiId: String) = Annotation(
        audioFileId = dateiId, startTimeSec = 1f, endTimeSec = 2f, lowFreqHz = 100f, highFreqHz = 200f, label = "Waldkauz"
    )

    private fun config() = ReportExporter.ReportConfig(
        audioFiles = listOf(
            ReportExporter.AudioFileInfo("a", "vier.wav", 60f, kanal = 3, kanaele = 4),
            ReportExporter.AudioFileInfo("b", "stereo.wav", 60f, kanal = 0, kanaele = 2),
            ReportExporter.AudioFileInfo("c", "mono.wav", 60f)
        ),
        annotations = listOf(ann("a"), ann("b"), ann("c"))
    )

    @Test
    fun `CSV nennt den geladenen Kanal`() {
        val datei = File.createTempFile("bericht", ".csv").apply { deleteOnExit() }
        ReportExporter.exportCsv(datei, config())
        val zeilen = datei.readText().trimStart('﻿').lines()
        val spalte = zeilen[0].split(";").indexOf("Kanal")
        assertTrue(spalte > 0)
        val jeDatei = zeilen.drop(1).associate { it.split(";")[1] to it.split(";")[spalte] }
        assertEquals(mapOf("vier.wav" to "3", "stereo.wav" to "Mittel", "mono.wav" to ""), jeDatei)
    }

    @Test
    fun `Raven-Tabelle nennt den geladenen Kanal, sonst 1`() {
        val datei = File.createTempFile("raven", ".txt").apply { deleteOnExit() }
        RavenSelectionExporter.export(datei, config(), emptyMap())
        val zeilen = datei.readLines()
        val kopf = zeilen[0].split("\t")
        val kanal = kopf.indexOf("Channel")
        val name = kopf.indexOf("Begin File")
        val jeDatei = zeilen.drop(1).associate { it.split("\t")[name] to it.split("\t")[kanal] }
        assertEquals(mapOf("vier.wav" to "3", "stereo.wav" to "1", "mono.wav" to "1"), jeDatei)
    }

    @Test
    fun `Anzeigename der Modellart`() {
        assertEquals("", ModellAdapterTabelle.anzeigeName(""))
        assertEquals("BirdNET V3.0", ModellAdapterTabelle.anzeigeName("birdnet-v3"))
        assertEquals("Perch 2.0", ModellAdapterTabelle.anzeigeName("perch-v2"))
        assertEquals("BirdNET 2.4", ModellAdapterTabelle.anzeigeName(ModellAdapterTabelle.ART_BIRDNET_PYTHON))
        assertEquals("sitta-birdnet", ModellAdapterTabelle.anzeigeName("sitta-birdnet"))
    }

    @Test
    fun `Hinweis nennt gestoerte Kanaele`() {
        assertEquals("  -  Kanal 3 von 4 (Kanal 2 gestoert)", kanalHinweis(3, 4, listOf(2)))
        assertEquals("  -  Kanal 3 von 4", kanalHinweis(3, 4, emptyList()))
        assertEquals("", kanalHinweis(0, 1, listOf(2)))
    }

    @Test
    fun `Art-Filter der Embedding-Suche wirkt auch bei kleiner Datenbank`() {
        val db = EmbeddingDatabase(Files.createTempDirectory("amsel_emb").toFile().apply { deleteOnExit() })
        db.add("r1", "Strix aluco", floatArrayOf(1f, 0f))
        db.add("r2", "Bubo bubo", floatArrayOf(0.9f, 0.1f))
        db.add("r3", "Strix aluco", floatArrayOf(0f, 1f))
        val frage = floatArrayOf(1f, 0f)
        assertEquals(3, db.search(frage).size)
        assertEquals(listOf("r2"), db.search(frage, speciesFilter = setOf("Bubo bubo")).map { it.recordingId })
        assertEquals(listOf("r1", "r3"), db.search(frage, speciesFilter = setOf("Strix aluco")).map { it.recordingId })
        assertTrue(db.search(frage, speciesFilter = setOf("Parus major")).isEmpty())
    }
}
