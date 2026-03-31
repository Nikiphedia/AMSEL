package ch.etasystems.amsel.core.spectrogram

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Lineares Spektrogramm (Standard-STFT) fuer hochaufloesenden Export.
 * Im Gegensatz zu MelSpectrogram werden die FFT-Bins NICHT auf Mel-Skala
 * zusammengefasst, sondern als lineare Frequenz-Bins gespeichert.
 *
 * Fuer Druckqualitaet (Glutz-Stil):
 * - FFT=16384 → Frequenzaufloesung ~2.93 Hz/Bin bei 48kHz
 * - ca. 5461 Bins fuer 0-16kHz
 * - 2D Gauss-Glaettung entfernt FFT-Rauschen fuer sauberes Bild
 */
object LinearSpectrogram {

    fun compute(
        samples: FloatArray,
        fftSize: Int = 16384,
        hopSize: Int = 64,
        sampleRate: Int,
        fMin: Float = 0f,
        fMax: Float = 16000f
    ): SpectrogramData {
        // Zero-Padding: Samples so verlängern, dass das letzte FFT-Fenster
        // die volle Audio-Dauer abdeckt (sonst fehlen ~fftSize/sampleRate Sekunden)
        val paddedSamples = if (samples.size < fftSize) {
            FloatArray(fftSize) // zu kurz → auffüllen
        } else {
            val needed = samples.size + fftSize  // ein volles Fenster extra
            val padded = FloatArray(needed)
            samples.copyInto(padded)
            padded
        }
        val nFrames = (paddedSamples.size - fftSize) / hopSize + 1
        if (nFrames <= 0) {
            return SpectrogramData(
                matrix = FloatArray(0), nMels = 0, nFrames = 0,
                sampleRate = sampleRate, hopSize = hopSize, fMin = fMin, fMax = fMax
            )
        }

        val numBins = fftSize / 2 + 1
        val hzPerBin = sampleRate.toFloat() / fftSize

        val binMin = (fMin / hzPerBin).roundToInt().coerceIn(0, numBins - 1)
        val binMax = (fMax / hzPerBin).roundToInt().coerceIn(binMin, numBins - 1)
        val nBins = binMax - binMin + 1

        val window = hannWindow(fftSize)
        val fft = DoubleFFT_1D(fftSize.toLong())
        val fftBuffer = DoubleArray(fftSize * 2)
        val matrix = FloatArray(nBins * nFrames)

        for (frame in 0 until nFrames) {
            val offset = frame * hopSize

            for (i in 0 until fftSize) {
                val sampleIdx = offset + i
                fftBuffer[i] = if (sampleIdx < paddedSamples.size) {
                    (paddedSamples[sampleIdx] * window[i]).toDouble()
                } else 0.0
            }
            for (i in fftSize until fftSize * 2) {
                fftBuffer[i] = 0.0
            }

            fft.realForwardFull(fftBuffer)

            for (b in 0 until nBins) {
                val k = binMin + b
                val re = fftBuffer[2 * k].toFloat()
                val im = fftBuffer[2 * k + 1].toFloat()
                val power = re * re + im * im
                matrix[b * nFrames + frame] = log10(power + 1e-10f)
            }
        }

        // Nur Frames behalten die tatsächliche Audio-Daten enthalten
        // (nicht die Zero-Padded Frames am Ende)
        val audioDurationSec = samples.size.toFloat() / sampleRate
        val realFrames = ((audioDurationSec * sampleRate) / hopSize).toInt().coerceIn(1, nFrames)

        // Matrix auf realFrames trimmen
        val trimmedMatrix = FloatArray(nBins * realFrames)
        for (b in 0 until nBins) {
            for (f in 0 until realFrames) {
                trimmedMatrix[b * realFrames + f] = matrix[b * nFrames + f]
            }
        }

        // 2D Gauss-Glaettung (3x3 Kernel) — entfernt FFT-Rauschen
        val smoothed = gaussSmooth(trimmedMatrix, nBins, realFrames)

        val actualFMin = binMin * hzPerBin
        val actualFMax = binMax * hzPerBin

        return SpectrogramData(
            matrix = smoothed,
            nMels = nBins,
            nFrames = realFrames,
            sampleRate = sampleRate,
            hopSize = hopSize,
            fMin = actualFMin,
            fMax = actualFMax
        )
    }

    /**
     * Separable 1D Gauss-Glaettung (erst horizontal, dann vertikal).
     * Anisotroper Kernel: Frequenz-Richtung staerker (sigma=6) als Zeit (sigma=3).
     * Bei 5461 linearen Bins (2.93 Hz/Bin) glaettet sigma=6 ueber ~18 Hz = ~6 Bins.
     * Bei hop=64 @ 48kHz (1.33ms/Frame) glaettet sigma=3 ueber ~4ms = ~3 Frames.
     * Entfernt FFT-Rauschen und sorgt fuer Glutz-artige weiche Uebergaenge.
     */
    private fun gaussSmooth(
        matrix: FloatArray,
        nBins: Int,
        nFrames: Int
    ): FloatArray {
        // Frequenz-Richtung: sigma=6 (staerker, Bins sind sehr eng)
        val freqRadius = 12
        val freqSigma = 6.0f
        val freqKernel = FloatArray(2 * freqRadius + 1) { i ->
            val x = (i - freqRadius).toFloat()
            exp(-x * x / (2f * freqSigma * freqSigma))
        }
        val fkSum = freqKernel.sum()
        for (i in freqKernel.indices) freqKernel[i] /= fkSum

        // Zeit-Richtung: sigma=3 (moderater)
        val radius = 6
        val sigma = 3.0f
        val kernel = FloatArray(2 * radius + 1) { i ->
            val x = (i - radius).toFloat()
            exp(-x * x / (2f * sigma * sigma))
        }
        val kSum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= kSum

        // Pass 1: horizontal (entlang Frames/Zeit)
        val temp = FloatArray(nBins * nFrames)
        for (b in 0 until nBins) {
            for (f in 0 until nFrames) {
                var sum = 0f
                var wAcc = 0f
                for (k in -radius..radius) {
                    val nf = f + k
                    if (nf in 0 until nFrames) {
                        val w = kernel[k + radius]
                        sum += matrix[b * nFrames + nf] * w
                        wAcc += w
                    }
                }
                temp[b * nFrames + f] = sum / wAcc
            }
        }

        // Pass 2: vertikal (entlang Bins/Frequenz) — staerkere Glaettung
        val result = FloatArray(nBins * nFrames)
        for (b in 0 until nBins) {
            for (f in 0 until nFrames) {
                var sum = 0f
                var wAcc = 0f
                for (k in -freqRadius..freqRadius) {
                    val nb = b + k
                    if (nb in 0 until nBins) {
                        val w = freqKernel[k + freqRadius]
                        sum += temp[nb * nFrames + f] * w
                        wAcc += w
                    }
                }
                result[b * nFrames + f] = sum / wAcc
            }
        }
        return result
    }

    private fun hannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
        }
    }
}
