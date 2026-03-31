package ch.etasystems.amsel.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class XenoCantoResponse(
    val numRecordings: String,
    val numSpecies: String,
    val page: Int,
    val numPages: Int,
    val recordings: List<XenoCantoRecording>
)

@Serializable
data class XenoCantoRecording(
    val id: String,
    val gen: String,              // Genus (z.B. "Turdus")
    val sp: String,               // Species (z.B. "merula")
    val ssp: String = "",         // Subspecies
    val grp: String = "",         // Gruppe (v3: "grp" statt "group")
    val en: String,               // Englischer Name
    val cnt: String,              // Land
    val loc: String,              // Standort
    val lat: String? = null,
    val lon: String? = null,      // v3: "lon" statt "lng"
    val q: String,                // Qualitaet (A-E)
    val length: String,           // Dauer "0:45"
    val file: String,             // Audio-Download URL
    @SerialName("file-name")
    val fileName: String = "",
    val sono: SonogramUrls,
    val type: String,             // "call", "song", etc.
    val rec: String               // Recordist
)

@Serializable
data class SonogramUrls(
    val small: String = "",       // Grayscale klein
    val med: String = "",         // Grayscale mittel
    val large: String = "",       // Farb-Sonogramm
    val full: String = ""         // Volle Groesse
)
