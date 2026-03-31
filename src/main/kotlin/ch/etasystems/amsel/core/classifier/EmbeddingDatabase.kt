package ch.etasystems.amsel.core.classifier

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ergebnis einer Embedding-Suche: Aufnahme-ID, Art, Aehnlichkeit und Rang.
 */
data class EmbeddingMatch(
    val recordingId: String,
    val species: String,
    val similarity: Float,  // 0..1 (Cosinus)
    val rank: Int
)

/**
 * Lokale Vektor-Datenbank fuer Embedding-basierte Aehnlichkeitssuche.
 *
 * Speichert Embeddings als Float-Arrays mit Metadaten (recordingId, species).
 * Persistenz via binaerer Datei (kein SQLite).
 *
 * Dateiformat (embeddings.bin):
 * - Header: 4 Bytes Magic ("BSED"), 4 Bytes Version (1),
 *   4 Bytes embeddingDim, 4 Bytes numEntries
 * - Pro Eintrag: 4 Bytes recordingId-Laenge, recordingId UTF-8 Bytes,
 *   4 Bytes species-Laenge, species UTF-8 Bytes,
 *   embeddingDim * 4 Bytes Float-Daten (Little-Endian)
 *
 * Suche: Cosinus-Aehnlichkeit (dot-product auf normalisierten Vektoren).
 * Fuer grosse Datenbanken (>100k): Optionaler Vorfilter nach Art-Gruppe.
 */
