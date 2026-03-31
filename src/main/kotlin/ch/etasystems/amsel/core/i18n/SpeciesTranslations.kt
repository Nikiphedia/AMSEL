package ch.etasystems.amsel.core.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Uebersetzungen fuer Vogel-/Tiernamen.
 * Key: Englischer Name (wie BirdNET ihn ausgibt)
 * Value: Deutscher Name
 *
 * Quelle: Schweizerische Vogelwarte / BirdLife Schweiz Nomenklatur
 */
object SpeciesTranslations {

    private val logger = LoggerFactory.getLogger(SpeciesTranslations::class.java)

    enum class Language { EN, DE, SCIENTIFIC }

    /** Deutsche Uebersetzungen — geladen aus /i18n/species_de.json */
    private val de: Map<String, String> by lazy {
        val stream = this::class.java.getResourceAsStream("/i18n/species_de.json")
            ?: error("species_de.json not found in resources")
        val jsonText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val jsonElement = Json.parseToJsonElement(jsonText).jsonObject
        val map = jsonElement.map { (key, value) -> key to value.jsonPrimitive.content }.toMap()
        logger.debug("Loaded {} species translations from species_de.json", map.size)
        map
    }

    /**
     * Uebersetzt einen Art-Namen in die gewuenschte Sprache.
     *
     * @param birdnetLabel Das BirdNET-Label (z.B. "Fringilla coelebs_Common Chaffinch")
     * @param language Zielsprache
     * @param showScientific Wissenschaftlichen Namen zusaetzlich anzeigen
     * @return Uebersetzter Name, ggf. mit wissenschaftlichem Namen in Klammern
     */
    fun translate(birdnetLabel: String, language: Language = Language.DE, showScientific: Boolean = false): String {
        val parts = birdnetLabel.split("_", limit = 2)
        val scientificName = if (parts.size >= 2) parts[0].trim() else ""
        val englishName = if (parts.size >= 2) parts[1].trim() else birdnetLabel.trim()

        val displayName = when (language) {
            Language.EN -> englishName
            Language.DE -> de[englishName] ?: englishName  // Fallback auf Englisch
            Language.SCIENTIFIC -> scientificName.ifEmpty { englishName }
        }

        return if (showScientific && scientificName.isNotEmpty() && language != Language.SCIENTIFIC) {
            "$displayName ($scientificName)"
        } else {
            displayName
        }
    }

    /**
     * Extrahiert nur den Art-Namen (ohne Konfidenz) aus einem Annotation-Label.
     * z.B. "Fringilla coelebs_Common Chaffinch (96%)" → "Fringilla coelebs_Common Chaffinch"
     */
    fun extractSpeciesFromLabel(label: String): String {
        return label.replace(Regex("\\s*\\(\\d+%\\)\\s*$"), "").trim()
    }

    /** Alle verfuegbaren deutschen Uebersetzungen */
    fun availableTranslations(): Map<String, String> = de

    /**
     * Bekannte Unterarten / kritische Artkomplexe.
     * Bei diesen Arten reicht BirdNET (Species-Level) nicht —
     * Sonogramm-Vergleich mit Unterart-Referenzen noetig.
     *
     * Key: BirdNET Species-Name
     * Value: Liste der relevanten Unterarten mit XC-Suchbegriff
     */
    val subspeciesComplexes = mapOf(
        "Common Chiffchaff" to listOf(
            SubspeciesInfo("collybita", "P. c. collybita", "Zilpzalp (Nominatform)", "Phylloscopus collybita collybita"),
            SubspeciesInfo("tristis", "P. c. tristis", "Taigazilpzalp", "Phylloscopus collybita tristis"),
            SubspeciesInfo("abietinus", "P. c. abietinus", "Nordischer Zilpzalp", "Phylloscopus collybita abietinus"),
        ),
        "Eurasian Treecreeper" to listOf(
            SubspeciesInfo("familiaris", "C. familiaris", "Waldbaumlaeufer", "Certhia familiaris"),
            // Verwechslungsart:
            SubspeciesInfo("brachydactyla", "C. brachydactyla", "Gartenbaumlaeufer", "Certhia brachydactyla"),
        ),
        "Willow Tit" to listOf(
            SubspeciesInfo("montanus", "P. montanus", "Weidenmeise (Alpenform)", "Poecile montanus"),
            // Verwechslungsart:
            SubspeciesInfo("palustris", "P. palustris", "Sumpfmeise", "Poecile palustris"),
        ),
        "Common Crossbill" to listOf(
            SubspeciesInfo("curvirostra", "L. curvirostra", "Fichtenkreuzschnabel", "Loxia curvirostra"),
            SubspeciesInfo("scotica", "L. scotica", "Schottischer Kreuzschnabel", "Loxia scotica"),
        ),
        "Yellow-browed Warbler" to listOf(
            SubspeciesInfo("inornatus", "P. inornatus", "Gelbbrauen-Laubsaenger", "Phylloscopus inornatus"),
            SubspeciesInfo("humei", "P. humei", "Humes Laubsaenger", "Phylloscopus humei"),
        ),
        "Western Yellow Wagtail" to listOf(
            SubspeciesInfo("flava", "M. f. flava", "Schafstelze (Nominatform)", "Motacilla flava flava"),
            SubspeciesInfo("flavissima", "M. f. flavissima", "Englische Schafstelze", "Motacilla flava flavissima"),
            SubspeciesInfo("thunbergi", "M. f. thunbergi", "Nordische Schafstelze", "Motacilla flava thunbergi"),
            SubspeciesInfo("feldegg", "M. f. feldegg", "Maskenstelze", "Motacilla flava feldegg"),
            SubspeciesInfo("cinereocapilla", "M. f. cinereocapilla", "Italienische Schafstelze", "Motacilla flava cinereocapilla"),
        ),
        "Common Redpoll" to listOf(
            SubspeciesInfo("flammea", "A. f. flammea", "Birkenzeisig", "Acanthis flammea"),
            SubspeciesInfo("cabaret", "A. f. cabaret", "Alpenbirkenzeisig", "Acanthis cabaret"),
        ),
        "Herring Gull" to listOf(
            SubspeciesInfo("argentatus", "L. argentatus", "Silbermoewe", "Larus argentatus"),
            SubspeciesInfo("michahellis", "L. michahellis", "Mittelmeermoewe", "Larus michahellis"),
            SubspeciesInfo("cachinnans", "L. cachinnans", "Steppenmoewe", "Larus cachinnans"),
        ),
    )

    data class SubspeciesInfo(
        val subspeciesKey: String,       // Kurzform fuer XC-Suche
        val abbreviation: String,        // Wissenschaftliche Kurzform
        val germanName: String,          // Deutscher Name
        val fullScientificName: String   // Vollstaendiger wissenschaftlicher Name
    )

    /**
     * Prueft ob eine Art einen bekannten Unterart-Komplex hat.
     * Wenn ja: BirdNET-Ergebnis ist nur Vorfilter, Sonogramm-Vergleich auf
     * Unterart-Ebene ist noetig.
     */
    fun hasSubspeciesComplex(englishName: String): Boolean {
        return subspeciesComplexes.containsKey(englishName)
    }

    /** Gibt Unterart-Info zurueck fuer die Detailansicht */
    fun getSubspeciesInfo(englishName: String): List<SubspeciesInfo>? {
        return subspeciesComplexes[englishName]
    }
}
