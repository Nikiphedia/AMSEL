package ch.etasystems.amsel.core.audio

import ch.etasystems.amsel.core.annotation.Annotation

/**
 * Ein einzelner Audio-Slice mit Start-/Endzeit und Ueberlappungsinformation.
 */
data class AudioSlice(
    val index: Int,
    val startSec: Float,
    val endSec: Float,
    val overlapStartSec: Float = 0f,  // Ueberlappung am Anfang (0 fuer ersten Slice)
    val overlapEndSec: Float = 0f     // Ueberlappung am Ende (0 fuer letzten Slice)
) {
    val durationSec: Float get() = endSec - startSec

    /** Effektiver Bereich ohne Ueberlappung (fuer Deduplizierung) */
    val effectiveStartSec: Float get() = startSec + overlapStartSec
    val effectiveEndSec: Float get() = endSec - overlapEndSec

    /** Anzeige-Label: "Slice 1: 0:00 – 10:00" */
    fun displayLabel(): String {
        val s = formatTime(startSec)
        val e = formatTime(endSec)
        return "Slice ${index + 1}: $s \u2013 $e"
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
 * Berechnet Slice-Grenzen fuer eine Audiodatei.
 *
 * @param totalDurationSec Gesamtdauer in Sekunden
 * @param sliceLengthSec Slice-Laenge in Sekunden
 * @param overlapSec Ueberlappung zwischen benachbarten Slices in Sekunden
 */
class SliceManager(
    val totalDurationSec: Float,
    val sliceLengthSec: Float,
    val overlapSec: Float = 5f
) {
    val slices: List<AudioSlice>
    val sliceCount: Int get() = slices.size
    val isSingleSlice: Boolean get() = slices.size <= 1

    init {
        slices = buildSlices()
    }

    private fun buildSlices(): List<AudioSlice> {
        if (totalDurationSec <= sliceLengthSec) {
            // Gesamte Datei passt in einen Slice
            return listOf(AudioSlice(index = 0, startSec = 0f, endSec = totalDurationSec))
        }

        val result = mutableListOf<AudioSlice>()
        var start = 0f
        var idx = 0

        while (start < totalDurationSec) {
            val end = (start + sliceLengthSec).coerceAtMost(totalDurationSec)
            val isFirst = idx == 0
            val isLast = end >= totalDurationSec

            result.add(
                AudioSlice(
                    index = idx,
                    startSec = start,
                    endSec = end,
                    overlapStartSec = if (isFirst) 0f else overlapSec,
                    overlapEndSec = if (isLast) 0f else overlapSec
                )
            )

            // Naechster Slice startet um (sliceLength - overlap) weiter
            start += sliceLengthSec - overlapSec
            idx++

            // Vermeide winzige Rest-Slices (< 2x Overlap)
            if (!isLast && totalDurationSec - start < overlapSec * 2) {
                // Letzten Slice bis zum Ende verlaengern
                val last = result.removeAt(result.lastIndex)
                result.add(last.copy(endSec = totalDurationSec, overlapEndSec = 0f))
                break
            }
        }

        return result
    }

    /** Findet den Slice der die gegebene Zeitposition enthaelt. */
    fun sliceForTime(timeSec: Float): AudioSlice? {
        return slices.firstOrNull { timeSec >= it.startSec && timeSec <= it.endSec }
    }

    /** Gibt den Slice-Index fuer eine Zeitposition zurueck (-1 wenn nicht gefunden). */
    fun sliceIndexForTime(timeSec: Float): Int {
        return slices.indexOfFirst { timeSec >= it.startSec && timeSec <= it.endSec }
    }

    /** Filtert Annotationen die mit dem gegebenen Slice ueberlappen. */
    fun annotationsForSlice(slice: AudioSlice, annotations: List<Annotation>): List<Annotation> {
        return annotations.filter { ann ->
            ann.startTimeSec < slice.endSec && ann.endTimeSec > slice.startSec
        }
    }

    /** Zaehlt Annotationen pro Slice. */
    fun annotationCountsPerSlice(annotations: List<Annotation>): Map<Int, Int> {
        return slices.associate { slice ->
            slice.index to annotationsForSlice(slice, annotations).size
        }
    }
}
