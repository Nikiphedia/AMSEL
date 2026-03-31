package ch.etasystems.amsel.core.similarity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard-Metrik: MFCC-Summary (26-dim) + Cosine Similarity.
 * Entspricht dem bisherigen Verhalten von SimilarityEngine.
 */
object CosineSimilarityMetric : SimilarityMetric {
    override val cacheKey: String = "mfcc"
    override val displayName: String = "MFCC Basis"

    override fun extractFeatures(samples: FloatArray, sampleRate: Int): ByteArray {
        val extractor = MfccExtractor.auto(sampleRate)
        val summary = extractor.extractSummary(samples)
        return floatsToBytes(summary)
    }

    override fun compare(a: ByteArray, b: ByteArray): Float {
        val featA = bytesToFloats(a)
        val featB = bytesToFloats(b)
        return SimilarityEngine.cosineSimilarity(featA, featB)
    }

    override fun featureSize(): Int = 26 * 4  // 13 mean + 13 stddev, je 4 Bytes

    /** FloatArray → ByteArray (Little-Endian) */
    fun floatsToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(floats)
        return buffer.array()
    }

    /** ByteArray → FloatArray (Little-Endian) */
    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
}
