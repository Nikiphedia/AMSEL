package ch.etasystems.amsel.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Referenz-Sammlung (Collection) — organisiert verifizierte Referenz-Sonogramme.
 *
 * Verzeichnisstruktur:
 *   %APPDATA%/AMSEL/references/
 *     collections.json               — Sammlungs-Index
 *     xeno/                          — Xeno-Canto (CC-lizenziert)
 *       index.json                   — Referenz-Eintraege
 *       audio/xc_12345.mp3           — Originales Audio
 *       sono/xc_12345.png            — Generiertes Referenz-Sonogramm
 *     glutz/                         — Persoenliche Sammlung
 *     eigene/                        — Eigene Aufnahmen
 */

@Serializable
data class ReferenceEntry(
    val id: String,                     // z.B. "xc_12345" oder "glutz_001"
    val scientificName: String,         // "Turdus merula"
    val commonName: String = "",        // "Amsel" / "Blackbird"
    val callType: String = "",          // "Gesang", "Ruf", "Alarm", "Flug", "Bettelruf"
    val quality: String = "",           // A-E oder "gut"/"mittel"
    val source: String = "",            // "Xeno-Canto", "Glutz", "Eigenaufnahme"
    val recordist: String = "",         // Aufnehmende Person
    val country: String = "",           // Land
    val location: String = "",          // Standort
    val notes: String = "",             // Freitextnotizen
    val verified: Boolean = false,      // Vom Benutzer verifiziert
    val clipStartSec: Float = 0f,       // Ausschnitt-Anfang im Original
    val clipEndSec: Float = 0f,         // Ausschnitt-Ende im Original
    val durationSec: Float = 0f,        // Original-Gesamtdauer
    val audioFile: String = "",         // Relativer Pfad zur Audio-Datei
    val sonogramFile: String = "",      // Relativer Pfad zum generierten Sonogramm-PNG
    val xcId: String = "",              // Xeno-Canto ID (falls XC-Quelle)
    val dateAdded: Long = System.currentTimeMillis()
)

@Serializable
data class CollectionInfo(
    val id: String,                     // "xeno", "glutz", "eigene"
    val name: String,                   // "Xeno-Canto"
    val description: String = "",       // "CC-lizenzierte Referenzen von xeno-canto.org"
    val isRedistributable: Boolean = false,  // Darf mit der App verteilt werden?
    val entryCount: Int = 0
)

@Serializable
data class CollectionsIndex(
    val collections: List<CollectionInfo> = listOf(
        CollectionInfo("xeno", "Xeno-Canto", "Referenzen aus Xeno-Canto (CC BY-NC-SA)", isRedistributable = true),
        CollectionInfo("glutz", "Glutz / Literatur", "Persoenliche Referenzen aus Fachliteratur"),
        CollectionInfo("eigene", "Eigene Aufnahmen", "Selbst aufgenommene Referenzen")
    )
)

@Serializable
data class CollectionIndex(
    val entries: List<ReferenceEntry> = emptyList()
)

/**
 * Verwaltet alle Referenz-Sammlungen.
 */
