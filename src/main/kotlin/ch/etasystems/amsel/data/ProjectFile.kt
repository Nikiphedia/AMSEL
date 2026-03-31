package ch.etasystems.amsel.data

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.core.model.AuditEntry
import ch.etasystems.amsel.core.model.VolumePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
 * Referenz auf die Audio-Datei + Chunk-Konfiguration.
 */
@Serializable
data class AudioReference(
    val originalFileName: String,
    val durationSec: Float,
    val sampleRate: Int,
    val chunkLengthMin: Float = 10f,
    val chunkOverlapSec: Float = 5f
)

/**
 * Komplettes AMSEL-Projekt — wird als .amsel.json gespeichert.
 */
@Serializable
data class AmselProject(
    val version: Int = 1,
    val metadata: ProjectMetadata = ProjectMetadata(),
    val audio: AudioReference,
    val annotations: List<Annotation> = emptyList(),
    val volumeEnvelope: List<VolumePoint> = emptyList(),
    val volumeEnvelopeActive: Boolean = false,
    val filterPreset: FilterPreset? = null,
    val auditLog: List<AuditEntry> = emptyList(),
    // Anzeige-Einstellungen
    val displayDbRange: Float = 10f,
    val displayGamma: Float = 1.0f,
    val isNormalized: Boolean = false,
    val normGainDb: Float = 0f
)

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

    /** Laedt Projekt aus .amsel.json Datei. */
    fun load(file: File): AmselProject {
        return json.decodeFromString(AmselProject.serializer(), file.readText())
    }

    /**
     * Erstellt den Dateinamen fuer die Projektdatei basierend auf dem Audio-Dateinamen.
     * audio.wav → audio.amsel.json
     */
    fun projectFileFor(audioFile: File): File {
        val baseName = audioFile.nameWithoutExtension
        return File(audioFile.parentFile, "$baseName.amsel.json")
    }
}
