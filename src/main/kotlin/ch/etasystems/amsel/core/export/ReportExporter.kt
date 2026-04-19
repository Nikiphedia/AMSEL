package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.core.annotation.Annotation
import ch.etasystems.amsel.data.ReportSortOrder
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

    /** Info ueber eine Audio-Datei fuer den Report. */
    data class AudioFileInfo(
        val fileId: String,
        val fileName: String,
        val durationSec: Float,
        val recordingDate: String = "",
        val recordingTime: String = ""
    )

    data class ReportConfig(
        val title: String = "AMSEL Analysebericht",
        val audioFiles: List<AudioFileInfo> = emptyList(),
        val operatorName: String = "",
        val deviceName: String = "",
        val locationName: String = "",
        val totalDurationSec: Float = 0f,
        val annotations: List<Annotation> = emptyList(),
        val sortOrder: ReportSortOrder = ReportSortOrder.CHRONOLOGICAL
    )

    /**
     * Exportiert als CSV (Semikolon-getrennt, UTF-8 mit BOM).
     */
    fun exportCsv(file: File, config: ReportConfig) {
        val filteredAnnotations = config.annotations.filter { !it.rejected }
        val fileInfoMap = config.audioFiles.associateBy { it.fileId }

        val sorted = sortAnnotations(filteredAnnotations, fileInfoMap, config.sortOrder)

        val bom = "\uFEFF"
        val header = "Nr;Datei;Art;Wissenschaftlich;Start (s);Ende (s);Dauer (s);Freq. tief (Hz);Freq. hoch (Hz);BirdNET Konfidenz;Quelle;Status;Verifiziert von;Verifiziert am;Bemerkung;Peak (Hz);Center (Hz);LowFreq-3dB (Hz);HighFreq-3dB (Hz);Bandwidth-3dB (Hz);SNR (dB)"

        val lines = sorted.mapIndexed { idx, ann ->
            val fileInfo = fileInfoMap[ann.audioFileId]
            val fileName = fileInfo?.fileName ?: ""
            val bestCandidate = ann.candidates.maxByOrNull { it.confidence }
            val confidence = bestCandidate?.confidence?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: ""
            val source = if (ann.isBirdNetDetection) "BirdNET" else "Manuell"

            val verifiedCandidate = ann.candidates.find { it.verified }
            val rejectedAll = ann.candidates.isNotEmpty() && ann.candidates.all { it.rejected }
            val hasUncertain = ann.candidates.any { it.uncertain }
            val verifiedCount = ann.candidates.count { it.verified }
            val status = when {
                verifiedCount > 1 -> "verifiziert (${verifiedCount} Arten)"
                verifiedCandidate != null -> "verifiziert"
                rejectedAll -> "abgelehnt"
                hasUncertain -> "unklar"
                else -> "offen"
            }
            val verifiedBy = verifiedCandidate?.verifiedBy ?: ""
            val verifiedAt = if (verifiedCandidate != null && verifiedCandidate.verifiedAt > 0) {
                java.time.Instant.ofEpochMilli(verifiedCandidate.verifiedAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            } else ""
            val notes = ann.notes.replace(";", ",").replace("\n", " ")

            // Akustische Messwerte (leer wenn nicht berechnet)
            val m = ann.metrics
            val peakHz = if (m.isComputed) String.format(java.util.Locale.US, "%.0f", m.peakFreqHz) else ""
            val centerHz = if (m.isComputed) String.format(java.util.Locale.US, "%.0f", m.centerFreqHz) else ""
            val lowHz3db = if (m.isComputed) String.format(java.util.Locale.US, "%.0f", m.lowFreq3dbHz) else ""
            val highHz3db = if (m.isComputed) String.format(java.util.Locale.US, "%.0f", m.highFreq3dbHz) else ""
            val bw3db = if (m.isComputed) String.format(java.util.Locale.US, "%.0f", m.bandwidth3dbHz) else ""
            val snrDb = if (m.isComputed) String.format(java.util.Locale.US, "%.1f", m.snrDb) else ""

            listOf(
                idx + 1, fileName, ann.label,
                bestCandidate?.scientificName ?: "",
                String.format(java.util.Locale.US, "%.2f", ann.startTimeSec),
                String.format(java.util.Locale.US, "%.2f", ann.endTimeSec),
                String.format(java.util.Locale.US, "%.2f", ann.durationSec),
                String.format(java.util.Locale.US, "%.0f", ann.lowFreqHz),
                String.format(java.util.Locale.US, "%.0f", ann.highFreqHz),
                confidence, source, status, verifiedBy, verifiedAt, notes,
                peakHz, centerHz, lowHz3db, highHz3db, bw3db, snrDb
            ).joinToString(";")
        }

        file.writeText(bom + header + "\n" + lines.joinToString("\n"), Charsets.UTF_8)
        logger.info("CSV exportiert: {} ({} Eintraege)", file.absolutePath, lines.size)
    }

    internal fun sortAnnotations(
        annotations: List<Annotation>,
        fileInfoMap: Map<String, AudioFileInfo>,
        sortOrder: ReportSortOrder
    ): List<Annotation> = when (sortOrder) {
        ReportSortOrder.CHRONOLOGICAL -> annotations.sortedWith(
            compareBy<Annotation> { fileInfoMap[it.audioFileId]?.recordingDate ?: "" }
                .thenBy { fileInfoMap[it.audioFileId]?.recordingTime ?: "" }
                .thenBy { it.startTimeSec }
        )
        ReportSortOrder.ALPHABETICAL -> annotations.sortedWith(
            compareBy<Annotation> { it.label }
                .thenBy { it.startTimeSec }
        )
        ReportSortOrder.SYSTEMATIC -> annotations.sortedWith(
            compareBy<Annotation> { it.label }
                .thenBy { it.startTimeSec }
        )  // TODO: Taxonomische Reihenfolge aus SpeciesRegistry (Fallback: alphabetisch)
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
        val filteredAnnotations = config.annotations.filter { !it.rejected }
        val fileInfoMap = config.audioFiles.associateBy { it.fileId }
        val sortedAnns = sortAnnotations(filteredAnnotations, fileInfoMap, config.sortOrder)
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
            content.showText(config.title)
            content.endText()
            yPos -= lineHeight * 2

            content.setFont(fontRegular, fontSize)
            val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            val headerLines = mutableListOf("Datum: $now")
            if (config.audioFiles.isNotEmpty()) {
                val fileList = config.audioFiles.joinToString(", ") { af ->
                    "${af.fileName} (${formatDuration(af.durationSec)})"
                }
                headerLines.add("Dateien: $fileList")
            }
            if (config.totalDurationSec > 0) {
                headerLines.add("Gesamtdauer: ${"%.1f".format(config.totalDurationSec / 60f)} min")
            }
            if (config.operatorName.isNotBlank()) headerLines.add("Bearbeiter: ${config.operatorName}")
            if (config.deviceName.isNotBlank()) headerLines.add("Geraet: ${config.deviceName}")
            if (config.locationName.isNotBlank()) headerLines.add("Standort: ${config.locationName}")

            for (line in headerLines) {
                content.beginText()
                content.newLineAtOffset(margin, yPos)
                content.showText(line)
                content.endText()
                yPos -= lineHeight
            }
            yPos -= lineHeight

            // === Zusammenfassung ===
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
            // Bestehende Spalten proportional gekuerzt um Platz fuer 6 Metriken-Spalten zu machen.
            // Bemerkung: 102pt → 60pt (Task-Vorgabe). Uebrige 11 Spalten: Faktor 0.758.
            val colWidths = floatArrayOf(
                19f, 61f, 83f, 42f, 42f, 34f, 34f, 38f, 42f, 45f, 45f, 60f,
                32f, 37f, 32f, 32f, 32f, 30f
            )
            val colHeaders = arrayOf(
                "Nr", "Datei", "Art", "Start", "Ende", "Dauer", "Konf.", "Quelle", "Status", "Verif. von", "Verif. am", "Bemerkung",
                "Peak", "Center", "f-", "f+", "BW", "SNR"
            )

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

                val fileInfo = fileInfoMap[ann.audioFileId]
                val shortFileName = (fileInfo?.fileName ?: "").take(12)
                val bestCandidate = ann.candidates.maxByOrNull { it.confidence }
                val conf = bestCandidate?.confidence?.let { "%.0f%%".format(it * 100) } ?: ""
                val source = if (ann.isBirdNetDetection) "BirdNET" else "Manuell"

                val verifiedCandidate = ann.candidates.find { it.verified }
                val rejectedAll = ann.candidates.isNotEmpty() && ann.candidates.all { it.rejected }
                val hasUncertain = ann.candidates.any { it.uncertain }
                val verifiedCount = ann.candidates.count { it.verified }
                val status = when {
                    verifiedCount > 1 -> "verifiziert (${verifiedCount} Arten)"
                    verifiedCandidate != null -> "verifiziert"
                    rejectedAll -> "abgelehnt"
                    hasUncertain -> "unklar"
                    else -> "offen"
                }
                val verifiedBy = (verifiedCandidate?.verifiedBy ?: "").take(8)
                val verifiedAtStr = if (verifiedCandidate != null && verifiedCandidate.verifiedAt > 0) {
                    java.time.Instant.ofEpochMilli(verifiedCandidate.verifiedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
                } else ""
                val pdfNotes = ann.notes.take(12)

                // Akustische Messwerte (leer wenn nicht berechnet)
                val m = ann.metrics
                val pdfPeak = if (m.isComputed) "%.0f".format(m.peakFreqHz) else ""
                val pdfCenter = if (m.isComputed) "%.0f".format(m.centerFreqHz) else ""
                val pdfLow3db = if (m.isComputed) "%.0f".format(m.lowFreq3dbHz) else ""
                val pdfHigh3db = if (m.isComputed) "%.0f".format(m.highFreq3dbHz) else ""
                val pdfBw = if (m.isComputed) "%.0f".format(m.bandwidth3dbHz) else ""
                val pdfSnr = if (m.isComputed) "%.1f".format(m.snrDb) else ""

                val colValues = arrayOf(
                    "${idx + 1}",
                    shortFileName,
                    ann.label.take(16),
                    "%.1f".format(ann.startTimeSec),
                    "%.1f".format(ann.endTimeSec),
                    "%.1f".format(ann.durationSec),
                    conf,
                    source,
                    status,
                    verifiedBy,
                    verifiedAtStr,
                    pdfNotes,
                    pdfPeak, pdfCenter, pdfLow3db, pdfHigh3db, pdfBw, pdfSnr
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
            content.showText("Erstellt mit AMSEL v0.0.7 — $now")
            content.endText()

            content.close()
            doc.save(file)
            logger.info("PDF exportiert: {} ({} Seiten)", file.absolutePath, doc.numberOfPages)
        } finally {
            doc.close()
        }
    }

    private fun formatDuration(sec: Float): String {
        val m = (sec / 60).toInt()
        val s = (sec % 60).toInt()
        return "${m}:${s.toString().padStart(2, '0')}"
    }
}
