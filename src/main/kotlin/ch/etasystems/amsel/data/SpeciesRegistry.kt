package ch.etasystems.amsel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Zentrale Species Master Table — Single Source of Truth fuer alle Artendaten in AMSEL.
 *
 * Laedt `species_master.json` aus dem User-Verzeichnis (~/Documents/AMSEL/species/).
 * Falls dort nicht vorhanden oder veraltet, wird die Default-Datei aus den JAR-Ressourcen kopiert.
 *
 * Taxon-uebergreifend: Voegel, Fledermaeuse, Amphibien, Heuschrecken etc.
 * Unterstuetzt 23 Sprachen (22 EU-Amtssprachen + Ukrainisch) seit v2.
 */
data class SpeciesInfo(
    val scientificName: String,
    val taxonGroup: String,        // "aves", "chiroptera", "amphibia", "orthoptera" etc.
    val order: String,
    val family: String,
    val commonNames: Map<String, String>,  // Sprachcode -> Name (z.B. "de" -> "Amsel")
    val iucnStatus: String?,
    val regionTags: Set<String>,
    val classifierSupport: Set<String>
)

object SpeciesRegistry {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Alle Taxa, indiziert nach scientific_name (mit Unterstrich) */
    private var speciesMap: Map<String, SpeciesInfo> = emptyMap()
    private var initialized = false

    /**
     * Initialisiert das Registry.
     * Kopiert species_master.json aus JAR-Ressource falls User-Kopie nicht existiert
     * oder eine aeltere Version hat.
     *
     * @param amselDataDir Das AMSEL-Datenverzeichnis (z.B. ~/Documents/AMSEL)
     */
    fun initialize(amselDataDir: File) {
        val speciesDir = File(amselDataDir, "species")
        val userFile = File(speciesDir, "species_master.json")

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
                val stream = this::class.java.getResourceAsStream("/species/species_master.json")
                if (stream != null) {
                    stream.use { input ->
                        userFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    logger.info("species_master.json v{} aus JAR nach {} kopiert", jarVersion, userFile.absolutePath)
                } else {
                    logger.warn("species_master.json nicht in JAR-Ressourcen gefunden")
                }
            } catch (e: Exception) {
                logger.warn("Fehler beim Kopieren von species_master.json: {}", e.message)
            }
        }

