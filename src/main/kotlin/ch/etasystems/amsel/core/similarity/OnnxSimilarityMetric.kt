package ch.etasystems.amsel.core.similarity

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.resolvedModelDir
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ONNX-basierte Aehnlichkeitsmetrik.
 * Nutzt ein vortrainiertes Modell (z.B. BirdNET-Lite, EfficientNet)
 * zur Feature-Extraktion aus Mel-Spektrogrammen.
 *
 * Das Modell wird erwartet unter: Documents/AMSEL/models/birdnet.onnx
 * Input:  [1, 1, nMels, nFrames] — Mel-Spektrogramm
 * Output: [1, embeddingSize] — Feature-Embedding
 *
 * Wenn kein Modell vorhanden: Fallback auf Mel-Spektrogramm-Embedding
 * (komprimierter Feature-Vektor aus Mel-Mittelwerten + Varianz).
 */
object OnnxSimilarityMetric : SimilarityMetric {
    override val cacheKey: String = "onnx_embed"
    override val displayName: String = "ONNX Neural"

    // Mel-Spektrogramm Parameter fuer Feature-Extraktion
    private const val N_MELS = 80
    private const val N_FFT = 1024
    private const val HOP_SIZE = 512
    private const val EMBEDDING_SIZE = 160  // N_MELS * 2 (mean + variance)

    // Maximale Audio-Laenge: 5 Sekunden
    private const val MAX_SAMPLES = 5 * 48000

    private val modelDir: File by lazy {
        SettingsStore.load().resolvedModelDir().also { it.mkdirs() }
    }

    private val modelFile: File get() {
        val candidates = listOf("birdnet_v3.onnx", "birdnet.onnx")
        return candidates.map { File(modelDir, it) }.firstOrNull { it.exists() }
            ?: modelDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            ?: File(modelDir, "birdnet_v3.onnx")
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /** Prueft ob das ONNX-Modell vorhanden ist */
    fun isModelAvailable(): Boolean = modelFile.exists()

    /** Initialisiert ONNX Runtime Session (lazy) */
    private fun ensureSession(): OrtSession? {
        if (ortSession != null) return ortSession
        if (!isModelAvailable()) return null

        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val session = env.createSession(modelFile.absolutePath)
            ortSession = session
            session
        } catch (e: Exception) {
            System.err.println("[ONNX] Modell konnte nicht geladen werden: ${e.message}")
            null
        }
    }

    override fun extractFeatures(samples: FloatArray, sampleRate: Int): ByteArray {
        // Audio auf max. 5 Sekunden begrenzen
        val trimmed = if (samples.size > MAX_SAMPLES) samples.copyOfRange(0, MAX_SAMPLES) else samples

        // Mel-Spektrogramm berechnen
        val melSpec = computeMelSpectrogram(trimmed, sampleRate)

        // Versuche ONNX-Modell
        val session = ensureSession()
        if (session != null) {
            return extractOnnxEmbedding(session, melSpec)
        }

        // Fallback: Statistisches Embedding aus Mel-Spektrogramm
        return extractStatisticalEmbedding(melSpec)
    }

    /**
     * ONNX-basierte Feature-Extraktion.
     * Fuettert das Mel-Spektrogramm ins Modell und liest das Embedding aus.
     */
    private fun extractOnnxEmbedding(session: OrtSession, melSpec: Array<FloatArray>): ByteArray {
        val env = ortEnv ?: return extractStatisticalEmbedding(melSpec)

        val nMels = melSpec.size
        val nFrames = if (melSpec.isNotEmpty()) melSpec[0].size else 0
        if (nFrames == 0) return ByteArray(0)

        // Input Tensor: [1, 1, nMels, nFrames]
        val flatData = FloatArray(nMels * nFrames)
        for (m in 0 until nMels) {
            for (f in 0 until nFrames) {
                flatData[m * nFrames + f] = melSpec[m][f]
            }
        }

        val shape = longArrayOf(1, 1, nMels.toLong(), nFrames.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatData), shape)

