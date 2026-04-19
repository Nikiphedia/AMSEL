package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.data.RecordingMetadata
import java.io.File

/**
 * Ergebnis eines Raven-Imports.
 *
 * @property annotationen Die erfolgreich geparsten Annotationen.
 * @property gpsMetadata  Erste gueltige GPS-Zeile als RecordingMetadata (null wenn keine GPS-Daten vorhanden).
 * @property fehler       Hinweise zu uebersprungenen Zeilen bzw. fehlenden Pflichtspalten.
 */
data class RavenImportResult(
    val annotationen: List<Annotation>,
    val gpsMetadata: RecordingMetadata?,
    val fehler: List<String>
)

/**
 * Liest eine Raven Pro Selection Table (.txt, TSV, UTF-8).
 * Gegenstueck zu [RavenSelectionExporter].
 *
 * Erwartete Pflichtspalten im Header:
 *   - Begin Time (s), End Time (s), Low Freq (Hz), High Freq (Hz)
 *
 * Optionale Spalten:
 *   - Species, Scientific Name, Confidence, Status, Notes
 *   - GPS: Latitude, Longitude, Altitude (m)
 *
 * Float-Parsing akzeptiert sowohl "." als auch "," als Dezimaltrenner (Locale-tolerant).
 */
object RavenSelectionImporter {

    private const val SPALTE_BEGIN   = "Begin Time (s)"
    private const val SPALTE_END     = "End Time (s)"
    private const val SPALTE_LOW     = "Low Freq (Hz)"
    private const val SPALTE_HIGH    = "High Freq (Hz)"
    private const val SPALTE_SPECIES = "Species"
    private const val SPALTE_LAT     = "Latitude"
    private const val SPALTE_LON     = "Longitude"
    private const val SPALTE_ALT     = "Altitude (m)"

    /**
     * Parst die angegebene Datei und gibt Annotationen + optionale GPS-Daten zurueck.
     *
     * IOException beim Lesen der Datei propagiert nach oben — der Aufrufer fangt sie
     * und zeigt sie im UI. Fehler in einzelnen Zeilen fuehren zu [RavenImportResult.fehler],
     * nicht zum Abbruch.
     *
     * @param datei  Die Raven .txt Datei.
     * @param fileId AudioFileId, die allen erstellten Annotationen zugewiesen wird.
     */
    fun importiere(datei: File, fileId: String): RavenImportResult {
        val zeilen = datei.bufferedReader(Charsets.UTF_8).readLines()
        if (zeilen.isEmpty()) {
            return RavenImportResult(emptyList(), null, listOf("Datei ist leer"))
        }

        val header = zeilen.first().split('\t')
        val idxBegin   = header.indexOf(SPALTE_BEGIN)
        val idxEnd     = header.indexOf(SPALTE_END)
        val idxLow     = header.indexOf(SPALTE_LOW)
        val idxHigh    = header.indexOf(SPALTE_HIGH)

        val fehlendePflicht = buildList {
            if (idxBegin < 0) add(SPALTE_BEGIN)
            if (idxEnd   < 0) add(SPALTE_END)
            if (idxLow   < 0) add(SPALTE_LOW)
            if (idxHigh  < 0) add(SPALTE_HIGH)
        }
        if (fehlendePflicht.isNotEmpty()) {
            return RavenImportResult(
                emptyList(),
                null,
                listOf("Fehlende Pflicht-Spalte(n): ${fehlendePflicht.joinToString(", ")}")
            )
        }

        val idxSpecies = header.indexOf(SPALTE_SPECIES)
        val idxLat     = header.indexOf(SPALTE_LAT)
        val idxLon     = header.indexOf(SPALTE_LON)
        val idxAlt     = header.indexOf(SPALTE_ALT)

        val annotationen = mutableListOf<Annotation>()
        val fehler       = mutableListOf<String>()
        var gpsMetadata: RecordingMetadata? = null

        for (zeilenIndex in 1 until zeilen.size) {
            val roh = zeilen[zeilenIndex]
            if (roh.isBlank()) continue

            val werte = roh.split('\t')

            val feld = { idx: Int -> if (idx in werte.indices) werte[idx] else "" }

            val begin = parseFloat(feld(idxBegin))
            val end   = parseFloat(feld(idxEnd))
            val low   = parseFloat(feld(idxLow))
            val high  = parseFloat(feld(idxHigh))

            if (begin == null || end == null || low == null || high == null) {
                fehler.add("Zeile ${zeilenIndex + 1}: ungueltige Pflichtwerte")
                continue
            }

            val label = if (idxSpecies >= 0) {
                feld(idxSpecies).trim().ifEmpty { "Unbekannt" }
            } else "Unbekannt"

            annotationen.add(
                Annotation(
                    audioFileId  = fileId,
                    startTimeSec = begin,
                    endTimeSec   = end,
                    lowFreqHz    = low,
                    highFreqHz   = high,
                    label        = label
                )
            )

            // Erste gueltige GPS-Zeile merken (lat != 0 UND lon != 0).
            if (gpsMetadata == null && idxLat >= 0 && idxLon >= 0) {
                val lat = parseDouble(feld(idxLat))
                val lon = parseDouble(feld(idxLon))
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    val alt = if (idxAlt >= 0) parseDouble(feld(idxAlt)) ?: 0.0 else 0.0
                    gpsMetadata = RecordingMetadata(
                        latitude  = lat,
                        longitude = lon,
                        altitude  = alt
                    )
                }
            }
        }

        return RavenImportResult(annotationen, gpsMetadata, fehler)
    }

    /** Akzeptiert "." und "," als Dezimaltrenner. Leere oder ungueltige Werte -> null. */
    private fun parseFloat(wert: String): Float? =
        wert.trim().replace(',', '.').toFloatOrNull()

    /** Akzeptiert "." und "," als Dezimaltrenner. Leere oder ungueltige Werte -> null. */
    private fun parseDouble(wert: String): Double? =
        wert.trim().replace(',', '.').toDoubleOrNull()
}