        // Laden
        loadFromFile(userFile)
    }

    /**
     * Liest die Version aus der JAR-Ressource (ohne vollstaendiges Parsen).
     */
    private fun getJarVersion(): Int {
        return try {
            val stream = this::class.java.getResourceAsStream("/species/species_master.json")
            if (stream == null) return 0
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = json.decodeFromString<SpeciesMasterFile>(text)
            data.version
        } catch (e: Exception) {
            logger.debug("Konnte JAR-Version nicht lesen: {}", e.message)
            0
        }
    }

    /**
     * Liest die Version aus der User-Datei (ohne vollstaendiges Parsen).
     */
    private fun getUserFileVersion(file: File): Int {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val data = json.decodeFromString<SpeciesMasterFile>(text)
            data.version
        } catch (e: Exception) {
            logger.debug("Konnte User-File-Version nicht lesen: {}", e.message)
            0
        }
    }

    /**
     * Laedt die Species-Daten aus einer JSON-Datei.
     */
    private fun loadFromFile(file: File) {
        speciesMap = try {
            if (!file.exists()) {
                logger.warn("species_master.json nicht gefunden: {}", file.absolutePath)
                // Fallback: Aus JAR-Ressource direkt laden
                loadFromResource()
            } else {
                val text = file.readText(Charsets.UTF_8)
                parseSpeciesJson(text)
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Laden von species_master.json: {} — Fallback auf JAR-Ressource", e.message)
            loadFromResource()
        }
        initialized = true
        logger.debug("SpeciesRegistry geladen: {} Taxa", speciesMap.size)
    }

    /**
     * Fallback: Laedt direkt aus der JAR-Ressource.
     */
    private fun loadFromResource(): Map<String, SpeciesInfo> {
        return try {
            val stream = this::class.java.getResourceAsStream("/species/species_master.json")
            if (stream == null) {
                logger.warn("species_master.json nicht in JAR-Ressourcen gefunden — Registry leer")
                emptyMap()
            } else {
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parseSpeciesJson(text)
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Laden aus JAR-Ressource: {}", e.message)
            emptyMap()
        }
    }

    /**
     * Parst den JSON-Text und baut die Species-Map auf.
     * Unterstuetzt sowohl v1 (common_name_de/en/fr) als auch v2 (common_names Map).
     */
    private fun parseSpeciesJson(text: String): Map<String, SpeciesInfo> {
        val data = json.decodeFromString<SpeciesMasterFile>(text)
        return data.taxa.associate { entry ->
            // v2: common_names Map direkt verwenden
            // v1 Rueckwaertskompatibilitaet: alte Felder in Map konvertieren
            val names = if (entry.commonNames.isNotEmpty()) {
                entry.commonNames.filterValues { it.isNotEmpty() }
            } else {
                buildMap {
                    if (entry.commonNameDe.isNotEmpty()) put("de", entry.commonNameDe)
                    if (entry.commonNameEn.isNotEmpty()) put("en", entry.commonNameEn)
                    if (entry.commonNameFr.isNotEmpty()) put("fr", entry.commonNameFr)
                }
            }

            val info = SpeciesInfo(
                scientificName = entry.scientificName,
                taxonGroup = entry.taxonGroup,
                order = entry.order,
                family = entry.family,
                commonNames = names,
                iucnStatus = entry.iucnStatus.ifEmpty { null },
                regionTags = entry.regionTags.toSet(),
                classifierSupport = entry.classifierSupport.toSet()
            )
            entry.scientificName to info
        }
    }

    // ================================================================
    // Abfragen
    // ================================================================

    /** Gibt die SpeciesInfo fuer einen wissenschaftlichen Namen zurueck (Unterstrich-Format). */
    fun getSpecies(scientificName: String): SpeciesInfo? {
        val normalized = scientificName.replace(' ', '_')
        return speciesMap[normalized]
    }

    /** Alle registrierten Arten. */
    fun getAllSpecies(): List<SpeciesInfo> = speciesMap.values.toList()

    /** Alle Arten einer Taxon-Gruppe (z.B. "aves", "chiroptera"). */
    fun getByTaxonGroup(group: String): List<SpeciesInfo> {
        return speciesMap.values.filter { it.taxonGroup == group }
    }

    /** Alle Arten mit einem bestimmten Region-Tag (z.B. "CH_breeding"). */
    fun getByRegionTag(tag: String): List<SpeciesInfo> {
        return speciesMap.values.filter { tag in it.regionTags }
    }

    /** Alle Arten die von einem bestimmten Classifier unterstuetzt werden. */
    fun getByClassifier(classifierId: String): List<SpeciesInfo> {
        return speciesMap.values.filter { classifierId in it.classifierSupport }
    }

    /** Set der wissenschaftlichen Namen die ein bestimmtes Modell unterstuetzt. */
    fun getSupportedSpeciesForModel(modelId: String): Set<String> {
        return speciesMap.values
            .filter { modelId in it.classifierSupport }
            .map { it.scientificName }
            .toSet()
    }

    // ================================================================
    // Name Resolution
    // ================================================================

    /**
     * Gibt den Anzeigenamen fuer eine Art zurueck.
     *
     * Fallback-Kette: locale -> "de" -> "en" -> scientific_name (mit Leerzeichen)
     *
     * @param scientificName Wissenschaftlicher Name (mit Unterstrich oder Leerzeichen)
     * @param locale Sprachcode: "de", "en", "fr", "it", "es", "uk" etc.
     * @return Anzeigename, nie null
     */
    fun getDisplayName(scientificName: String, locale: String = "de"): String {
        val normalized = scientificName.replace(' ', '_')
        val info = speciesMap[normalized]
            ?: return normalized.replace('_', ' ')  // Unbekannte Art: Unterstrich -> Leerzeichen

        val fallback = normalized.replace('_', ' ')
        return info.commonNames[locale]
            ?: info.commonNames["de"]
            ?: info.commonNames["en"]
            ?: fallback
    }

    /**
     * Sucht Arten nach Teilstring (case-insensitive) in allen verfuegbaren Namen.
     * Durchsucht: wissenschaftlicher Name, deutscher Name, englischer Name.
     * @param query Suchtext (min. 2 Zeichen)
     * @param locale Sprache fuer Anzeigenamen ("de", "en", "scientific")
     * @param maxResults Maximale Anzahl Ergebnisse
     * @return Liste von Paaren (scientificName, displayName)
     */
    fun searchSpecies(query: String, locale: String = "de", maxResults: Int = 10): List<Pair<String, String>> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase()
        return speciesMap.values
            .filter { info ->
                val sciName = info.scientificName.lowercase()
                val sciSpaced = sciName.replace('_', ' ')
                val displayName = getDisplayName(info.scientificName, locale).lowercase()
                val enName = getDisplayName(info.scientificName, "en").lowercase()
                sciName.contains(q) || sciSpaced.contains(q) || displayName.contains(q) || enName.contains(q)
            }
            .take(maxResults)
            .map { info ->
                val display = getDisplayName(info.scientificName, locale)
                val sciFormatted = info.scientificName.replace('_', ' ')
                Pair(info.scientificName, if (display != sciFormatted) "$display ($sciFormatted)" else display)
            }
    }

    // ================================================================
    // IUCN
    // ================================================================

    /** IUCN-Schutzstatus fuer eine Art (LC, NT, VU, EN, CR, EW, EX, DD, NE). */
    fun getIucnStatus(scientificName: String): String? {
        val normalized = scientificName.replace(' ', '_')
        return speciesMap[normalized]?.iucnStatus
    }

    // ================================================================
    // Reload (fuer zukuenftiges Update-Feature)
    // ================================================================

    /**
     * Laedt die Species-Daten neu aus dem User-Verzeichnis.
     * Wird in Zukunft nach einem Online-Update aufgerufen.
     */
    fun reload() {
        val userHome = System.getProperty("user.home")
        val userFile = File(userHome, "Documents/AMSEL/species/species_master.json")
        loadFromFile(userFile)
        logger.info("SpeciesRegistry neu geladen: {} Taxa", speciesMap.size)
    }
}

// ================================================================
// JSON-Serialisierung (kotlinx.serialization)
// ================================================================

@Serializable
private data class SpeciesMasterFile(
    val version: Int = 1,
    val generated: String = "",
    val sources: List<String> = emptyList(),
    val taxa: List<SpeciesMasterEntry> = emptyList()
)

@Serializable
private data class SpeciesMasterEntry(
    @SerialName("scientific_name") val scientificName: String,
    @SerialName("taxon_group") val taxonGroup: String,
    val order: String = "",
    val family: String = "",
    // v2: mehrsprachige Namen als Map
    @SerialName("common_names") val commonNames: Map<String, String> = emptyMap(),
    // v1 Rueckwaertskompatibilitaet: alte Einzelfelder
    @SerialName("common_name_de") val commonNameDe: String = "",
    @SerialName("common_name_en") val commonNameEn: String = "",
    @SerialName("common_name_fr") val commonNameFr: String = "",
    @SerialName("iucn_status") val iucnStatus: String = "",
    @SerialName("region_tags") val regionTags: List<String> = emptyList(),
    @SerialName("classifier_support") val classifierSupport: List<String> = emptyList()
)
