package ch.etasystems.amsel.data.reference

import ch.etasystems.amsel.data.RegionSetRegistry
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.api.XenoCantoApi
import ch.etasystems.amsel.data.api.XenoCantoRecording
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

/**
 * Laedt Referenzdaten von Xeno-Canto herunter und speichert sie in curated/.
 * Ersetzt den alten DownloadManager fuer die ordnerbasierte Referenzbibliothek.
 */
class ReferenceDownloader(
    private val library: ReferenceLibrary,
    private val api: XenoCantoApi
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    data class DownloadProgress(
        val species: String = "",
        val current: Int = 0,
        val total: Int = 0,
        val phase: String = "",
        val isRunning: Boolean = false,
        val errors: Int = 0,
        val totalDownloaded: Int = 0,
        val totalReferencesSaved: Int = 0,
        val totalSkipped: Int = 0
    )

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val referencesDir get() = library.getReferencesDir()
    private val curatedDir get() = File(referencesDir, "curated")
    private val csvFile get() = File(referencesDir, "referenzen.csv")

    /**
     * Startet Bulk-Download: Sonogramm-PNGs von Xeno-Canto herunterladen.
     *
     * @param speciesList z.B. ["Turdus merula", "Erithacus rubecula"]
     * @param maxPerSpecies 0 = alle, sonst Limit pro Art
     */
    fun startDownload(
        speciesList: List<String>,
        maxPerSpecies: Int = 0
    ) {
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            _progress.value = DownloadProgress(isRunning = true)

            // Artenset-Filter: nur Arten im aktiven Set herunterladen
            val settings = SettingsStore.load()
            val regionSetId = settings.activeRegionSet
            val filteredSpeciesList = if (regionSetId == "all") speciesList
            else speciesList.filter { RegionSetRegistry.isSpeciesInSet(regionSetId, it) }

            logger.debug("Download mit Artenset '{}': {}/{} Arten nach Filter",
                regionSetId, filteredSpeciesList.size, speciesList.size)

            // CSV-Index einmalig einlesen (Performance: nicht pro Datei parsen)
            val csvIndex = buildCsvIndex()
            logger.debug("CSV-Index geladen: {} Eintraege", csvIndex.size)

            var totalDownloaded = 0
            var totalErrors = 0
            var totalSkipped = 0
            var totalCsvRepaired = 0
            var totalRefs = 0

            for ((speciesIndex, species) in filteredSpeciesList.withIndex()) {
                if (!isActive) break

                _progress.value = _progress.value.copy(
                    species = species,
                    phase = "Suche API...",
                    current = speciesIndex + 1,
                    total = filteredSpeciesList.size
                )

                try {
                    var page = 1
                    var speciesDownloaded = 0
                    var hasMore = true

                    while (hasMore && isActive) {
                        val limit = if (maxPerSpecies > 0) maxPerSpecies - speciesDownloaded else Int.MAX_VALUE
                        if (limit <= 0) break

                        _progress.value = _progress.value.copy(phase = "Seite $page laden...")

                        val recordings = try {
                            api.searchBySpecies(species = species, page = page)
                        } catch (_: Exception) {
                            totalErrors++
                            break
                        }

                        if (recordings.isEmpty()) break

                        // Qualitaetsfilter anwenden
                        val qualitySettings = SettingsStore.load()
                        val qualityFiltered = recordings.filter { rec ->
                            meetsQuality(rec.q, qualitySettings.referenceMinQualityDownload)
                        }
                        val toProcess = if (maxPerSpecies > 0) qualityFiltered.take(limit) else qualityFiltered

                        for (recording in toProcess) {
                            if (!isActive) break

                            val speciesFolder = species.replace(' ', '_')
                            val xcId = "XC${recording.id}"
                            val pngFile = File(File(curatedDir, speciesFolder), "${xcId}_${speciesFolder}.png")
                            val fileExists = pngFile.exists() && pngFile.length() > 0
                            val csvExists = csvIndex.contains(xcId)

                            // Skip-Check: Datei UND CSV vorhanden → nichts tun
                            if (fileExists && csvExists) {
                                logger.debug("Skip: {}_{} bereits vorhanden", xcId, speciesFolder)
                                totalSkipped++
                                speciesDownloaded++
                                _progress.value = _progress.value.copy(
                                    totalSkipped = totalSkipped
                                )
                                continue
                            }

                            // Datei vorhanden, aber kein CSV-Eintrag → nur CSV ergaenzen
                            if (fileExists && !csvExists) {
                                logger.debug("CSV-Eintrag ergaenzen fuer: {}_{}", xcId, speciesFolder)
                                appendCsvEntry(
                                    id = xcId,
                                    art = speciesFolder,
                                    xcId = "Xeno-Canto $xcId",
                                    typ = recording.type,
                                    qualitaet = recording.q
                                )
                                csvIndex.add(xcId)
                                totalCsvRepaired++
                                totalRefs++
                                speciesDownloaded++
                                _progress.value = _progress.value.copy(
                                    totalSkipped = totalSkipped,
                                    totalReferencesSaved = totalRefs
                                )
                                continue
                            }

                            // CSV vorhanden aber keine Datei (oder 0 Bytes) → neu herunterladen
                            // Beides fehlt → normal herunterladen
                            _progress.value = _progress.value.copy(
                                phase = "$species — ${speciesDownloaded + 1}/${toProcess.size} (S.$page)",
                                totalDownloaded = totalDownloaded,
                                totalReferencesSaved = totalRefs
                            )

                            try {
                                val saved = downloadReference(recording, speciesFolder, skipCsvAppend = csvExists)
                                if (saved) {
                                    totalDownloaded++
                                    if (!csvExists) {
                                        totalRefs++
                                        csvIndex.add(xcId)
                                    }
                                }
                                speciesDownloaded++
                            } catch (_: Exception) {
                                totalErrors++
                            }

                            _progress.value = _progress.value.copy(
                                totalDownloaded = totalDownloaded,
                                errors = totalErrors,
                                totalReferencesSaved = totalRefs,
                                totalSkipped = totalSkipped
                            )

                            // Rate-Limit
                            delay(200)
                        }

                        hasMore = recordings.size >= 500
                        page++
                        if (page > 20) break
                    }
                } catch (_: Exception) {
                    totalErrors++
                }
            }

            // Nach allen Downloads: Index neu generieren
            try {
                library.rescan()
            } catch (e: Exception) {
                logger.warn("Rescan nach Download fehlgeschlagen: {}", e.message)
            }

            val summary = "Download fertig: $totalDownloaded neu, $totalSkipped uebersprungen, $totalErrors fehlgeschlagen" +
                if (totalCsvRepaired > 0) ", $totalCsvRepaired CSV-Eintraege ergaenzt" else ""
            logger.info(summary)

            _progress.value = _progress.value.copy(
                phase = "Fertig — $totalDownloaded heruntergeladen, $totalSkipped vorhanden, $totalErrors Fehler",
                isRunning = false,
                totalDownloaded = totalDownloaded,
                totalReferencesSaved = totalRefs,
                totalSkipped = totalSkipped
            )
        }
    }

    /**
     * CSV-Index einmalig einlesen: Alle IDs als Set fuer schnellen Lookup.
     * Wird beim Start des Download-Vorgangs einmal aufgerufen.
     */
    private fun buildCsvIndex(): MutableSet<String> {
        val ids = mutableSetOf<String>()
        if (!csvFile.exists() || csvFile.length() == 0L) return ids
        try {
            csvFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var idColumnIndex = -1
                for ((lineNum, line) in lines.withIndex()) {
                    val cleaned = line.removePrefix("\uFEFF").trim()
                    if (cleaned.isBlank()) continue
                    val columns = cleaned.split(";")
                    if (lineNum == 0) {
                        // Header-Zeile: ID-Spalte finden
                        idColumnIndex = columns.indexOfFirst { it.trim().equals("id", ignoreCase = true) }
                        if (idColumnIndex < 0) {
                            logger.warn("referenzen.csv: Keine 'id'-Spalte im Header gefunden")
                            return ids
                        }
                        continue
                    }
                    if (idColumnIndex < columns.size) {
                        val id = columns[idColumnIndex].trim()
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("CSV-Index konnte nicht geladen werden: {}", e.message)
        }
        return ids
    }

    /**
     * Einzelne Referenz herunterladen: Sonogramm-PNG von Xeno-Canto.
     * Speichert in curated/{Art}/ und appended referenzen.csv.
     *
     * @param skipCsvAppend true wenn CSV-Eintrag bereits existiert (nur Datei neu laden)
     * @return true wenn erfolgreich gespeichert
     */
    private suspend fun downloadReference(
        recording: XenoCantoRecording,
        speciesFolder: String,
        skipCsvAppend: Boolean = false
    ): Boolean {
        val xcId = "XC${recording.id}"
        val speciesDir = File(curatedDir, speciesFolder).also { it.mkdirs() }
        val pngFile = File(speciesDir, "${xcId}_${speciesFolder}.png")

        // Sonogramm-URL auswaehlen (gross bevorzugt)
        val sonoUrl = when {
            recording.sono.large.isNotBlank() -> fixUrl(recording.sono.large)
            recording.sono.med.isNotBlank() -> fixUrl(recording.sono.med)
            recording.sono.full.isNotBlank() -> fixUrl(recording.sono.full)
            else -> return false
        }

        // PNG herunterladen
        val pngBytes = downloadBytes(sonoUrl, maxBytes = 2_000_000) ?: return false
        pngFile.writeBytes(pngBytes)

        // CSV-Eintrag anhaengen (nur wenn noch nicht vorhanden)
        if (!skipCsvAppend) {
            appendCsvEntry(
                id = xcId,
                art = speciesFolder,
                xcId = "Xeno-Canto $xcId",
                typ = recording.type,
                qualitaet = recording.q
            )
        }

        logger.debug("Referenz gespeichert: {} → {}", recording.id, pngFile.name)
        return true
    }

    /** CSV-Zeile an referenzen.csv anhaengen */
    private fun appendCsvEntry(id: String, art: String, xcId: String, typ: String, qualitaet: String) {
        // Header schreiben falls Datei nicht existiert
        if (!csvFile.exists() || csvFile.length() == 0L) {
            csvFile.writeText("id;art;quelle;typ;beschreibung;qualitaet\n")
        }
        csvFile.appendText("$id;$art;$xcId;$typ;;$qualitaet\n")
    }

    /**
     * Audio on-demand herunterladen (wenn User Referenz abspielen will).
     * Speichert WAV/MP3 neben dem PNG im gleichen Ordner.
     *
     * @param recording Die Referenz-Aufnahme aus der Library
     * @return Die lokale Audio-Datei (oder null bei Fehler)
     */
    suspend fun downloadAudioOnDemand(recording: ReferenceRecording): File? {
        // Schon vorhanden?
        if (recording.wavFile?.exists() == true) return recording.wavFile

        // Audio-URL aus Xeno-Canto suchen
        val xcId = recording.quelle.replace(Regex(".*XC"), "").trim()
        if (xcId.isBlank()) return null

        return try {
            val results = api.searchBySpecies(species = recording.scientificName, page = 1)
            val xcRecording = results.find { it.id == xcId } ?: return null
            val audioUrl = fixUrl(xcRecording.file)
            if (audioUrl.isBlank()) return null

            val speciesFolder = recording.scientificName.replace(' ', '_')
            val sourceDir = if (recording.source == "user") "user" else "curated"
            val targetDir = File(referencesDir, "$sourceDir/$speciesFolder").also { it.mkdirs() }
            val audioFile = File(targetDir, "${recording.id}_${speciesFolder}.mp3")

            val audioBytes = downloadBytes(audioUrl, maxBytes = 20_000_000) ?: return null
            audioFile.writeBytes(audioBytes)

            // Index aktualisieren
            library.rescan()
            audioFile
        } catch (e: Exception) {
            logger.warn("Audio-Download fehlgeschlagen fuer {}: {}", recording.id, e.message)
            null
        }
    }

    // ================================================================
    // Audio Batch-Download (Task 24)
    // ================================================================

    data class BatchResult(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean
    )

    private var audioBatchJob: Job? = null

    /**
     * Batch-Download von Audio-Dateien (MP3) fuer alle Referenzen im angegebenen Artenset.
     * Iteriert ueber alle Arten, laedt pro Art alle XC-IDs aus referenzen.csv.
     */
    suspend fun batchDownloadAudio(
        onProgress: (current: Int, total: Int, currentSpecies: String) -> Unit,
        cancellationToken: () -> Boolean = { false }
    ): BatchResult {
        val settings = SettingsStore.load()
        val regionSetId = settings.activeRegionSet

        // Alle Referenzen aus der Library laden
        val allRecordings = library.getAllRecordings()

        // Artenset-Filter anwenden
        val filtered = if (regionSetId == "all") allRecordings
        else allRecordings.filter { RegionSetRegistry.isSpeciesInSet(regionSetId, it.scientificName) }

        // Nur Curated-Eintraege mit XC-IDs (User-Aufnahmen brauchen keinen Download)
        val toProcess = filtered.filter { rec ->
            rec.source == "curated" && rec.quelle.contains("XC", ignoreCase = true)
        }

        val total = toProcess.size
        var downloaded = 0
        var skipped = 0
        var failed = 0

        for ((idx, recording) in toProcess.withIndex()) {
            if (cancellationToken()) {
                return BatchResult(downloaded, skipped, failed, cancelled = true)
            }

            onProgress(idx + 1, total, "${recording.scientificName} (${recording.id})")

            // Pruefen ob Audio schon existiert
            if (recording.wavFile?.exists() == true && recording.wavFile.length() > 0) {
                skipped++
                continue
            }

            // Audio herunterladen
            try {
                val result = downloadAudioOnDemand(recording)
                if (result != null) {
                    downloaded++
                } else {
                    failed++
                }
            } catch (_: Exception) {
                failed++
            }

            // Rate-Limiting: 500ms zwischen Downloads
            kotlinx.coroutines.delay(500)
        }

        return BatchResult(downloaded, skipped, failed, cancelled = false)
    }

    /**
     * Startet den Audio-Batch-Download als Job.
     */
    fun startAudioBatchDownload(
        onProgress: (current: Int, total: Int, currentSpecies: String) -> Unit,
        onComplete: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        if (audioBatchJob?.isActive == true) return

        audioBatchJob = scope.launch {
            val result = batchDownloadAudio(
                onProgress = onProgress,
                cancellationToken = { !isActive }
            )

            // Index neu generieren
            try { library.rescan() } catch (_: Exception) {}

            if (result.cancelled) {
                onCancel()
            } else {
                onComplete("Fertig: ${result.downloaded} geladen, ${result.skipped} vorhanden, ${result.failed} Fehler")
            }
        }
    }

    fun cancelAudioBatchDownload() {
        audioBatchJob?.cancel()
        audioBatchJob = null
    }

    /**
     * Zaehlt vorhandene und gesamte Audio-Dateien fuer ein Artenset.
     * @return Pair(vorhanden, gesamt)
     */
    fun getAudioStats(regionSetId: String): Pair<Int, Int> {
        val allRecordings = library.getAllRecordings()
        val filtered = if (regionSetId == "all") allRecordings
        else allRecordings.filter { RegionSetRegistry.isSpeciesInSet(regionSetId, it.scientificName) }

        val curated = filtered.filter { it.source == "curated" && it.quelle.contains("XC", ignoreCase = true) }
        val existing = curated.count { it.wavFile?.exists() == true && it.wavFile.length() > 0 }
        return Pair(existing, curated.size)
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _progress.value = _progress.value.copy(isRunning = false, phase = "Abgebrochen")
    }

    fun dispose() {
        cancelDownload()
        cancelAudioBatchDownload()
        scope.cancel()
    }

    companion object {
        private val qualityOrder = listOf("A", "B", "C", "D", "E")

        /** true wenn recordQuality mindestens so gut wie minQuality ist */
        internal fun meetsQuality(recordQuality: String, minQuality: String): Boolean {
            if (recordQuality.isBlank()) return true
            val recIdx = qualityOrder.indexOf(recordQuality.uppercase())
            val minIdx = qualityOrder.indexOf(minQuality.uppercase())
            return recIdx in 0..minIdx
        }

        internal fun fixUrl(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "https://$url"
        }

        internal fun downloadBytes(url: String, maxBytes: Int): ByteArray? {
            return try {
                val connection = URI(url).toURL().openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", "AMSEL/0.1 (Desktop Sonogram Comparison)")

                connection.getInputStream().use { input ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    var totalRead = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1 && totalRead < maxBytes) {
                        output.write(buffer, 0, read)
                        totalRead += read
                    }
                    output.toByteArray()
                }
            } catch (_: Exception) {
                null
            }
        }

        internal fun parseDuration(length: String): Float {
            val parts = length.split(":")
            return when (parts.size) {
                2 -> parts[0].toFloatOrNull()?.times(60)?.plus(parts[1].toFloatOrNull() ?: 0f) ?: 0f
                3 -> {
                    val h = parts[0].toFloatOrNull() ?: 0f
                    val m = parts[1].toFloatOrNull() ?: 0f
                    val s = parts[2].toFloatOrNull() ?: 0f
                    h * 3600 + m * 60 + s
                }
                else -> 0f
            }
        }
    }
}
