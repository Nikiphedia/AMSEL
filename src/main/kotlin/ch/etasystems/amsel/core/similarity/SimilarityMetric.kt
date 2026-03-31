package ch.etasystems.amsel.core.similarity

/**
 * Abstraktion fuer verschiedene Aehnlichkeits-Metriken.
 * Jede Metrik definiert Feature-Extraktion und Vergleich.
 */
interface SimilarityMetric {
    /** Eindeutiger Schluessel fuer den Cache-Ordner (z.B. "mfcc", "mfcc_v2_dtw") */
    val cacheKey: String

    /** Anzeigename fuer die UI (z.B. "MFCC Basis") */
    val displayName: String

    /**
     * Extrahiert Feature-Vektor aus Audio-Samples.
     * @return Features als ByteArray (Serialisierungsformat metrik-spezifisch)
     */
    fun extractFeatures(samples: FloatArray, sampleRate: Int): ByteArray

    /**
     * Vergleicht zwei Feature-Vektoren.
     * @return Aehnlichkeit zwischen 0.0 (verschieden) und 1.0 (identisch)
     */
    fun compare(a: ByteArray, b: ByteArray): Float

    /**
     * Groesse des Feature-Vektors in Bytes. -1 wenn variabel.
     */
    fun featureSize(): Int
}
