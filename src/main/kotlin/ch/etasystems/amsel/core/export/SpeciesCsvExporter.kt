package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.classifier.ClassifierResult
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exportiert Artenerkennung-Ergebnisse als CSV (Semikolon-getrennt, UTF-8 mit BOM).
 * Kompatibel mit Excel und LibreOffice Calc (DACH-Locale).
 */
object SpeciesCsvExporter {
    private val logger = LoggerFactory.getLogger(SpeciesCsvExporter::class.java)

    /**
     * Schreibt Klassifikationsergebnisse als CSV.
     *
     * @param file Zieldatei (.csv)
     * @param results BirdNET-Ergebnisse
     * @param audioFileName Name der analysierten Audiodatei (fuer Kommentarzeile)
     */
    fun export(file: File, results: List<ClassifierResult>, audioFileName: String = "") {
        val bom = "\uFEFF"
        val datum = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val kommentar = "# AMSEL Artenanalyse — $audioFileName — $datum"
        val header = "startTime;endTime;species;scientificName;confidence"

        val zeilen = results
            .sortedBy { it.startTime }
            .map { r ->
                val species = r.species.replace(";", ",")
                val scientificName = r.scientificName.replace(";", ",")
                listOf(
                    String.format(java.util.Locale.US, "%.2f", r.startTime),
                    String.format(java.util.Locale.US, "%.2f", r.endTime),
                    species,
                    scientificName,
                    String.format(java.util.Locale.US, "%.4f", r.confidence)
                ).joinToString(";")
            }

        val inhalt = buildString {
            append(bom)
            append(kommentar)
            append("\n")
            append(header)
            append("\n")
            zeilen.forEach { zeile ->
                append(zeile)
                append("\n")
            }
        }

        file.writeText(inhalt, Charsets.UTF_8)
        logger.info("CSV exportiert: {} ({} Eintraege)", file.absolutePath, results.size)
    }
}
