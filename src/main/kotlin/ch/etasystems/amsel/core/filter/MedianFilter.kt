package ch.etasystems.amsel.core.filter

import ch.etasystems.amsel.core.spectrogram.SpectrogramData

/**
 * 2D Median-Filter auf dem Spektrogramm.
 * Entfernt Impulsrauschen (Klicks, Knackser).
 */
object MedianFilter {

    fun apply(data: SpectrogramData, kernelSize: Int = 3): SpectrogramData {
        if (data.nFrames == 0) return data

        val half = kernelSize / 2
        val nMels = data.nMels
        val nFrames = data.nFrames
        val matrix = data.matrix
        val filteredMatrix = FloatArray(nMels * nFrames)
        // Vorab-alloziertes Window-Array (wird wiederverwendet, kein copyOf pro Pixel)
        // Schleife geht von -half..+half → (2*half+1) Elemente pro Dimension
        val windowSide = 2 * half + 1
        val maxWindowSize = windowSide * windowSide
        val window = FloatArray(maxWindowSize)

        for (mel in 0 until nMels) {
            val baseIdx = mel * nFrames
            for (frame in 0 until nFrames) {
                var count = 0
                for (dm in -half..half) {
                    val m = mel + dm
                    if (m < 0 || m >= nMels) continue
                    val mBaseIdx = m * nFrames
                    for (df in -half..half) {
                        val f = frame + df
                        if (f >= 0 && f < nFrames) {
                            window[count++] = matrix[mBaseIdx + f]
                        }
                    }
                }
                // In-place Partial-Sort: nur bis zur Mitte sortieren reicht fuer Median
                // Fuer kleine kernel (3x3=9, 5x5=25) ist Insertion-Sort optimal
                insertionSort(window, count)
                filteredMatrix[baseIdx + frame] = window[count / 2]
            }
        }

        return data.copy(matrix = filteredMatrix)
    }

    /** Insertion-Sort fuer kleine Arrays (optimal fuer n < 30) */
    private fun insertionSort(arr: FloatArray, size: Int) {
        for (i in 1 until size) {
            val key = arr[i]
            var j = i - 1
            while (j >= 0 && arr[j] > key) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = key
        }
    }
}