        return try {
            val inputName = session.inputNames.first()
            val result = session.run(mapOf(inputName to tensor))
            val output = result[0].value

            // Output als Float-Array lesen
            val embedding = when (output) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (output as Array<FloatArray>)[0]
                }
                is FloatArray -> output
                else -> {
                    // Fallback
                    return extractStatisticalEmbedding(melSpec)
                }
            }

            // In ByteArray serialisieren
            floatArrayToBytes(embedding)
        } catch (e: Exception) {
            System.err.println("[ONNX] Inference fehlgeschlagen: ${e.message}")
            extractStatisticalEmbedding(melSpec)
        } finally {
            tensor.close()
        }
    }

    /**
     * Statistisches Embedding als Fallback (kein Modell noetig).
     * Berechnet pro Mel-Band: Mittelwert + Varianz → 160-dimensionaler Vektor.
     * Robust und schnell, aber weniger aussagekraeftig als neuronales Embedding.
     */
    private fun extractStatisticalEmbedding(melSpec: Array<FloatArray>): ByteArray {
        val nMels = melSpec.size
        val nFrames = if (melSpec.isNotEmpty()) melSpec[0].size else 0
        if (nFrames == 0) return ByteArray(0)

        val embedding = FloatArray(nMels * 2)  // mean + variance pro Band

        for (m in 0 until nMels) {
            var sum = 0f
            var sumSq = 0f
            for (f in 0 until nFrames) {
                val v = melSpec[m][f]
                sum += v
                sumSq += v * v
            }
            val mean = sum / nFrames
            val variance = (sumSq / nFrames) - (mean * mean)
            embedding[m] = mean
            embedding[nMels + m] = sqrt(variance.coerceAtLeast(0f))  // Standardabweichung
        }

        // L2-Normalisierung
        var norm = 0f
        for (v in embedding) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8f) {
            for (i in embedding.indices) embedding[i] /= norm
        }

        return floatArrayToBytes(embedding)
    }

    override fun compare(a: ByteArray, b: ByteArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f

        val fa = bytesToFloatArray(a)
        val fb = bytesToFloatArray(b)

        if (fa.size != fb.size) return 0f

        // Cosinus-Aehnlichkeit
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in fa.indices) {
            dot += fa[i] * fb[i]
            normA += fa[i] * fa[i]
            normB += fb[i] * fb[i]
        }

        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-8f) return 0f

        // Cosinus [-1, 1] → [0, 1]
        return ((dot / denom + 1f) / 2f).coerceIn(0f, 1f)
    }

    override fun featureSize(): Int = EMBEDDING_SIZE * 4  // Float = 4 Bytes

    /**
     * Berechnet ein einfaches Mel-Spektrogramm fuer die Feature-Extraktion.
     * Optimiert fuer Geschwindigkeit (nicht fuer Anzeige-Qualitaet).
     */
    private fun computeMelSpectrogram(samples: FloatArray, sampleRate: Int): Array<FloatArray> {
        val nFrames = (samples.size - N_FFT) / HOP_SIZE + 1
        if (nFrames <= 0) return emptyArray()

        // Mel-Filterbank
        val melFilters = createMelFilterbank(N_FFT, sampleRate, N_MELS, 0f, sampleRate / 2f)
        val window = FloatArray(N_FFT) { i ->
            // Hann-Fenster
            (0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * i / (N_FFT - 1)))).toFloat()
        }

        val fft = org.jtransforms.fft.DoubleFFT_1D(N_FFT.toLong())
        val result = Array(N_MELS) { FloatArray(nFrames) }

        for (frame in 0 until nFrames) {
            val offset = frame * HOP_SIZE
            val fftBuffer = DoubleArray(N_FFT * 2)

            for (i in 0 until N_FFT) {
                val idx = offset + i
                fftBuffer[i] = if (idx < samples.size) (samples[idx] * window[i]).toDouble() else 0.0
            }

            fft.realForwardFull(fftBuffer)

            // Power Spectrum
            val power = FloatArray(N_FFT / 2 + 1)
            for (k in 0..N_FFT / 2) {
                val re = fftBuffer[2 * k].toFloat()
                val im = fftBuffer[2 * k + 1].toFloat()
                power[k] = re * re + im * im
            }

            // Mel-Filter anwenden
            for (m in 0 until N_MELS) {
                var sum = 0f
                for (k in melFilters[m].indices) {
                    if (melFilters[m][k].first < power.size) {
                        sum += power[melFilters[m][k].first] * melFilters[m][k].second
                    }
                }
                result[m][frame] = log10(sum + 1e-10f)
            }
        }

        return result
    }

    /** Erstellt Mel-Filterbank als Sparse-Matrix (nur nicht-null Eintraege) */
    private fun createMelFilterbank(
        fftSize: Int, sampleRate: Int, nMels: Int, fMin: Float, fMax: Float
    ): Array<Array<Pair<Int, Float>>> {
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }
        val binPoints = IntArray(nMels + 2) { i ->
            ((melPoints[i] * fftSize / sampleRate).toInt()).coerceIn(0, fftSize / 2)
        }

        return Array(nMels) { m ->
            val entries = mutableListOf<Pair<Int, Float>>()
            for (k in binPoints[m]..binPoints[m + 1]) {
                val range = binPoints[m + 1] - binPoints[m]
                if (range > 0) {
                    val weight = (k - binPoints[m]).toFloat() / range
                    entries.add(k to weight)
                }
            }
            for (k in binPoints[m + 1]..binPoints[m + 2]) {
                val range = binPoints[m + 2] - binPoints[m + 1]
                if (range > 0) {
                    val weight = (binPoints[m + 2] - k).toFloat() / range
                    entries.add(k to weight)
                }
            }
            entries.toTypedArray()
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val bytes = ByteArray(arr.size * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in arr) buf.putFloat(v)
        return bytes
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(bytes.size / 4)
        for (i in arr.indices) arr[i] = buf.float
        return arr
    }
}
