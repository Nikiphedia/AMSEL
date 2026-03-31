package ch.etasystems.amsel.core.audio

/**
 * Rohe PCM-Audiodaten als Float-Array [-1.0 .. 1.0], Mono.
 */
data class AudioSegment(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSec: Float = samples.size.toFloat() / sampleRate
) {
    /**
     * Extrahiert einen Teilbereich als neues AudioSegment.
     * @param startSample Index des ersten Samples (inklusive)
     * @param endSample Index des letzten Samples (exklusive)
     */
    fun subRange(startSample: Int, endSample: Int): AudioSegment {
        val from = startSample.coerceIn(0, samples.size)
        val to = endSample.coerceIn(from, samples.size)
        return AudioSegment(
            samples = samples.copyOfRange(from, to),
            sampleRate = sampleRate
        )
    }

    /**
     * Extrahiert einen Zeitbereich als neues AudioSegment.
     * @param startSec Startzeit in Sekunden
     * @param endSec Endzeit in Sekunden
     */
    fun subRangeSec(startSec: Float, endSec: Float): AudioSegment {
        val startSample = (startSec * sampleRate).toInt()
        val endSample = (endSec * sampleRate).toInt()
        return subRange(startSample, endSample)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSegment) return false
        return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}
