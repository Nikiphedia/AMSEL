package ch.etasystems.amsel.ui.compare

import ch.etasystems.amsel.core.model.AuditEntry
import ch.etasystems.amsel.data.AmselProject
import ch.etasystems.amsel.data.AppSettings
import ch.etasystems.amsel.data.AudioReference
import ch.etasystems.amsel.data.ProjectMetadata
import ch.etasystems.amsel.data.ProjectStore
import ch.etasystems.amsel.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Verwaltet Projekt-Persistenz (Load/Save/AutoSave), Audit-Trail und Export-State.
 *
 * Architektur-Entscheidung: ProjectManager kuemmert sich um Serialisierung/Deserialisierung
 * und State-Verwaltung. Die Orchestrierung (Manager-States wiederherstellen/sammeln)
 * bleibt im CompareViewModel, da loadProject/saveProject auf ALLE Manager zugreifen muessen.
 */
class ProjectManager(
    private val onStateChanged: () -> Unit = {},
    private val onStatusUpdate: (String) -> Unit = {}
) {

    data class State(
        val projectFile: File? = null,
        val projectDir: File? = null,
        val projectDirty: Boolean = false,
        val auditLog: List<AuditEntry> = emptyList(),
        val lastExportFile: File? = null,
        val exportBlackAndWhite: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // ====================================================================
    // Audit-Trail
    // ====================================================================

    /** Fuegt einen Eintrag zum Audit-Trail hinzu. Max 500 Eintraege. */
    fun addAuditEntry(action: String, details: String) {
        _state.update { state ->
            val newLog = state.auditLog + AuditEntry(action = action, details = details)
            state.copy(auditLog = newLog.takeLast(500))
        }
        onStateChanged()
    }

    // ====================================================================
    // Dirty-Tracking
    // ====================================================================

    /** Markiert das Projekt als geaendert. */
    fun markDirty() {
        _state.update { it.copy(projectDirty = true) }
        onStateChanged()
    }

    // ====================================================================
    // Projekt erstellen
    // ====================================================================

    /** Erstellt automatisch eine Projektdatei fuer lange Audio-Dateien. */
    fun autoCreateProject(audioFile: File, durationSec: Float, sampleRate: Int, settings: AppSettings) {
        val projectFile = ProjectStore.projectFileFor(audioFile)
        val project = AmselProject(
            metadata = ProjectMetadata(
                location = settings.locationName,
                latitude = settings.locationLat,
                longitude = settings.locationLon,
                operator = settings.operatorName,
                device = settings.deviceName
            ),
            audioFiles = listOf(AudioReference(
                originalFileName = audioFile.name,
                durationSec = durationSec,
                sampleRate = sampleRate,
                sliceLengthMin = settings.sliceLengthMin,
                sliceOverlapSec = settings.sliceOverlapSec
            ))
        )
        try {
            ProjectStore.save(project, projectFile)
            _state.update { it.copy(projectFile = projectFile, projectDir = projectFile.parentFile, projectDirty = false) }
            saveLastProjectPath(projectFile)
            addAuditEntry("Projekt erstellt", projectFile.name)
        } catch (e: Exception) {
            System.err.println("[ProjectManager] Projekt-Erstellung fehlgeschlagen: ${e.message}")
        }
    }

    /** Erstellt ein neues Projekt in einem dedizierten Ordner. */
    fun createProject(projectDir: File, projectName: String, metadata: ProjectMetadata): File {
        projectDir.mkdirs()
        File(projectDir, "audio").mkdirs()
        File(projectDir, "export").mkdirs()

        val projectFile = File(projectDir, "$projectName.amsel.json")
        val project = AmselProject(
            version = 2,
            metadata = metadata,
            audioFiles = emptyList()
        )
        ProjectStore.save(project, projectFile)

        _state.update { it.copy(
            projectFile = projectFile,
            projectDir = projectDir,
            projectDirty = false,
            auditLog = emptyList()
        ) }
        saveLastProjectPath(projectFile)
        addAuditEntry("Projekt erstellt", projectFile.name)
        return projectFile
    }

    /** Gibt den Audio-Unterordner des Projekts zurueck (oder null wenn kein Projekt). */
    fun projectAudioDir(): File? {
        val dir = _state.value.projectDir ?: return null
        return File(dir, "audio").also { it.mkdirs() }
    }

    /** Gibt den Export-Unterordner des Projekts zurueck (oder null wenn kein Projekt). */
    fun projectExportDir(): File? {
        val dir = _state.value.projectDir ?: return null
        return File(dir, "export").also { it.mkdirs() }
    }

    /** Kopiert eine Audio-Datei in den audio/ Unterordner des Projekts. */
    fun copyAudioToProject(originalFile: File): File {
        val audioDir = projectAudioDir() ?: return originalFile
        val target = File(audioDir, originalFile.name)
        if (!target.exists()) {
            originalFile.copyTo(target, overwrite = false)
        }
        return target
    }

    // ====================================================================
    // Projekt laden (Deserialisierung)
    // ====================================================================

    /**
     * Deserialisiert eine .amsel.json Projektdatei.
     * @return Das geladene AmselProject oder null bei Fehler
     */
    fun deserializeProject(projectFile: File): AmselProject? {
        return try {
            ProjectStore.load(projectFile)
        } catch (e: Exception) {
            onStatusUpdate("Projekt-Fehler: ${e.message}")
            null
        }
    }

    /** Setzt den Projekt-State nach erfolgreichem Laden. */
    fun setProjectLoaded(projectFile: File, auditLog: List<AuditEntry>) {
        _state.update { it.copy(
            projectFile = projectFile,
            projectDir = projectFile.parentFile,
            projectDirty = false,
            auditLog = auditLog
        ) }
        addAuditEntry("Projekt geladen", projectFile.name)
        saveLastProjectPath(projectFile)
        onStateChanged()
    }

    // ====================================================================
    // Projekt speichern (Serialisierung)
    // ====================================================================

    /**
     * Serialisiert und speichert ein AmselProject in die Projektdatei.
     * Wird vom ViewModel aufgerufen, das den State von allen Managern gesammelt hat.
     */
    fun serializeProject(project: AmselProject) {
        val pf = _state.value.projectFile ?: return
        try {
            ProjectStore.save(project, pf)
            _state.update { it.copy(projectDirty = false) }
            saveLastProjectPath(pf)
            onStateChanged()
        } catch (e: Exception) {
            System.err.println("[ProjectManager] Auto-Save fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Manuelles Speichern: Setzt ggf. neue Projektdatei und markiert dirty.
     * Gibt die Projektdatei zurueck (fuer anschliessenden autoSave im ViewModel).
     */
    fun prepareManualSave(chosenFile: File?, audioFile: File): File? {
        val pf = chosenFile ?: _state.value.projectFile ?: ProjectStore.projectFileFor(audioFile)
        _state.update { it.copy(projectFile = pf, projectDirty = true) }
        onStateChanged()
        return pf
    }

    // ====================================================================
    // Audio-Metadaten
    // ====================================================================

    /** Aktualisiert die RecordingMetadata einer Audio-Datei im Projekt. */
    fun updateAudioMetadata(fileId: String, metadata: ch.etasystems.amsel.data.RecordingMetadata) {
        val pf = _state.value.projectFile ?: return
        try {
            val project = ProjectStore.load(pf)
            val updatedFiles = project.audioFiles.map { ref ->
                if (ref.id == fileId) ref.copy(recordingMeta = metadata) else ref
            }
            ProjectStore.save(project.copy(audioFiles = updatedFiles), pf)
            markDirty()
        } catch (e: Exception) {
            System.err.println("[ProjectManager] Metadaten-Update fehlgeschlagen: ${e.message}")
        }
    }

    // ====================================================================
    // Export-State
    // ====================================================================

    /** Toggle S/W-Export. */
    fun toggleExportBlackAndWhite() {
        _state.update { it.copy(exportBlackAndWhite = !it.exportBlackAndWhite) }
        onStateChanged()
    }

    /** Setzt die letzte Export-Datei (nach erfolgreichem Export). */
    fun setLastExportFile(file: File) {
        _state.update { it.copy(lastExportFile = file) }
        onStateChanged()
    }

    // ====================================================================
    // Settings-Persistenz
    // ====================================================================

    /** Speichert den Pfad des aktuellen Projekts in den Settings. */
    fun saveLastProjectPath(projectFile: File) {
        val settings = SettingsStore.load()
        SettingsStore.save(settings.copy(lastProjectPath = projectFile.absolutePath))
    }

    /** Loescht den gespeicherten Projekt-Pfad (beim Schliessen). */
    fun clearLastProjectPath() {
        val settings = SettingsStore.load()
        SettingsStore.save(settings.copy(lastProjectPath = ""))
    }

    // ====================================================================
    // Lifecycle
    // ====================================================================

    /** Setzt den komplettten State zurueck. */
    fun reset() {
        _state.update { State() }
        onStateChanged()
    }

    /** Setzt Projekt-Referenz zurueck fuer neuen Audio-Import (ohne Audit-Log zu loeschen). */
    fun resetForNewAudio() {
        _state.update { it.copy(projectFile = null, projectDir = null, projectDirty = false) }
    }

    /** Stellt State aus einem geladenen Projekt wieder her. */
    fun restoreFromProject(projectFile: File, auditLog: List<AuditEntry>) {
        _state.update { State(
            projectFile = projectFile,
            projectDir = projectFile.parentFile,
            projectDirty = false,
            auditLog = auditLog
        ) }
        onStateChanged()
    }
}
