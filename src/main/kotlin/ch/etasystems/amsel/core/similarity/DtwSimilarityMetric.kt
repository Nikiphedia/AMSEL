package ch.etasystems.amsel.core.similarity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enhanced MFCC + DTW Metrik.
 *
 * Cache-Format:
 *   [4 Bytes: summarySize (int32 LE)]
 *   [summarySize * 4 Bytes: summary floats]
 *   [4 Bytes: nFrames (int32 LE)]
 *   [4 Bytes: nFeatures (int32 LE)]
 *   [nFrames * nFeatures * 4 Bytes: frame data]
 */
object DtwSimilarityMetric : SimilarityMetric {
    override val cacheKey: String = "mfcc_v2_dtw"
    override val displayName: String = "MFCC + DTW"

    override fun extractFeatures(samples: FloatArray, sampleRate: Int): ByteArray {
        val extractor = MfccExtractor.autoEnhanced(sampleRate)
        val enhanced = extractor.extractEnhanced(samples)
        val summary = extractor.summarizeEnhanced(enhanced)
        return serializeFeatures(summary, enhanced)
    }

    override fun compare(a: ByteArray, b: ByteArray): Float {
        val framesA = deserializeFrames(a)
        val framesB = deserializeFrames(b)
        val distance = DtwDistance.compute(framesA, framesB, bandWidth = 50)
        return DtwDistance.distanceToSimilarity(distance)
    }

    override fun featureSize(): Int = -1  // Variabel (abhaengig von Audio-Laenge)

    // ================================================================
    // Hilfsmethoden fuer Zwei-Phasen-Vergleich in SimilarityEngine
    // ================================================================

    /**
     * Extrahiert nur den Summary-Vektor (78-dim) aus den gespeicherten Features.
     * Fuer schnelle Vorfilterung via Cosine Similarity.
     */
    fun deserializeSummary(data: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val summarySize = buf.getInt()
        val summary = FloatArray(summarySize)
        buf.asFloatBuffer().get(summary)
        return summary
    }

    /**
     * Extrahiert die Frame-Sequenz aus den gespeicherten Features.
     * Fuer DTW-Vergleich.
     */
    fun deserializeFrames(data: ByteArray): Array<FloatArray> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val summarySize = buf.getInt()
        // Summary ueberspringen
        buf.position(buf.position() + summarySize * 4)
        val nFrames = buf.getInt()
        val nFeatures = buf.getInt()
        val frames = Array(nFrames) { FloatArray(nFeatures) }
        val floatBuf = buf.asFloatBuffer()
        for (t in 0 until nFrames) {
            floatBuf.get(frames[t])
        }
        return frames
    }

    /**
     * Serialisiert Summary + Frame-Matrix in ein ByteArray.
     */
    fun serializeFeatures(summary: FloatArray, frames: Array<FloatArray>): ByteArray {
            val nFrames = frames.size
            val nFeatures = if (nFrames > 0) frames[0].size else 0
            // Header: summarySize(4) + summary(summarySize*4) + nFrames(4) + nFeatures(4) + data(nFrames*nFeatures*4)
            val totalBytes = 4 + summary.size * 4 + 4 + 4 + nFrames * nFeatures * 4
            val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Summary
            buf.putInt(summary.size)
            for (v in summary) buf.putFloat(v)

            // Frames
            buf.putInt(nFrames)
            buf.putInt(nFeatures)
            for (frame in frames) {
                for (v in frame) buf.putFloat(v)
            }

            return buf.array()
    }
}
