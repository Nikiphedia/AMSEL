package ch.etasystems.amsel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Verwaltet Artensets (Regionfilter) fuer Download und Klassifikation.
 * Laedt Sets aus resources/species/region_sets.json.
 */
data class RegionSet(
    val id: String,
    val nameDe: String,
    val nameEn: String,
    val descriptionDe: String,
    val species: Set<String>  // leer = alle Arten
)

object RegionSetRegistry {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private var sets: List<RegionSet> = emptyList()

    init {
        sets = loadSetsFromResource()
    }

    fun loadSets(): List<RegionSet> = sets

    fun getSet(id: String): RegionSet? = sets.find { it.id == id }

    /**
     * Prueft ob eine Art im angegebenen Set enthalten ist.
     * Wenn setId == "all" oder das Set eine leere species-Liste hat → immer true.
     * Der species-Parameter kann mit Leerzeichen ("Parus major") oder
     * Unterstrich ("Parus_major") kommen — beides wird unterstuetzt.
     */
    fun isSpeciesInSet(setId: String, species: String): Boolean {
        val set = getSet(setId) ?: return true  // Set nicht gefunden → kein Filter
        if (set.species.isEmpty()) return true   // Leeres Set = alle Arten
        val normalized = species.replace(' ', '_')
        return normalized in set.species
    }

    /**
     * Gibt die Artenliste fuer ein Set zurueck.
     * Leere Liste = alle Arten (keine Einschraenkung).
     */
    fun getSpeciesForSet(setId: String): Set<String> {
        val set = getSet(setId) ?: return emptySet()
        return set.species
    }

    private fun loadSetsFromResource(): List<RegionSet> {
        return try {
            val stream = this::class.java.getResourceAsStream("/species/region_sets.json")
            if (stream == null) {
                logger.warn("region_sets.json nicht gefunden — Fallback auf 'all'")
                return listOf(fallbackAllSet())
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = json.decodeFromString<RegionSetsFile>(text)
            data.sets.map { entry ->
                RegionSet(
                    id = entry.id,
                    nameDe = entry.nameDe,
                    nameEn = entry.nameEn,
                    descriptionDe = entry.descriptionDe,
                    species = entry.species.toSet()
                )
            }.also {
                logger.debug("RegionSetRegistry geladen: {} Sets", it.size)
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Laden von region_sets.json: {}", e.message)
            listOf(fallbackAllSet())
        }
    }

    private fun fallbackAllSet() = RegionSet(
        id = "all",
        nameDe = "Alle Arten",
        nameEn = "All Species",
        descriptionDe = "Alle vom Modell unterstuetzten Arten",
        species = emptySet()
    )
}

// JSON-Struktur fuer region_sets.json
@Serializable
private data class RegionSetsFile(
    val version: Int = 1,
    val sets: List<RegionSetEntry> = emptyList()
)

@Serializable
private data class RegionSetEntry(
    val id: String,
    @SerialName("name_de") val nameDe: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("description_de") val descriptionDe: String,
    val species: List<String> = emptyList()
)
