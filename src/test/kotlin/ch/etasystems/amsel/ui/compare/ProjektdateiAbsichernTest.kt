package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.data.AmselProject
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.AudioReference
import ch.etasystems.amsel.data.ProjectMetadata
import ch.etasystems.amsel.data.ProjectStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sicherungskopie beim Speichern, vorhandenes Projekt beim Import, Metadaten bleiben erhalten. */
class ProjektdateiAbsichernTest {

    private fun ordner(): File = Files.createTempDirectory("amsel_absichern").toFile().apply { deleteOnExit() }

    private fun projekt(label: String, datei: String = "aufnahme.wav") = AmselProject(
        audioFiles = listOf(AudioReference(id = "a", originalFileName = datei, durationSec = 1800f, sampleRate = 48_000)),
        annotations = listOf(Annotation(audioFileId = "a", startTimeSec = 1f, endTimeSec = 2f, lowFreqHz = 100f, highFreqHz = 200f, label = label))
    )

    @Test
    fun `Sicherung haelt den Stand vor der Sitzung fest`() {
        val datei = File(ordner(), "p.amsel.json")
        val sicherung = ProjectStore.backupFileFor(datei)
        val vorher = "{\"version\":2,\"annotations\":[]}"
        datei.writeText(vorher)

        ProjectStore.save(projekt("Waldkauz"), datei)
        assertEquals(vorher, sicherung.readText())
        assertEquals("Waldkauz", ProjectStore.load(datei).annotations.single().label)

        // Zweites Speichern in derselben Sitzung ersetzt die Sicherung nicht
        ProjectStore.save(projekt("Uhu"), datei)
        assertEquals(vorher, sicherung.readText())
        assertEquals("Uhu", ProjectStore.load(datei).annotations.single().label)
        assertFalse(File(datei.parentFile, datei.name + ".tmp").exists())
    }

    @Test
    fun `neue Projektdatei bekommt keine Sicherung`() {
        val datei = File(ordner(), "neu.amsel.json")
        ProjectStore.save(projekt("Waldkauz"), datei)
        ProjectStore.save(projekt("Uhu"), datei)
        assertFalse(ProjectStore.backupFileFor(datei).exists())
    }

    @Test
    fun `vorhandenes Projekt neben der Audiodatei wird gefunden`() {
        val dir = ordner()
        val audio = File(dir, "aufnahme.wav")
        val manager = ProjectManager()
        assertNull(manager.vorhandenesProjektFuer(audio))

        val projektDatei = ProjectStore.projectFileFor(audio)
        ProjectStore.save(projekt("Waldkauz"), projektDatei)
        assertEquals(projektDatei, manager.vorhandenesProjektFuer(audio))
        assertEquals(projektDatei, manager.vorhandenesProjektFuer(File(dir, "AUFNAHME.WAV")))

        // Gleicher Name, aber das Projekt gehoert zu einer anderen Audiodatei
        assertNull(manager.vorhandenesProjektFuer(File(dir, "aufnahme.flac")))

        // Nicht lesbare Projektdatei: kein Projekt, kein Absturz
        projektDatei.writeText("kein json")
        assertNull(manager.vorhandenesProjektFuer(audio))
    }

    @Test
    fun `Metadaten eines gespeicherten Projekts bleiben beim Speichern erhalten`() {
        val manager = ProjectManager()
        val settings = AppSettings(locationName = "Einstellung", operatorName = "Neu")
        val alt = AmselProject(metadata = ProjectMetadata(location = "Reussdelta", operator = "Alt", createdAt = 1234L, notes = "Notiz"))

        val behalten = manager.metadatenFuerSpeichern(alt, settings)
        assertEquals(1234L, behalten.createdAt)
        assertEquals("Reussdelta", behalten.location)
        assertEquals("Notiz", behalten.notes)

        val neu = manager.metadatenFuerSpeichern(null, settings)
        assertEquals("Einstellung", neu.location)
        assertEquals("Neu", neu.operator)
        assertTrue(neu.createdAt > 1234L)
    }
}
