package ch.etasystems.amsel.core.audio

import kotlin.math.floor

/**
 * Linearer Interpolations-Resampler.
 * Resampled Audio auf beliebige Ziel-Samplerate.
 */
object AudioResampler {

    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples.copyOf()

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputLength = (samples.size / ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIdx = floor(srcPos).toInt()
            val frac = (srcPos - srcIdx).toFloat()

            output[i] = if (srcIdx + 1 < samples.size) {
                samples[srcIdx] * (1f - frac) + samples[srcIdx + 1] * frac
            } else {
                samples[srcIdx.coerceAtMost(samples.size - 1)]
            }
        }

        return output
    }

    /**
     * Resample ein AudioSegment auf die Ziel-Samplerate.
     */
    fun resample(segment: AudioSegment, toRate: Int): AudioSegment {
        if (segment.sampleRate == toRate) return segment
        val resampled = resample(segment.samples, segment.sampleRate, toRate)
        return AudioSegment(samples = resampled, sampleRate = toRate)
    }
}
