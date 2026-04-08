package ch.etasystems.amsel.core.classifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ch.etasystems.amsel.core.audio.AudioResampler
import ch.etasystems.amsel.core.similarity.MfccExtractor
import ch.etasystems.amsel.data.SettingsStore
import ch.etasystems.amsel.data.resolvedModelDir
import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * Extrahiert Embedding-Vektoren aus Audio-Segmenten.
 *
 * Zwei Modi:
 * 1. ONNX-Modus: Nutzt das BirdNET-Modell und extrahiert die Logits (vor Softmax)
 *    als Embedding-Vektor. Diese enthalten semantische Information ueber die Vogelart.
 * 2. MFCC-Fallback: Berechnet ein 43-dimensionales Pseudo-Embedding aus:
 *    - 13 MFCCs + 13 Delta-MFCCs + 13 Delta-Delta-MFCCs
 *    - Spectral Centroid, Spectral Bandwidth, Zero-Crossing-Rate, Spectral Flatness
 *    Kein Deep Learning, aber deutlich besser als einfaches MFCC-Cosinus.
 *
 * Alle Embeddings werden auf Unit-Length (L2-Norm) normalisiert.
 */
class EmbeddingExtractor(
    modelPath: String? = null
) {
    companion object {
        // BirdNET Audio-Parameter (identisch zu OnnxClassifier)
        private const val TARGET_SAMPLE_RATE = 48000
        private const val CHUNK_DURATION_SEC = 3.0f
        private const val CHUNK_SAMPLES = (TARGET_SAMPLE_RATE * CHUNK_DURATION_SEC).toInt()

        // Mel-Spektrogramm Parameter
        private const val N_FFT = 2048
        private const val HOP_SIZE = 512
        private const val N_MELS = 64
        private const val F_MIN = 40f
        private const val F_MAX = 15000f

        // MFCC-Fallback Dimensionen
        private const val N_MFCC = 13
        // 13 MFCC + 13 Delta + 13 DeltaDelta + 4 Spectral = 43
        private const val FALLBACK_EMBEDDING_DIM = 43
    }

    private val modelFile: File

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var embeddingDim: Int = 0  // wird nach erster Inferenz gesetzt

    init {
        val basePath = modelPath ?: run {
            val modelsDir = SettingsStore.load().resolvedModelDir().also { it.mkdirs() }
            val found = listOf("birdnet_v3.onnx", "birdnet.onnx")
                .map { File(modelsDir, it) }.firstOrNull { it.exists() }
                ?: modelsDir.listFiles()?.firstOrNull { it.extension == "onnx" }
            (found?.absolutePath ?: File(modelsDir, "birdnet_v3.onnx").absolutePath)
        }
        modelFile = File(basePath)
    }

    /** Prueft ob das ONNX-Modell vorhanden ist */
    fun isModelAvailable(): Boolean = modelFile.exists()

    /** Dimensionalitaet des Embedding-Vektors (0 bis zur ersten Extraktion) */
    fun getEmbeddingDim(): Int = if (embeddingDim > 0) embeddingDim else FALLBACK_EMBEDDING_DIM

    /**
     * Extrahiert einen Embedding-Vektor aus Audio-Samples.
     *
     * @param samples PCM-Audiodaten (Mono, Float [-1..1])
     * @param sampleRate Samplerate der Eingabe
     * @return L2-normalisierter Embedding-Vektor, oder leeres Array bei Fehler
     */
    fun extract(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        // Audio auf 48 kHz resamplen wenn noetig
        val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
            AudioResampler.resample(samples, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            samples
        }

        // In 3-Sekunden-Chunks aufteilen
        val chunks = splitIntoChunks(resampled)

        // Versuche ONNX-Extraktion, sonst MFCC-Fallback
        val session = ensureSession()
        if (session != null) {
            return extractOnnxEmbedding(session, chunks)
        }

        return extractMfccFallback(resampled, TARGET_SAMPLE_RATE)
    }

    // ================================================================
    // ONNX-basierte Embedding-Extraktion
    // ================================================================

    /**
     * Extrahiert Embeddings via ONNX-Modell.
     * Nutzt die Logits (Model-Output VOR Softmax) als Embedding.
     * Mehrere Chunks werden gemittelt.
     */
    private fun extractOnnxEmbedding(session: OrtSession, chunks: List<FloatArray>): FloatArray {
        val env = ortEnv ?: return FloatArray(0)

        val chunkEmbeddings = mutableListOf<FloatArray>()

        for (chunk in chunks) {
            val melSpec = computeMelSpectrogram(chunk)
            if (melSpec.isEmpty()) continue

            val nFrames = melSpec.size / N_MELS
            val shape = longArrayOf(1, 1, N_MELS.toLong(), nFrames.toLong())

            val tensor = try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(melSpec), shape)
            } catch (e: Exception) {
                System.err.println("[EmbeddingExtractor] Tensor-Erstellung fehlgeschlagen: ${e.message}")
                continue
            }

            try {
                val inputName = session.inputNames.first()
                val result = session.run(mapOf(inputName to tensor))
                val output = result[0].value

                // Output als Float-Array lesen (Logits = Embedding)
                val logits = when (output) {
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (output as Array<FloatArray>)[0]
                    }
                    is FloatArray -> output
                    else -> continue
                }

                if (logits.isNotEmpty()) {
                    chunkEmbeddings.add(logits)
                    if (embeddingDim == 0) embeddingDim = logits.size
                }
            } catch (e: Exception) {
                System.err.println("[EmbeddingExtractor] Inferenz fehlgeschlagen: ${e.message}")
            } finally {
                tensor.close()
            }
        }

        if (chunkEmbeddings.isEmpty()) return FloatArray(0)

        // Mittelwert ueber alle Chunk-Embeddings
        val averaged = averageEmbeddings(chunkEmbeddings)

        // L2-Normalisierung
        return normalizeL2(averaged)
    }

    // ================================================================
    // MFCC-basiertes Pseudo-Embedding (Fallback)
    // ================================================================

    /**
     * Berechnet ein 43-dimensionales Pseudo-Embedding:
     * - 13 MFCCs (Mittelwert ueber Frames)
     * - 13 Delta-MFCCs (Mittelwert)
     * - 13 Delta-Delta-MFCCs (Mittelwert)
     * - Spectral Centroid (Mittelwert)
     * - Spectral Bandwidth (Mittelwert)
     * - Zero-Crossing-Rate (Mittelwert)
     * - Spectral Flatness (Mittelwert)
     *
     * L2-normalisiert.
     */
    private fun extractMfccFallback(samples: FloatArray, sampleRate: Int): FloatArray {
        val extractor = MfccExtractor(
            sampleRate = sampleRate,
            fftSize = N_FFT,
            hopSize = HOP_SIZE,
            nMels = 40,
            nMfcc = N_MFCC,
            fMin = 125f,
            fMax = 16000f,
            computeDeltas = true,
            applyCmvn = true
        )

        // MFCCs + Deltas + Delta-Deltas extrahieren
        val enhanced = extractor.extractEnhanced(samples)  // [nFrames][39]
        if (enhanced.isEmpty()) return FloatArray(FALLBACK_EMBEDDING_DIM)

        // Mittelwert ueber alle Frames: 39 Dimensionen
        val nFeatures = enhanced[0].size
        val meanFeatures = FloatArray(nFeatures)
        for (frame in enhanced) {
            for (f in 0 until nFeatures) {
                meanFeatures[f] += frame[f]
            }
        }
        for (f in 0 until nFeatures) {
            meanFeatures[f] /= enhanced.size
        }

        // Spectral Features berechnen (4 zusaetzliche Dimensionen)
        val spectralFeatures = computeSpectralFeatures(samples, sampleRate)

        // Kombinieren: 39 MFCC + 4 Spectral = 43
        val embedding = FloatArray(FALLBACK_EMBEDDING_DIM)
        System.arraycopy(meanFeatures, 0, embedding, 0, min(nFeatures, 39))
        System.arraycopy(spectralFeatures, 0, embedding, 39, 4)

        return normalizeL2(embedding)
    }

    /**
     * Berechnet 4 spektrale Features (Mittelwert ueber alle Frames):
     * - Spectral Centroid: Schwerpunkt des Spektrums
     * - Spectral Bandwidth: Breite des Spektrums
     * - Zero-Crossing-Rate: Anzahl Nulldurchgaenge pro Sample
     * - Spectral Flatness: Geometrischer / Arithmetischer Mittelwert (Rauschen-Indikator)
     */
    private fun computeSpectralFeatures(samples: FloatArray, sampleRate: Int): FloatArray {
        val nFrames = (samples.size - N_FFT) / HOP_SIZE + 1
        if (nFrames <= 0) return FloatArray(4)

        val fft = DoubleFFT_1D(N_FFT.toLong())
        val window = FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * i / (N_FFT - 1)))).toFloat()
        }

        var sumCentroid = 0.0
        var sumBandwidth = 0.0
        var sumZcr = 0.0
        var sumFlatness = 0.0

        for (frame in 0 until nFrames) {
            val offset = frame * HOP_SIZE

            // Zero-Crossing-Rate fuer diesen Frame
            var zeroCrossings = 0
            for (i in 1 until N_FFT) {
                val idx = offset + i
                val idxPrev = offset + i - 1
                if (idx < samples.size && idxPrev >= 0) {
                    if ((samples[idx] >= 0f && samples[idxPrev] < 0f) ||
                        (samples[idx] < 0f && samples[idxPrev] >= 0f)) {
                        zeroCrossings++
                    }
                }
            }
            sumZcr += zeroCrossings.toDouble() / N_FFT

            // FFT berechnen
            val fftBuffer = DoubleArray(N_FFT * 2)
            for (i in 0 until N_FFT) {
                val idx = offset + i
                fftBuffer[i] = if (idx < samples.size) {
                    (samples[idx] * window[i]).toDouble()
                } else 0.0
            }
            fft.realForwardFull(fftBuffer)

            // Power-Spektrum
            val numBins = N_FFT / 2 + 1
            val power = DoubleArray(numBins)
            var totalPower = 0.0
            for (k in 0 until numBins) {
                val re = fftBuffer[2 * k]
                val im = fftBuffer[2 * k + 1]
                power[k] = re * re + im * im
                totalPower += power[k]
            }

            if (totalPower < 1e-10) continue

            // Spectral Centroid
            var centroid = 0.0
            for (k in 0 until numBins) {
                val freqHz = k.toDouble() * sampleRate / N_FFT
                centroid += freqHz * power[k]
            }
            centroid /= totalPower
            sumCentroid += centroid

            // Spectral Bandwidth
            var bandwidth = 0.0
            for (k in 0 until numBins) {
                val freqHz = k.toDouble() * sampleRate / N_FFT
                val diff = freqHz - centroid
                bandwidth += diff * diff * power[k]
            }
            bandwidth = sqrt(bandwidth / totalPower)
            sumBandwidth += bandwidth

            // Spectral Flatness: exp(mean(log(S))) / mean(S)
            var logSum = 0.0
            var linearSum = 0.0
            var validBins = 0
            for (k in 0 until numBins) {
                if (power[k] > 1e-20) {
                    logSum += ln(power[k])
                    linearSum += power[k]
                    validBins++
                }
            }
            if (validBins > 0 && linearSum > 0) {
                val geometricMean = exp(logSum / validBins)
                val arithmeticMean = linearSum / validBins
                sumFlatness += (geometricMean / arithmeticMean).coerceIn(0.0, 1.0)
            }
        }

        // Mittelwerte und Normalisierung auf sinnvolle Bereiche
        val result = FloatArray(4)
        result[0] = (sumCentroid / nFrames / sampleRate).toFloat()  // Centroid normalisiert auf [0,1]
        result[1] = (sumBandwidth / nFrames / sampleRate).toFloat() // Bandwidth normalisiert
        result[2] = (sumZcr / nFrames).toFloat()                    // ZCR bereits in [0,1]
        result[3] = (sumFlatness / nFrames).toFloat()               // Flatness bereits in [0,1]

        return result
    }

    // ================================================================
    // Hilfsmethoden
    // ================================================================

    /** ONNX Runtime Session initialisieren (lazy) */
    private fun ensureSession(): OrtSession? {
        if (ortSession != null) return ortSession
        if (!isModelAvailable()) return null

        return try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val session = env.createSession(modelFile.absolutePath)
            ortSession = session
            println("[EmbeddingExtractor] Modell geladen: ${modelFile.name}")
            session
        } catch (e: Exception) {
            System.err.println("[EmbeddingExtractor] Modell konnte nicht geladen werden: ${e.message}")
            null
        }
    }

    /** Audio in 3-Sekunden-Chunks aufteilen */
    private fun splitIntoChunks(samples: FloatArray): List<FloatArray> {
        if (samples.size <= CHUNK_SAMPLES) {
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

    /** Mel-Spektrogramm berechnen (BirdNET-kompatibel) */
    private fun computeMelSpectrogram(samples: FloatArray): FloatArray {
        val nFrames = (samples.size - N_FFT) / HOP_SIZE + 1
        if (nFrames <= 0) return FloatArray(0)

        val melFilters = createMelFilterbank()
        val window = FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * i / (N_FFT - 1)))).toFloat()
        }

        val fft = DoubleFFT_1D(N_FFT.toLong())
        val numBins = N_FFT / 2 + 1
        val matrix = FloatArray(N_MELS * nFrames)

        for (frame in 0 until nFrames) {
            val offset = frame * HOP_SIZE
            val fftBuffer = DoubleArray(N_FFT * 2)

            for (i in 0 until N_FFT) {
                val idx = offset + i
                fftBuffer[i] = if (idx < samples.size) {
                    (samples[idx] * window[i]).toDouble()
                } else 0.0
            }

            fft.realForwardFull(fftBuffer)

            val power = FloatArray(numBins)
            for (k in 0 until numBins) {
                val re = fftBuffer[2 * k].toFloat()
                val im = fftBuffer[2 * k + 1].toFloat()
                power[k] = re * re + im * im
            }

            for (m in 0 until N_MELS) {
                var energy = 0f
                for (k in 0 until numBins) {
                    energy += melFilters[m][k] * power[k]
                }
                matrix[m * nFrames + frame] = log10(energy + 1e-10f)
            }
        }

        return matrix
    }

    /** Mel-Filterbank erstellen */
    private fun createMelFilterbank(): Array<FloatArray> {
        val numBins = N_FFT / 2 + 1
        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        val melPoints = FloatArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }
        val binPoints = IntArray(N_MELS + 2) { i ->
            ((melPoints[i] * N_FFT / TARGET_SAMPLE_RATE).toInt()).coerceIn(0, numBins - 1)
        }

        return Array(N_MELS) { m ->
            val filter = FloatArray(numBins)
            for (k in binPoints[m]..binPoints[m + 1]) {
                val range = binPoints[m + 1] - binPoints[m]
                if (range > 0) filter[k] = (k - binPoints[m]).toFloat() / range
            }
            for (k in binPoints[m + 1]..binPoints[m + 2]) {
                val range = binPoints[m + 2] - binPoints[m + 1]
                if (range > 0) filter[k] = (binPoints[m + 2] - k).toFloat() / range
            }
            filter
        }
    }

    /** Mittelwert ueber mehrere Embedding-Vektoren */
    private fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(0)
        if (embeddings.size == 1) return embeddings[0].copyOf()

        val dim = embeddings[0].size
        val result = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until min(dim, emb.size)) {
                result[i] += emb[i]
            }
        }
        for (i in result.indices) {
            result[i] /= embeddings.size
        }
        return result
    }

    /** L2-Normalisierung auf Unit-Length */
    private fun normalizeL2(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)

        if (norm < 1e-8f) return vec

        val result = vec.copyOf()
        for (i in result.indices) result[i] /= norm
        return result
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
    private fun melToHz(mel: Float): Float =
        700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

    /** Ressourcen freigeben */
    fun close() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            System.err.println("[EmbeddingExtractor] Fehler beim Schliessen: ${e.message}")
        }
    }
}
