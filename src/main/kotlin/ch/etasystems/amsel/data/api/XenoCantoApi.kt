package ch.etasystems.amsel.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Xeno-Canto API v3 Client.
 * v3 ist seit 2025 Pflicht — v2 wurde abgeschaltet.
 * Braucht einen API-Key (kostenlos, https://xeno-canto.org/account).
 *
 * WICHTIG v3-Query-Syntax:
 * - Genus und Species GETRENNT: gen:Turdus sp:merula (NICHT sp:"Turdus merula")
 * - Qualitaet: q:A oder q:B (NICHT q>:B)
 * - Alle Queries muessen Tags verwenden
 */
class XenoCantoApi {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.NONE
        }
    }

    /** API-Key wird zur Laufzeit gesetzt (Settings-Dialog) */
    var apiKey: String = ""

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    /**
     * Suche nach Aufnahmen via Xeno-Canto API v3.
     * @param query v3-kompatible Query mit Tags (z.B. "gen:Turdus sp:merula")
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        perPage: Int = 100
    ): List<XenoCantoRecording> {
        check(apiKey.isNotBlank()) { "Xeno-Canto API-Key nicht gesetzt." }

        val response: XenoCantoResponse = client.get("https://xeno-canto.org/api/3/recordings") {
            parameter("query", query)
            parameter("key", apiKey)
            parameter("page", page)
            parameter("per_page", perPage.coerceIn(50, 500))
        }.body()

        return response.recordings
    }

    /**
     * Suche nach wissenschaftlichem Artnamen.
     * Erwartet Format "Genus species" z.B. "Turdus merula".
     * Wird in v3-Tags aufgespalten: gen:Turdus sp:merula
     */
    suspend fun searchBySpecies(
        species: String,
        minQuality: Char = ' ',
        country: String? = null,
        page: Int = 1,
        perPage: Int = 500
    ): List<XenoCantoRecording> {
        val parts = species.trim().split("\\s+".toRegex())
        val queryParts = mutableListOf<String>()

        // Genus + Species getrennt als Tags
        if (parts.size >= 2) {
            queryParts.add("gen:${parts[0]}")
            queryParts.add("sp:${parts[1]}")
        } else if (parts.size == 1) {
            // Nur Genus oder englischer Name
            queryParts.add("en:\"${parts[0]}\"")
        }

        // Qualitaet (v3: q:A, q:B, etc.)
        if (minQuality in 'A'..'B') {
            queryParts.add("q:$minQuality")
        }

        if (country != null) {
            queryParts.add("cnt:\"$country\"")
        }

        return search(queryParts.joinToString(" "), page, perPage)
    }

    /**
     * Breit-Suche (fuer Vergleich ohne spezifische Art).
     * Sucht nach Voegel mit guter Qualitaet.
     */
    suspend fun searchBroad(page: Int = 1): List<XenoCantoRecording> {
        return search("grp:birds q:A", page)
    }

    fun close() {
        client.close()
    }
}
