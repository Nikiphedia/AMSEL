package ch.etasystems.amsel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Verwaltet Artensets (Regionfilter) fuer Download und Klassifikation.
 * Laedt Sets aus dem User-Verzeichnis (~/Documents/AMSEL/species/region_sets.json).
 * Falls dort nicht vorhanden oder veraltet, wird die Default-Datei aus den JAR-Ressourcen kopiert.
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

    /**
     * Initialisiert das Registry.
     * Kopiert region_sets.json aus JAR-Ressource falls User-Kopie nicht existiert
     * oder eine aeltere Version hat.
     *
     * @param amselDataDir Das AMSEL-Datenverzeichnis (z.B. ~/Documents/AMSEL)
     */
    fun initialize(amselDataDir: File) {
        val speciesDir = File(amselDataDir, "species")
        val userFile = File(speciesDir, "region_sets.json")

        // JAR-Version pruefen und bei Bedarf aktualisieren
        val jarVersion = getJarVersion()
        val needsCopy = if (!userFile.exists()) {
            true
        } else {
            val userVersion = getUserFileVersion(userFile)
            jarVersion > userVersion
        }

        if (needsCopy) {
            try {
                speciesDir.mkdirs()
                val stream = this::class.java.getResourceAsStream("/species/region_sets.json")
                if (stream != null) {
                    stream.use { input ->
                        userFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    logger.info("region_sets.json v{} aus JAR nach {} kopiert", jarVersion, userFile.absolutePath)
                } else {
                    logger.warn("region_sets.json nicht in JAR-Ressourcen gefunden")
                }
            } catch (e: Exception) {
                logger.warn("Fehler beim Kopieren von region_sets.json: {}", e.message)
            }
        }

        // Laden
        loadFromFile(userFile)
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

    /**
     * Liest die Version aus der JAR-Ressource.
     */
    private fun getJarVersion(): Int {
        return try {
            val stream = this::class.java.getResourceAsStream("/species/region_sets.json")
            if (stream == null) return 0
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = json.decodeFromString<RegionSetsFile>(text)
            data.version
        } catch (e: Exception) {
            logger.debug("Konnte JAR-Version nicht lesen: {}", e.message)
            0
        }
    }

    /**
     * Liest die Version aus der User-Datei.
     */
    private fun getUserFileVersion(file: File): Int {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val data = json.decodeFromString<RegionSetsFile>(text)
            data.version
        } catch (e: Exception) {
            logger.debug("Konnte User-File-Version nicht lesen: {}", e.message)
            0
        }
    }

    /**
     * Laedt die Region-Sets aus einer JSON-Datei.
     */
    private fun loadFromFile(file: File) {
        sets = try {
            if (!file.exists()) {
                logger.warn("region_sets.json nicht gefunden: {} — Fallback auf JAR-Ressource", file.absolutePath)
                loadFromResource()
            } else {
                val text = file.readText(Charsets.UTF_8)
                parseSetsJson(text)
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Laden von region_sets.json: {} — Fallback auf JAR-Ressource", e.message)
            loadFromResource()
        }
        logger.debug("RegionSetRegistry geladen: {} Sets", sets.size)
    }

    /**
     * Fallback: Laedt direkt aus der JAR-Ressource.
     */
    private fun loadFromResource(): List<RegionSet> {
        return try {
            val stream = this::class.java.getResourceAsStream("/species/region_sets.json")
            if (stream == null) {
                logger.warn("region_sets.json nicht in JAR-Ressourcen gefunden — Fallback auf 'all'")
                listOf(fallbackAllSet())
            } else {
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseSetsJson(text)
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Laden aus JAR-Ressource: {}", e.message)
            listOf(fallbackAllSet())
        }
    }

    /**
     * Parst den JSON-Text und baut die Sets-Liste auf.
     */
    private fun parseSetsJson(text: String): List<RegionSet> {
        val data = json.decodeFromString<RegionSetsFile>(text)
        return data.sets.map { entry ->
            RegionSet(
                id = entry.id,
                nameDe = entry.nameDe,
                nameEn = entry.nameEn,
                descriptionDe = entry.descriptionDe,
                species = entry.species.toSet()
            )
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
