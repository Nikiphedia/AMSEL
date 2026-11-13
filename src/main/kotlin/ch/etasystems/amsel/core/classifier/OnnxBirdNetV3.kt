package ch.etasystems.amsel.core.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ch.etasystems.amsel.core.audio.AudioResampler
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.SpeciesRegistry
import ch.etasystems.amsel.data.resolvedModelDir
import ch.etasystems.amsel.core.audio.SilenceDetector
import ch.etasystems.amsel.core.AmselDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * ONNX-Classifier fuer Roh-Audio-Modelle (kein Mel-Spektrogramm noetig).
 * Der Klassenname ist historisch: was das Modell erwartet (Rate, Fensterlaenge, Normierung,
 * Umrechnung, Label-Format), steht im [ModellAdapter]. BirdNET V3 ist die Vorgabe, Perch v2
 * die zweite Zeile der Tabelle.
 *
 * BirdNET V3 erwartet:
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
    labelsPath: String? = null,
    val adapter: ModellAdapter = ModellAdapterTabelle.BIRDNET_V3
) : AudioClassifier {

    companion object {
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

        /**
         * Klassifikator fuer eine ONNX-Datei im Modellordner. Die Modellart ergibt sich aus dem
         * Dateinamen, die Labels aus "<Name>_labels.csv" (bei BirdNET ersatzweise die bekannten Namen).
         * @return null wenn die Datei fehlt oder keine ONNX-Datei ist
         */
        fun fuerDatei(dateiname: String, ordner: File = defaultModelsDir()): OnnxBirdNetV3? {
            val datei = File(ordner, dateiname)
            if (!datei.isFile || datei.extension.lowercase() != "onnx") return null
            val adapter = ModellAdapterTabelle.fuerDatei(dateiname)
            val eigeneLabels = File(ordner, datei.nameWithoutExtension + "_labels.csv")
            val labels = when {
                eigeneLabels.exists() || adapter !== ModellAdapterTabelle.BIRDNET_V3 -> eigeneLabels.absolutePath
                else -> null  // BirdNET: bekannte Label-Dateien suchen
            }
            return OnnxBirdNetV3(datei.absolutePath, labels, adapter)
        }

        /**
         * Klassifikator fuer die Auswahl aus dem Modell-Dialog. Unbekannte, fehlende oder
         * unvollstaendige Auswahl: BirdNET wie bisher, nie ein Absturz.
         */
        fun fuerAuswahl(dateiname: String, ordner: File = defaultModelsDir()): OnnxBirdNetV3 {
            val gewaehlt = fuerDatei(dateiname, ordner)
            val brauchbar = gewaehlt != null && gewaehlt.isAvailable() &&
                (gewaehlt.adapter !== ModellAdapterTabelle.BIRDNET_V3 || dateiname.contains("birdnet", ignoreCase = true))
            if (brauchbar) return gewaehlt!!
            if (dateiname != "_python_bridge" && gewaehlt?.isAvailable() != true) {
                logger.warn("Gewaehltes Modell {} nicht verwendbar (Datei oder Labels fehlen) - nehme BirdNET", dateiname)
            }
            return OnnxBirdNetV3()
        }

        /**
         * Selbstpruefung fuer die Auswahl im Modell-Dialog.
         * @return null wenn das Modell aktiviert werden darf, sonst der Grund in Worten
         */
        fun pruefeAuswahl(dateiname: String, ordner: File = defaultModelsDir()): String? {
            if (dateiname == "_python_bridge") return null
            val klassifikator = fuerDatei(dateiname, ordner) ?: return "Modelldatei fehlt: $dateiname"
            return try { klassifikator.pruefen() } finally { klassifikator.close() }
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

    override val name: String = adapter.name
    override val version: String = adapter.version
    override val inputSampleRate: Int = adapter.sampleRate

    private val modelFile: File
    private val labelsFile: File

    private var ortSession: OrtSession? = null
    /** Name des Eingangs und des Prediction-Ausgangs, einmal beim Laden bestimmt. */
    private var inputName: String = ""
    private var predictionOutput: String? = null
    private var sessionFailed = false
    private var ladeFehler: String? = null
    private var labels = ModellLabels(emptyList(), emptyList(), BooleanArray(0))

    override val speciesCount: Int
        get() = labels.istArt.count { it }

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
        val zielRate = adapter.sampleRate
        // Modelle mit festem Eingang bestimmen die Fensterlaenge selbst
        @Suppress("NAME_SHADOWING")
        val chunkDurationSec = if (adapter.fensterFest) adapter.fensterSek else chunkDurationSec

        // Fehler werden nach aussen gegeben (nicht als leere Liste): sonst sieht ein defektes
        // Modell aus wie "keine Voegel", und der Aufrufer kann nicht auf die Python-Bridge ausweichen.
        if (!isAvailable()) {
            throw IllegalStateException(
                "${adapter.name}-Modell oder Labels nicht gefunden: ${modelFile.absolutePath}, ${labelsFile.absolutePath}"
            )
        }

        val session = ensureSession()
            ?: throw IllegalStateException("${adapter.name}-Modell konnte nicht geladen werden: ${modelFile.absolutePath}")

        return try {
            // 1. Resample auf 32 kHz
            val resampled = if (sampleRate != zielRate) {
                AudioResampler.resample(samples, sampleRate, zielRate, adapter.resamplerUmNyquist)
            } else {
                samples
            }

            // 2. Chunk-Ranges berechnen (keine Kopien)
            val chunkSamples = (zielRate * chunkDurationSec).toInt()
            val hopSamples = if (overlap > 0f) {
                (chunkSamples - (zielRate * overlap).toInt()).coerceAtLeast(1)
            } else {
                chunkSamples
            }
            val chunkRanges = splitIntoChunkRanges(resampled.size, chunkSamples, hopSamples)

            // 2b. Stille-Detektion: Chunks unter Schwelle ueberspringen (nur wenn in den Einstellungen aktiviert)
            val skipSilence = SettingsStore.load().birdnetSkipSilence
            var skippedChunks = 0
            val activeRanges = chunkRanges.mapIndexedNotNull { index, range ->
                if (skipSilence && SilenceDetector.isSilent(resampled, range.first, range.last - range.first + 1)) {
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
                    async(AmselDispatchers.Inference) {
                        val chunk = FloatArray(chunkSamples)
                        val copyLen = range.last - range.first + 1
                        System.arraycopy(resampled, range.first, chunk, 0, copyLen)
                        // Rest bleibt 0 (Zero-Padding falls letzter Chunk kuerzer)
                        adapter.spitze?.let { aufSpitzeNormieren(chunk, it) }
                        val startTimeSec = index * hopSamples.toFloat() / zielRate
                        classifyChunk(session, chunk, startTimeSec, chunkDurationSec, minConfidence)
                    }
                }.awaitAll().flatten()
            }

            // 4. Sortieren (Filterung passiert bereits in classifyChunk)
            val sorted = allResults.sortedByDescending { it.confidence }
            if (topN > 0) sorted.take(topN) else sorted
        } catch (e: CancellationException) {
            throw e  // Abbruch durchreichen, nicht als Fehler behandeln
        } catch (e: Exception) {
            logger.error("Klassifikation fehlgeschlagen", e)
            throw e
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
            // Das Result haelt nativen Speicher und muss geschlossen werden; die Scores sind eine Java-Kopie.
            // Ist der Prediction-Ausgang bekannt, wird nur er angefordert (keine Kopie der Embeddings).
            val predName = predictionOutput
            val roh = if (predName != null) {
                session.run(mapOf(inputName to tensor), setOf(predName)).use { result ->
                    flattenOutput(result[0].value) ?: FloatArray(0)
                }
            } else {
                session.run(mapOf(inputName to tensor)).use { result ->
                    extractPredictions(result, session)
                }
            }

            val endTimeSec = startTimeSec + chunkDurationSec

            // Alle Arten mit Index sammeln (Geraeuschklassen auslassen), Modellausgang in Wert 0..1
            // umrechnen und absteigend sortieren
            val scoredIndices = roh.indices
                .filter { it < labels.anzahl && labels.istArt[it] }
                .map { Pair(it, adapter.wert(roh[it])) }
                .sortedByDescending { it.second }

            // Mindestens 3 Kandidaten, aber alle ueber minConfidence
            val aboveThreshold = scoredIndices.filter { it.second >= minConfidence }
            val candidates = if (aboveThreshold.size >= 3) {
                aboveThreshold.take(10)
            } else {
                // Unter 3 ueber Schwelle: auffuellen mit naechstbesten
                scoredIndices.take(3.coerceAtLeast(aboveThreshold.size).coerceAtMost(10))
            }

            candidates.map { (index, score) ->
                val sciName = labels.wissenschaftlich[index]
                // Ohne Trivialname im Label (Perch): englischer Name aus dem Artenregister, damit dieselbe
                // Art bei beiden Modellen gleich beschriftet ist; sonst den wissenschaftlichen Namen wiederholen
                val comName = labels.trivial[index].ifEmpty {
                    SpeciesRegistry.getSpecies(sciName)?.commonNames?.get("en") ?: sciName
                }
                ClassifierResult(
                    species = "${sciName}_${comName}",
                    confidence = score,
                    startTime = startTimeSec,
                    endTime = endTimeSec,
                    scientificName = sciName,
                    modell = adapter.art
                )
            }
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
            if (scores != null && (labels.anzahl == 0 || scores.size == labels.anzahl)) {
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

    /** Skaliert das Fenster so, dass der groesste Betrag [spitze] ist. Reine Stille bleibt unveraendert. */
    private fun aufSpitzeNormieren(chunk: FloatArray, spitze: Float) {
        var max = 0f
        for (v in chunk) max = maxOf(max, abs(v))
        if (max <= 0f) return
        val faktor = spitze / max
        for (i in chunk.indices) chunk[i] *= faktor
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
            // Ein Thread je run(): die Parallelitaet kommt aus AmselDispatchers.Inference (viele Fenster
            // gleichzeitig). Gemessen in B3: schneller als wenige run() mit internem Thread-Pool.
            opts.setIntraOpNumThreads(1)

            val session = env.createSession(modelFile.absolutePath, opts)
            ortSession = session

            // Labels laden
            loadLabels()

            // Eingang und Prediction-Ausgang einmal bestimmen: der Ausgang, dessen letzte
            // Dimension zur Label-Anzahl passt. Nicht eindeutig -> null, dann wird je Fenster gesucht.
            inputName = session.inputNames.first()
            predictionOutput = session.outputInfo.entries.filter { (_, node) ->
                val shape = (node.info as? TensorInfo)?.shape
                shape != null && shape.isNotEmpty() && shape.last() == labels.anzahl.toLong()
            }.map { it.key }.singleOrNull()

            // Modell-Info loggen
            logger.info("{} geladen: {}", adapter.name, modelFile.name)
            logger.info("  Input: {}", session.inputInfo.entries.joinToString { "${it.key}: ${it.value.info}" })
            logger.info("  Output: {}", session.outputInfo.entries.joinToString { "${it.key}: ${it.value.info}" })

            session
        } catch (e: Exception) {
            logger.error("Modell konnte nicht geladen werden: {}", modelFile.absolutePath, e)
            sessionFailed = true
            ladeFehler = e.message
            ortSession?.close()
            ortSession = null
            null
        }
    }

    /** Labels im Format der Modellart laden. Fehler bleiben nicht still: ohne Labels ist das Modell unbrauchbar. */
    private fun loadLabels() {
        val synonyme = if (adapter.labelFormat == LabelFormat.NAMENSLISTE) ModellAdapterTabelle.perchSynonyme else emptyMap()
        labels = ModellAdapterTabelle.labelsLesen(labelsFile, adapter.labelFormat, synonyme)
        logger.info("Labels geladen: {} Klassen, davon {} Arten", labels.anzahl, labels.istArt.count { it })
    }

    /**
     * Selbstpruefung beim Aktivieren eines Modells: laden, Eingang und Ausgang gegen Modellart und
     * Labels pruefen, ein Fenster Stille rechnen.
     * @return null wenn alles passt, sonst der Grund in Worten
     */
    fun pruefen(): String? {
        if (!modelFile.exists()) return "Modelldatei fehlt: ${modelFile.name}"
        if (!labelsFile.exists()) return "Label-Datei fehlt: ${labelsFile.name}"
        val session = ensureSession() ?: return "Modell laesst sich nicht laden: ${ladeFehler ?: modelFile.name}"
        if (labels.anzahl == 0) return "Label-Datei ${labelsFile.name} enthaelt keine Namen"

        val fenster = (adapter.sampleRate * adapter.fensterSek).toInt()
        val eingang = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        val fest = eingang?.lastOrNull()?.takeIf { it > 0 }
        if (fest != null && fest != fenster.toLong()) {
            return "Modell erwartet $fest Samples je Fenster, ${adapter.name} rechnet mit $fenster"
        }
        if (predictionOutput == null) {
            return "Kein Ausgang des Modells hat ${labels.anzahl} Werte (so viele Namen stehen in ${labelsFile.name})"
        }
        return try {
            val start = System.nanoTime()
            classifyChunk(session, FloatArray(fenster), 0f, adapter.fensterSek, 0f)
            logger.info("Selbstpruefung {}: ein Fenster in {} ms", modelFile.name, (System.nanoTime() - start) / 1_000_000)
            null
        } catch (e: Exception) {
            "Proberechnung fehlgeschlagen: ${e.message}"
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
