package ch.etasystems.amsel.core.similarity

import ch.etasystems.amsel.core.audio.AudioDecoder
import ch.etasystems.amsel.core.audio.AudioResampler
import ch.etasystems.amsel.core.audio.AudioSegment
import ch.etasystems.amsel.core.annotation.MatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Vergleicht einen markierten Audio-Ausschnitt mit Referenz-Aufnahmen.
 *
 * Zwei Modi:
 * - OFFLINE: Vergleicht gegen vorberechnete MFCC-Vektoren im lokalen Cache (schnell, kein Internet)
 * - ONLINE: Laedt Kandidaten von Xeno-Canto herunter und vergleicht live (langsam, braucht Internet)
 *
 * Offline wird bevorzugt wenn Cache-Eintraege vorhanden sind.
 */
class SimilarityEngine(
    private val recordings: RecordingProvider,
    private val cache: FeatureCacheProvider,
    val metric: SimilarityMetric = CosineSimilarityMetric
) {
    /**
     * Vergleicht einen Audio-Ausschnitt — offline-first.
     * Wenn lokale MFCC-Daten vorhanden: nutzt diese (schnell).
     * Sonst: ladet von Xeno-Canto (braucht Internet + API-Key).
     */
    suspend fun compare(
        segment: AudioSegment,
        query: String = "",
        maxCandidates: Int = 30,
        topN: Int = 10,
        onProgress: (Float) -> Unit = {}
    ): List<MatchResult> = withContext(Dispatchers.Default) {

        onProgress(0f)

        // 1. Features des Quell-Segments berechnen (ueber Metrik-Abstraktion)
        val sourceBytes = metric.extractFeatures(segment.samples, segment.sampleRate)
        val extractor = MfccExtractor.auto(segment.sampleRate)
        val sourceFeatures = extractor.extractSummary(segment.samples)
        onProgress(0.1f)

        // 2. Lokale Daten verfuegbar? (Cache-Key der aktiven Metrik)
        val localFeatures = cache.loadAllFeatures(metric.cacheKey)
        val legacyFeatures = if (localFeatures.isEmpty()) cache.loadAllMfccFeatures() else emptyMap()

        if (localFeatures.isNotEmpty()) {
            // OFFLINE-Modus: Vergleich gegen lokale Datenbank (neue Metrik)
            val results = compareOfflineBytes(sourceBytes, localFeatures, topN, onProgress)
            onProgress(1f)
            return@withContext results
        }

        if (legacyFeatures.isNotEmpty()) {
            // OFFLINE-Modus: Vergleich gegen legacy MFCC-Datenbank
            val results = compareOffline(sourceFeatures, legacyFeatures, topN, onProgress)
            onProgress(1f)
            return@withContext results
        }

        // 3. Fallback: ONLINE-Modus (braucht API-Key)
        val results = compareOnline(segment, sourceFeatures, extractor, query, maxCandidates, topN, onProgress)
        onProgress(1f)
        results
    }

    /**
     * Offline-Vergleich ueber die abstrakte SimilarityMetric.
     * Bei DtwSimilarityMetric: Zwei-Phasen-Vergleich:
     *   Phase 1: Cosine Similarity auf Summary-Vektoren → Top 100
     *   Phase 2: DTW auf Frame-Sequenzen der Top 100 → Re-Ranking
     * Bei anderen Metriken: direkter Vergleich via metric.compare().
     */
    private fun compareOfflineBytes(
        sourceBytes: ByteArray,
        localFeatures: Map<String, ByteArray>,
        topN: Int,
        onProgress: (Float) -> Unit
    ): List<MatchResult> {
        val infoMap = cache.getRecordingInfos().associateBy { it.recordingId }

        // Zwei-Phasen-Vergleich fuer DTW
        if (metric is DtwSimilarityMetric) {
            return compareOfflineDtw(sourceBytes, localFeatures, infoMap, topN, onProgress)
        }

        // Standard: direkter Vergleich
        val results = mutableListOf<MatchResult>()
        val total = localFeatures.size

        for ((index, entry) in localFeatures.entries.withIndex()) {
            val (recordingId, features) = entry
            val info = infoMap[recordingId] ?: continue

            val similarity = metric.compare(sourceBytes, features)
            results.add(buildMatchResult(info, similarity))
            onProgress(0.1f + 0.8f * (index + 1).toFloat() / total)
        }

        return results.sortedByDescending { it.similarity }.take(topN)
    }

    /**
     * Zwei-Phasen DTW-Vergleich:
     * 1. Schnelle Vorfilterung via Cosine Similarity auf 78-dim Summary
     * 2. Praezise Bewertung der Top 100 via DTW auf Frame-Sequenzen
     */
    private fun compareOfflineDtw(
        sourceBytes: ByteArray,
        localFeatures: Map<String, ByteArray>,
        infoMap: Map<String, RecordingInfo>,
        topN: Int,
        onProgress: (Float) -> Unit
    ): List<MatchResult> {
        val dtwMetric = metric as DtwSimilarityMetric
        val sourceSummary = dtwMetric.deserializeSummary(sourceBytes)

        // Phase 1: Cosine Similarity auf Summary-Vektoren
        data class PreFilterResult(val recordingId: String, val bytes: ByteArray, val cosineSim: Float)

        val preFiltered = mutableListOf<PreFilterResult>()
        val total = localFeatures.size

        for ((index, entry) in localFeatures.entries.withIndex()) {
            val (recordingId, features) = entry
            if (infoMap[recordingId] == null) continue

            val candidateSummary = dtwMetric.deserializeSummary(features)
            val sim = cosineSimilarity(sourceSummary, candidateSummary)
            preFiltered.add(PreFilterResult(recordingId, features, sim))
            onProgress(0.1f + 0.4f * (index + 1).toFloat() / total)
        }

        // Top 100 nach Cosine Similarity
        val topCandidates = preFiltered.sortedByDescending { it.cosineSim }.take(100)

        // Phase 2: DTW auf Frame-Sequenzen
        val sourceFrames = dtwMetric.deserializeFrames(sourceBytes)
        val results = mutableListOf<MatchResult>()

        for ((index, candidate) in topCandidates.withIndex()) {
            val info = infoMap[candidate.recordingId] ?: continue
            val candidateFrames = dtwMetric.deserializeFrames(candidate.bytes)
            val distance = DtwDistance.compute(sourceFrames, candidateFrames, bandWidth = 50)
            val similarity = DtwDistance.distanceToSimilarity(distance)

            results.add(buildMatchResult(info, similarity))
            onProgress(0.5f + 0.4f * (index + 1).toFloat() / topCandidates.size)
        }

        return results.sortedByDescending { it.similarity }.take(topN)
    }

    /** Erstellt ein MatchResult aus RecordingInfo */
    private fun buildMatchResult(info: RecordingInfo, similarity: Float): MatchResult {
        return MatchResult(
            recordingId = info.recordingId,
            species = info.species,
            scientificName = info.scientificName,
            sonogramUrl = info.sonogramUrl,
            audioUrl = info.audioUrl,
            quality = info.quality,
            country = info.country,
            similarity = similarity,
            type = info.type
        )
    }

    /**
     * Offline-Vergleich: schnell, kein Internet noetig.
     * Vergleicht MFCC-Vektor gegen alle lokalen Vektoren via Cosine-Similarity.
     */
    private fun compareOffline(
        sourceFeatures: FloatArray,
        localFeatures: Map<String, FloatArray>,
        topN: Int,
        onProgress: (Float) -> Unit
    ): List<MatchResult> {
        val infoMap = cache.getRecordingInfos().associateBy { it.recordingId }

        val results = mutableListOf<MatchResult>()
        val total = localFeatures.size

        for ((index, entry) in localFeatures.entries.withIndex()) {
            val (recordingId, features) = entry
            val info = infoMap[recordingId] ?: continue

            val similarity = cosineSimilarity(sourceFeatures, features)

            results.add(buildMatchResult(info, similarity))
            onProgress(0.1f + 0.8f * (index + 1).toFloat() / total)
        }

        return results.sortedByDescending { it.similarity }.take(topN)
    }

    /**
     * Online-Vergleich: ladet von Xeno-Canto herunter.
     * Fallback wenn kein lokaler Cache vorhanden.
     */
    private suspend fun compareOnline(
        segment: AudioSegment,
        sourceFeatures: FloatArray,
        extractor: MfccExtractor,
        query: String,
        maxCandidates: Int,
        topN: Int,
        onProgress: (Float) -> Unit
    ): List<MatchResult> {
        // v3-kompatible Query
        val searchQuery = if (query.isBlank()) "grp:birds q:A" else "$query q:A"
        val candidates = try {
            recordings.search(searchQuery, page = 1, perPage = maxCandidates.coerceIn(50, 500))
                .take(maxCandidates)
        } catch (e: Exception) {
            emptyList()
        }

        if (candidates.isEmpty()) return emptyList()

        onProgress(0.2f)

        val results = mutableListOf<MatchResult>()
        val progressPerCandidate = 0.7f / candidates.size

        for ((index, recording) in candidates.withIndex()) {
            try {
                val similarity = computeSimilarityOnline(recording, sourceFeatures, extractor)

                results.add(
                    MatchResult(
                        recordingId = recording.recordingId,
                        species = recording.species,
                        scientificName = recording.scientificName,
                        sonogramUrl = recording.sonogramUrl,
                        audioUrl = recording.audioUrl,
                        quality = recording.quality,
                        country = recording.country,
                        similarity = similarity,
                        type = recording.type
                    )
                )
            } catch (_: Exception) {
                // Kandidat ueberspringen
            }

            onProgress(0.2f + (index + 1) * progressPerCandidate)
        }

        onProgress(0.95f)
        return results.sortedByDescending { it.similarity }.take(topN)
    }

    private suspend fun computeSimilarityOnline(
        recording: RecordingInfo,
        sourceFeatures: FloatArray,
        extractor: MfccExtractor
    ): Float = withContext(Dispatchers.IO) {
        val cacheFile = recordings.downloadAudio(recording.audioUrl, recording.recordingId)

        val candidateSegment = AudioDecoder.decode(cacheFile)
        val resampled = if (candidateSegment.sampleRate != extractor.sampleRate) {
            AudioResampler.resample(candidateSegment, extractor.sampleRate)
        } else {
            candidateSegment
        }

        val maxSamples = extractor.sampleRate * 30
        val trimmed = if (resampled.samples.size > maxSamples) {
            resampled.samples.copyOfRange(0, maxSamples)
        } else {
            resampled.samples
        }

        val candidateFeatures = extractor.extractSummary(trimmed)
        cosineSimilarity(sourceFeatures, candidateFeatures)
    }

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f

            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0

            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }

            val denominator = kotlin.math.sqrt(normA * normB)
            if (denominator < 1e-10) return 0f

            val cosine = (dotProduct / denominator).toFloat()
            return ((cosine + 1f) / 2f).coerceIn(0f, 1f)
        }
    }
}