class ReferenceStore(
    private val baseDir: File = defaultRefDir()
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init { baseDir.mkdirs() }

    // ════════════════════════════════════════════════════
    // Sammlungen
    // ════════════════════════════════════════════════════

    fun getCollections(): List<CollectionInfo> {
        val file = File(baseDir, "collections.json")
        return try {
            if (file.exists()) {
                val idx = json.decodeFromString<CollectionsIndex>(file.readText())
                // Entry-Count aktualisieren
                idx.collections.map { c ->
                    c.copy(entryCount = getEntries(c.id).size)
                }
            } else {
                val default = CollectionsIndex()
                saveCollections(default)
                default.collections
            }
        } catch (_: Exception) {
            CollectionsIndex().collections
        }
    }

    fun addCollection(info: CollectionInfo) {
        val existing = getCollections().toMutableList()
        existing.removeAll { it.id == info.id }
        existing.add(info)
        saveCollections(CollectionsIndex(existing))
    }

    private fun saveCollections(index: CollectionsIndex) {
        File(baseDir, "collections.json").writeText(
            json.encodeToString(CollectionsIndex.serializer(), index)
        )
    }

    // ════════════════════════════════════════════════════
    // Eintraege pro Sammlung
    // ════════════════════════════════════════════════════

    fun getEntries(collectionId: String): List<ReferenceEntry> {
        val dir = File(baseDir, collectionId)
        val file = File(dir, "index.json")
        return try {
            if (file.exists()) {
                json.decodeFromString<CollectionIndex>(file.readText()).entries
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun getVerifiedEntries(collectionId: String): List<ReferenceEntry> =
        getEntries(collectionId).filter { it.verified }

    fun getAllVerifiedEntries(): List<ReferenceEntry> =
        getCollections().flatMap { getVerifiedEntries(it.id) }

    fun getEntriesForSpecies(scientificName: String): List<ReferenceEntry> =
        getCollections().flatMap { c ->
            getEntries(c.id).filter { it.scientificName.equals(scientificName, ignoreCase = true) }
        }

    fun addEntry(collectionId: String, entry: ReferenceEntry) {
        val dir = File(baseDir, collectionId)
        dir.mkdirs()
        File(dir, "audio").mkdirs()
        File(dir, "sono").mkdirs()

        val entries = getEntries(collectionId).toMutableList()
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
        saveEntries(collectionId, entries)
    }

    fun updateEntry(collectionId: String, entry: ReferenceEntry) {
        val entries = getEntries(collectionId).toMutableList()
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            entries[idx] = entry
            saveEntries(collectionId, entries)
        }
    }

    fun removeEntry(collectionId: String, entryId: String) {
        val entries = getEntries(collectionId).toMutableList()
        val entry = entries.find { it.id == entryId }
        if (entry != null) {
            entries.remove(entry)
            // Dateien loeschen
            val dir = File(baseDir, collectionId)
            if (entry.audioFile.isNotEmpty()) File(dir, entry.audioFile).delete()
            if (entry.sonogramFile.isNotEmpty()) File(dir, entry.sonogramFile).delete()
            saveEntries(collectionId, entries)
        }
    }

    private fun saveEntries(collectionId: String, entries: List<ReferenceEntry>) {
        val dir = File(baseDir, collectionId)
        dir.mkdirs()
        File(dir, "index.json").writeText(
            json.encodeToString(CollectionIndex.serializer(), CollectionIndex(entries))
        )
    }

    // ════════════════════════════════════════════════════
    // Dateizugriff
    // ════════════════════════════════════════════════════

    fun getCollectionDir(collectionId: String): File =
        File(baseDir, collectionId).also { it.mkdirs() }

    fun getAudioFile(collectionId: String, entry: ReferenceEntry): File? {
        if (entry.audioFile.isEmpty()) return null
        val f = File(baseDir, "$collectionId/${entry.audioFile}")
        return if (f.exists()) f else null
    }

    fun getSonogramFile(collectionId: String, entry: ReferenceEntry): File? {
        if (entry.sonogramFile.isEmpty()) return null
        val f = File(baseDir, "$collectionId/${entry.sonogramFile}")
        return if (f.exists()) f else null
    }

    // ════════════════════════════════════════════════════
    // Statistik
    // ════════════════════════════════════════════════════

    fun getTotalEntryCount(): Int = getCollections().sumOf { it.entryCount }

    fun getVerifiedCount(): Int = getCollections().sumOf { getVerifiedEntries(it.id).size }

    fun getSpeciesCount(): Int =
        getCollections().flatMap { getEntries(it.id) }.map { it.scientificName }.distinct().size

    companion object {
        fun defaultRefDir(): File {
            // Sichtbarer Ordner in Dokumente (nicht verstecktes AppData)
            val userHome = System.getProperty("user.home")
            return File(userHome, "Documents/AMSEL/references").also { it.mkdirs() }
        }
    }
}
