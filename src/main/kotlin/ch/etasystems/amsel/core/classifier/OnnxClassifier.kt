package ch.etasystems.amsel.core.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ch.etasystems.amsel.core.audio.AudioResampler
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.min

/**
 * Ergebnis einer Klassifikation: Artname + Konfidenz (0..1).
 */
data class ClassifierResult(
    val species: String,
    val confidence: Float,
    val startTime: Float = 0f,
    val endTime: Float = 0f,
    val scientificName: String = ""
)

/**
 * ONNX-basierter Vogel-Classifier (BirdNET-kompatibel).
 *
 * Laed ein ONNX-Modell und klassifiziert Audio-Segmente.
 * Das Modell muss der User selbst bereitstellen (BirdNET-Analyzer von GitHub).
 * Wir liefern es NICHT mit der App aus.
 *
 * Standard-Modellpfad: Dokumente/AMSEL/models/birdnet.onnx
 * Optionale Label-Datei:  Dokumente/AMSEL/models/birdnet_labels.txt (eine Art pro Zeile)
 *
 * BirdNET erwartet:
 * - 3-Sekunden Audio-Chunks
 * - 48 kHz Samplerate
 * - Mel-Spektrogramm mit Frequenzbereich 40-15000 Hz
 *
 * Graceful Fallback: Wenn kein Modell vorhanden ist, wird eine leere Liste
 * zurueckgegeben und eine Warnung geloggt.
 */
