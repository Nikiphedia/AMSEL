package ch.etasystems.amsel.data.reference

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.spectrogram.MelSpectrogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Ordnerbasierte Referenzbibliothek — ersetzt OfflineCache komplett.
 *
 * Verzeichnisstruktur:
 *   ~/Documents/AMSEL/references/
 *     curated/                     — Verwaltete Downloads (Xeno-Canto)
 *       Parus_major/
 *         000001_Parus_major.png
 *         000001_Parus_major.wav   (optional, on-demand)
 *     user/                        — Eigene Aufnahmen
 *       Parus_major/
 *         000010_Parus_major.wav
 *         000010_Parus_major.png   (wird generiert wenn fehlend)
 *     referenzen.csv               — Globale Knowledge Base
 *     reference_index.json         — Auto-generiert beim Startup
 */
class ReferenceLibrary(
    private val referencesDir: File = defaultReferencesDir()
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val curatedDir = File(referencesDir, "curated")
    private val userDir = File(referencesDir, "user")
    private val csvFile = File(referencesDir, "referenzen.csv")
    private val indexFile = File(referencesDir, "reference_index.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Alle Referenzen, indexiert nach wissenschaftlichem Namen (mit Leerzeichen) */
    private var index: Map<String, List<ReferenceRecording>> = emptyMap()

    // ================================================================
    // Oeffentliche API
    // ================================================================

    /** Beim App-Start aufrufen */
    suspend fun initialize() {
        referencesDir.mkdirs()
        curatedDir.mkdirs()
        userDir.mkdirs()
        loadOrScan()
        logger.info("ReferenceLibrary initialisiert: {} Arten, {} Aufnahmen",
            index.size, index.values.sumOf { it.size })
    }

    /** Arten-Liste (wissenschaftliche Namen, z.B. "Parus major") */
    fun getSpeciesList(): List<String> = index.keys.sorted()

    /** Referenzen fuer eine Art */
    fun getRecordingsForSpecies(scientificName: String): List<ReferenceRecording> =
        index[scientificName] ?: emptyList()

    /** Fuzzy-Suche (enthaelt, case-insensitive) */
    fun searchSpecies(query: String): List<String> {
        val q = query.lowercase()
        return index.keys.filter { it.lowercase().contains(q) }.sorted()
    }

    /** Anzahl Arten */
    fun getSpeciesCount(): Int = index.size

    /** Anzahl Aufnahmen */
    fun getRecordingCount(): Int = index.values.sumOf { it.size }

    /** Alle Aufnahmen (flat) */
    fun getAllRecordings(): List<ReferenceRecording> = index.values.flatten()

    /** Index neu generieren (z.B. nach Download) */
    suspend fun rescan() {
        indexFile.delete()
        val scanned = scanFolders()
        index = scanned
        writeIndex(scanned)
        logger.info("ReferenceLibrary rescan: {} Arten, {} Aufnahmen",
            index.size, index.values.sumOf { it.size })
    }

    /** Naechste freie 6-stellige ID ermitteln */
    fun nextId(): String {
        val maxId = index.values.flatten()
            .mapNotNull { it.id.toIntOrNull() }
            .maxOrNull() ?: 0
        return (maxId + 1).toString().padStart(6, '0')
    }

    /** Referenz-Verzeichnis (fuer Downloader) */
    fun getReferencesDir(): File = referencesDir

    // ================================================================
    // Interne Methoden
    // ================================================================

    /** JSON-Index laden oder Ordner scannen */
    private suspend fun loadOrScan() {
        val loaded = readIndex()
        if (loaded != null) {
            index = loaded
            logger.debug("JSON-Index geladen: {} Arten", index.size)
        } else {
            logger.debug("Kein JSON-Index — starte Ordner-Scan")
            val scanned = scanFolders()
            index = scanned
            writeIndex(scanned)
        }
    }

    /** Ordner rekursiv scannen, Dateien nach ID gruppieren, CSV-Metadaten zuordnen */
    private suspend fun scanFolders(): Map<String, List<ReferenceRecording>> =
        withContext(Dispatchers.IO) {
            val csvEntries = readCsv()
            val result = mutableMapOf<String, MutableList<ReferenceRecording>>()

            // Beide Quellen scannen
            for ((sourceDir, sourceName) in listOf(curatedDir to "curated", userDir to "user")) {
                if (!sourceDir.exists()) continue

                // Art-Ordner durchlaufen (z.B. Parus_major/)
                val speciesDirs = sourceDir.listFiles { f -> f.isDirectory } ?: continue
                for (speciesDir in speciesDirs) {
                    val speciesUnderscore = speciesDir.name // "Parus_major"
                    val scientificName = speciesUnderscore.replace('_', ' ') // "Parus major"

                    // Dateien nach ID gruppieren
                    val files = speciesDir.listFiles { f -> f.isFile } ?: continue
                    val byId = mutableMapOf<String, MutableMap<String, File>>()

                    for (file in files) {
                        val id = extractId(file.name) ?: continue
                        val ext = file.extension.lowercase()
                        byId.getOrPut(id) { mutableMapOf() }[ext] = file
                    }

                    for ((id, fileMap) in byId) {
                        val pngFile = fileMap["png"]
                        val wavFile = fileMap["wav"] ?: fileMap["mp3"] ?: fileMap["flac"]

                        // Fehlende PNG generieren (WAV vorhanden aber kein PNG)
                        val resolvedPng = if (pngFile == null && wavFile != null) {
                            val targetPng = File(speciesDir, "${id}_${speciesUnderscore}.png")
                            try {
                                generatePng(wavFile, targetPng)
                                targetPng
                            } catch (e: Exception) {
                                logger.warn("PNG-Generierung fehlgeschlagen fuer {}: {}", wavFile.name, e.message)
                                null
                            }
                        } else {
                            pngFile
                        }

                        val csv = csvEntries[id]
                        val recording = ReferenceRecording(
                            id = id,
                            scientificName = scientificName,
                            source = sourceName,
                            pngFile = resolvedPng,
                            wavFile = wavFile,
                            quelle = csv?.quelle ?: "",
                            typ = csv?.typ ?: "",
                            beschreibung = csv?.beschreibung ?: "",
                            qualitaet = csv?.qualitaet ?: ""
                        )

                        result.getOrPut(scientificName) { mutableListOf() }.add(recording)
                    }
                }
            }

            result
        }

    /** referenzen.csv lesen — Semikolon-Delimiter, UTF-8 (optionaler BOM) */
    private fun readCsv(): Map<String, CsvEntry> {
        if (!csvFile.exists()) return emptyMap()

        val entries = mutableMapOf<String, CsvEntry>()
        try {
            val lines = csvFile.readText(Charsets.UTF_8)
                .trimStart('\uFEFF') // BOM entfernen
                .lines()
                .filter { it.isNotBlank() }

            if (lines.size < 2) return emptyMap()

            // Header parsen
            val headers = lines[0].split(";").map { it.trim().lowercase() }
            val idIdx = headers.indexOf("id")
            val artIdx = headers.indexOf("art")
            val quelleIdx = headers.indexOf("quelle")
            val typIdx = headers.indexOf("typ")
            val beschreibungIdx = headers.indexOf("beschreibung")
            val qualitaetIdx = headers.indexOf("qualitaet")

            if (idIdx < 0 || artIdx < 0) {
                logger.warn("referenzen.csv: Pflicht-Spalten 'id' und 'art' fehlen")
                return emptyMap()
            }

            for (i in 1 until lines.size) {
                val cols = lines[i].split(";")
                val id = cols.getOrElse(idIdx) { "" }.trim()
                if (id.isBlank()) continue

                entries[id] = CsvEntry(
                    id = id,
                    art = cols.getOrElse(artIdx) { "" }.trim(),
                    quelle = if (quelleIdx >= 0) cols.getOrElse(quelleIdx) { "" }.trim() else "",
                    typ = if (typIdx >= 0) cols.getOrElse(typIdx) { "" }.trim() else "",
                    beschreibung = if (beschreibungIdx >= 0) cols.getOrElse(beschreibungIdx) { "" }.trim() else "",
                    qualitaet = if (qualitaetIdx >= 0) cols.getOrElse(qualitaetIdx) { "" }.trim() else ""
                )
            }
        } catch (e: Exception) {
            logger.warn("Fehler beim Lesen von referenzen.csv: {}", e.message)
        }
        return entries
    }

    /** JSON-Index schreiben */
    private fun writeIndex(data: Map<String, List<ReferenceRecording>>) {
        try {
            val species = data.map { (sciName, recordings) ->
                val folderName = sciName.replace(' ', '_')
                folderName to IndexSpecies(
                    scientificName = sciName,
                    recordings = recordings.map { rec ->
                        IndexRecording(
                            id = rec.id,
                            source = rec.source,
                            pngPath = rec.pngFile?.let { relativePath(it) } ?: "",
                            wavPath = rec.wavFile?.let { relativePath(it) } ?: "",
                            hasAudio = rec.wavFile != null,
                            quelle = rec.quelle,
                            typ = rec.typ,
                            beschreibung = rec.beschreibung,
                            qualitaet = rec.qualitaet
                        )
                    }
                )
            }.toMap()

            val idx = ReferenceIndex(
                version = 1,
                generatedAt = java.time.LocalDateTime.now().toString(),
                species = species,
                totalSpecies = data.size,
                totalRecordings = data.values.sumOf { it.size }
            )

            indexFile.writeText(json.encodeToString(ReferenceIndex.serializer(), idx))
            logger.debug("JSON-Index geschrieben: {}", indexFile.absolutePath)
        } catch (e: Exception) {
            logger.warn("Fehler beim Schreiben von reference_index.json: {}", e.message)
        }
    }

    /** JSON-Index laden */
    private fun readIndex(): Map<String, List<ReferenceRecording>>? {
        if (!indexFile.exists()) return null
        return try {
            val idx = json.decodeFromString<ReferenceIndex>(indexFile.readText())
            idx.species.map { (_, speciesData) ->
                speciesData.scientificName to speciesData.recordings.map { rec ->
                    ReferenceRecording(
                        id = rec.id,
                        scientificName = speciesData.scientificName,
                        source = rec.source,
                        pngFile = if (rec.pngPath.isNotBlank()) File(referencesDir, rec.pngPath).takeIf { it.exists() } else null,
                        wavFile = if (rec.wavPath.isNotBlank()) File(referencesDir, rec.wavPath).takeIf { it.exists() } else null,
                        quelle = rec.quelle,
                        typ = rec.typ,
                        beschreibung = rec.beschreibung,
                        qualitaet = rec.qualitaet
                    )
                }
            }.toMap()
        } catch (e: Exception) {
            logger.warn("JSON-Index beschaedigt — wird neu generiert: {}", e.message)
            null
        }
    }

    /** PNG aus WAV generieren (Mel-Spektrogramm, 800x400, Magma-Colormap) */
    private suspend fun generatePng(wavFile: File, pngFile: File) {
        val segment = AudioDecoder.decode(wavFile) ?: throw IllegalStateException("Audio nicht dekodierbar: ${wavFile.name}")
        val mel = MelSpectrogram.bird(segment.sampleRate)
        val spectro = mel.compute(segment.samples)

        val width = 800
        val height = 400
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

        for (x in 0 until width) {
            val frame = (x.toFloat() / width * spectro.nFrames).toInt().coerceIn(0, spectro.nFrames - 1)
            for (y in 0 until height) {
                val melBin = ((height - 1 - y).toFloat() / height * spectro.nMels).toInt().coerceIn(0, spectro.nMels - 1)
                val value = spectro.valueAt(melBin, frame)
                val norm = ((value - spectro.minValue) / (spectro.maxValue - spectro.minValue).coerceAtLeast(1e-6f))
                    .coerceIn(0f, 1f)
                img.setRGB(x, y, magmaColor(norm))
            }
        }

        ImageIO.write(img, "png", pngFile)
        logger.debug("PNG generiert: {}", pngFile.name)
    }

    /** Pfad relativ zum references/-Verzeichnis */
    private fun relativePath(file: File): String {
        return referencesDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    }

    /** ID aus Dateiname extrahieren: XC-ID (z.B. "XC994347") oder 6-stellige Nummer ("000001") */
    private fun extractId(filename: String): String? {
        val match = Regex("^(XC\\d+|\\d{6})_").find(filename) ?: return null
        return match.groupValues[1]
    }

    companion object {
        fun defaultReferencesDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, "Documents/AMSEL/references")
        }

        /** Magma-Colormap (vereinfacht, 5 Stuetzpunkte) */
        private fun magmaColor(t: Float): Int {
            // Schwarz → Dunkelviolett → Rot → Orange → Gelb
            val r: Int; val g: Int; val b: Int
            when {
                t < 0.25f -> {
                    val s = t / 0.25f
                    r = (s * 80).roundToInt()
                    g = 0
                    b = (s * 120).roundToInt()
                }
                t < 0.5f -> {
                    val s = (t - 0.25f) / 0.25f
                    r = (80 + s * 120).roundToInt()
                    g = (s * 20).roundToInt()
                    b = (120 - s * 40).roundToInt()
                }
                t < 0.75f -> {
                    val s = (t - 0.5f) / 0.25f
                    r = (200 + s * 55).roundToInt()
                    g = (20 + s * 100).roundToInt()
                    b = (80 - s * 60).roundToInt()
                }
                else -> {
                    val s = (t - 0.75f) / 0.25f
                    r = 255
                    g = (120 + s * 115).roundToInt()
                    b = (20 + s * 80).roundToInt()
                }
            }
            return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
}

// ================================================================
// Datenklassen
// ================================================================

data class ReferenceRecording(
    val id: String,                    // "000001"
    val scientificName: String,        // "Parus major"
    val source: String,                // "curated" oder "user"
    val pngFile: File?,                // Absoluter Pfad zum Sonogramm
    val wavFile: File?,                // Absoluter Pfad zum Audio (null wenn nicht vorhanden)
    val quelle: String = "",           // "Xeno-Canto XC456789"
    val typ: String = "",              // "Gesang"
    val beschreibung: String = "",
    val qualitaet: String = ""         // "A"
)

internal data class CsvEntry(
    val id: String,
    val art: String,
    val quelle: String = "",
    val typ: String = "",
    val beschreibung: String = "",
    val qualitaet: String = ""
)

// ================================================================
// JSON-Index Serialisierung
// ================================================================

@Serializable
internal data class ReferenceIndex(
    val version: Int = 1,
    val generatedAt: String = "",
    val species: Map<String, IndexSpecies> = emptyMap(),
    val totalSpecies: Int = 0,
    val totalRecordings: Int = 0
)

@Serializable
internal data class IndexSpecies(
    val scientificName: String,
    val recordings: List<IndexRecording> = emptyList()
)

@Serializable
internal data class IndexRecording(
    val id: String,
    val source: String,
    val pngPath: String = "",
    val wavPath: String = "",
    val hasAudio: Boolean = false,
    val quelle: String = "",
    val typ: String = "",
    val beschreibung: String = "",
    val qualitaet: String = ""
)
