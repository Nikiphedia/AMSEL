package ch.etasystems.amsel.core.export

import ch.etasystems.amsel.data.AudioReference
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale

/**
 * Exportiert Analyse-Ergebnisse als Raven Selection Table (Cornell Lab Standard).
 * Tab-separierte Textdatei, UTF-8 ohne BOM.
 * Kompatibel mit Raven Pro, Raven Lite und der Vogelwarte bioacoustic-tools (R-Shiny).
 */
object RavenSelectionExporter {

    private val logger = LoggerFactory.getLogger(RavenSelectionExporter::class.java)

    fun export(file: File, config: ReportExporter.ReportConfig, audioRefs: Map<String, AudioReference>) {
        val filteredAnnotations = config.annotations.filter { !it.rejected }
        val fileInfoMap = config.audioFiles.associateBy { it.fileId }

        val sorted = ReportExporter.sortAnnotations(filteredAnnotations, fileInfoMap, config.sortOrder)

        // GPS-Spalten einbeziehen wenn mindestens eine Audio-Datei GPS-Daten hat
        val hatGps = audioRefs.values.any { ref ->
            val meta = ref.recordingMeta
            meta != null && (meta.latitude != 0.0 || meta.longitude != 0.0)
        }

        // Kopfzeile: Raven-Pflichtspalten + AMSEL-Zusatz + ggf. GPS
        val pflichtSpalten = listOf(
            "Selection", "View", "Channel",
            "Begin Time (s)", "End Time (s)",
            "Low Freq (Hz)", "High Freq (Hz)"
        )
        val amselSpalten = listOf(
            "Species", "Scientific Name", "Confidence",
            "Source", "Status", "Notes", "Begin File"
        )
        val metrikSpalten = listOf(
            "Peak Freq (Hz)", "Center Freq (Hz)", "Low-3dB (Hz)",
            "High-3dB (Hz)", "Bandwidth-3dB (Hz)", "SNR (dB)"
        )
        val gpsSpalten = if (hatGps) listOf("Latitude", "Longitude", "Altitude (m)") else emptyList()
        val header = (pflichtSpalten + amselSpalten + metrikSpalten + gpsSpalten).joinToString("\t")

        val zeilen = sorted.mapIndexed { idx, ann ->
            val fileInfo = fileInfoMap[ann.audioFileId]
            val fileName = fileInfo?.fileName ?: ""

            // Kandidaten-Auswahl:
            // 1. Verifizierte Kandidaten vorhanden → den mit hoechster Konfidenz nehmen
            // 2. Kein verifizierter → Top-Kandidat nach Konfidenz
            // 3. Gar kein Kandidat → ann.label, leere Konfidenz
            val verifizierteListe = ann.candidates.filter { it.verified }
            val gewaehlterKandidat = if (verifizierteListe.isNotEmpty()) {
                verifizierteListe.maxByOrNull { it.confidence }
            } else {
                ann.candidates.maxByOrNull { it.confidence }
            }

            val species = gewaehlterKandidat?.species ?: ann.label
            val wissenschaftlicherName = gewaehlterKandidat?.scientificName ?: ""
            val konfidenz = gewaehlterKandidat?.confidence
                ?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val quelle = if (ann.isBirdNetDetection) "BirdNET" else "Manuell"

            // Status analog zu ReportExporter.exportCsv
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

            // Tabs und Newlines aus Notes entfernen (wuerden TSV-Struktur brechen)
            val notes = ann.notes.replace('\t', ' ').replace('\n', ' ')

            val pflichtWerte = listOf(
                "${idx + 1}",
                "Spectrogram 1",
                "1",
                String.format(Locale.US, "%.4f", ann.startTimeSec),
                String.format(Locale.US, "%.4f", ann.endTimeSec),
                String.format(Locale.US, "%.0f", ann.lowFreqHz),
                String.format(Locale.US, "%.0f", ann.highFreqHz)
            )
            val amselWerte = listOf(
                species, wissenschaftlicherName, konfidenz,
                quelle, status, notes, fileName
            )
            val mu = ann.metrics
            val metrikWerte = listOf(
                if (mu.isComputed) String.format(Locale.US, "%.0f", mu.peakFreqHz) else "",
                if (mu.isComputed) String.format(Locale.US, "%.0f", mu.centerFreqHz) else "",
                if (mu.isComputed) String.format(Locale.US, "%.0f", mu.lowFreq3dbHz) else "",
                if (mu.isComputed) String.format(Locale.US, "%.0f", mu.highFreq3dbHz) else "",
                if (mu.isComputed) String.format(Locale.US, "%.0f", mu.bandwidth3dbHz) else "",
                if (mu.isComputed) String.format(Locale.US, "%.1f", mu.snrDb) else ""
            )
            val gpsWerte = if (hatGps) {
                val meta = audioRefs[ann.audioFileId]?.recordingMeta
                listOf(
                    if (meta != null && meta.latitude != 0.0) String.format(Locale.US, "%.6f", meta.latitude) else "",
                    if (meta != null && meta.longitude != 0.0) String.format(Locale.US, "%.6f", meta.longitude) else "",
                    if (meta != null && meta.altitude != 0.0) String.format(Locale.US, "%.1f", meta.altitude) else ""
                )
            } else emptyList()

            (pflichtWerte + amselWerte + metrikWerte + gpsWerte).joinToString("\t")
        }

        // UTF-8 ohne BOM, \n als Zeilenende (Raven-Kompatibilitaet)
        file.writeText(header + "\n" + zeilen.joinToString("\n"), Charsets.UTF_8)
        logger.info("Raven Selection Table exportiert: {} ({} Eintraege)", file.absolutePath, zeilen.size)
    }
}
