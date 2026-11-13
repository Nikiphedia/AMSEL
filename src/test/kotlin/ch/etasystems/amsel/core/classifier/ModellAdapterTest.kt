package ch.etasystems.amsel.core.classifier

import ch.etasystems.amsel.core.annotation.SpeciesCandidate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Modellarten als Tabellenzeilen: Label-Formate, Umrechnung, Auswahl und Selbstpruefung. */
class ModellAdapterTest {

    private fun ordner(): File = Files.createTempDirectory("amsel_modelle").toFile().apply { deleteOnExit() }

    /** Winzige echte ONNX-Datei: y = Identity(x), Form [1, 4]. */
    private fun attrappe(ziel: File): File {
        ziel.writeBytes(javaClass.getResourceAsStream("/models/attrappe_4.onnx")!!.readBytes())
        return ziel
    }

    /** Modellart, die zur Attrappe passt: 4 Samples je Fenster, Ausgang wird verdoppelt. */
    private val attrappenArt = ModellAdapter(
        art = "attrappe", name = "Attrappe", version = "1", sampleRate = 4, fensterSek = 1f, fensterFest = true,
        labelFormat = LabelFormat.NAMENSLISTE, spitze = 0.25f, wert = { it * 2f }
    )

    @Test
    fun `unbekannte oder unvollstaendige Auswahl fuehrt zu BirdNET`() {
        val dir = ordner()
        assertSame(ModellAdapterTabelle.BIRDNET_V3, OnnxBirdNetV3.fuerAuswahl("gibt_es_nicht.onnx", dir).adapter)
        assertSame(ModellAdapterTabelle.BIRDNET_V3, OnnxBirdNetV3.fuerAuswahl("_python_bridge", dir).adapter)
        // Perch-Datei ohne Label-Datei: nicht verwendbar
        attrappe(File(dir, "perch_v2.onnx"))
        assertSame(ModellAdapterTabelle.BIRDNET_V3, OnnxBirdNetV3.fuerAuswahl("perch_v2.onnx", dir).adapter)
        // Mit Labels wird Perch gewaehlt
        File(dir, "perch_v2_labels.csv").writeText("inat2024_fsd50k\nStrix aluco\n")
        assertSame(ModellAdapterTabelle.PERCH_V2, OnnxBirdNetV3.fuerAuswahl("perch_v2.onnx", dir).adapter)
        // Fremde ONNX-Datei ohne BirdNET im Namen wird nicht als BirdNET geladen
        attrappe(File(dir, "efficientnet_embedding.onnx"))
        assertTrue(OnnxBirdNetV3.fuerAuswahl("efficientnet_embedding.onnx", dir).let { it.adapter === ModellAdapterTabelle.BIRDNET_V3 })
    }

    @Test
    fun `beide Label-Formate werden gelesen`() {
        val dir = ordner()
        val birdnet = File(dir, "b.csv").apply {
            writeText("﻿idx;id;sci_name;com_name;class;order\n0;x;Parus major;Great Tit;Aves;P\n1;y;Strix aluco;Tawny Owl;Aves;S\n")
        }
        val b = ModellAdapterTabelle.labelsLesen(birdnet, LabelFormat.BIRDNET_CSV)
        assertEquals(listOf("Parus major", "Strix aluco"), b.wissenschaftlich)
        assertEquals(listOf("Great Tit", "Tawny Owl"), b.trivial)
        assertTrue(b.istArt.all { it })

        val perch = File(dir, "p.csv").apply {
            writeText("inat2024_fsd50k\nAbavorana luctuosa\nAccordion\nCorvus monedula\nStrix aluco\n")
        }
        val p = ModellAdapterTabelle.labelsLesen(perch, LabelFormat.NAMENSLISTE, mapOf("Corvus monedula" to "Coloeus monedula"))
        // Kopfzeile (Namensraum) ist kein Label; die Position bleibt der Klassenindex
        assertEquals(listOf("Abavorana luctuosa", "Accordion", "Coloeus monedula", "Strix aluco"), p.wissenschaftlich)
        assertEquals(listOf(true, false, true, true), p.istArt.toList())  // "Accordion" ist eine Geraeuschklasse
    }

    @Test
    fun `Synonym-Tabelle traegt nur eindeutige Umbenennungen`() {
        val s = ModellAdapterTabelle.perchSynonyme
        assertEquals(210, s.size)
        assertNull(s["Acanthis flammea"])  // umbenennen=nein: mehrere BirdNET-Arten fallen zusammen
        assertTrue(s.keys.none { it.startsWith("#") || it == "perch" })
    }

