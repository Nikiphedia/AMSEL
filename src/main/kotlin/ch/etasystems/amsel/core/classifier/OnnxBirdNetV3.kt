package ch.etasystems.amsel.core.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ch.etasystems.amsel.core.audio.AudioResampler
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.resolvedModelDir
import ch.etasystems.amsel.core.audio.SilenceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer

/**
 * BirdNET V3.0 ONNX-Classifier.
 *
 * Laedt das BirdNET V3.0 ONNX-Modell und klassifiziert Audio-Segmente direkt
 * mit Raw-Audio-Input (kein Mel-Spektrogramm noetig).
 *
 * Das Modell erwartet:
 * - Input: [batch, samples] Float32, 32 kHz Mono
 * - Output: (embeddings, predictions) — predictions [batch, 11560]
 *
 * Standard-Modellpfad: ~/Documents/AMSEL/models/birdnet_v3.onnx
 * Labels: ~/Documents/AMSEL/models/birdnet_v3_labels.csv (Semikolon-CSV)
 */
/** Statistik eines classify()-Aufrufs (Chunk-Zaehler). */
data class ClassifyStats(
    val totalChunks: Int,
    val skippedChunks: Int,
    val classifiedChunks: Int
)

class OnnxBirdNetV3(
    modelPath: String? = null,
    labelsPath: String? = null
) : AudioClassifier {

    companion object {
        private const val TARGET_SAMPLE_RATE = 32000
        private const val DEFAULT_CHUNK_SEC = 3.0f
        private const val CHUNK_SAMPLES = (TARGET_SAMPLE_RATE * DEFAULT_CHUNK_SEC).toInt() // 96000

        private val logger = LoggerFactory.getLogger(OnnxBirdNetV3::class.java)

        /** Standard-Modellverzeichnis */
        private fun defaultModelsDir(): File {
            return SettingsStore.load().resolvedModelDir().also { it.mkdirs() }
        }

        /** Sucht das V3-Modell im Standard-Verzeichnis */
        fun findModel(): File? {
            val dir = defaultModelsDir()
            return listOf(
                "birdnet_v3.onnx",
                "BirdNET+_V3.0-preview3_Global_11K_FP16.onnx",
                "BirdNET+_V3.0-preview3_Global_11K_FP32.onnx"
            ).map { File(dir, it) }.firstOrNull { it.exists() }
        }

        /** Sucht die V3-Labels im Standard-Verzeichnis */
        fun findLabels(): File? {
            val dir = defaultModelsDir()
            return listOf(
                "birdnet_v3_labels.csv",
                "BirdNET+_V3.0-preview3_Global_11K_Labels.csv"
            ).map { File(dir, it) }.firstOrNull { it.exists() }
        }
    }

    override val name: String = "BirdNET V3.0"
    override val version: String = "preview3"
    override val inputSampleRate: Int = TARGET_SAMPLE_RATE

    private val modelFile: File
    private val labelsFile: File

    private var ortSession: OrtSession? = null
    private var sessionFailed = false
    private var scientificNames: List<String> = emptyList()
    private var commonNames: List<String> = emptyList()

    override val speciesCount: Int
        get() = scientificNames.size

    /** Statistik des letzten classify()-Aufrufs (Chunk-Zaehler fuer PerformanceLog). */
    var lastClassifyStats: ClassifyStats? = null
        private set

    init {
        modelFile = if (modelPath != null) File(modelPath) else (findModel() ?: File(defaultModelsDir(), "birdnet_v3.onnx"))
        labelsFile = if (labelsPath != null) File(labelsPath) else (findLabels() ?: File(defaultModelsDir(), "birdnet_v3_labels.csv"))
    }

    override fun isAvailable(): Boolean = modelFile.exists() && labelsFile.exists()

    override suspend fun classify(
        samples: FloatArray,
        sampleRate: Int,
        chunkDurationSec: Float,
        overlap: Float,
        minConfidence: Float,
        topN: Int
    ): List<ClassifierResult> {
        if (samples.isEmpty()) return emptyList()

        if (!isAvailable()) {
            logger.warn("Modell oder Labels nicht gefunden: model={}, labels={}", modelFile.absolutePath, labelsFile.absolutePath)
            return emptyList()
        }

        val session = ensureSession() ?: return emptyList()

        return try {
            // 1. Resample auf 32 kHz
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
                AudioResampler.resample(samples, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                samples
            }

            // 2. Chunk-Ranges berechnen (keine Kopien)
            val chunkSamples = (TARGET_SAMPLE_RATE * chunkDurationSec).toInt()
            val hopSamples = if (overlap > 0f) {
                (chunkSamples - (TARGET_SAMPLE_RATE * overlap).toInt()).coerceAtLeast(1)
            } else {
                chunkSamples
            }
            val chunkRanges = splitIntoChunkRanges(resampled.size, chunkSamples, hopSamples)

            // 2b. Stille-Detektion: Chunks unter Schwelle ueberspringen
            var skippedChunks = 0
            val activeRanges = chunkRanges.mapIndexedNotNull { index, range ->
                if (SilenceDetector.isSilent(resampled, range.first, range.last - range.first + 1)) {
                    skippedChunks++
                    null
                } else {
                    IndexedValue(index, range)
                }
            }

            lastClassifyStats = ClassifyStats(
                totalChunks = chunkRanges.size,
                skippedChunks = skippedChunks,
                classifiedChunks = chunkRanges.size - skippedChunks
            )

            if (skippedChunks > 0) {
                logger.info("Stille-Skip: {} von {} Chunks uebersprungen ({} %)",
                    skippedChunks, chunkRanges.size, (skippedChunks * 100 / chunkRanges.size))
            }

            // 3. Parallel klassifizieren (nur aktive Chunks)
            // Jede Coroutine kopiert ihren Chunk lokal — vermeidet shared-buffer Konflikte
            val allResults = coroutineScope {
                activeRanges.map { (index, range) ->
                    async(Dispatchers.Default) {
                        val chunk = FloatArray(chunkSamples)
                        val copyLen = range.last - range.first + 1
                        System.arraycopy(resampled, range.first, chunk, 0, copyLen)
                        // Rest bleibt 0 (Zero-Padding falls letzter Chunk kuerzer)
                        val startTimeSec = index * hopSamples.toFloat() / TARGET_SAMPLE_RATE
                        classifyChunk(session, chunk, startTimeSec, chunkDurationSec, minConfidence)
                    }
                }.awaitAll().flatten()
            }

            // 4. Filtern und sortieren
            val filtered = allResults.filter { it.confidence >= minConfidence }
            val sorted = filtered.sortedByDescending { it.confidence }
            if (topN > 0) sorted.take(topN) else sorted
        } catch (e: Exception) {
            logger.error("Klassifikation fehlgeschlagen", e)
            emptyList()
        }
    }

    /**
     * Klassifiziert einen einzelnen Audio-Chunk.
     */
    private fun classifyChunk(
        session: OrtSession,
        chunk: FloatArray,
        startTimeSec: Float,
        chunkDurationSec: Float,
        minConfidence: Float
    ): List<ClassifierResult> {
        val env = OrtEnvironment.getEnvironment()

        // Input-Tensor: [1, samples]
        val shape = longArrayOf(1, chunk.size.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), shape)

        return try {
            val inputName = session.inputNames.first()
            val result = session.run(mapOf(inputName to tensor))

            // Output: (embeddings, predictions) oder nur predictions
            val scores = extractPredictions(result, session)

            val endTimeSec = startTimeSec + chunkDurationSec
            val results = mutableListOf<ClassifierResult>()
            for (index in scores.indices) {
                val score = scores[index]
                if (score >= minConfidence && index < scientificNames.size) {
                    val sciName = scientificNames[index]
                    val comName = if (index < commonNames.size) commonNames[index] else ""
                    results.add(ClassifierResult(
                        species = "${sciName}_${comName}",
                        confidence = score,
                        startTime = startTimeSec,
                        endTime = endTimeSec,
                        scientificName = sciName
                    ))
                }
            }
            results
        } finally {
            tensor.close()
        }
    }

    /**
     * Extrahiert die Predictions aus dem ONNX-Output.
     * V3.0 hat 2 Outputs: (embeddings, predictions).
     * Wir nehmen den Tensor mit der passenden Label-Anzahl, oder den letzten.
     */
    private fun extractPredictions(result: OrtSession.Result, session: OrtSession): FloatArray {
        val outputNames = session.outputNames.toList()

        // Bei 2 Outputs: predictions ist der zweite (Index 1)
        // Bei 1 Output: predictions ist der einzige
        val predIndex = if (outputNames.size >= 2) 1 else 0

        // Alternativ: den Output suchen dessen Dimension zur Label-Anzahl passt
        for (i in outputNames.indices) {
            val output = result[i].value
            val scores = flattenOutput(output)
            if (scores != null && (scientificNames.isEmpty() || scores.size == scientificNames.size)) {
                return scores
            }
        }

        // Fallback: letzten Output nehmen
        val output = result[predIndex].value
        return flattenOutput(output) ?: FloatArray(0)
    }

    /**
     * Konvertiert ONNX-Output in ein flaches FloatArray.
     */
    private fun flattenOutput(output: Any?): FloatArray? {
        return when (output) {
            is Array<*> -> {
                // [batch, classes] → erste Batch-Zeile
                @Suppress("UNCHECKED_CAST")
                (output as? Array<FloatArray>)?.firstOrNull()
            }
            is FloatArray -> output
            else -> {
                logger.warn("Unbekanntes Output-Format: {}", output?.javaClass)
                null
            }
        }
    }

    /**
     * Audio in Chunk-Ranges aufteilen. Gibt Paare von (offset, length) zurueck
     * statt kopierte Arrays — die Daten werden direkt aus dem Quell-Array gelesen.
     */
    private fun splitIntoChunkRanges(
        totalSamples: Int,
        chunkSamples: Int,
        hopSamples: Int
    ): List<IntRange> {
        if (totalSamples <= 0) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var offset = 0

        while (offset < totalSamples) {
            val end = minOf(offset + chunkSamples, totalSamples)
            ranges.add(offset until end)
            offset += hopSamples
        }

        return ranges
    }

    /**
     * ONNX Session initialisieren (lazy) + Labels laden.
     */
    @Synchronized
    private fun ensureSession(): OrtSession? {
        if (ortSession != null) return ortSession
        if (sessionFailed) return null

        return try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            val session = env.createSession(modelFile.absolutePath, opts)
            ortSession = session

            // Labels laden
            loadLabels()

            // Modell-Info loggen
            logger.info("BirdNET V3.0 geladen: {}", modelFile.name)
            logger.info("  Input: {}", session.inputInfo.entries.joinToString { "${it.key}: ${it.value.info}" })
            logger.info("  Output: {}", session.outputInfo.entries.joinToString { "${it.key}: ${it.value.info}" })
            logger.info("  Labels: {} Arten", scientificNames.size)

            session
        } catch (e: Exception) {
            logger.error("Modell konnte nicht geladen werden: {}", modelFile.absolutePath, e)
            sessionFailed = true
            null
        }
    }

    /**
     * Labels aus CSV laden.
     * Format: idx;id;sci_name;com_name;class;order (Semikolon-Delimiter)
     */
    private fun loadLabels() {
        if (!labelsFile.exists()) {
            logger.warn("Labels-Datei nicht gefunden: {}", labelsFile.absolutePath)
            return
        }

        try {
            val sciNames = mutableListOf<String>()
            val comNames = mutableListOf<String>()

            BufferedReader(InputStreamReader(labelsFile.inputStream(), Charsets.UTF_8)).use { reader ->
                val headerLine = reader.readLine() ?: return
                val headers = headerLine.trimStart('\uFEFF').split(";")
                val sciIndex = headers.indexOf("sci_name")
                val comIndex = headers.indexOf("com_name")

                if (sciIndex < 0) {
                    logger.warn("Labels-CSV hat keine 'sci_name' Spalte. Header: {}", headers)
                    return
                }

                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val cols = line.split(";")
                    val sci = cols.getOrElse(sciIndex) { "" }.trim()
                    val com = if (comIndex >= 0) cols.getOrElse(comIndex) { "" }.trim() else ""

                    if (sci.isNotEmpty()) {
                        sciNames.add(sci)
                        comNames.add(com)
                    }
                }
            }

            scientificNames = sciNames
            commonNames = comNames
            logger.info("Labels geladen: {} Arten", scientificNames.size)
        } catch (e: Exception) {
            logger.error("Fehler beim Laden der Labels", e)
        }
    }

    override fun close() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            logger.error("Fehler beim Schliessen der ONNX-Session", e)
        }
    }
}