class EmbeddingDatabase(
    private val storageDir: File = defaultStorageDir()
) {
    companion object {
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'D'.code.toByte())
        private const val VERSION = 1

        fun defaultStorageDir(): File {
            val userHome = System.getProperty("user.home")
            return File(userHome, "Documents/AMSEL/embeddings").also { it.mkdirs() }
        }
    }

    // Interne Datenhaltung
    private data class EmbeddingEntry(
        val recordingId: String,
        val species: String,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EmbeddingEntry) return false
            return recordingId == other.recordingId
        }
        override fun hashCode(): Int = recordingId.hashCode()
    }

    private val entries = mutableListOf<EmbeddingEntry>()
    private var embeddingDim: Int = 0

    // Index fuer Art-basierte Vorfilterung (fuer grosse Datenbanken)
    private val speciesIndex = mutableMapOf<String, MutableList<Int>>()

    private val storageFile: File get() = File(storageDir, "embeddings.bin")

    init {
        storageDir.mkdirs()
    }

    /**
     * Fuegt ein Embedding zur Datenbank hinzu.
     * Ersetzt vorhandene Eintraege mit gleicher recordingId.
     */
    fun add(recordingId: String, species: String, embedding: FloatArray) {
        if (embedding.isEmpty()) return

        // Dimension konsistent halten
        if (embeddingDim == 0) {
            embeddingDim = embedding.size
        } else if (embedding.size != embeddingDim) {
            System.err.println(
                "[EmbeddingDatabase] Dimensionsmismatch: erwartet $embeddingDim, bekommen ${embedding.size}"
            )
            return
        }

        // Vorhandenen Eintrag ersetzen
        val existingIdx = entries.indexOfFirst { it.recordingId == recordingId }
        val entry = EmbeddingEntry(recordingId, species, embedding.copyOf())

        if (existingIdx >= 0) {
            val oldSpecies = entries[existingIdx].species
            entries[existingIdx] = entry

            // Species-Index aktualisieren
            speciesIndex[oldSpecies]?.remove(existingIdx)
            speciesIndex.getOrPut(species) { mutableListOf() }.add(existingIdx)
        } else {
            val idx = entries.size
            entries.add(entry)
            speciesIndex.getOrPut(species) { mutableListOf() }.add(idx)
        }
    }

    /**
     * Sucht die aehnlichsten Embeddings via Cosinus-Aehnlichkeit.
     *
     * @param query Normalisierter Query-Vektor
     * @param topN Maximale Anzahl Ergebnisse
     * @param speciesFilter Optionaler Art-Filter (nur diese Arten durchsuchen)
     * @return Liste der besten Treffer, absteigend nach Aehnlichkeit
     */
    fun search(
        query: FloatArray,
        topN: Int = 10,
        speciesFilter: Set<String>? = null
    ): List<EmbeddingMatch> {
        if (query.isEmpty() || entries.isEmpty()) return emptyList()
        if (query.size != embeddingDim) {
            System.err.println(
                "[EmbeddingDatabase] Query-Dimension ${query.size} passt nicht zur DB-Dimension $embeddingDim"
            )
            return emptyList()
        }

        // Indizes bestimmen (Vorfilterung nach Art bei grossen DBs)
        val indicesToSearch: List<Int> = if (speciesFilter != null && entries.size > 100_000) {
            // Partitionierte Suche: nur relevante Arten
            speciesFilter.flatMap { species ->
                speciesIndex[species] ?: emptyList()
            }
        } else {
            entries.indices.toList()
        }

        // Brute-Force Cosinus-Aehnlichkeit (dot-product auf normalisierten Vektoren)
        data class ScoredEntry(val index: Int, val similarity: Float)

        val scored = mutableListOf<ScoredEntry>()
        for (idx in indicesToSearch) {
            if (idx < 0 || idx >= entries.size) continue
            val entry = entries[idx]
            val sim = dotProduct(query, entry.embedding)
            // Cosinus [-1,1] → [0,1] fuer normalisierte Vektoren
            val normalizedSim = ((sim + 1f) / 2f).coerceIn(0f, 1f)
            scored.add(ScoredEntry(idx, normalizedSim))
        }

        // Top-N sortiert zurueckgeben
        return scored
            .sortedByDescending { it.similarity }
            .take(topN)
            .mapIndexed { rank, s ->
                val entry = entries[s.index]
                EmbeddingMatch(
                    recordingId = entry.recordingId,
                    species = entry.species,
                    similarity = s.similarity,
                    rank = rank + 1
                )
            }
    }

    /** Anzahl der Eintraege in der Datenbank */
    fun size(): Int = entries.size

    /** Embedding-Dimension (0 wenn leer) */
    fun dimension(): Int = embeddingDim

    /** Alle vorhandenen Arten */
    fun getSpecies(): Set<String> = speciesIndex.keys.toSet()

    // ================================================================
    // Batch-Aufbau aus Cache-Verzeichnis
    // ================================================================

    /**
     * Baut die Embedding-Datenbank aus gecachten Audio-Dateien auf.
     * Erwartet WAV/FLAC/MP3-Dateien im Cache-Verzeichnis.
     *
     * @param cacheDir Verzeichnis mit Audio-Dateien
     * @param extractor EmbeddingExtractor-Instanz
     * @param onProgress Callback mit (verarbeitet, gesamt)
     */
    fun buildFromCache(
        cacheDir: File,
        extractor: EmbeddingExtractor,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        if (!cacheDir.exists()) {
            System.err.println("[EmbeddingDatabase] Cache-Verzeichnis nicht gefunden: ${cacheDir.absolutePath}")
            return
        }

        val audioFiles = cacheDir.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("wav", "flac", "mp3")
        }?.toList() ?: emptyList()

        if (audioFiles.isEmpty()) {
            println("[EmbeddingDatabase] Keine Audio-Dateien im Cache gefunden")
            return
        }

        val total = audioFiles.size
        println("[EmbeddingDatabase] Verarbeite $total Audio-Dateien...")

        for ((index, file) in audioFiles.withIndex()) {
            try {
                // RecordingId aus Dateinamen extrahieren (z.B. "xc_12345.wav" → "12345")
                val recordingId = file.nameWithoutExtension
                    .removePrefix("xc_")
                    .removePrefix("XC")

                // Audio laden (vereinfacht — in echtem Code via AudioDecoder)
                // Hier nur Platzhalter, da AudioDecoder in der Praxis verwendet wird
                // val segment = AudioDecoder.decode(file)
                // val embedding = extractor.extract(segment.samples, segment.sampleRate)
                // add(recordingId, "unknown", embedding)

                onProgress(index + 1, total)
            } catch (e: Exception) {
                System.err.println("[EmbeddingDatabase] Fehler bei ${file.name}: ${e.message}")
            }
        }
    }

    // ================================================================
    // Persistenz: Binaeres Dateiformat
    // ================================================================

    /**
     * Speichert die Datenbank als binaere Datei.
     * Format: Header + N * (Metadaten + Float-Vektor)
     */
    fun save() {
        if (entries.isEmpty()) return

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(storageFile))).use { out ->
                // Header
                out.write(MAGIC)
                out.writeIntLE(VERSION)
                out.writeIntLE(embeddingDim)
                out.writeIntLE(entries.size)

                // Eintraege
                for (entry in entries) {
                    // RecordingId
                    val idBytes = entry.recordingId.toByteArray(Charsets.UTF_8)
                    out.writeIntLE(idBytes.size)
                    out.write(idBytes)

                    // Species
                    val speciesBytes = entry.species.toByteArray(Charsets.UTF_8)
                    out.writeIntLE(speciesBytes.size)
                    out.write(speciesBytes)

                    // Embedding (Little-Endian Floats)
                    val buf = ByteBuffer.allocate(entry.embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    buf.asFloatBuffer().put(entry.embedding)
                    out.write(buf.array())
                }
            }
            println("[EmbeddingDatabase] Gespeichert: ${entries.size} Eintraege, ${storageFile.length() / 1024} KB")
        } catch (e: Exception) {
            System.err.println("[EmbeddingDatabase] Speichern fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Laedt die Datenbank aus der binaeren Datei.
     */
    fun load() {
        if (!storageFile.exists()) return

        try {
            DataInputStream(BufferedInputStream(FileInputStream(storageFile))).use { inp ->
                // Header pruefen
                val magic = ByteArray(4)
                inp.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    System.err.println("[EmbeddingDatabase] Ungueltige Magic Bytes")
                    return
                }

                val version = inp.readIntLE()
                if (version != VERSION) {
                    System.err.println("[EmbeddingDatabase] Unbekannte Version: $version")
                    return
                }

                embeddingDim = inp.readIntLE()
                val numEntries = inp.readIntLE()

                entries.clear()
                speciesIndex.clear()

                for (i in 0 until numEntries) {
                    // RecordingId
                    val idLen = inp.readIntLE()
                    val idBytes = ByteArray(idLen)
                    inp.readFully(idBytes)
                    val recordingId = String(idBytes, Charsets.UTF_8)

                    // Species
                    val speciesLen = inp.readIntLE()
                    val speciesBytes = ByteArray(speciesLen)
                    inp.readFully(speciesBytes)
                    val species = String(speciesBytes, Charsets.UTF_8)

                    // Embedding
                    val embBytes = ByteArray(embeddingDim * 4)
                    inp.readFully(embBytes)
                    val embedding = FloatArray(embeddingDim)
                    ByteBuffer.wrap(embBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(embedding)

                    val idx = entries.size
                    entries.add(EmbeddingEntry(recordingId, species, embedding))
                    speciesIndex.getOrPut(species) { mutableListOf() }.add(idx)
                }

                println("[EmbeddingDatabase] Geladen: ${entries.size} Eintraege, Dim=$embeddingDim")
            }
        } catch (e: Exception) {
            System.err.println("[EmbeddingDatabase] Laden fehlgeschlagen: ${e.message}")
            entries.clear()
            speciesIndex.clear()
        }
    }

    // ================================================================
    // Hilfsmethoden
    // ================================================================

    /** Dot-Product zweier Float-Arrays */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            sum += a[i] * b[i]
        }
        return sum
    }

    /** Int als Little-Endian schreiben */
    private fun DataOutputStream.writeIntLE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        write(buf.array())
    }

    /** Int als Little-Endian lesen */
    private fun DataInputStream.readIntLE(): Int {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
