package ch.etasystems.amsel.core.similarity

import ch.etasystems.amsel.core.classifier.EmbeddingDatabase
import ch.etasystems.amsel.core.classifier.EmbeddingExtractor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * SimilarityMetric-Implementierung basierend auf Embedding-Vektoren.
 *
 * Nutzt EmbeddingExtractor (ONNX oder MFCC-Fallback) fuer Feature-Extraktion
 * und EmbeddingDatabase fuer optionale lokale Vektor-Suche.
 *
 * Im Vergleich zu den anderen Metriken:
 * - MFCC_BASIC: 26-dim, sehr schnell, Basis-Qualitaet
 * - MFCC_DTW: 78-dim + DTW, langsam, gute Qualitaet
 * - ONNX_EFFICIENTNET: Mel-Statistiken, mittel
 * - EMBEDDING: 43-dim (MFCC) oder N-dim (ONNX Logits), semantisch reichhaltig
 */
class EmbeddingSimilarityMetric : SimilarityMetric {
    override val cacheKey: String = "embedding_v1"
    override val displayName: String = "Embedding Vektor-Suche"

    private val extractor = EmbeddingExtractor()
    private var database: EmbeddingDatabase? = null

    /** Zugriff auf die Embedding-Datenbank (lazy geladen) */
    fun getDatabase(): EmbeddingDatabase {
        if (database == null) {
            database = EmbeddingDatabase().also { it.load() }
        }
        return database!!
    }

    override fun extractFeatures(samples: FloatArray, sampleRate: Int): ByteArray {
        val embedding = extractor.extract(samples, sampleRate)
        if (embedding.isEmpty()) return ByteArray(0)

        // Optional: Embedding in die lokale DB einfuegen (wird beim Speichern persistiert)
        return floatArrayToBytes(embedding)
    }

    override fun compare(a: ByteArray, b: ByteArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f

        val fa = bytesToFloatArray(a)
        val fb = bytesToFloatArray(b)

        if (fa.size != fb.size) return 0f

        // Cosinus-Aehnlichkeit (beide Vektoren sind bereits L2-normalisiert)
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

    override fun featureSize(): Int = -1  // Variabel: 43 (MFCC-Fallback) oder modellabhaengig

    /** Prueft ob das ONNX-Modell verfuegbar ist */
    fun isOnnxAvailable(): Boolean = extractor.isModelAvailable()

    /** Ressourcen freigeben */
    fun close() {
        extractor.close()
        database?.save()
    }

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val bytes = ByteArray(arr.size * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in arr) buf.putFloat(v)
        return bytes
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(bytes.size / 4)
        for (i in arr.indices) arr[i] = buf.float
        return arr
    }
}
