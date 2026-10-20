package ch.etasystems.amsel.core.spectrogram

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpectrumUtilsTest {

    /** Referenz: Power-Spektrum ueber realForwardFull (bisherige Implementierung). */
    private fun powerViaFullFft(signal: DoubleArray): FloatArray {
        val n = signal.size
        val buffer = DoubleArray(2 * n)
        signal.copyInto(buffer)
        DoubleFFT_1D(n.toLong()).realForwardFull(buffer)
        return FloatArray(n / 2 + 1) { k ->
            val re = buffer[2 * k].toFloat()
            val im = buffer[2 * k + 1].toFloat()
            re * re + im * im
        }
    }

    @Test
    fun `realForward liefert bitgenau dasselbe Power-Spektrum wie realForwardFull`() {
        val rnd = Random(42)
        for (n in listOf(256, 1024, 4096)) {
            val signal = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
            val buffer = signal.copyOf()
            DoubleFFT_1D(n.toLong()).realForward(buffer)
            val power = FloatArray(n / 2 + 1)
            SpectrumUtils.powerFromRealForward(buffer, n, power)

            assertContentEquals(powerViaFullFft(signal), power, "n=$n")
        }
    }

    @Test
    fun `duenne Filterbank rechnet identisch zur dichten`() {
        val dense = MelFilterbank.build(nMels = 64, fftSize = 2048, sampleRate = 48000, fMin = 100f, fMax = 16000f)
        val sparse = SparseFilterbank(dense)
        val rnd = Random(7)
        val power = FloatArray(2048 / 2 + 1) { rnd.nextFloat() * 100f }

        for (m in dense.indices) {
            var erwartet = 0f
            for (k in power.indices) erwartet += dense[m][k] * power[k]
            assertEquals(erwartet, sparse.energy(m, power), "Filter $m")
        }
    }

    @Test
    fun `MelSpectrogram findet Sinuston im richtigen Mel-Band`() = kotlinx.coroutines.runBlocking {
        val sampleRate = 48000
        val freqHz = 3000.0
        val samples = FloatArray(sampleRate) { (0.5 * kotlin.math.sin(2 * Math.PI * freqHz * it / sampleRate)).toFloat() }
        val mel = MelSpectrogram(fftSize = 2048, hopSize = 512, nMels = 64, fMin = 100f, fMax = 16000f, sampleRate = sampleRate)
        val data = mel.compute(samples)

        assertEquals((samples.size - 2048) / 512 + 1, data.nFrames)

        // Band mit maximaler Energie im mittleren Frame
        val frame = data.nFrames / 2
        val maxBand = (0 until data.nMels).maxBy { data.valueAt(it, frame) }

        // Erwartetes Band aus der Mel-Skala
        val melMin = MelFilterbank.hzToMel(100f)
        val melMax = MelFilterbank.hzToMel(16000f)
        val erwartet = ((MelFilterbank.hzToMel(freqHz.toFloat()) - melMin) / (melMax - melMin) * (data.nMels + 1)).toInt() - 1
        kotlin.test.assertTrue(kotlin.math.abs(maxBand - erwartet) <= 1, "Band $maxBand, erwartet ~$erwartet")
    }
}
