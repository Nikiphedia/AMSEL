package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.ProjectStore
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das automatische Anlegen eines Projekts fuer lange Dateien darf eine vorhandene Projektdatei
 * nicht ersetzen. Frueher wurde sie bei jedem Laden des Projekts durch ein leeres Projekt
 * ueberschrieben; ohne spaetere Aenderung gingen die Annotationen auf der Platte verloren.
 */
class ProjektNichtUeberschreibenTest {

    @Test
    fun `vorhandene Projektdatei bleibt unveraendert`() {
        val ordner = Files.createTempDirectory("amsel_projekt").toFile().apply { deleteOnExit() }
        val audio = File(ordner, "aufnahme.wav")
        val projektDatei = ProjectStore.projectFileFor(audio)
        val inhalt = """{"version":2,"annotations":[{"startTimeSec":1.0,"endTimeSec":2.0,"lowFreqHz":100.0,"highFreqHz":200.0,"label":"Waldkauz"}]}"""
        projektDatei.writeText(inhalt)

        val meldungen = ArrayList<String>()
        val manager = ProjectManager(onStatusUpdate = { meldungen.add(it) })
        manager.autoCreateProject(audio, durationSec = 1800f, sampleRate = 48_000, settings = AppSettings())

        assertEquals(inhalt, projektDatei.readText())
        assertNull(manager.state.value.projectFile)  // nicht angehaengt: ein spaeteres Speichern trifft die Datei nicht
        assertTrue(meldungen.single().contains("bleibt unveraendert"))
    }
}
