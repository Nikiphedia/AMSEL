package ch.etasystems.amsel.core.similarity

import java.io.File

/**
 * Abstraktion fuer den Zugriff auf Online-Aufnahmen — entkoppelt core.similarity von data.api.
 * Nur die Methoden die SimilarityEngine fuer den Online-Fallback braucht.
 */
interface RecordingProvider {
    /** Sucht Aufnahmen via Query-String (Xeno-Canto v3 Syntax) */
    suspend fun search(query: String, page: Int = 1, perPage: Int = 100): List<RecordingInfo>

    /** Laedt Audio-Datei herunter und gibt lokale Datei zurueck */
    suspend fun downloadAudio(audioUrl: String, recordingId: String): File
}
