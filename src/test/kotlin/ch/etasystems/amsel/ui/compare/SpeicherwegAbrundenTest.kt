package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.annotation.SpeciesCandidate
import ch.etasystems.amsel.core.export.ReportExporter
import ch.etasystems.amsel.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Neuaufbau nur bei Einstellungen, die das Audio betreffen; Spalte "Quelle" nennt die Modellfamilie. */
class SpeicherwegAbrundenTest {

    @Test
    fun `Aufbau haengt nur an den Audio-Einstellungen`() {
        val basis = AppSettings()
        // Aenderungen ohne Einfluss auf die geladene Datei
        assertEquals(aufbauSchluessel(basis), aufbauSchluessel(basis.copy(operatorName = "x", locationName = "y")))
        assertEquals(aufbauSchluessel(basis), aufbauSchluessel(basis.copy(importKanal = 2)))
        // Aenderungen, die einen Neuaufbau brauchen
        assertNotEquals(aufbauSchluessel(basis), aufbauSchluessel(basis.copy(maxFrequencyHz = basis.maxFrequencyHz + 1000)))
        assertNotEquals(aufbauSchluessel(basis), aufbauSchluessel(basis.copy(sliceLengthMin = basis.sliceLengthMin + 1f)))
        assertNotEquals(aufbauSchluessel(basis), aufbauSchluessel(basis.copy(minDisplayDurationSec = basis.minDisplayDurationSec + 1f)))
    }

    @Test
    fun `Quelle nennt die Modellfamilie`() {
        fun ann(auto: Boolean) = Annotation(
            audioFileId = "a", startTimeSec = 1f, endTimeSec = 2f, lowFreqHz = 100f, highFreqHz = 200f,
            label = "x", isBirdNetDetection = auto
        )
        fun kandidat(modell: String) = SpeciesCandidate(species = "x", scientificName = "x", confidence = 0.5f, modell = modell)
        assertEquals("Manuell", ReportExporter.quelle(ann(false), kandidat("perch-v2")))
        assertEquals("Perch", ReportExporter.quelle(ann(true), kandidat("perch-v2")))
        assertEquals("BirdNET", ReportExporter.quelle(ann(true), kandidat("birdnet-v3")))
        assertEquals("BirdNET", ReportExporter.quelle(ann(true), kandidat("")))
        assertEquals("BirdNET", ReportExporter.quelle(ann(true), null))
    }
}