    @Test
    fun `Umrechnung des Modellausgangs`() {
        assertTrue(abs(ModellAdapterTabelle.PERCH_V2.wert(10.309f) - 0.5f) < 0.001f)
        assertTrue(ModellAdapterTabelle.PERCH_V2.wert(0f) < 0.001f)
        assertEquals(0.42f, ModellAdapterTabelle.BIRDNET_V3.wert(0.42f))
        // Perch braucht den Resampler mit Uebergang um Nyquist, BirdNET bleibt bei der Vorgabe
        assertTrue(ModellAdapterTabelle.PERCH_V2.resamplerUmNyquist)
        assertTrue(!ModellAdapterTabelle.BIRDNET_V3.resamplerUmNyquist)
        assertSame(ModellAdapterTabelle.PERCH_V2, ModellAdapterTabelle.fuerDatei("Perch_V2.onnx"))
        assertSame(ModellAdapterTabelle.BIRDNET_V3, ModellAdapterTabelle.fuerDatei("irgendwas.onnx"))
    }

    @Test
    fun `Selbstpruefung weist Modell ab, dessen Ausgang nicht zur Label-Zahl passt`() {
        val dir = ordner()
        val modell = attrappe(File(dir, "attrappe.onnx"))
        val dreiNamen = File(dir, "drei.csv").apply { writeText("raum\nA a\nB b\nC c\n") }
        val k = OnnxBirdNetV3(modell.absolutePath, dreiNamen.absolutePath, attrappenArt)
        try {
            val grund = assertNotNull(k.pruefen())
            assertTrue(grund.contains("3 Werte"), grund)
        } finally { k.close() }

        // Falsche Fensterlaenge: die Attrappe nimmt 4 Samples, BirdNET rechnet mit 96000
        val vierNamen = File(dir, "vier.csv").apply { writeText("idx;sci_name;com_name\n0;A a;a\n1;B b;b\n2;C c;c\n3;D d;d\n") }
        val falsch = OnnxBirdNetV3(modell.absolutePath, vierNamen.absolutePath)
        try {
            assertTrue(assertNotNull(falsch.pruefen()).contains("4 Samples"))
        } finally { falsch.close() }

        assertEquals("Modelldatei fehlt: nichts.onnx", OnnxBirdNetV3.pruefeAuswahl("nichts.onnx", dir))
        assertNull(OnnxBirdNetV3.pruefeAuswahl("_python_bridge", dir))
    }

    @Test
    fun `passendes Modell besteht die Pruefung und rechnet mit Normierung, Umrechnung und Herkunft`() {
        val dir = ordner()
        val modell = attrappe(File(dir, "attrappe.onnx"))
        val labels = File(dir, "vier.csv").apply { writeText("raum\nA a\nGeraeusch\nC c\nD d\n") }
        val k = OnnxBirdNetV3(modell.absolutePath, labels.absolutePath, attrappenArt)
        try {
            assertNull(k.pruefen())
            assertEquals(3, k.speciesCount)
            // Ein Fenster [0.1, 0.9, 0.5, -1.0]: normiert auf Spitze 0.25, Identity, mal 2
            val treffer = runBlocking {
                k.classify(floatArrayOf(0.1f, 0.9f, 0.5f, -1.0f), sampleRate = 4, chunkDurationSec = 3f, minConfidence = 0f, topN = 0)
            }
            // Fensterlaenge kommt vom Modell (1 s), nicht vom Aufrufer (3 s); die Geraeuschklasse fehlt
            assertEquals(listOf("C c", "A a", "D d"), treffer.map { it.scientificName })
            assertTrue(abs(treffer[0].confidence - 0.25f) < 1e-5f)   // 0.5 / 1.0 * 0.25 * 2
            assertTrue(abs(treffer[1].confidence - 0.05f) < 1e-5f)
            assertTrue(treffer.all { it.modell == "attrappe" && it.endTime == 1f })
        } finally { k.close() }
    }

    @Test
    fun `alter Kandidat ohne Herkunft laedt, Herkunft uebersteht Speichern`() {
        val json = Json { ignoreUnknownKeys = true }
        val alt = """{"species":"Parus major_Great Tit","scientificName":"Parus major","confidence":0.8}"""
        assertEquals("", json.decodeFromString(SpeciesCandidate.serializer(), alt).modell)
        val neu = SpeciesCandidate("Strix aluco_Strix aluco", "Strix aluco", 0.9f, modell = "perch-v2")
        assertEquals("perch-v2", json.decodeFromString(SpeciesCandidate.serializer(), json.encodeToString(SpeciesCandidate.serializer(), neu)).modell)
    }
}