class OnnxClassifier(
    modelPath: String? = null
) {
    companion object {
        // BirdNET Audio-Parameter
        private const val TARGET_SAMPLE_RATE = 48000
        private const val CHUNK_DURATION_SEC = 3.0f
        private const val CHUNK_SAMPLES = (TARGET_SAMPLE_RATE * CHUNK_DURATION_SEC).toInt()

        // BirdNET Mel-Spektrogramm Parameter
        private const val N_FFT = 2048
        private const val HOP_SIZE = 512
        private const val N_MELS = 64
        private const val F_MIN = 40f
        private const val F_MAX = 15000f

        // Standard Top-N Ergebnisse
        private const val DEFAULT_TOP_N = 10
    }

    private val modelFile: File
    private val labelsFile: File

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var labels: List<String> = emptyList()

    init {
        val basePath = modelPath ?: run {
            val userHome = System.getProperty("user.home")
            val modelsDir = File(userHome, "Documents/AMSEL/models")
            val found = listOf("birdnet_v3.onnx", "birdnet.onnx")
                .map { File(modelsDir, it) }.firstOrNull { it.exists() }
                ?: modelsDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            (found?.absolutePath ?: File(modelsDir, "birdnet_v3.onnx").absolutePath)
        }
        modelFile = File(basePath)
        labelsFile = File(modelFile.parentFile, "birdnet_labels.txt")
    }

    /** Prueft ob das ONNX-Modell vorhanden ist */
    fun isModelAvailable(): Boolean = modelFile.exists()

    /**
     * Klassifiziert ein Audio-Segment und gibt die Top-N Ergebnisse zurueck.
     *
     * @param samples PCM-Audiodaten (Mono, Float [-1..1])
     * @param sampleRate Samplerate der Eingabe
     * @param topN Anzahl der besten Ergebnisse (Standard: 10)
     * @return Liste der wahrscheinlichsten Arten, absteigend nach Konfidenz.
     *         Leere Liste wenn kein Modell vorhanden oder Fehler auftreten.
     */
    fun classify(
        samples: FloatArray,
        sampleRate: Int,
        topN: Int = DEFAULT_TOP_N
    ): List<ClassifierResult> {
        if (samples.isEmpty()) return emptyList()

        // Graceful Fallback: Kein Modell vorhanden
        if (!isModelAvailable()) {
            System.err.println(
                "[OnnxClassifier] Kein Modell gefunden unter: ${modelFile.absolutePath}. " +
                "Bitte BirdNET-Analyzer Modell von GitHub herunterladen und dort ablegen."
            )
            return emptyList()
        }

        val session = ensureSession() ?: return emptyList()

        return try {
            // 1. Audio auf 48 kHz resamplen wenn noetig
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
                AudioResampler.resample(samples, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                samples
            }

            // 2. In 3-Sekunden-Chunks aufteilen (BirdNET-Format)
            val chunks = splitIntoChunks(resampled)

            // 3. Jeden Chunk klassifizieren und Ergebnisse aggregieren
            val aggregated = mutableMapOf<String, Float>()
            for (chunk in chunks) {
                val melSpec = computeMelSpectrogram(chunk)
                val predictions = runInference(session, melSpec)

                for ((species, confidence) in predictions) {
                    val current = aggregated[species] ?: 0f
                    // Maximale Konfidenz ueber alle Chunks nehmen
                    aggregated[species] = maxOf(current, confidence)
                }
            }

            // 4. Top-N sortiert zurueckgeben
            aggregated.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { ClassifierResult(species = it.key, confidence = it.value) }
        } catch (e: Exception) {
            System.err.println("[OnnxClassifier] Klassifikation fehlgeschlagen: ${e.message}")
            emptyList()
        }
    }

    /**
     * ONNX Runtime Session initialisieren (lazy).
     * Laedt auch die Label-Datei wenn vorhanden.
     */
    private fun ensureSession(): OrtSession? {
        if (ortSession != null) return ortSession

        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val session = env.createSession(modelFile.absolutePath)
            ortSession = session

            // Labels laden (optional)
            if (labelsFile.exists()) {
                labels = labelsFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                println("[OnnxClassifier] Modell geladen: ${modelFile.name}, ${labels.size} Labels")
            } else {
                System.err.println(
                    "[OnnxClassifier] Keine Label-Datei gefunden: ${labelsFile.absolutePath}. " +
                    "Ergebnisse werden als Index zurueckgegeben."
                )
            }

            session
        } catch (e: Exception) {
            System.err.println("[OnnxClassifier] Modell konnte nicht geladen werden: ${e.message}")
            null
        }
    }

    /**
     * Audio in 3-Sekunden-Chunks aufteilen.
     * Letzter Chunk wird mit Nullen gepaddet wenn noetig.
     */
    private fun splitIntoChunks(samples: FloatArray): List<FloatArray> {
        if (samples.size <= CHUNK_SAMPLES) {
            // Einzelner Chunk, ggf. zero-padden
            val chunk = FloatArray(CHUNK_SAMPLES)
            samples.copyInto(chunk, 0, 0, min(samples.size, CHUNK_SAMPLES))
            return listOf(chunk)
        }

        val chunks = mutableListOf<FloatArray>()
        var offset = 0
        while (offset < samples.size) {
            val chunk = FloatArray(CHUNK_SAMPLES)
            val copyLen = min(CHUNK_SAMPLES, samples.size - offset)
            System.arraycopy(samples, offset, chunk, 0, copyLen)
            chunks.add(chunk)
            offset += CHUNK_SAMPLES
        }
        return chunks
    }

    /**
     * Mel-Spektrogramm berechnen (BirdNET-kompatibel).
     * 48 kHz, 40-15000 Hz, 64 Mel-Bins.
     */
    private fun computeMelSpectrogram(samples: FloatArray): FloatArray {
        val nFrames = (samples.size - N_FFT) / HOP_SIZE + 1
        if (nFrames <= 0) return FloatArray(0)

        val melFilters = createMelFilterbank()
        val window = FloatArray(N_FFT) { i ->
            // Hann-Fenster
            (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (N_FFT - 1)))).toFloat()
        }

        val fft = org.jtransforms.fft.DoubleFFT_1D(N_FFT.toLong())
        val numBins = N_FFT / 2 + 1
        val matrix = FloatArray(N_MELS * nFrames)

        for (frame in 0 until nFrames) {
            val offset = frame * HOP_SIZE
            val fftBuffer = DoubleArray(N_FFT * 2)

            // Hann-Fenster anwenden
            for (i in 0 until N_FFT) {
                val idx = offset + i
                fftBuffer[i] = if (idx < samples.size) {
                    (samples[idx] * window[i]).toDouble()
                } else {
                    0.0
                }
            }

            // FFT berechnen
            fft.realForwardFull(fftBuffer)

            // Power-Spektrum
            val power = FloatArray(numBins)
            for (k in 0 until numBins) {
                val re = fftBuffer[2 * k].toFloat()
                val im = fftBuffer[2 * k + 1].toFloat()
                power[k] = re * re + im * im
            }

            // Mel-Filter anwenden
            for (m in 0 until N_MELS) {
                var energy = 0f
                for (k in 0 until numBins) {
                    energy += melFilters[m][k] * power[k]
                }
                // Log-Skalierung
                matrix[m * nFrames + frame] = log10(energy + 1e-10f)
            }
        }

        return matrix
    }

    /**
     * ONNX Inferenz ausfuehren.
     * Input:  [1, 1, N_MELS, nFrames] — Mel-Spektrogramm
     * Output: [1, numClasses] — Wahrscheinlichkeiten pro Art
     */
    private fun runInference(
        session: OrtSession,
        melSpec: FloatArray
    ): List<ClassifierResult> {
        val env = ortEnv ?: return emptyList()
        if (melSpec.isEmpty()) return emptyList()

        val nFrames = melSpec.size / N_MELS
        val shape = longArrayOf(1, 1, N_MELS.toLong(), nFrames.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(melSpec), shape)

        return try {
            val inputName = session.inputNames.first()
            val result = session.run(mapOf(inputName to tensor))
            val output = result[0].value

            // Output als Float-Array lesen
            val scores = when (output) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (output as Array<FloatArray>)[0]
                }
                is FloatArray -> output
                else -> {
                    System.err.println("[OnnxClassifier] Unbekanntes Output-Format: ${output?.javaClass}")
                    return emptyList()
                }
            }

            // Scores in ClassifierResult umwandeln
            scores.mapIndexed { index, score ->
                val speciesName = if (index < labels.size) {
                    labels[index]
                } else {
                    "species_$index"
                }
                ClassifierResult(species = speciesName, confidence = score)
            }.filter { it.confidence > 0.01f }  // Nur relevante Ergebnisse
        } catch (e: Exception) {
            System.err.println("[OnnxClassifier] Inferenz fehlgeschlagen: ${e.message}")
            emptyList()
        } finally {
            tensor.close()
        }
    }

    /**
     * Mel-Filterbank erstellen (Dense-Matrix).
     * BirdNET-kompatibel: 40-15000 Hz, 64 Mel-Bins.
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val numBins = N_FFT / 2 + 1
        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        // Gleichmaessig verteilte Mel-Punkte
        val melPoints = FloatArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }

        // In FFT-Bin-Indizes umrechnen
        val binPoints = IntArray(N_MELS + 2) { i ->
            ((melPoints[i] * N_FFT / TARGET_SAMPLE_RATE).toInt()).coerceIn(0, numBins - 1)
        }

        // Dreiecks-Filterbank aufbauen
        return Array(N_MELS) { m ->
            val filter = FloatArray(numBins)

            // Ansteigende Flanke
            for (k in binPoints[m]..binPoints[m + 1]) {
                val range = binPoints[m + 1] - binPoints[m]
                if (range > 0) {
                    filter[k] = (k - binPoints[m]).toFloat() / range
                }
            }

            // Abfallende Flanke
            for (k in binPoints[m + 1]..binPoints[m + 2]) {
                val range = binPoints[m + 2] - binPoints[m + 1]
                if (range > 0) {
                    filter[k] = (binPoints[m + 2] - k).toFloat() / range
                }
            }

            filter
        }
    }

    /** Hz zu Mel-Skala */
    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)

    /** Mel-Skala zu Hz */
    private fun melToHz(mel: Float): Float =
        700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    /**
     * Ressourcen freigeben.
     * Sollte aufgerufen werden wenn der Classifier nicht mehr benoetigt wird.
     */
    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            // OrtEnvironment wird global verwaltet, nicht schliessen
        } catch (e: Exception) {
            System.err.println("[OnnxClassifier] Fehler beim Schliessen: ${e.message}")
        }
    }
}
