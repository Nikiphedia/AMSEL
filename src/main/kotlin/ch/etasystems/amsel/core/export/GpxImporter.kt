package ch.etasystems.amsel.core.export

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/** Ein einzelner GPS-Trackpunkt */
data class GpxPunkt(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val zeitpunkt: Instant?
)

/** Ergebnis des GPX-Imports: Zuordnung Dateiname -> GPS-Koordinaten */
data class GpxZuordnung(
    val lat: Double,
    val lon: Double,
    val alt: Double,
    /** Zeitdifferenz zum naechsten Trackpunkt in Sekunden */
    val abstandSek: Long
)

object GpxImporter {

    /**
     * Liest alle Track- und Wegpunkte aus einer GPX-Datei.
     */
    fun lesePunkte(datei: File): List<GpxPunkt> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(datei)
        doc.documentElement.normalize()

        val punkte = mutableListOf<GpxPunkt>()

        // trkpt und wpt einlesen
        for (tag in listOf("trkpt", "wpt")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val lat = node.attributes.getNamedItem("lat")?.nodeValue?.toDoubleOrNull() ?: continue
                val lon = node.attributes.getNamedItem("lon")?.nodeValue?.toDoubleOrNull() ?: continue
                var alt = 0.0
                var zeitpunkt: Instant? = null

                val kinder = node.childNodes
                for (j in 0 until kinder.length) {
                    when (kinder.item(j).nodeName) {
                        "ele"  -> alt = kinder.item(j).textContent.trim().toDoubleOrNull() ?: 0.0
                        "time" -> zeitpunkt = parseZeit(kinder.item(j).textContent.trim())
                    }
                }
                punkte.add(GpxPunkt(lat, lon, alt, zeitpunkt))
            }
        }
        return punkte
    }

    /**
     * Findet den zeitlich naechsten Trackpunkt fuer ein Aufnahmedatum/-uhrzeit.
     *
     * @param punkte    GPX-Punkte (aus lesePunkte())
     * @param datum     Aufnahmedatum z.B. "2026-04-15"
     * @param uhrzeit   Aufnahmeuhrzeit z.B. "07:30" oder "07:30:00"
     * @return naechster Punkt oder null wenn kein Datum/Uhrzeit oder keine Punkte mit Zeitstempel
     */
    fun findeNaechstenPunkt(punkte: List<GpxPunkt>, datum: String, uhrzeit: String): GpxZuordnung? {
        if (datum.isBlank() || uhrzeit.isBlank()) return null

        val aufnahmeZeit = parseAufnahmeZeit(datum, uhrzeit) ?: return null
        val mitZeit = punkte.filter { it.zeitpunkt != null }
        if (mitZeit.isEmpty()) return null

        val naechster = mitZeit.minByOrNull { abs(it.zeitpunkt!!.epochSecond - aufnahmeZeit.epochSecond) }
            ?: return null

        val abstand = abs(naechster.zeitpunkt!!.epochSecond - aufnahmeZeit.epochSecond)
        return GpxZuordnung(naechster.lat, naechster.lon, naechster.alt, abstand)
    }

    private fun parseZeit(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (e: Exception) { null }

    private fun parseAufnahmeZeit(datum: String, uhrzeit: String): Instant? = try {
        val d = LocalDate.parse(datum.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        val zeitStr = uhrzeit.trim()
        val t = when (zeitStr.length) {
            5    -> LocalTime.parse(zeitStr, DateTimeFormatter.ofPattern("HH:mm"))
            else -> LocalTime.parse(zeitStr, DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
        d.atTime(t).toInstant(ZoneOffset.UTC)
    } catch (e: Exception) { null }
}
