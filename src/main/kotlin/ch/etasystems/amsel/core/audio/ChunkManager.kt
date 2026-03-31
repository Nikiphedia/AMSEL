package ch.etasystems.amsel.core.audio

import ch.etasystems.amsel.core.annotation.Annotation

/**
 * Ein einzelner Audio-Chunk mit Start-/Endzeit und Ueberlappungsinformation.
 */
data class AudioChunk(
    val index: Int,
    val startSec: Float,
    val endSec: Float,
    val overlapStartSec: Float = 0f,  // Ueberlappung am Anfang (0 fuer ersten Chunk)
    val overlapEndSec: Float = 0f     // Ueberlappung am Ende (0 fuer letzten Chunk)
) {
    val durationSec: Float get() = endSec - startSec

    /** Effektiver Bereich ohne Ueberlappung (fuer Deduplizierung) */
    val effectiveStartSec: Float get() = startSec + overlapStartSec
    val effectiveEndSec: Float get() = endSec - overlapEndSec

    /** Anzeige-Label: "Chunk 1: 0:00 – 10:00" */
    fun displayLabel(): String {
        val s = formatTime(startSec)
        val e = formatTime(endSec)
        return "Chunk ${index + 1}: $s \u2013 $e"
    }

    /** Kompakt-Label: "1: 0:00-10:00" */
    fun shortLabel(): String {
        val s = formatTime(startSec)
        val e = formatTime(endSec)
        return "${index + 1}: $s-$e"
    }

    private fun formatTime(sec: Float): String {
        val totalSec = sec.toInt()
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}

/**
 * Berechnet Chunk-Grenzen fuer eine Audiodatei.
 *
 * @param totalDurationSec Gesamtdauer in Sekunden
 * @param chunkLengthSec Chunk-Laenge in Sekunden
 * @param overlapSec Ueberlappung zwischen benachbarten Chunks in Sekunden
 */
class ChunkManager(
    val totalDurationSec: Float,
    val chunkLengthSec: Float,
    val overlapSec: Float = 5f
) {
    val chunks: List<AudioChunk>
    val chunkCount: Int get() = chunks.size
    val isSingleChunk: Boolean get() = chunks.size <= 1

    init {
        chunks = buildChunks()
    }

    private fun buildChunks(): List<AudioChunk> {
        if (totalDurationSec <= chunkLengthSec) {
            // Gesamte Datei passt in einen Chunk
            return listOf(AudioChunk(index = 0, startSec = 0f, endSec = totalDurationSec))
        }

        val result = mutableListOf<AudioChunk>()
        var start = 0f
        var idx = 0

        while (start < totalDurationSec) {
            val end = (start + chunkLengthSec).coerceAtMost(totalDurationSec)
            val isFirst = idx == 0
            val isLast = end >= totalDurationSec

            result.add(
                AudioChunk(
                    index = idx,
                    startSec = start,
                    endSec = end,
                    overlapStartSec = if (isFirst) 0f else overlapSec,
                    overlapEndSec = if (isLast) 0f else overlapSec
                )
            )

            // Naechster Chunk startet um (chunkLength - overlap) weiter
            start += chunkLengthSec - overlapSec
            idx++

            // Vermeide winzige Rest-Chunks (< 2x Overlap)
            if (!isLast && totalDurationSec - start < overlapSec * 2) {
                // Letzten Chunk bis zum Ende verlaengern
                val last = result.removeAt(result.lastIndex)
                result.add(last.copy(endSec = totalDurationSec, overlapEndSec = 0f))
                break
            }
        }

        return result
    }

    /** Findet den Chunk der die gegebene Zeitposition enthaelt. */
    fun chunkForTime(timeSec: Float): AudioChunk? {
        return chunks.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }
    }

    /** Gibt den Chunk-Index fuer eine Zeitposition zurueck (-1 wenn nicht gefunden). */
    fun chunkIndexForTime(timeSec: Float): Int {
        return chunks.indexOfFirst { timeSec >= it.startSec && timeSec <= it.endSec }
    }

    /** Filtert Annotationen die mit dem gegebenen Chunk ueberlappen. */
    fun annotationsForChunk(chunk: AudioChunk, annotations: List<Annotation>): List<Annotation> {
        return annotations.filter { ann ->
            ann.startTimeSec < chunk.endSec && ann.endTimeSec > chunk.startSec
        }
    }

    /** Zaehlt Annotationen pro Chunk. */
    fun annotationCountsPerChunk(annotations: List<Annotation>): Map<Int, Int> {
        return chunks.associate { chunk ->
            chunk.index to annotationsForChunk(chunk, annotations).size
        }
    }
}
