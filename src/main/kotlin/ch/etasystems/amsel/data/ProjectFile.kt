package ch.etasystems.amsel.data

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.model.AuditEntry
import ch.etasystems.amsel.core.model.VolumePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/**
 * Metadaten eines AMSEL-Projekts.
 */
@Serializable
data class ProjectMetadata(
    val location: String = "",
    val latitude: Float = 0f,
    val longitude: Float = 0f,
    val operator: String = "",
    val device: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/**
 * Aufnahme-Metadaten einer Audio-Datei.
 * Entweder manuell zugewiesen (Datum/Uhrzeit) oder aus Pirol-Datei (inkl. GPS).
 */
@Serializable
data class RecordingMetadata(
    /** Aufnahmedatum als ISO-8601 String: "2026-04-12" */
    val date: String = "",
    /** Aufnahme-Startzeit als "HH:mm:ss" oder "HH:mm" */
    val time: String = "",
    /** true wenn Metadaten aus Pirol-Geraet stammen (haben GPS) */
    val isPirolFile: Boolean = false,
    /** GPS Breitengrad (nur bei Pirol-Dateien, 0.0 = nicht gesetzt) */
    val latitude: Double = 0.0,
    /** GPS Laengengrad (nur bei Pirol-Dateien, 0.0 = nicht gesetzt) */
    val longitude: Double = 0.0,
    /** GPS Hoehe in Metern (nur bei Pirol-Dateien, 0.0 = nicht gesetzt) */
    val altitude: Double = 0.0
)

/**
 * Referenz auf die Audio-Datei + Slice-Konfiguration.
 */
@Serializable
data class AudioReference(
    /** Eindeutige ID fuer diese Audio-Datei im Projekt */
    val id: String = UUID.randomUUID().toString(),
    val originalFileName: String,
    val durationSec: Float,
    val sampleRate: Int,
    val sliceLengthMin: Float = 10f,
    val sliceOverlapSec: Float = 5f,
    /** Aufnahme-Metadaten (null = nicht gesetzt) */
    val recordingMeta: RecordingMetadata? = null
)

/**
 * Komplettes AMSEL-Projekt — wird als .amsel.json gespeichert.
 */
@Serializable
data class AmselProject(
    val version: Int = 2,
    val metadata: ProjectMetadata = ProjectMetadata(),
    /** Multi-Audio: Liste aller Audio-Dateien im Projekt.
     *  Fuer Backward-Compat (version=1): wird bei Migration aus dem alten "audio"-Feld befuellt. */
    val audioFiles: List<AudioReference> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val volumeEnvelope: List<VolumePoint> = emptyList(),
    val volumeEnvelopeActive: Boolean = false,
    val filterPreset: FilterPreset? = null,
    val auditLog: List<AuditEntry> = emptyList(),
    // Anzeige-Einstellungen (global, nicht pro Audio-Datei)
    val displayDbRange: Float = 10f,
    val displayGamma: Float = 1.0f,
    val isNormalized: Boolean = false,
    val normGainDb: Float = 0f
) {
    /** Komfort-Accessor: erstes Audio-File (fuer Single-File-Modus oder Rueckwaerts-Compat). */
    val primaryAudio: AudioReference? get() = audioFiles.firstOrNull()
}

/**
 * Laedt und speichert AMSEL-Projektdateien (.amsel.json).
 */
object ProjectStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Speichert Projekt als .amsel.json im gegebenen Verzeichnis. */
    fun save(project: AmselProject, projectFile: File) {
        val updated = project.copy(
            metadata = project.metadata.copy(lastModifiedAt = System.currentTimeMillis())
        )
        projectFile.writeText(json.encodeToString(AmselProject.serializer(), updated))
    }

    /** Laedt Projekt aus .amsel.json Datei. Migriert v1 → v2 automatisch (mit Backup). */
    fun load(file: File): AmselProject {
        val raw = file.readText()
        val project = json.decodeFromString(AmselProject.serializer(), raw)

        // Migration: v1 → v2
        // Erkennung: audioFiles leer UND altes "audio"-Feld im JSON vorhanden
        if (project.audioFiles.isEmpty()) {
            val jsonElement = json.parseToJsonElement(raw)
            val audioObj = jsonElement.jsonObject["audio"]
            if (audioObj != null) {
                // Legacy AudioReference parsen (ignoreUnknownKeys fuer alte Chunk-Felder)
                val legacyJson = Json { ignoreUnknownKeys = true }
                val legacyAudio = legacyJson.decodeFromJsonElement(AudioReference.serializer(), audioObj)
                val fileId = legacyAudio.id.ifBlank { UUID.randomUUID().toString() }
                val migratedAudio = legacyAudio.copy(id = fileId)

                // Annotations mit audioFileId versehen
                val migratedAnnotations = project.annotations.map { ann ->
                    if (ann.audioFileId.isEmpty()) ann.copy(audioFileId = fileId) else ann
                }

                val migrated = project.copy(
                    version = 2,
                    audioFiles = listOf(migratedAudio),
                    annotations = migratedAnnotations
                )

                // Backup erstellen und migriertes Projekt persistent zurueckschreiben
                try {
                    val backupFile = File(file.parentFile, file.name + ".v1.backup")
                    if (!backupFile.exists()) {
                        file.copyTo(backupFile, overwrite = false)
                    }
                    save(migrated, file)
                    System.err.println("[ProjectStore] Projekt migriert v1 → v2: ${file.name} (Backup: ${backupFile.name})")
                } catch (e: Exception) {
                    // Fehler beim Backup/Write-back duerfen den Load nicht blockieren
                    System.err.println("[ProjectStore] Migration-Write-back fehlgeschlagen: ${e.message}")
                }

                return migrated
            }
        }
        return project
    }

    /**
     * Erstellt den Dateinamen fuer die Projektdatei basierend auf dem Audio-Dateinamen.
     * audio.wav → audio.amsel.json
     */
    fun projectFileFor(audioFile: File): File {
        val baseName = audioFile.nameWithoutExtension
        return File(audioFile.parentFile, "$baseName.amsel.json")
    }

    /** Erstellt den Projekt-Dateinamen fuer einen Projekt-Ordner. */
    fun projectFileInDir(projectDir: File, projectName: String): File {
        return File(projectDir, "$projectName.amsel.json")
    }
}

/**
 * Sortierreihenfolge fuer Multi-Audio-Reports.
 */
@Serializable
enum class ReportSortOrder {
    /** Nach Aufnahmezeit sortiert (RecordingMetadata.date + time) */
    CHRONOLOGICAL,
    /** Alphabetisch nach Artname */
    ALPHABETICAL,
    /** Systematisch nach taxonomischer Reihenfolge (SpeciesRegistry-Index) */
    SYSTEMATIC
}
