package ch.etasystems.amsel.core.similarity

/**
 * Schlankes DTO fuer Aufnahme-Metadaten, verwendet von SimilarityEngine.
 * Bildet nur die Felder ab die fuer den Vergleich benoetigt werden —
 * keine 1:1-Kopie von CacheEntry oder XenoCantoRecording.
 */
data class RecordingInfo(
    val recordingId: String,
    val species: String,           // Englischer Name
    val scientificName: String,    // "Turdus merula"
    val sonogramUrl: String,       // Bereits aufgeloest (file:/// oder https://)
    val audioUrl: String,
    val quality: String,
    val country: String,
    val type: String               // "song", "call", etc.
)
