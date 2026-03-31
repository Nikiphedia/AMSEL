package ch.etasystems.amsel.core.classifier

/**
 * Interface fuer austauschbare Audio-Classifier.
 * Ermoeglicht spaeter weitere Modelle (eigene ONNX-Modelle, andere Classifier).
 *
 * Jeder Classifier nimmt PCM-Audio (Mono, Float [-1..1]) entgegen und gibt
 * eine Liste von ClassifierResult zurueck (Art + Konfidenz + Zeitbereich).
 */
interface AudioClassifier {

    /** Anzeigename, z.B. "BirdNET V3.0" */
    val name: String

    /** Version, z.B. "preview3" */
    val version: String

    /** Anzahl erkannter Arten */
    val speciesCount: Int

    /** Erwartete Samplerate in Hz (32000 fuer V3.0) */
    val inputSampleRate: Int

    /** Prueft ob der Classifier einsatzbereit ist (Modell vorhanden etc.) */
    fun isAvailable(): Boolean

    /**
     * Klassifiziert Audio-Samples.
     *
     * @param samples PCM-Audiodaten (Mono, Float [-1..1])
     * @param sampleRate Samplerate der Eingabe (wird intern resampled)
     * @param chunkDurationSec Chunk-Laenge in Sekunden (Standard: 3.0)
     * @param overlap Overlap zwischen Chunks in Sekunden (Standard: 0)
     * @param minConfidence Minimale Konfidenz fuer Ergebnisse (Standard: 0.1)
     * @param topN Maximale Anzahl Ergebnisse pro Chunk (0 = alle ueber minConfidence)
     * @return Liste der Detektionen mit Art, Konfidenz und Zeitbereich
     */
    suspend fun classify(
        samples: FloatArray,
        sampleRate: Int,
        chunkDurationSec: Float = 3.0f,
        overlap: Float = 0f,
        minConfidence: Float = 0.1f,
        topN: Int = 10
    ): List<ClassifierResult>

    /** Ressourcen freigeben */
    fun close()
}
