package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exportiert Analyse-Ergebnisse als PDF-Report und/oder CSV-Tabelle.
 */
object ReportExporter {
    private val logger = LoggerFactory.getLogger(ReportExporter::class.java)

    data class ReportConfig(
        val title: String = "AMSEL Analysebericht",
        val audioFileName: String = "",
        val operatorName: String = "",
        val deviceName: String = "",
        val locationName: String = "",
        val audioDurationSec: Float = 0f,
        val annotations: List<Annotation> = emptyList()
    )

    /**
     * Exportiert als CSV (Semikolon-getrennt, UTF-8 mit BOM).
     */
    fun exportCsv(file: File, config: ReportConfig) {
        val filteredConfig = config.copy(annotations = config.annotations.filter { !it.rejected })
        val bom = "\uFEFF"
        val header = "Nr;Art;Wissenschaftlich;Start (s);Ende (s);Dauer (s);Freq. tief (Hz);Freq. hoch (Hz);BirdNET Konfidenz;Quelle;Status;Verifiziert von;Verifiziert am;Bemerkung"

        val lines = filteredConfig.annotations
            .sortedBy { it.startTimeSec }
            .mapIndexed { idx, ann ->
                val bestCandidate = ann.candidates.maxByOrNull { it.confidence }
                val confidence = bestCandidate?.confidence?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
                val source = if (ann.isBirdNetDetection) "BirdNET" else "Manuell"

                // Verifizierungsstatus
                val verifiedCandidate = ann.candidates.find { it.verified }
                val rejectedAll = ann.candidates.isNotEmpty() && ann.candidates.all { it.rejected }
                val status = when {
                    verifiedCandidate != null -> "verifiziert"
                    rejectedAll -> "abgelehnt"
                    else -> "offen"
                }
                val verifiedBy = verifiedCandidate?.verifiedBy ?: ""
                val verifiedAt = if (verifiedCandidate != null && verifiedCandidate.verifiedAt > 0) {
                    java.time.Instant.ofEpochMilli(verifiedCandidate.verifiedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                } else ""
                // Bemerkung (Semikolons escapen)
                val notes = ann.notes.replace(";", ",").replace("\n", " ")

                listOf(
                    idx + 1,
                    ann.label,
                    bestCandidate?.scientificName ?: "",
                    String.format(java.util.Locale.US, "%.2f", ann.startTimeSec),
                    String.format(java.util.Locale.US, "%.2f", ann.endTimeSec),
                    String.format(java.util.Locale.US, "%.2f", ann.durationSec),
                    String.format(java.util.Locale.US, "%.0f", ann.lowFreqHz),
                    String.format(java.util.Locale.US, "%.0f", ann.highFreqHz),
                    confidence,
                    source,
                    status,
                    verifiedBy,
                    verifiedAt,
                    notes
                ).joinToString(";")
            }

        file.writeText(bom + header + "\n" + lines.joinToString("\n"), Charsets.UTF_8)
        logger.info("CSV exportiert: {} ({} Eintraege)", file.absolutePath, lines.size)
    }

    /**
     * Exportiert als PDF-Report.
     *
     * Seitenaufbau:
     * - Header: Titel, Datum, Dateiname, Bearbeiter, Standort
     * - Zusammenfassung: Dauer, Anzahl Annotationen, Artenliste
     * - Tabelle: Alle Annotationen sortiert nach Zeit
     */
    fun exportPdf(file: File, config: ReportConfig) {
        val filteredConfig = config.copy(annotations = config.annotations.filter { !it.rejected })
        val doc = PDDocument()

        try {
            val fontRegular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val fontBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            val fontSize = 9f
            val headerSize = 14f
            val lineHeight = 14f
            val margin = 50f
            val landscape = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            val pageWidth = landscape.width
            val pageHeight = landscape.height
            val contentWidth = pageWidth - 2 * margin

            var page = PDPage(landscape)
            doc.addPage(page)
            var content = PDPageContentStream(doc, page)
            var yPos = pageHeight - margin

            // === Header ===
            content.setFont(fontBold, headerSize)
            content.beginText()
            content.newLineAtOffset(margin, yPos)
            content.showText(filteredConfig.title)
            content.endText()
            yPos -= lineHeight * 2

            content.setFont(fontRegular, fontSize)
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            val headerLines = listOfNotNull(
                "Datum: $now",
                if (filteredConfig.audioFileName.isNotBlank()) "Datei: ${filteredConfig.audioFileName}" else null,
                if (filteredConfig.operatorName.isNotBlank()) "Bearbeiter: ${filteredConfig.operatorName}" else null,
                if (filteredConfig.deviceName.isNotBlank()) "Geraet: ${filteredConfig.deviceName}" else null,
                if (filteredConfig.locationName.isNotBlank()) "Standort: ${filteredConfig.locationName}" else null,
                if (filteredConfig.audioDurationSec > 0) "Dauer: ${"%.1f".format(filteredConfig.audioDurationSec / 60f)} min" else null
            )
            for (line in headerLines) {
                content.beginText()
                content.newLineAtOffset(margin, yPos)
                content.showText(line)
                content.endText()
                yPos -= lineHeight
            }
            yPos -= lineHeight

            // === Zusammenfassung ===
            val sortedAnns = filteredConfig.annotations.sortedBy { it.startTimeSec }
            val speciesList = sortedAnns
                .map { it.label }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            content.setFont(fontBold, 11f)
            content.beginText()
            content.newLineAtOffset(margin, yPos)
            content.showText("Zusammenfassung")
            content.endText()
            yPos -= lineHeight * 1.5f

            content.setFont(fontRegular, fontSize)
            content.beginText()
            content.newLineAtOffset(margin, yPos)
            content.showText("${sortedAnns.size} Annotationen, ${speciesList.size} Arten")
            content.endText()
            yPos -= lineHeight

            if (speciesList.isNotEmpty()) {
                content.beginText()
                content.newLineAtOffset(margin, yPos)
                content.showText("Arten: ${speciesList.joinToString(", ")}")
                content.endText()
                yPos -= lineHeight
            }
            yPos -= lineHeight

            // === Tabelle ===
            content.setFont(fontBold, 11f)
            content.beginText()
            content.newLineAtOffset(margin, yPos)
            content.showText("Detektionen")
            content.endText()
            yPos -= lineHeight * 1.5f

            // Tabellen-Header (Landscape A4 = 842pt breit, 50pt Margin = 742pt nutzbar)
            val colWidths = floatArrayOf(25f, 120f, 60f, 60f, 50f, 50f, 50f, 55f, 70f, 70f, 132f)
            val colHeaders = arrayOf("Nr", "Art", "Start", "Ende", "Dauer", "Konf.", "Quelle", "Status", "Verif. von", "Verif. am", "Bemerkung")

            content.setFont(fontBold, fontSize)
            var xPos = margin
            for (i in colHeaders.indices) {
                content.beginText()
                content.newLineAtOffset(xPos, yPos)
                content.showText(colHeaders[i])
                content.endText()
                xPos += colWidths[i]
            }
            yPos -= lineHeight

            // Trennlinie
            content.moveTo(margin, yPos + lineHeight * 0.3f)
            content.lineTo(margin + contentWidth, yPos + lineHeight * 0.3f)
            content.stroke()

            // Tabellen-Zeilen
            content.setFont(fontRegular, fontSize)
            for ((idx, ann) in sortedAnns.withIndex()) {
                if (yPos < margin + lineHeight) {
                    // Neue Seite
                    content.close()
                    page = PDPage(landscape)
                    doc.addPage(page)
                    content = PDPageContentStream(doc, page)
                    yPos = pageHeight - margin
                    content.setFont(fontRegular, fontSize)
                }

                val bestCandidate = ann.candidates.maxByOrNull { it.confidence }
                val conf = bestCandidate?.confidence?.let { "%.0f%%".format(it * 100) } ?: ""
                val source = if (ann.isBirdNetDetection) "BirdNET" else "Manuell"

                val verifiedCandidate = ann.candidates.find { it.verified }
                val rejectedAll = ann.candidates.isNotEmpty() && ann.candidates.all { it.rejected }
                val status = when {
                    verifiedCandidate != null -> "verifiziert"
                    rejectedAll -> "abgelehnt"
                    else -> "offen"
                }
                val verifiedBy = verifiedCandidate?.verifiedBy ?: ""
                val verifiedAtStr = if (verifiedCandidate != null && verifiedCandidate.verifiedAt > 0) {
                    java.time.Instant.ofEpochMilli(verifiedCandidate.verifiedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
                } else ""
                val pdfNotes = ann.notes.take(40)

                val colValues = arrayOf(
                    "${idx + 1}",
                    ann.label.take(20),
                    "%.1f".format(ann.startTimeSec),
                    "%.1f".format(ann.endTimeSec),
                    "%.1f".format(ann.durationSec),
                    conf,
                    source,
                    status,
                    verifiedBy,
                    verifiedAtStr,
                    pdfNotes
                )

                xPos = margin
                for (i in colValues.indices) {
                    content.beginText()
                    content.newLineAtOffset(xPos, yPos)
                    content.showText(colValues[i])
                    content.endText()
                    xPos += colWidths[i]
                }
                yPos -= lineHeight
            }

            // Footer
            yPos -= lineHeight
            content.setFont(fontRegular, 7f)
            content.beginText()
            content.newLineAtOffset(margin, margin / 2)
            content.showText("Erstellt mit AMSEL v0.0.6 — $now")
            content.endText()

            content.close()
            doc.save(file)
            logger.info("PDF exportiert: {} ({} Seiten)", file.absolutePath, doc.numberOfPages)
        } finally {
            doc.close()
        }
    }
}
