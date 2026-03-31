package ch.etasystems.amsel.core.similarity

/**
 * Abstraktion fuer den Feature-Cache — entkoppelt core.similarity von data.
 * Nur die Methoden die SimilarityEngine tatsaechlich braucht.
 */
interface FeatureCacheProvider {
    /** Alle Features fuer einen bestimmten Metrik-Cache-Key laden */
    fun loadAllFeatures(cacheKey: String): Map<String, ByteArray>

    /** Legacy MFCC-Vektoren laden (fuer Abwaertskompatibilitaet) */
    fun loadAllMfccFeatures(): Map<String, FloatArray>

    /** Metadaten aller gecachten Aufnahmen (mit aufgeloesten Sonogramm-URLs) */
    fun getRecordingInfos(): List<RecordingInfo>
}
